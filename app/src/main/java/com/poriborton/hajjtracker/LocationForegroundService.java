package com.poriborton.hajjtracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LocationForegroundService
 *
 * A Foreground Service that uses Google's Fused Location Provider to get GPS
 * coordinates every 15 seconds and sends them to your website's Firebase
 * database via the Firebase REST API — no Firebase SDK needed here.
 *
 * Why a Foreground Service?
 *   Android normally kills background processes to save battery.
 *   A Foreground Service is exempt from this — it shows a persistent notification
 *   ("Hajj Tracker — ট্র্যাকিং চালু") which tells the user tracking is active.
 *   It runs even when:
 *     - The user presses Home button
 *     - The screen locks
 *     - The phone is in a bag
 *     - The app is swiped from recents
 *
 * The service only stops when:
 *     - The user taps "ট্র্যাকিং বন্ধ করুন" in the notification
 *     - The user logs out via the website (JS calls Android.stopTracking())
 *     - ACTION_STOP intent is sent
 *
 * After a phone reboot, BootReceiver restarts this service automatically.
 */
public class LocationForegroundService extends Service {

    private static final String TAG         = "HajjLocationSvc";
    public  static final String CHANNEL_ID  = "hajj_location_channel";
    public  static final int    NOTIF_ID    = 2001;
    public  static final String ACTION_START = "ACTION_START";
    public  static final String ACTION_STOP  = "ACTION_STOP";

    // ── GPS update frequency ──────────────────────────────────────
    // 15 seconds between updates — good balance of accuracy vs battery
    // Reduce to 10000 for more frequent updates, increase to 30000 to save battery
    private static final long UPDATE_INTERVAL_MS  = 15_000;
    private static final long FASTEST_INTERVAL_MS = 10_000;
    // ─────────────────────────────────────────────────────────────

    // ── Firebase REST API ─────────────────────────────────────────
    // We write directly to Firebase Realtime Database via its REST API.
    // This avoids including the Firebase SDK in the app — simpler and smaller.
    // Format: https://<project-id>.firebaseio.com/locations/<uid>.json?auth=<token>
    private static final String FIREBASE_DB_URL =
        "https://pilgrim-tracker-ee93d-default-rtdb.asia-southeast1.firebasedatabase.app";
    // ─────────────────────────────────────────────────────────────

    private FusedLocationProviderClient fusedClient;
    private LocationCallback locationCallback;
    private ExecutorService networkExecutor;

    // User info passed from MainActivity / BootReceiver
    private String uid;
    private String userName;
    private String userRole;
    private String groupId;
    private String firebaseToken;  // Firebase Auth ID token for REST API auth

    // Last known location — accessible via getLastLocationJson()
    private static volatile double lastLat = 0;
    private static volatile double lastLng = 0;
    private static volatile double lastAcc = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        networkExecutor = Executors.newSingleThreadExecutor();
        fusedClient     = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            uid          = intent.getStringExtra("uid");
            userName     = intent.getStringExtra("name");
            userRole     = intent.getStringExtra("role");
            groupId      = intent.getStringExtra("groupId");
            firebaseToken= intent.getStringExtra("token");  // optional

            if (uid == null || uid.isEmpty()) {
                // No UID yet — service will still collect GPS but not push to Firebase
                // until UID is provided via a subsequent START intent
                uid = "anonymous_" + System.currentTimeMillis();
            }

            // Start as foreground immediately — Android requires this within 5 seconds
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification("GPS চালু হচ্ছে…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, buildNotification("GPS চালু হচ্ছে…"));
            }

