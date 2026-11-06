package com.massaro.c1250deck;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The phone companion (1.14.0): phone.html, served to a phone on the home Wi-Fi, where workouts are
 * built and sent here, and, if allowed, a run is started and controlled.
 *
 * Two switches, both off until someone turns them on at the console (Settings → Phone). The link alone
 * only holds workouts: the one marked next shows on Home and a person taps Start there. Control from
 * phone also lets /api/run through: this class knows nothing about the belt, it passes each command to
 * the page, which treats it like a tap on its own buttons (canMove, limits, the countdown) and answers.
 * If the page doesn't answer, a pause or an end stops the belt directly and everything else is refused.
 * A start or resume also needs a one-use token from the latest state, so nothing stale can fire one.
 *
 * A phone pairs with a code shown on the console, in a QR code or typed in, and sends it with every API
 * call. A wrong code locks that address out for a second, so it can't be guessed. No CORS headers, so another
 * website in the phone's browser can't call it. It's plain http on the home network: anyone on the Wi-Fi
 * who captured a request could learn the code.
 *
 * Every workout is checked here (cleaned below), since it came over the network: the number of steps,
 * their length, and speed and incline held to what this treadmill can do. Native clamps again when the
 * workout runs. While on, it keeps the CPU and Wi-Fi awake, so a sleeping console can still be reached.
 */
final class PhoneLink {
    interface Push { void push(JSONObject o); }

    static final int PORT = 8770;
    private static final int MAX_HEAD = 8 * 1024, MAX_BODY = 64 * 1024, MAX_WORKOUTS = 30;
    private static final int MAX_BLOCKS = 40, MAX_INNER = 8, MIN_TIMES = 2, MAX_TIMES = 30, MAX_SEGS = 300;
    private static final int MIN_SECS = 15, MAX_SECS = 2 * 3600, MAX_TOTAL = 4 * 3600, MAX_NAME = 40;
    private static final String CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";   // nothing that reads as another letter
    private static final int CODE_LEN = 8;
    private static final long LOCK_MS = 1000;
    private static final String EMPTY = "{\"list\":[],\"next\":\"\"}";
    private static final long ANSWER_MS = 1500, STALE_MS = 4000, NONCE_MS = 120_000;
    private static final double MAX_SPEED_STEP = 1.0, MAX_INCLINE_STEP = 2.5;   // in one command, either way

    private final MainActivity a;
    private final Context ctx;
    private final Push push;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();

    private ServerSocket server;
    private ExecutorService pool;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private String startError = "";
    private long seenAt = 0;
    private final Map<String, Long> badUntil = new java.util.HashMap<>();   // by address, so one stale phone can't lock out another's Pause

    // What this treadmill can do, and whether a workout is under way: the page tells us (tell).
    private double mphMin = 0.5, mphMax = 12, incMin = -3, incMax = 12;
    private boolean busy = false;

    // Control from phone: the page's picture of the run, and commands waiting for its answer.
    private String runJson = "";
    private long runAt = 0, nonceAt = 0;
    private String nonce = "";
    private final AtomicLong seq = new AtomicLong();
    private final Map<Long, Waiting> waiting = new ConcurrentHashMap<>();

    private static final class Waiting {
        final CountDownLatch answered = new CountDownLatch(1);
        volatile boolean ok;
        volatile String why = "";
    }

    PhoneLink(MainActivity a, Push push) {
        this.a = a;
        this.ctx = a.getApplicationContext();
        this.push = push;
        prefs = this.ctx.getSharedPreferences("phone", Context.MODE_PRIVATE);
    }

    // ---- what the page asks ----

    void startIfOn() { if (prefs.getBoolean("on", false)) start(); }

    synchronized void setOn(boolean on) {
        prefs.edit().putBoolean("on", on).apply();
        if (on) start(); else stop();
    }

    boolean control() { return prefs.getBoolean("control", false); }

