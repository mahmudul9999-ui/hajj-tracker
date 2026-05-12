package com.poriborton.hajjtracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * BootReceiver
 *
 * Android broadcasts BOOT_COMPLETED when the phone restarts.
 * This receiver catches it and restarts the location service
 * if the user was previously tracking (logged in as a pilgrim).
 *
 * User info is saved to SharedPreferences when the service starts
 * and read back here after a reboot.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG   = "HajjBootReceiver";
    public  static final String PREFS = "hajj_prefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Only restart if user was previously logged in and tracking
        boolean wasTracking = prefs.getBoolean("was_tracking", false);
        if (!wasTracking) {
            Log.d(TAG, "Was not tracking before reboot — skipping restart");
            return;
        }

        String uid     = prefs.getString("uid",      null);
        String name    = prefs.getString("name",     "Unknown");
        String role    = prefs.getString("role",     "pilgrim");
        String groupId = prefs.getString("groupId",  "");
        String token   = prefs.getString("token",    "");

        if (uid == null) {
            Log.d(TAG, "No uid saved — cannot restart tracking");
            return;
        }

        Log.d(TAG, "Restarting tracking for uid=" + uid + " after boot");

        Intent serviceIntent = new Intent(context, LocationForegroundService.class);
        serviceIntent.setAction(LocationForegroundService.ACTION_START);
        serviceIntent.putExtra("uid",     uid);
        serviceIntent.putExtra("name",    name);
        serviceIntent.putExtra("role",    role);
        serviceIntent.putExtra("groupId", groupId);
        serviceIntent.putExtra("token",   token);

        ContextCompat.startForegroundService(context, serviceIntent);
    }

    // ── Called from JS bridge when tracking starts ──
    public static void saveTrackingState(Context ctx, String uid, String name,
                                          String role, String groupId, String token) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit()
           .putBoolean("was_tracking", true)
           .putString("uid",      uid)
           .putString("name",     name)
           .putString("role",     role)
           .putString("groupId",  groupId)
           .putString("token",    token != null ? token : "")
           .apply();
    }

    // ── Called when user logs out ──
    public static void clearTrackingState(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit()
           .putBoolean("was_tracking", false)
           .remove("uid")
           .remove("name")
           .remove("role")
           .remove("groupId")
           .remove("token")
           .apply();
    }
}
