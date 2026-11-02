package com.massaro.c1250deck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;

import org.json.JSONObject;

/**
 * Turning the screen fully off when the page sleeps (1.10.19).
 *
 * The page's sleep is a black screen, and the window's backlight override only goes down to the
 * panel's minimum on this console: it still glows (2 Nov 2026). Only Android switching the display
 * off makes it dark, and no ordinary app can do that directly. What an app can do: stop holding the
 * screen on (FLAG_KEEP_SCREEN_ON) and, with "Modify system settings" granted, shorten Android's own
 * screen timeout to 10 s while asleep, putting the person's value back on waking.
 *
 * Waking from a real screen-off is the risk: a tablet usually wakes from its power button, and this
 * console's is out of reach. So a real screen-off is only used after Try it has shown something wakes
 * it: a tap on the screen, or the console's Start key (the app keeps listening to the motor board
 * while the screen is off, and turns it back on with a wake lock when Start is pressed). Try it turns
 * the screen back on by itself after 15 s if nothing did.
 */
final class ScreenSleep {
    interface Push { void push(JSONObject o); }

    private static final int SLEEP_TIMEOUT_MS = 10_000;
    private final MainActivity a;
    private final Push push;
    private final SharedPreferences prefs;
    private final PowerManager pm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();

    volatile boolean sleeping = false;   // the page is asleep (black)
    volatile boolean testing = false;    // Try it is running
    private volatile String wokeBy = null;
    private long testAt = 0, offAt = 0;

    ScreenSleep(MainActivity a, Push push) {
        this.a = a; this.push = push;
        prefs = a.getSharedPreferences("sleep", Context.MODE_PRIVATE);
        pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        restoreTimeout();   // the app went down while asleep: put the person's timeout back
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        a.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) screenOff(); else screenOn();
            }
        }, f);
    }

    boolean enabled() { return prefs.getBoolean("off", false); }
    void setEnabled(boolean on) { prefs.edit().putBoolean("off", on).apply(); }
    boolean canWrite() { try { return Settings.System.canWrite(a); } catch (Exception e) { return false; } }
    int systemTimeout() { try { return Settings.System.getInt(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT); } catch (Exception e) { return -1; } }

    /** What the Settings row shows. */
    String status() {
        try {
            int t = prefs.getInt("orig", -1) > 0 ? prefs.getInt("orig", -1) : systemTimeout();
            return new JSONObject().put("enabled", enabled()).put("canWrite", canWrite()).put("timeoutMs", t)
                    .put("result", prefs.getString("result", "")).toString();
        } catch (Exception e) { return "{}"; }
    }

    void openPermission() {
        KeepFront.leaving("modify system settings");
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + a.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try { a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
        }
    }

    /** The page went to sleep (black) or woke. */
    void sleep(boolean on) {
        sleeping = on;
        a.setBacklightOff(on);
        if (on && enabled()) letScreenGo();
        else if (!on) { holdScreen(); wake("page"); }   // the doorbell, or anything else the page wakes for
    }

    /** The console's Start key, while the screen may be off. */
    void startKey() { if (!pm.isInteractive()) wake("start"); }

    /** A doorbell or anything else that should light the screen. */
    void wake(String why) {
        if (pm.isInteractive()) return;
        wokeBy = why;
        note("waking: " + why);
        try {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "deck:wake");
            wl.acquire(3000);
        } catch (Exception e) { note("wake failed: " + e.getMessage()); }
    }

    /** Try it: turn the screen off now and see what wakes it. */
    void test() {
        if (testing) return;
        int t = systemTimeout();
        if (!canWrite() && (t < 0 || t > 60_000)) { result("permission"); return; }
        testing = true; wokeBy = null; offAt = 0; testAt = SystemClock.elapsedRealtime();
        log.setLength(0);
        note("test: system timeout " + t + " ms, can write " + canWrite());
        letScreenGo();
        final long at = testAt;
        // Android's timeout counts from the last touch (the tap on Try it), so off in about 10 s.
        h.postDelayed(() -> { if (testing && testAt == at && offAt == 0) { holdScreen(); result("stayedOn"); } }, 45_000);
    }

    // ---- inside ----

    private void letScreenGo() {
        a.runOnUiThread(() -> a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        if (canWrite()) {
            try {
                if (prefs.getInt("orig", -1) < 0) prefs.edit().putInt("orig", systemTimeout()).apply();
                Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, SLEEP_TIMEOUT_MS);
            } catch (Exception e) { note("couldn't set the timeout: " + e.getMessage()); }
        }
    }

    private void holdScreen() {
        a.runOnUiThread(() -> a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        restoreTimeout();
    }

    private void restoreTimeout() {
        int orig = prefs.getInt("orig", -1);
        if (orig < 0 || !canWrite()) return;
        try { Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, orig); } catch (Exception ignored) {}
        prefs.edit().remove("orig").apply();
    }

    private void screenOff() {
        note("screen off");
        if (!testing || offAt != 0) return;
        offAt = SystemClock.elapsedRealtime();
        final long at = testAt;
        // Nothing woke it in 15 s: turn it back on ourselves.
        h.postDelayed(() -> { if (testing && testAt == at && !pm.isInteractive()) wake("timer"); }, 15_000);
    }

    private void screenOn() {
        note("screen on" + (wokeBy != null ? " (" + wokeBy + ")" : ""));
        if (testing && offAt != 0) {
            String by = wokeBy != null ? wokeBy : "tap";
            holdScreen();
            result(by);
        }
        wokeBy = null;
    }

    private void result(String r) {
        testing = false;
        if (r.equals("tap") || r.equals("start")) setEnabled(true);
        if (!r.equals("permission")) prefs.edit().putString("result", r).apply();
        note("result: " + r);
        try { push.push(new JSONObject().put("type", "screenTest").put("result", r).put("status", new JSONObject(status()))); } catch (Exception ignored) {}
        final String text = "Screen off test: " + r + "\n" + status() + "\n\n" + log;
        new Thread(() -> Reports.send("sleep", "", text), "deck-sleep").start();
    }

    private synchronized void note(String s) {
        if (log.length() > 4000) log.delete(0, 1000);
        log.append((SystemClock.elapsedRealtime() - testAt) / 1000.0).append("s ").append(s).append('\n');
    }
}
