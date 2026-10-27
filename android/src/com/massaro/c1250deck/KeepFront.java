package com.massaro.c1250deck;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps C1250 Deck in front after the treadmill powers up or wakes, when iFit's own app opens a
 * little later and takes the screen (the boot-time relaunches alone gave up at 45 s).
 *
 * For 4 minutes after boot, and 2 minutes after the screen comes on, it looks every 3 seconds and
 * brings the console back if something else is in front, unless you left on purpose (Settings,
 * an update) in the last 10 minutes; at most 20 tries a window. Outside those windows it does nothing. A foreground service so
 * Android keeps it, and so it hears the screen come on; the console hides the notification bar.
 *
 * Three minutes after each boot it uploads a "startup" report (Reports): what it saw and did, which
 * app answers Home, and whether a kiosk lock (lock task) was holding the screen, which would block
 * any app from coming forward and is the one thing this can't undo.
 */
public class KeepFront extends Service {
    static volatile boolean visible = false;     // MainActivity onStart / onStop
    static volatile long leftAt = 0;              // elapsedRealtime when we opened something else on purpose
    private static final StringBuilder LOG = new StringBuilder();
    private static final String CH = "keepfront";

    private final Handler h = new Handler(Looper.getMainLooper());
    private long until = 0;
    private boolean ticking = false, reported = false;
    private int relaunches = 0, windowTries = 0;
    private long lastReport = 0, pauseUntil = 0;
    private final java.util.ArrayDeque<Long> recent = new java.util.ArrayDeque<>();
    private BroadcastReceiver screen;
    private Notification notif;

    /** Start or extend the window. why is a word for the log; seconds from now. */
    static void start(Context c, String why, int seconds) {
        Intent i = new Intent(c, KeepFront.class).putExtra("why", why).putExtra("secs", seconds);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { note("couldn't start: " + e.getMessage()); }
    }

    /** We're opening another screen on purpose: don't pull the console back over it. */
    static void leaving(String what) { leftAt = SystemClock.elapsedRealtime(); note("left on purpose: " + what); }

    static synchronized void note(String s) {
        if (LOG.length() > 12000) LOG.delete(0, 4000);
        LOG.append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
           .append(" (+").append(SystemClock.elapsedRealtime() / 1000).append("s) ").append(s).append('\n');
    }

