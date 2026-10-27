package com.massaro.c1250deck;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Full-screen console: the C1250 Deck page in a WebView, with a bridge (window.DeckNative)
 * to the motor. Since 1.8 that's the USB motor board directly (Belt.java, FitPro.java); the
 * GlassOS gRPC service is the fallback for a console that still has it. All hardware calls run
 * in order on one background thread, and the belt state is pushed to the page four times a second.
 */
public class MainActivity extends Activity {
    private static final String TAG = "C1250Deck";
    private static final String[] CONSOLE_STATES = {"DISCONNECTED", "UNKNOWN", "IDLE", "WORKOUT", "PAUSED", "RESULTS",
            "SAFETY_KEY", "WARMUP", "COOLDOWN", "RESUME", "LOCKED", "DEMO", "SLEEP", "ERROR"};

    private WebView web;
    private final GlassOS glass = new GlassOS();
    private Belt belt;
    private ScheduledExecutorService hw;
    private long nextInitTry = 0;
    private String lastError = null;
    private HeartRate hr;
    private Updater updater;
    private HomeAuth ha;
    private boolean hrResumed = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WebView.setWebContentsDebuggingEnabled(true);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        hr = new HeartRate(this, this::pushHr);
        updater = new Updater(this, this::pushHr);
        ha = new HomeAuth(this, this::pushHr);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (!hrResumed) { hrResumed = true; hr.resume(); updater.startup(); }
            }
        });
        web.setBackgroundColor(0xFF08090A);
        web.addJavascriptInterface(new Bridge(this), "DeckNative");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");

        belt = new Belt(this);
        hw = Executors.newSingleThreadScheduledExecutor();
        hw.scheduleWithFixedDelay(this::poll, 500, 250, TimeUnit.MILLISECONDS);
        // Look for a new version every 6 hours; the console stays on for days at a time.
        hw.scheduleWithFixedDelay(() -> updater.check(false), 6, 6, TimeUnit.HOURS);
        // Running, so it hears the screen wake and can take the console back from iFit (KeepFront).
        KeepFront.start(this, "app start", 60);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @Override
    public void onBackPressed() {
        // The console is the whole app; ignore Back so a stray tap can't hide the STOP button.
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        hr.onPermissionResult(code, results);
    }

    @Override
    protected void onStart() {
        super.onStart();
        KeepFront.visible = true;
        if (hw != null) hw.execute(() -> belt.resume());
    }

    @Override
    protected void onStop() {
        KeepFront.visible = false;
        // Off screen: pause a running belt and let go of the board, so iFit can have it if it's opened.
        if (hw != null) hw.execute(() -> belt.suspend());
        super.onStop();
    }

    /** reportOut on its own thread (it uploads), for callers on the hardware thread. */
    void reportLater(String kind, String text) {
        new Thread(() -> reportOut(kind, text), "deck-report").start();
    }

    /** Is the USB motor board the driver? (It is whenever one is plugged in.) */
    private boolean usb() { return belt.holding() || belt.attached(); }

    /** Show a report on the Diagnostics sheet, then upload it to the site's report box. */
    void reportOut(String kind, String text) {
        try { pushHr(new JSONObject().put("type", "diag").put("kind", kind).put("text", text)); } catch (Exception ignored) {}
        String why = Reports.send(kind, updater.versionName(), text);
        try { pushHr(new JSONObject().put("type", "diagSent").put("kind", kind).put("ok", why == null).put("why", why == null ? "" : why)); }
        catch (Exception ignored) {}
    }

    private void pushHr(JSONObject o) {
        final String js = "window.__deckHr && window.__deckHr(" + o.toString() + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onDestroy() {
        Diagnostics.stopShare();
        if (hr != null) hr.shutdown();
        if (hw != null) { hw.execute(() -> belt.suspend()); hw.shutdown(); }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Make sure the GlassOS client is up. Returns false (and reports why) if it isn't. */
    private boolean ensureReady() {
        if (glass.ready()) return true;
        long now = System.currentTimeMillis();
        if (now < nextInitTry) return false;
        nextInitTry = now + 3000;
        try {
            glass.init(this);
            glass.connect();
            lastError = null;
            return true;
        } catch (GlassOS.CertsMissing e) {
            push(error("certs", e.getMessage()));
        } catch (Exception e) {
            Log.w(TAG, "init", e);
            push(error("service", String.valueOf(e.getMessage())));
        }
        return false;
    }

    private void poll() {
        if (belt.suspended) return;
        if (usb()) {
            JSONObject st = belt.tick();
            if (st != null) push(st);
            else if (belt.problem != null) push(error("board", belt.problem));
            return;
        }
        if (!ensureReady()) return;
        try {
            JSONObject st = new JSONObject();
            int cs = glass.consoleState();
            st.put("ok", true);
            st.put("console", cs >= 0 && cs < CONSOLE_STATES.length ? CONSOLE_STATES[cs] : "UNKNOWN");
            st.put("workout", glass.workoutState());
            st.put("kph", glass.speedKph());
            st.put("incline", glass.inclinePct());
            st.put("distKm", glass.distanceKm());
            st.put("elapsed", glass.elapsedSec());
            st.put("kcal", glass.calories());
            if (lastError != null) { st.put("cmdError", lastError); lastError = null; }
            push(st);
        } catch (Exception e) {
            Log.w(TAG, "poll", e);
            push(error("service", String.valueOf(e.getMessage())));
        }
    }

    private JSONObject error(String kind, String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("error", kind); o.put("message", msg); } catch (Exception ignored) {}
        return o;
    }

    private void push(JSONObject o) {
        final String js = "window.__deckNative && window.__deckNative(" + o.toString() + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private interface HwTask { void run() throws Exception; }

    private void command(String name, Runnable usbTask, HwTask t) {
        hw.execute(() -> {
            if (usb()) { usbTask.run(); return; }
            if (!ensureReady()) return;
            try { t.run(); } catch (Exception e) {
                Log.w(TAG, name, e);
                lastError = name + ": " + e.getMessage();
            }
        });
    }

    /**
     * Exposed to the page as window.DeckNative. A static class with an explicit constructor:
     * the dexer in build-tools 34 chokes on the implicit outer-instance parameter of inner classes.
     */
    static final class Bridge {
        private final MainActivity a;
        private final GlassOS g;

        Bridge(MainActivity activity) { a = activity; g = activity.glass; }

        @JavascriptInterface public boolean isConsole() { return true; }

        @JavascriptInterface public void hrScan() { a.runOnUiThread(() -> a.hr.scan()); }

        @JavascriptInterface public void hrConnect(String id) { a.runOnUiThread(() -> a.hr.connectTo(id)); }

        @JavascriptInterface public void hrForget() { a.runOnUiThread(() -> a.hr.forget()); }

        @JavascriptInterface public String hrSaved() { return a.hr.savedName(); }

        @JavascriptInterface public String appVersion() { return a.updater.versionName(); }

        @JavascriptInterface public void updateCheck() { a.updater.check(true); }

        @JavascriptInterface public void updateInstall() { a.updater.install(); }

        @JavascriptInterface public void runDiagnostics() {
            new Thread(() -> {
                String text;
                try { text = Diagnostics.report(a); } catch (Throwable t) { text = "Diagnostics failed: " + t; }
                a.reportOut("report", text);
            }, "deck-diag").start();
        }

        /** The USB motor link: how the board answered, and what it says now. Moves nothing. */
        @JavascriptInterface public void motorTest() {
            a.hw.execute(() -> {
                String text;
                try { text = a.belt.attached() || a.belt.holding() ? a.belt.linkReport() : FitPro.readOnlyTest(a); }
                catch (Throwable t) { text = "Motor link test failed: " + t; }
                a.reportLater("motor", text);
            });
        }

        /** The belt test: the slowest speed for 10 seconds, then stop (Belt.beltTest). */
        @JavascriptInterface public void beltTest() {
            a.hw.execute(() -> {
                if (!a.belt.attached() && !a.belt.holding()) { a.reportLater("belt", "Belt test: no USB motor board on this console."); return; }
                a.belt.beltTest(text -> a.reportLater("belt", text));
            });
        }

        /** Gym lights (1.8.1 pages): the gym webhook with an empty body. */
        @JavascriptInterface public void homeWebhook(String url) { homeCall(url, "{}", "gym"); }

        /** POST a JSON body to a Home Assistant webhook on the local network; the answer comes back as {type:'home', kind}. */
        @JavascriptInterface public void homeCall(String url, String body, String kind) {
            new Thread(() -> {
                String why = null;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(4000); c.setReadTimeout(4000);
                    c.setRequestMethod("POST"); c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.getOutputStream().write((body == null || body.isEmpty() ? "{}" : body).getBytes("UTF-8"));
                    int code = c.getResponseCode();
                    c.disconnect();
                    if (code < 200 || code >= 300) why = "It answered " + code + ".";
                } catch (Exception e) {
                    why = e instanceof java.net.SocketTimeoutException ? "No answer in 4 seconds." : String.valueOf(e.getMessage());
                }
                try { a.pushHr(new JSONObject().put("type", "home").put("kind", kind).put("ok", why == null).put("why", why == null ? "" : why)); } catch (Exception ignored) {}
            }, "deck-home").start();
        }

        /** Is C1250 Deck Android's Home app (so the console starts here on power-up)? */
        @JavascriptInterface public boolean isHome() {
            try {
                android.content.pm.ResolveInfo r = a.getPackageManager().resolveActivity(
                        new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                        android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
                return r != null && r.activityInfo != null && a.getPackageName().equals(r.activityInfo.packageName);
            } catch (Exception e) { return false; }
        }

        /** Open Android's Home app picker; falls back to the general Settings app. */
        @JavascriptInterface public void chooseHome() {
            KeepFront.leaving("Settings");
            a.runOnUiThread(() -> {
                try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); return; } catch (Exception ignored) {}
                try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
            });
        }

        /** Upload a finished run (history backup, and the hand-off to Apple Fitness); the answer comes back as {type:'runSent'}. */
        @JavascriptInterface public void uploadRun(String id, String json) {
            new Thread(() -> {
                String why = Reports.sendRun(json);
                try { a.pushHr(new JSONObject().put("type", "runSent").put("id", id).put("ok", why == null).put("why", why == null ? "" : why)); } catch (Exception ignored) {}
            }, "deck-run").start();
        }

        /** Cameras: Home Assistant's own sign-in (HomeAuth). base is like http://192.168.4.31:80. */
        @JavascriptInterface public void haSignIn(String base) { a.ha.signIn(base); }
        @JavascriptInterface public boolean haSignedIn() { return a.ha.signedIn(); }
        @JavascriptInterface public String haBase() { return a.ha.base(); }
        /** A current access token for Home Assistant's websocket, or "" (may take a moment: it refreshes when due). */
        @JavascriptInterface public String haToken() { return a.ha.token(); }
        @JavascriptInterface public void haSignOut() { a.ha.signOut(); }

        /** Keep iFit away: can we see which app is in front (Usage access)? */
        @JavascriptInterface public boolean usageAccess() { return KeepFront.usageAccess(a); }
        /** Open Android's Usage access list, where C1250 Deck is switched on. */
        @JavascriptInterface public void openUsageAccess() {
            KeepFront.leaving("Usage access settings");
            a.runOnUiThread(() -> {
                try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)); return; } catch (Exception ignored) {}
                try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
            });
        }

        /** A quiet upload to the site's report box (no Diagnostics sheet), e.g. kind "camera" from a live view. */
        @JavascriptInterface public void sendReport(String kind, String text) {
            final String v = a.updater.versionName();
            new Thread(() -> Reports.send(kind == null ? "report" : kind, v, text == null ? "" : text), "deck-report").start();
        }

        @JavascriptInterface public String shareApps(boolean on) {
            if (!on) { Diagnostics.stopShare(); return ""; }
            return Diagnostics.startShare(a);
        }

        @JavascriptInterface public void start() {
            a.command("start", () -> a.belt.start(), () -> {
                int ws = g.workoutState();
                if (ws == GlassOS.WK_PAUSED) g.resume();
                else if (ws != GlassOS.WK_RUNNING) g.startWorkout();
            });
        }

        @JavascriptInterface public void pause() { a.command("pause", () -> a.belt.pause(), () -> g.pause()); }

        @JavascriptInterface public void stop() { a.command("stop", () -> a.belt.stop(), () -> g.stop()); }

        @JavascriptInterface public void setSpeedKph(double kph) {
            final double k = Math.max(0, Math.min(19.3, kph));
            a.command("speed", () -> a.belt.speed(k), () -> g.setSpeed(k));
        }

        @JavascriptInterface public void setIncline(double pct) {
            final double p = Math.max(-3, Math.min(12, pct));
            a.command("incline", () -> a.belt.incline(p), () -> g.setIncline(p));
        }
    }
}
