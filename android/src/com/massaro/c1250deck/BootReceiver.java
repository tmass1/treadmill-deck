package com.massaro.c1250deck;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Power-up: bring C1250 Deck up instead of iFit's "Get started" screen.
 *
 * The sure way is making C1250 Deck the Home app (Settings → Start on power-up opens Android's
 * picker; the activity already answers HOME). This is the fallback when iFit's launcher is still
 * Home: open the console now, and again at 20 and 45 seconds, since iFit's own app comes up a little
 * after boot and takes the screen. MainActivity is singleTask, so each launch just brings it forward.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(a)) return;
        Intent launch = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        KeepFront.note("boot: " + a);
        try { ctx.startActivity(launch); } catch (Exception e) { KeepFront.note("boot launch refused: " + e.getMessage()); }
        // Hold the screen for the first 4 minutes: iFit's app opens after the relaunches below (1.10.7).
        KeepFront.start(ctx, "boot", 240);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        int[] delays = {20, 45};
        for (int i = 0; i < delays.length; i++) {
            PendingIntent pi = PendingIntent.getActivity(ctx, 100 + i, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delays[i] * 1000L, pi);
        }
    }
}