    /** Has Usage access been granted (Settings → Keep iFit away)? It's how we see which app is in front. */
    static boolean usageAccess(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    /** The app most recently brought to the front (from usage events), or "" without usage access. */
    static String front(Context c) {
        try {
            UsageStatsManager u = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents ev = u.queryEvents(now - 6 * 3600_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = "";
            while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.getPackageName(); }
            return last;
        } catch (Exception e) { return ""; }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Console", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CH);
        } else b = new Notification.Builder(this);
        notif = b.setSmallIcon(R.drawable.ic_launcher).setContentTitle("C1250 Deck").setContentText("Keeping the console in front").build();
        startForeground(7, notif);
        screen = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { note("screen: " + i.getAction()); extend(120); }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, f);
        // The startup report, once, three minutes into this boot.
        long sinceBoot = SystemClock.elapsedRealtime();
        if (sinceBoot < 10 * 60_000L) h.postDelayed(() -> report("Startup"), Math.max(5_000L, 180_000L - sinceBoot));
        // With usage access: watch all the time, and send iFit back whenever it takes the screen.
        h.postDelayed(this::watch, 2000);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        // Every startForegroundService needs a startForeground, even when the service is already
        // running; without it Android closes the whole app five seconds later.
        if (notif != null) startForeground(7, notif);
        String why = i != null ? i.getStringExtra("why") : "restarted";
        int secs = i != null ? i.getIntExtra("secs", 120) : 120;
        note("window " + secs + "s (" + why + ")" + lockState());
        extend(secs);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(screen); } catch (Exception ignored) {}
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void extend(int secs) {
        if (!ticking) windowTries = 0;
        until = Math.max(until, SystemClock.elapsedRealtime() + secs * 1000L);
        if (!ticking) { ticking = true; h.postDelayed(this::tick, 3000); }
    }

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        if (now > until) { ticking = false; note("window over (" + relaunches + " relaunches)"); return; }
        if (!visible && now - leftAt > 10 * 60_000L) {
            // At most 20 tries a window (about a minute of trying): past that, whatever is in front
            // is probably there on purpose (privileged mode, Android settings), so leave it.
            if (windowTries >= 20) { ticking = false; until = 0; note("gave up after 20 tries" + lockState()); report("Gave up: something else stayed in front"); return; }
            windowTries++;
            relaunches++;
            note("not in front, relaunching" + lockState());
            try {
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception e) { note("relaunch refused: " + e.getMessage()); }
        }
        h.postDelayed(this::tick, 3000);
    }

    /** Every 2 s: if iFit is in front (and we didn't leave on purpose), bring the console back. */
    private void watch() {
        h.postDelayed(this::watch, 2000);
        long now = SystemClock.elapsedRealtime();
        if (visible || now < pauseUntil || now - leftAt < 10 * 60_000L || !usageAccess(this)) return;
        String fg = front(this);
        if (!fg.startsWith("com.ifit.")) return;
        while (!recent.isEmpty() && now - recent.peekFirst() > 60_000L) recent.pollFirst();
        if (recent.size() >= 15) { pauseUntil = now + 60_000L; note(fg + " keeps coming back; resting a minute" + lockState()); report("iFit keeps coming back"); return; }
        recent.addLast(now);
        relaunches++;
        note(fg + " in front, bringing the console back" + lockState());
        if (recent.size() == 1) h.postDelayed(() -> report("iFit took the screen (" + fg + ")"), 8000);
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception e) { note("relaunch refused: " + e.getMessage()); }
    }

    /** " · kiosk lock: locked" when a lock task is holding the screen. */
    private String lockState() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            int s = Build.VERSION.SDK_INT >= 23 ? am.getLockTaskModeState() : 0;
            return s == ActivityManager.LOCK_TASK_MODE_LOCKED ? " · kiosk lock: locked"
                 : s == ActivityManager.LOCK_TASK_MODE_PINNED ? " · kiosk lock: pinned" : "";
        } catch (Exception e) { return ""; }
    }

    /** Upload what happened (kind "startup"): at most one every 10 minutes. */
    private void report(String title) {
        long now = SystemClock.elapsedRealtime();
        if (lastReport != 0 && now - lastReport < 10 * 60_000L) return;
        lastReport = now;
        StringBuilder r = new StringBuilder(title + "\n");
        r.append("Usage access: ").append(usageAccess(this) ? "on · in front now: " + front(this) : "off").append('\n');
        r.append("Up ").append(SystemClock.elapsedRealtime() / 1000).append(" s · console ").append(visible ? "in front" : "NOT in front")
         .append(" · relaunches ").append(relaunches).append(lockState()).append('\n');
        PackageManager pm = getPackageManager();
        try {
            ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            r.append("Home app: ").append(home == null || home.activityInfo == null ? "none" : home.activityInfo.packageName).append('\n');
        } catch (Exception ignored) {}
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            for (String p : new String[]{"com.ifit.arda", "com.ifit.eru", "com.ifit.launcher", "com.ifit.standalone"}) {
                boolean owner = dpm.isDeviceOwnerApp(p), lock = Build.VERSION.SDK_INT >= 26 && dpm.isLockTaskPermitted(p);
                boolean on;
                try { on = pm.getApplicationInfo(p, 0).enabled; } catch (Exception e) { on = false; }
                r.append(p).append(": ").append(on ? "enabled" : "disabled/missing")
                 .append(owner ? " · DEVICE OWNER" : "").append(lock ? " · may kiosk-lock" : "").append('\n');
            }
            r.append(getPackageName()).append(": may kiosk-lock ").append(Build.VERSION.SDK_INT >= 26 && dpm.isLockTaskPermitted(getPackageName())).append('\n');
        } catch (Exception e) { r.append("policy: ").append(e.getMessage()).append('\n'); }
        r.append("\nLog:\n");
        synchronized (KeepFront.class) { r.append(LOG); }
        final String text = r.toString();
        final String v;
        String vv = "?";
        try { vv = pm.getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        v = vv;
        new Thread(() -> Reports.send("startup", v, text), "deck-startup").start();
    }
}