    synchronized void setControl(boolean on) {
        prefs.edit().putBoolean("control", on).apply();
        newNonce();
    }

    /** The page's picture of the run, about once a second while the link is on. */
    synchronized String run(String json) {
        runJson = json == null ? "" : json;
        runAt = SystemClock.elapsedRealtime();
        try {
            return new JSONObject().put("control", control()).put("seenAgo", seenAt == 0 ? -1 : (System.currentTimeMillis() - seenAt) / 1000).toString();
        } catch (JSONException e) { return "{}"; }
    }

    /** The page's answer to a command. */
    void answer(long id, boolean ok, String why) {
        Waiting w = waiting.get(id);
        if (w == null) return;
        w.ok = ok;
        w.why = why == null ? "" : why;
        w.answered.countDown();
    }

    /** A new code: every phone paired so far has to pair again. */
    synchronized void forget() {
        prefs.edit().putString("code", newCode()).apply();
        seenAt = 0;
    }

    synchronized void tell(double mphMin, double mphMax, double incMin, double incMax, boolean busy) {
        if (mphMax > mphMin && mphMin > 0) { this.mphMin = mphMin; this.mphMax = mphMax; }
        if (incMax > incMin) { this.incMin = incMin; this.incMax = incMax; }
        this.busy = busy;
    }

    synchronized String status() {
        try {
            String ip = lanIp();
            boolean up = server != null && !server.isClosed();
            return new JSONObject().put("on", prefs.getBoolean("on", false)).put("up", up).put("error", startError).put("control", control())
                    .put("code", code()).put("address", ip.isEmpty() ? "" : ip + ":" + PORT)
                    .put("url", ip.isEmpty() ? "" : "http://" + ip + ":" + PORT + "/#" + code())
                    .put("seenAgo", seenAt == 0 ? -1 : (System.currentTimeMillis() - seenAt) / 1000).toString();
        } catch (JSONException e) { return "{}"; }
    }

    synchronized String data() { return prefs.getString("data", EMPTY); }

    /** The workout marked next: set from the console, or cleared when it has been run. */
    synchronized void setNext(String id) {
        try {
            JSONObject d = new JSONObject(data());
            d.put("next", id == null ? "" : id);
            prefs.edit().putString("data", d.toString()).apply();
        } catch (JSONException ignored) {}
    }

    void shutdown() { stop(); }

    // ---- the server ----

