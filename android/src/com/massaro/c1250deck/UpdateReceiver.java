package com.massaro.c1250deck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * Receives PackageInstaller results for our own self-update, and relaunches the console
 * after Android replaces the app (MY_PACKAGE_REPLACED).
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Intent launch = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(launch); } catch (Exception ignored) {}
            return;
        }
        if (!Updater.ACTION_STATUS.equals(action)) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        Updater u = Updater.current;
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android wants a tap on its own "Update" dialog (first update, or older Android).
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                KeepFront.leaving("update confirm");
                try { ctx.startActivity(confirm); } catch (Exception ignored) {}
            }
            if (u != null) u.needsConfirmation(confirm);
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String text = status == PackageInstaller.STATUS_FAILURE_ABORTED
                    ? "The update was cancelled."
                    : "Android couldn't install the update" + (msg != null ? " (" + msg + ")." : ".");
            if (u != null) u.installFailed(text);
            else Updater.rememberError(ctx, text);
        }
    }
}
