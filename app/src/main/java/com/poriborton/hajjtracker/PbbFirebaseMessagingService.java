package com.poriborton.hajjtracker;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Receives Blood Bank push notifications and shows alarm-style alerts.
 *
 * - High-priority channel with alarm sound + vibration + lockscreen visible
 * - Tapping the notification opens MainActivity with request_id, which loads
 *   the blood-bank chat for that request.
 * - Token refresh handler stashes the new FCM token in SharedPreferences so
 *   the WebView page can pick it up and sync to Supabase next time the donor
 *   opens the blood-bank page.
 */
public class PbbFirebaseMessagingService extends FirebaseMessagingService {

    public static final String CHANNEL_URGENT = "blood_urgent";
    public static final String CHANNEL_NORMAL = "blood_normal";
    public static final String PREFS = "pbb";
    public static final String KEY_TOKEN = "fcm_token";
    public static final String KEY_TOKEN_DIRTY = "fcm_token_dirty";

    @Override
    public void onNewToken(@NonNull String token) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_TOKEN, token)
                .putBoolean(KEY_TOKEN_DIRTY, true)
                .apply();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        RemoteMessage.Notification notif = message.getNotification();

        String title = pickString(notif != null ? notif.getTitle() : null,
                data.get("title"), "রক্তের প্রয়োজন");
        String body  = pickString(notif != null ? notif.getBody() : null,
                data.get("body"), "");
        boolean urgent = "true".equalsIgnoreCase(data.get("urgent"));
        String requestId = data.get("request_id");

        ensureChannels();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (requestId != null) intent.putExtra("request_id", requestId);

        int reqCode = requestId != null ? requestId.hashCode() : 0;
        PendingIntent pending = PendingIntent.getActivity(
                this, reqCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String channel = urgent ? CHANNEL_URGENT : CHANNEL_NORMAL;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(urgent ? NotificationCompat.PRIORITY_MAX
                                    : NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(RingtoneManager.getDefaultUri(
                        urgent ? RingtoneManager.TYPE_ALARM
                               : RingtoneManager.TYPE_NOTIFICATION))
                .setVibrate(urgent
                        ? new long[]{0, 500, 250, 500, 250, 500}
                        : new long[]{0, 250, 250, 250})
                .setAutoCancel(true)
                .setContentIntent(pending);
        if (urgent) b.setFullScreenIntent(pending, true);

        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int notifId = requestId != null ? requestId.hashCode()
                                        : (int) System.currentTimeMillis();
        mgr.notify(notifId, b.build());
    }

    private static String pickString(String... candidates) {
        for (String c : candidates) if (c != null && !c.isEmpty()) return c;
        return "";
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (mgr.getNotificationChannel(CHANNEL_URGENT) == null) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_URGENT,
                    "জরুরি রক্তের আবেদন",
                    NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("৩০ কিমির মধ্যে জরুরি রক্তের আবেদন");
            AudioAttributes audio = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            c.setSound(alarmSound, audio);
            c.enableVibration(true);
            c.setVibrationPattern(new long[]{0, 500, 250, 500, 250, 500});
            c.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            c.setBypassDnd(true);
            mgr.createNotificationChannel(c);
        }

        if (mgr.getNotificationChannel(CHANNEL_NORMAL) == null) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_NORMAL,
                    "রক্তের আবেদন",
                    NotificationManager.IMPORTANCE_DEFAULT);
            c.setDescription("সাধারণ রক্তের আবেদন");
            mgr.createNotificationChannel(c);
        }
    }
}