    private synchronized void start() {
        if (server != null && !server.isClosed()) return;
        startError = "";
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(PORT));
            server = ss;
        } catch (Exception e) {
            startError = String.valueOf(e.getMessage());
            server = null;
            return;
        }
        // A pool, not one thread: browsers open spare connections that send nothing (see Diagnostics).
        pool = Executors.newFixedThreadPool(8);
        final ServerSocket ss = server;
        final ExecutorService workers = pool;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    workers.execute(() -> {
                        try { serve(s); } catch (Exception ignored) {}
                        finally { try { s.close(); } catch (Exception ignored) {} }
                    });
                } catch (Exception ignored) {}
            }
        }, "deck-phone");
        t.setDaemon(true);
        t.start();
        holdAwake(true);
    }

    private synchronized void stop() {
        if (server != null) { try { server.close(); } catch (Exception ignored) {} server = null; }
        if (pool != null) { pool.shutdownNow(); pool = null; }
        holdAwake(false);
    }

    /** Asleep with the screen off, Android may suspend the CPU and Wi-Fi, and the phone couldn't reach us. */
    @SuppressWarnings("deprecation")
    private void holdAwake(boolean on) {
        try {
            if (on) {
                if (cpu == null) {
                    cpu = ((PowerManager) ctx.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deck:phone");
                    cpu.setReferenceCounted(false);
                }
                if (wifi == null) {
                    wifi = ((WifiManager) ctx.getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "deck:phone");
                    wifi.setReferenceCounted(false);
                }
                if (!cpu.isHeld()) cpu.acquire();
                if (!wifi.isHeld()) wifi.acquire();
            } else {
                if (cpu != null && cpu.isHeld()) cpu.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
            }
        } catch (Exception ignored) {}
    }

    private void serve(Socket s) throws Exception {
        s.setSoTimeout(6000);
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        int c, tail = 0;
        while (tail < 2) {
            if ((c = in.read()) == -1) return;
            head.write(c);
            if (head.size() > MAX_HEAD) { reply(s, 431, "text/plain", bytes("Too long"), false); return; }
            if (c == '\n') tail++; else if (c != '\r') tail = 0;
        }
        String[] lines = head.toString("UTF-8").split("\r?\n");
        String[] first = lines[0].trim().split(" ");
        if (first.length < 2) { reply(s, 400, "text/plain", bytes("Bad request"), false); return; }
        String method = first[0], path = first[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        String sent = "";
        int length = 0;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            String k = lines[i].substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), v = lines[i].substring(colon + 1).trim();
            if (k.equals("x-deck-code")) sent = v;
            else if (k.equals("transfer-encoding")) chunked = true;
            else if (k.equals("content-length")) {
                try { length = Integer.parseInt(v); } catch (NumberFormatException e) { length = -1; }
            }
        }
        if (chunked || length < 0) { reply(s, 400, "text/plain", bytes("Bad request"), false); return; }
        if (length > MAX_BODY) { json(s, 413, fail("That workout is too big to send.")); return; }
        byte[] body = new byte[length];
        for (int got = 0; got < length; ) {
            int r = in.read(body, got, length - got);
            if (r == -1) return;
            got += r;
        }

        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) { reply(s, 200, "text/html; charset=utf-8", asset("www/phone.html"), true); return; }
        if (method.equals("GET") && path.equals("/icon.png")) { reply(s, 200, "image/png", asset("www/icon.png"), false); return; }
        if (!path.startsWith("/api/")) { reply(s, 404, "text/plain", bytes("Not found"), false); return; }

        int gate = gate(sent, s.getInetAddress().getHostAddress());
        if (gate != 0) { json(s, gate, fail(gate == 429 ? "Too many tries. Wait a moment." : "That code didn't match.")); return; }
        synchronized (this) { seenAt = System.currentTimeMillis(); }

        try {
            if (method.equals("GET") && path.equals("/api/state")) json(s, 200, state());
            else if (method.equals("POST") && path.equals("/api/workouts")) json(s, 200, save(new JSONObject(new String(body, StandardCharsets.UTF_8))));
            else if (method.equals("DELETE") && path.startsWith("/api/workouts/")) json(s, 200, delete(path.substring("/api/workouts/".length())));
            else if (method.equals("POST") && path.equals("/api/next")) json(s, 200, next(new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("id", "")));
            else if (method.equals("POST") && path.equals("/api/run")) json(s, 200, command(new JSONObject(new String(body, StandardCharsets.UTF_8))));
            else json(s, 404, fail("Not found"));
        } catch (Refused e) {
            json(s, 400, fail(e.getMessage()));
        } catch (JSONException e) {
            json(s, 400, fail("The treadmill couldn't read that."));
        }
    }

    /** 0 if the code is right. A wrong one locks that address out for a second, so guessing would take thousands of years. */
    private synchronized int gate(String sent, String from) {
        long now = System.currentTimeMillis();
        Long until = badUntil.get(from);
        if (until != null && now < until) return 429;
        String want = code();
        String got = sent.toUpperCase(java.util.Locale.ROOT).replaceAll("[^0-9A-Z]", "");
        if (MessageDigest.isEqual(want.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8))) return 0;
        if (badUntil.size() > 256) badUntil.clear();
        badUntil.put(from, now + LOCK_MS);
        return 401;
    }

    private String code() {
        String c = prefs.getString("code", "");
        if (c.length() != CODE_LEN) { c = newCode(); prefs.edit().putString("code", c).apply(); }
        return c;
    }

    private String newCode() {
        StringBuilder b = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) b.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        return b.toString();
    }

    // ---- workouts ----

    private static final class Refused extends Exception { Refused(String why) { super(why); } }

    private synchronized JSONObject state() throws JSONException {
        JSONObject d = new JSONObject(data());
        String version = "";
        try { version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        d.put("ok", true).put("version", version).put("busy", busy).put("control", control())
                .put("limits", new JSONObject().put("mphMin", mphMin).put("mphMax", mphMax).put("incMin", incMin).put("incMax", incMax));
        if (control()) {
            JSONObject r = new JSONObject();
            if (!runJson.isEmpty()) { try { r = new JSONObject(runJson); } catch (JSONException ignored) {} }
            r.put("stale", SystemClock.elapsedRealtime() - runAt > STALE_MS);
            if (nonce.isEmpty() || SystemClock.elapsedRealtime() - nonceAt > NONCE_MS) newNonce();
            d.put("run", r).put("nonce", nonce);
        }
        return d;
    }

    private void newNonce() {
        nonce = Long.toString(Math.abs(random.nextLong()), 36);
        nonceAt = SystemClock.elapsedRealtime();
    }

    /** Good once: it changes as soon as it's used, so a repeat or a stale tab can't start anything. */
    private synchronized boolean takeNonce(String sent) {
        boolean good = !nonce.isEmpty() && nonce.equals(sent);
        newNonce();
        return good;
    }

    // ---- control from phone ----

    /** Pass one command to the page and wait for its answer. Only what the page needs goes through. */
    private JSONObject command(JSONObject in) throws JSONException, Refused {
        if (!control()) throw new Refused("Control from phone is off. Turn it on at the treadmill: Settings, then Phone.");
        String cmd = in.optString("cmd", "");
        JSONObject out = new JSONObject().put("cmd", cmd);
        switch (cmd) {
            case "speed":
            case "incline": {
                double d = in.optDouble("d");
                if (bad(d) || Math.abs(d) > (cmd.equals("speed") ? MAX_SPEED_STEP : MAX_INCLINE_STEP)) throw new Refused("That's too big a change in one go.");
                out.put("d", d).put("run", in.optLong("run", 0));
                break;
            }
            case "pause":
            case "stop":
            case "done":
                out.put("run", in.optLong("run", 0));
                break;
            case "cancel":
                break;
            case "resume":
                out.put("run", in.optLong("run", 0));
                startAllowed(in);
                break;
            case "start": {
                String what = in.optString("what", "");
                if (!what.equals("next") && !what.equals("free")) throw new Refused("The treadmill couldn't read that.");
                out.put("what", what);
                startAllowed(in);
                break;
            }
            default:
                throw new Refused("The treadmill couldn't read that.");
        }
        long id = seq.incrementAndGet();
        Waiting w = new Waiting();
        waiting.put(id, w);
        boolean answered = false;
        try {
            push.push(new JSONObject().put("type", "phoneCmd").put("id", id).put("cmd", out));
            answered = w.answered.await(ANSWER_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            waiting.remove(id);
        }
        if (!answered) {
            // The page has stalled. Stopping can't wait for it; nothing else should happen without it.
            if (cmd.equals("pause") || cmd.equals("stop")) {
                a.phoneHalt();
                return state().put("note", "The treadmill’s screen didn’t answer, so the belt was paused directly.");
            }
            throw new Refused("The treadmill’s screen didn’t answer. Use the treadmill’s own controls.");
        }
        if (!w.ok) throw new Refused(w.why.isEmpty() ? "The treadmill couldn’t do that." : w.why);
        return state();
    }

    private void startAllowed(JSONObject in) throws Refused {
        if (!takeNonce(in.optString("nonce", ""))) throw new Refused("That tap was out of date. Try again.");
        if (!a.phoneCanStart()) throw new Refused("The treadmill isn’t showing C1250 Deck, so it can’t start the belt.");
    }

    private synchronized JSONObject save(JSONObject in) throws JSONException, Refused {
        JSONObject w = clean(in);
        String id = w.getString("id");
        JSONObject d = new JSONObject(data());
        JSONArray old = d.optJSONArray("list"), list = new JSONArray().put(w);
        for (int i = 0; old != null && i < old.length(); i++) {
            JSONObject o = old.getJSONObject(i);
            if (!id.equals(o.optString("id"))) list.put(o);
        }
        if (list.length() > MAX_WORKOUTS) throw new Refused("The treadmill holds " + MAX_WORKOUTS + " workouts. Delete one first.");
        d.put("list", list);
        if (in.optBoolean("next", false)) d.put("next", id);
        return changed(d);
    }

    private synchronized JSONObject delete(String id) throws JSONException {
        JSONObject d = new JSONObject(data());
        JSONArray old = d.optJSONArray("list"), list = new JSONArray();
        for (int i = 0; old != null && i < old.length(); i++) {
            JSONObject o = old.getJSONObject(i);
            if (!id.equals(o.optString("id"))) list.put(o);
        }
        d.put("list", list);
        if (id.equals(d.optString("next"))) d.put("next", "");
        return changed(d);
    }

    private synchronized JSONObject next(String id) throws JSONException, Refused {
        JSONObject d = new JSONObject(data());
        if (!id.isEmpty()) {
            boolean found = false;
            JSONArray list = d.optJSONArray("list");
            for (int i = 0; list != null && i < list.length(); i++) if (id.equals(list.getJSONObject(i).optString("id"))) found = true;
            if (!found) throw new Refused("That workout isn't on the treadmill any more.");
        }
        d.put("next", id);
        return changed(d);
    }

    private JSONObject changed(JSONObject d) throws JSONException {
        prefs.edit().putString("data", d.toString()).apply();
        push.push(new JSONObject().put("type", "phone").put("data", new JSONObject(d.toString())));
        return state();
    }

    /** A workout from the network, down to only what the console will use. Speed and incline are held to this treadmill's limits. */
    private JSONObject clean(JSONObject in) throws JSONException, Refused {
        String name = in.optString("name", "").replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}<>]", "").trim();
        if (name.isEmpty()) throw new Refused("Give this workout a name.");
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME).trim();

        JSONArray blocks = in.optJSONArray("blocks");
        if (blocks == null || blocks.length() == 0) throw new Refused("Add at least one step.");
        if (blocks.length() > MAX_BLOCKS) throw new Refused("That workout has too many steps.");

        JSONArray cleanBlocks = new JSONArray(), segs = new JSONArray();
        int total = 0;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.optJSONObject(i);
            if (b == null) throw new Refused("The treadmill couldn't read that.");
            if ("repeat".equals(b.optString("kind"))) {
                int times = b.optInt("times", 0);
                if (times < MIN_TIMES || times > MAX_TIMES) throw new Refused("A repeat runs " + MIN_TIMES + " to " + MAX_TIMES + " times.");
                JSONArray steps = b.optJSONArray("steps");
                if (steps == null || steps.length() == 0) throw new Refused("A repeat needs at least one step.");
                if (steps.length() > MAX_INNER) throw new Refused("A repeat holds up to " + MAX_INNER + " steps.");
                JSONArray cleanSteps = new JSONArray();
                for (int k = 0; k < steps.length(); k++) cleanSteps.put(step(steps.optJSONObject(k)));
                cleanBlocks.put(new JSONObject().put("kind", "repeat").put("times", times).put("steps", cleanSteps));
                for (int t = 1; t <= times; t++) {
                    for (int k = 0; k < cleanSteps.length(); k++) {
                        JSONObject st = cleanSteps.getJSONObject(k);
                        total += st.getInt("d");
                        segs.put(seg(st, "run".equals(st.getString("ph")) ? "Rep " + t + " of " + times : null));
                    }
                }
            } else {
                JSONObject st = step(b);
                cleanBlocks.put(st);
                total += st.getInt("d");
                segs.put(seg(st, null));
            }
            if (segs.length() > MAX_SEGS) throw new Refused("That workout has too many steps.");
        }
        if (total > MAX_TOTAL) throw new Refused("A workout can be up to " + (MAX_TOTAL / 3600) + " hours long.");

        String id = in.optString("id", "");
        if (!id.matches("[a-z0-9]{4,16}")) {
            StringBuilder b = new StringBuilder("w");
            for (int i = 0; i < 9; i++) b.append("abcdefghijklmnopqrstuvwxyz0123456789".charAt(random.nextInt(36)));
            id = b.toString();
        }
        return new JSONObject().put("id", id).put("name", name).put("blocks", cleanBlocks).put("segs", segs).put("updated", System.currentTimeMillis());
    }

    private JSONObject step(JSONObject in) throws JSONException, Refused {
        if (in == null) throw new Refused("The treadmill couldn't read that.");
        String ph = in.optString("ph", "");
        if (label(ph) == null) throw new Refused("The treadmill couldn't read that.");
        double d = in.optDouble("d"), mph = in.optDouble("mph"), inc = in.optDouble("inc");
        if (bad(d) || bad(mph) || bad(inc)) throw new Refused("The treadmill couldn't read that.");
        int secs = (int) Math.max(MIN_SECS, Math.min(MAX_SECS, Math.round(d / MIN_SECS) * MIN_SECS));
        return new JSONObject().put("ph", ph).put("d", secs)
                .put("mph", Math.round(Math.max(mphMin, Math.min(mphMax, mph)) * 10) / 10.0)
                .put("inc", Math.round(Math.max(incMin, Math.min(incMax, inc)) * 2) / 2.0);
    }

    private static boolean bad(double v) { return Double.isNaN(v) || Double.isInfinite(v); }

    private static JSONObject seg(JSONObject st, String label) throws JSONException {
        return new JSONObject().put("d", st.getInt("d")).put("mph", st.getDouble("mph")).put("inc", st.getDouble("inc"))
                .put("label", label != null ? label : label(st.getString("ph"))).put("ph", st.getString("ph"));
    }

    private static String label(String ph) {
        switch (ph) {
            case "warm": return "Warm up";
            case "walk": return "Walk";
            case "run": return "Run";
            case "rec": return "Recover";
            case "climb": return "Climb";
            case "cool": return "Cool down";
            default: return null;
        }
    }

    // ---- replies ----

    private static JSONObject fail(String why) {
        try { return new JSONObject().put("ok", false).put("why", why); } catch (JSONException e) { return new JSONObject(); }
    }

    private void json(Socket s, int status, JSONObject o) throws Exception { reply(s, status, "application/json; charset=utf-8", bytes(o.toString()), false); }

    private static byte[] bytes(String t) { return t.getBytes(StandardCharsets.UTF_8); }

    private byte[] asset(String name) throws Exception {
        try (InputStream a = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = a.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }

    private static void reply(Socket s, int status, String type, byte[] b, boolean page) throws Exception {
        String words = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found"
                : status == 413 ? "Payload Too Large" : status == 429 ? "Too Many Requests" : status == 431 ? "Request Header Fields Too Large" : "Error";
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(words)
                .append("\r\nContent-Type: ").append(type)
                .append("\r\nContent-Length: ").append(b.length)
                .append("\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer");
        if (page) h.append("\r\nContent-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        h.append("\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(bytes(h.toString()));
        out.write(b);
        out.flush();
        s.shutdownOutput();
    }

    /** The Wi-Fi address, before anything else that's up. "" if there's no network. */
    private static String lanIp() {
        String other = "";
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")) return a.getHostAddress();
                    if (other.isEmpty()) other = a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return other;
    }
}