            startLocationUpdates();
        }

        // START_STICKY: if Android kills this service due to memory pressure,
        // it will be restarted automatically with the last intent
        return START_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopLocationUpdates();
        if (networkExecutor != null) networkExecutor.shutdown();
        // Mark user as offline in Firebase when service is explicitly stopped
        if (uid != null && !uid.startsWith("anonymous_")) {
            pushOffline();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GPS — Fused Location Provider
    // This handles GPS, WiFi-based, and cell-tower location automatically,
    // choosing the most accurate source available and managing battery usage.
    // ═══════════════════════════════════════════════════════════════
    private void startLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setWaitForAccurateLocation(false)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc == null) return;

                lastLat = loc.getLatitude();
                lastLng = loc.getLongitude();
                lastAcc = loc.getAccuracy();

                Log.d(TAG, "Location: " + lastLat + ", " + lastLng + " ±" + lastAcc + "m");

                updateNotification(loc);
                pushToFirebase(loc);
            }
        };

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "Location updates started for uid=" + uid);
        } else {
            Log.e(TAG, "Location permission not granted — stopping service");
            stopSelf();
        }
    }

    private void stopLocationUpdates() {
        if (fusedClient != null && locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FIREBASE REST API — write location
    //
    // We use Firebase Realtime Database's REST API (simple HTTP PUT).
    // This is the same database your web tracker uses — data is shared.
    //
    // The database rules must allow write for authenticated users.
    // If you are using Firebase Auth on the website, the user's ID token
    // can be passed via the 'token' extra when starting this service.
    // ═══════════════════════════════════════════════════════════════
    private void pushToFirebase(Location loc) {
        if (uid == null) return;

        networkExecutor.execute(() -> {
            try {
                // Build the JSON payload matching exactly what your web tracker writes
                JSONObject data = new JSONObject();
                data.put("uid",      uid);
                data.put("name",     userName != null ? userName : "Unknown");
                data.put("groupId",  groupId  != null ? groupId  : "");
                data.put("role",     userRole  != null ? userRole  : "pilgrim");
                data.put("lat",      loc.getLatitude());
                data.put("lng",      loc.getLongitude());
                data.put("acc",      loc.getAccuracy());
                data.put("lastSeen", System.currentTimeMillis());
                data.put("online",   true);  // stays true until explicit logout
                data.put("sos",      false);

                // Firebase REST API endpoint for this user's location
                String urlStr = FIREBASE_DB_URL + "/locations/" + uid + ".json";

                // Append auth token if available
                if (firebaseToken != null && !firebaseToken.isEmpty()) {
                    urlStr += "?auth=" + firebaseToken;
                }

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");   // PUT = set (overwrites)
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                byte[] body = data.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    Log.w(TAG, "Firebase PUT response: " + responseCode);
                }
                conn.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Firebase push failed: " + e.getMessage());
            }
        });
    }

    private void pushOffline() {
        networkExecutor.execute(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("online", false);

                String urlStr = FIREBASE_DB_URL + "/locations/" + uid + "/online.json";
                if (firebaseToken != null && !firebaseToken.isEmpty()) {
                    urlStr += "?auth=" + firebaseToken;
                }

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write("false".getBytes(StandardCharsets.UTF_8));
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "pushOffline failed: " + e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // NOTIFICATION
    // The persistent notification is REQUIRED for foreground services.
    // It tells the user tracking is active and cannot be swiped away.
    // Tapping it opens the app. "বন্ধ করুন" action stops the service.
    // ═══════════════════════════════════════════════════════════════
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Hajj Tracker — লাইভ ট্র্যাকিং",
                    NotificationManager.IMPORTANCE_LOW  // LOW = silent, no sound
            );
            ch.setDescription("আপনার লোকেশন গ্রুপের সাথে শেয়ার হচ্ছে");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        // Tap notification → open the app
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // "বন্ধ করুন" action → stop the service
        Intent stopIntent = new Intent(this, LocationForegroundService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hajj Pilgrim Tracker")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "ট্র্যাকিং বন্ধ করুন", stopPi)
                .setOngoing(true)          // cannot be swiped away
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(Location loc) {
        String text = String.format(
                "লাইভ ট্র্যাকিং চালু  •  %.5f, %.5f (±%.0fm)",
                loc.getLatitude(), loc.getLongitude(), loc.getAccuracy());
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification(text));
    }

    // ═══════════════════════════════════════════════════════════════
    // Static accessor — called from JS bridge in MainActivity
    // ═══════════════════════════════════════════════════════════════
    public static String getLastLocationJson() {
        try {
            JSONObject j = new JSONObject();
            j.put("lat", lastLat);
            j.put("lng", lastLng);
            j.put("acc", lastAcc);
            return j.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
