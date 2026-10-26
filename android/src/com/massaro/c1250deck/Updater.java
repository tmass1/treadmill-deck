package com.massaro.c1250deck;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Over-the-air updates. Reads a small manifest from the update site, and when a newer build is
 * offered, downloads it, checks its SHA-256 against the manifest, and hands it to Android's
 * PackageInstaller. Android itself refuses any APK not signed with this app's key, so a
 * tampered or foreign build can't be installed even if the site were compromised.
 *
 * Manifest: {"versionCode":4,"versionName":"1.3","apk":"c1250-deck-1.3.apk","sha256":"…","size":123,"notes":"…"}
 * "apk" may be absolute or relative to the manifest URL.
 */
public class Updater {
    public interface Sink { void send(JSONObject msg); }

    static final String DEFAULT_MANIFEST = Secrets.SITE + "/update/manifest.json";
    static final String ACTION_STATUS = "com.massaro.c1250deck.INSTALL_STATUS";

    /** The live updater, so UpdateReceiver can report install results back to the screen. */
    static volatile Updater current;

    private final Activity act;
    private final Sink sink;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private JSONObject offer;
    private volatile boolean busy;

    public Updater(Activity a, Sink s) {
        act = a;
        sink = s;
        prefs = a.getSharedPreferences("update", Context.MODE_PRIVATE);
        current = this;
    }

    public int versionCode() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    public String versionName() {
        try { return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private String manifestUrl() { return prefs.getString("manifest", DEFAULT_MANIFEST); }

    /** Report a failed install from a previous attempt (stored by UpdateReceiver), then check. */
    public void startup() {
        String failed = prefs.getString("lastError", null);
        if (failed != null) {
            prefs.edit().remove("lastError").apply();
            send("error", failed, -1);
        }
        check(false);
    }

    public void check(boolean manual) {
        if (busy) return;
        io.execute(() -> {
            try {
                if (manual) send("checking", "", -1);
                JSONObject m = new JSONObject(new String(get(manifestUrl() + "?t=" + System.currentTimeMillis()), "UTF-8"));
                int vc = m.getInt("versionCode");
                if (vc > versionCode()) {
                    offer = m;
                    JSONObject o = base("available");
                    o.put("version", m.optString("versionName", String.valueOf(vc)));
                    o.put("notes", m.optString("notes", ""));
                    o.put("size", m.optLong("size", 0));
                    sink.send(o);
                } else {
                    offer = null;
                    send("current", "", -1);
                }
            } catch (Exception e) {
                if (manual) send("error", "Couldn't check for updates. Check the treadmill's Wi-Fi and try again.", -1);
            }
        });
    }

    public void install() {
        KeepFront.leaving("update");
        final JSONObject m = offer;
        if (m == null || busy) return;
        busy = true;
        io.execute(() -> {
            File apk = new File(act.getCacheDir(), "update.apk");
            try {
                String href = m.getString("apk");
                URL url = new URL(new URL(manifestUrl()), href);
                if (!"https".equals(url.getProtocol())) throw new Exception("Update link isn't secure.");
                send("downloading", "", 0);
                download(url, apk, m.optLong("size", 0));

                String want = m.getString("sha256").toLowerCase();
                if (!want.equals(sha256(apk))) throw new Exception("The download didn't match the published update, so it wasn't installed.");

                send("installing", "", -1);
                commit(apk);
            } catch (Exception e) {
                busy = false;
                apk.delete();
                send("error", e.getMessage() != null ? e.getMessage() : "Update failed.", -1);
            }
        });
    }

    private void commit(File apk) throws Exception {
        PackageInstaller pi = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(act.getPackageName());
        p.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(act, UpdateReceiver.class).setAction(ACTION_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(act, id, i, flags);
            s.commit(pend.getIntentSender());
        }
    }

    /** Called by UpdateReceiver when Android reports a failure (success restarts the app instead). */
    void installFailed(String msg) {
        busy = false;
        send("error", msg, -1);
    }

    void needsConfirmation() { send("confirm", "", -1); }

    // ---------- network ----------

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("X-Deck-Key", Secrets.DECK_KEY);   // the update channel is private (worker/index.js)
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                return o.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    private void download(URL url, File out, long expected) throws Exception {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("X-Deck-Key", Secrets.DECK_KEY);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new Exception("Download failed (HTTP " + c.getResponseCode() + ").");
            long total = expected > 0 ? expected : c.getContentLength();
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] b = new byte[16384];
                long got = 0;
                int n, last = -1;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) Math.min(100, got * 100 / total);
                        if (pct != last && pct % 5 == 0) { last = pct; send("downloading", "", pct); }
                    }
                }
            }
        } finally { c.disconnect(); }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------- messages to the page ----------

    private JSONObject base(String state) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "update");
        o.put("state", state);
        o.put("current", versionName());
        return o;
    }

    private void send(String state, String message, int progress) {
        try {
            JSONObject o = base(state);
            o.put("message", message);
            if (progress >= 0) o.put("progress", progress);
            if (offer != null) o.put("version", offer.optString("versionName", ""));
            sink.send(o);
        } catch (Exception ignored) {}
    }

    static void rememberError(Context c, String msg) {
        c.getSharedPreferences("update", Context.MODE_PRIVATE).edit().putString("lastError", msg).apply();
    }
}
