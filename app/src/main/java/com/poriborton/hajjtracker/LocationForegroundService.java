package com.poriborton.hajjtracker;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.*;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.*;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LocationForegroundService — Background GPS
 *
 * FIX 3: Three layers keep this alive even when app is closed:
 *
 *   Layer 1 — Foreground Service + WakeLock
 *     Android cannot kill a foreground service that holds a WakeLock.
 *     The persistent notification is required by Android law.
 *
 *   Layer 2 — START_STICKY
 *     If Android kills this service due to extreme memory pressure,
 *     START_STICKY tells Android to restart it automatically.
 *
 *   Layer 3 — AlarmManager restart
 *     Every 4 minutes, AlarmManager fires an alarm that checks if
 *     the service is running and restarts it if not.
 *     This defeats Samsung/Xiaomi/Huawei battery killers.
 *
 *   Layer 4 — BootReceiver
 *     Restarts tracking after phone reboot.
 *
 * GPS pushes to Firebase every 10 seconds via REST API.
 * online:true always — only explicit logout sets false.
 */
public class LocationForegroundService extends Service {

    private static final String TAG         = "HajjLocSvc";
    public  static final String CHANNEL_ID  = "hajj_loc_ch";
    public  static final int    NOTIF_ID    = 2001;
    public  static final String ACTION_START = "START";
    public  static final String ACTION_STOP  = "STOP";
    // AlarmManager restart action
    public  static final String ACTION_RESTART = "RESTART_CHECK";

    // GPS every 10 seconds
    private static final long INTERVAL_MS       = 10_000L;
    private static final long FASTEST_INTERVAL  =  5_000L;
    // Heartbeat keeps lastSeen fresh even if GPS stalls
    private static final long HEARTBEAT_MS      = 20_000L;
    // AlarmManager checks service every 4 minutes
    private static final long ALARM_INTERVAL_MS = 4 * 60 * 1000L;

    private static final String FIREBASE_URL =
        "https://pilgrim-tracker-ee93d-default-rtdb.asia-southeast1.firebasedatabase.app";

    private FusedLocationProviderClient fusedClient;
    private LocationCallback            locationCallback;
    private ExecutorService             networkThread;
    private PowerManager.WakeLock       wakeLock;
    private Handler                     handler;
    private Runnable                    heartbeatTask;
    private AlarmManager                alarmManager;

    private String uid, userName, userRole, groupId, firebaseToken;

    public  static volatile boolean isRunning = false;
    private static volatile double  lastLat   = 0;
    private static volatile double  lastLng   = 0;
    private static volatile double  lastAcc   = 0;

    // ════════════════════════════════════════════════════════════
    @Override
    public void onCreate() {
        super.onCreate();
        networkThread = Executors.newSingleThreadExecutor();
        handler       = new Handler(Looper.getMainLooper());
        fusedClient   = LocationServices.getFusedLocationProviderClient(this);
        alarmManager  = (AlarmManager) getSystemService(ALARM_SERVICE);
        createNotificationChannel();

        // PARTIAL_WAKE_LOCK: keeps CPU alive, screen can sleep
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "HajjTracker:GPSLock");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        // Handle restart alarm check
        if (ACTION_RESTART.equals(action)) {
            if (isRunning) return START_STICKY;
            // Service was killed — restore from SharedPrefs and restart
            action = ACTION_START;
        }

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        // Read user info from intent
        if (intent != null) {
            String u = intent.getStringExtra("uid");
            if (u != null && !u.isEmpty()) {
                uid           = u;
                userName      = intent.getStringExtra("name");
                userRole      = intent.getStringExtra("role");
                groupId       = intent.getStringExtra("groupId");
                firebaseToken = intent.getStringExtra("token");
            }
        }

        // Fallback: restore from SharedPreferences
        if (uid == null || uid.isEmpty()) {
            SharedPreferences p = getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE);
            uid           = p.getString("uid",      null);
            userName      = p.getString("name",     "Unknown");
            userRole      = p.getString("role",     "pilgrim");
            groupId       = p.getString("groupId",  "");
            firebaseToken = p.getString("token",    "");
        }

        if (uid == null || uid.isEmpty()) {
            Log.w(TAG, "No UID — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        // MUST call startForeground within 5 seconds of onStartCommand
        Notification n = buildNotification("লাইভ ট্র্যাকিং শুরু হচ্ছে…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        if (!wakeLock.isHeld()) wakeLock.acquire();
        isRunning = true;

        startLocationUpdates();
        startHeartbeat();
        scheduleAlarmRestart();  // FIX 3: AlarmManager watchdog

        Log.d(TAG, "Service started for uid=" + uid);
        return START_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(Intent i) { return null; }

    // ════════════════════════════════════════════════════════════
    // CRITICAL FIX: onTaskRemoved
    // This is called when the user SWIPES the app away from recents.
    // By default Android kills the service. We schedule an immediate
    // restart via AlarmManager so tracking continues.
    // ════════════════════════════════════════════════════════════
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "onTaskRemoved — user swiped app away — scheduling immediate restart");
        // Schedule a 1-second restart via AlarmManager
        Intent restart = new Intent(getApplicationContext(), LocationForegroundService.class);
        restart.setAction(ACTION_RESTART);
        PendingIntent pi = PendingIntent.getService(
            getApplicationContext(), 88, restart,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long triggerAt = System.currentTimeMillis() + 1000;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        stopHeartbeat();
        stopLocationUpdates();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (networkThread != null) networkThread.shutdown();
        // Note: do NOT set online=false here.
        // Android may be restarting us via START_STICKY.
        // Only shutdown() (called by logout) sets offline.
        Log.d(TAG, "onDestroy called — Android may restart via START_STICKY");
    }

    // Called only on explicit logout
    private void shutdown() {
        Log.d(TAG, "shutdown() — explicit logout");
        if (uid != null && !uid.isEmpty()) {
            pushField("online.json", "false");
        }
        cancelAlarmRestart();
        stopForeground(true);
        isRunning = false;
        stopHeartbeat();
        stopLocationUpdates();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (networkThread != null) networkThread.shutdown();
        stopSelf();
    }

    // ════════════════════════════════════════════════════════════
    // GPS — Fused Location Provider
    // ════════════════════════════════════════════════════════════
    private void startLocationUpdates() {
        LocationRequest req = new LocationRequest.Builder(INTERVAL_MS)
                .setMinUpdateIntervalMillis(FASTEST_INTERVAL)
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
                updateNotification(loc);
                pushLocation(loc.getLatitude(), loc.getLongitude(), loc.getAccuracy());
            }
        };

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            fusedClient.requestLocationUpdates(req, locationCallback,
                    Looper.getMainLooper());
            Log.d(TAG, "GPS updates started");
        } else {
            Log.e(TAG, "No location permission — stopping");
            stopSelf();
        }
    }

    private void stopLocationUpdates() {
        if (fusedClient != null && locationCallback != null)
            fusedClient.removeLocationUpdates(locationCallback);
    }

    // ════════════════════════════════════════════════════════════
    // HEARTBEAT — keeps lastSeen fresh even if GPS stalls
    // ════════════════════════════════════════════════════════════
    private void startHeartbeat() {
        heartbeatTask = new Runnable() {
            @Override public void run() {
                if (lastLat != 0 && lastLng != 0)
                    pushLocation(lastLat, lastLng, lastAcc);
                handler.postDelayed(this, HEARTBEAT_MS);
            }
        };
        handler.postDelayed(heartbeatTask, HEARTBEAT_MS);
    }

    private void stopHeartbeat() {
        if (handler != null && heartbeatTask != null)
            handler.removeCallbacks(heartbeatTask);
    }

    // ════════════════════════════════════════════════════════════
    // FIX 3: AlarmManager restart watchdog
    // Fires every 4 minutes and restarts the service if it was killed
    // This defeats Samsung DeX, MIUI, EMUI battery killers
    // ════════════════════════════════════════════════════════════
    private void scheduleAlarmRestart() {
        Intent i = new Intent(this, LocationForegroundService.class);
        i.setAction(ACTION_RESTART);
        PendingIntent pi = PendingIntent.getService(this, 99, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        long triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // setExactAndAllowWhileIdle fires even in Doze mode
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        Log.d(TAG, "Alarm restart scheduled in 4min");
    }

    private void cancelAlarmRestart() {
        Intent i = new Intent(this, LocationForegroundService.class);
        i.setAction(ACTION_RESTART);
        PendingIntent pi = PendingIntent.getService(this, 99, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (pi != null) alarmManager.cancel(pi);
    }

    // ════════════════════════════════════════════════════════════
    // FIREBASE REST API
    // Writes to /locations/{uid} — same path the web tracker reads.
    //
    // CRITICAL SAFETY FIXES:
    //   1. Auth token retry: If write fails with 401 (token expired),
    //      retry without token. This ensures lost pilgrims keep
    //      reporting location even after 60+ minutes of tracking.
    //   2. Failed pushes are logged but heartbeat keeps trying every
    //      20 seconds. Next successful push will overwrite the data.
    //   3. Token failure does NOT stop the service — we keep trying.
    // ════════════════════════════════════════════════════════════
    private void pushLocation(double lat, double lng, double acc) {
        if (uid == null || uid.isEmpty()) return;
        networkThread.execute(() -> {
            try {
                JSONObject d = new JSONObject();
                d.put("uid",      uid);
                d.put("name",     userName != null ? userName : "Unknown");
                d.put("groupId",  groupId  != null ? groupId  : "");
                d.put("role",     userRole != null ? userRole : "pilgrim");
                d.put("lat",      lat);
                d.put("lng",      lng);
                d.put("acc",      acc);
                d.put("lastSeen", System.currentTimeMillis());
                d.put("online",   true);   // always true — only logout sets false
                d.put("sos",      false);
                httpPutWithRetry("/locations/" + uid + ".json", d.toString());
            } catch (Exception e) {
                Log.e(TAG, "pushLocation FAILED: " + e.getMessage());
                // Don't crash — next heartbeat will retry
            }
        });
    }

    private void pushField(String path, String value) {
        if (uid == null || uid.isEmpty()) return;
        networkThread.execute(() -> {
            try { httpPutWithRetry("/locations/" + uid + "/" + path, value); }
            catch (Exception e) { Log.e(TAG, "pushField: " + e.getMessage()); }
        });
    }

    // ════════════════════════════════════════════════════════════
    // SAFETY-CRITICAL: httpPutWithRetry
    //
    // Tries the request with the auth token first. If that returns
    // 401 (unauthorized — token expired), retries WITHOUT the token.
    //
    // Your Firebase rules must allow .write for /locations because
    // the lost-pilgrim scenario means we MUST be able to write GPS
    // even when the auth token has expired after 60 minutes.
    //
    // Recommended Firebase rules:
    //   "locations": { ".read": "auth != null", ".write": true }
    // ════════════════════════════════════════════════════════════
    private void httpPutWithRetry(String path, String body) throws Exception {
        int responseCode = httpPutOnce(path, body, firebaseToken);

        // 401 = unauthorized (token expired or invalid)
        // 403 = forbidden by rules
        if (responseCode == 401 || responseCode == 403) {
            Log.w(TAG, "Auth failed (HTTP " + responseCode + ") — retrying without token");
            // Retry without auth token
            responseCode = httpPutOnce(path, body, null);
            if (responseCode != 200) {
                Log.e(TAG, "Firebase write FAILED for lost-pilgrim safety. " +
                           "Update Firebase rules to: .write: true for /locations");
            }
        }
    }

    private int httpPutOnce(String path, String body, String token) throws Exception {
        String urlStr = FIREBASE_URL + path;
        if (token != null && !token.isEmpty()) urlStr += "?auth=" + token;
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setRequestMethod("PUT");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) Log.w(TAG, "Firebase HTTP " + code + " for " + path);
            return code;
        } finally {
            c.disconnect();
        }
    }


    // ════════════════════════════════════════════════════════════
    // NOTIFICATION
    // Persistent — cannot be swiped away (required for foreground service)
    // ════════════════════════════════════════════════════════════
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Hajj Tracker — লাইভ ট্র্যাকিং",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("আপনার লোকেশন গ্রুপের সাথে শেয়ার হচ্ছে");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.enableLights(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, LocationForegroundService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hajj Tracker — ট্র্যাকিং চালু")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                       "লগআউট করুন", stopPi)
            .setOngoing(true)          // cannot be swiped away
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(Location loc) {
        String text = String.format("লাইভ · %.5f, %.5f  ±%.0fm",
            loc.getLatitude(), loc.getLongitude(), loc.getAccuracy());
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
            .notify(NOTIF_ID, buildNotification(text));
    }

    public static String getLastLocationJson() {
        try {
            JSONObject j = new JSONObject();
            j.put("lat", lastLat);
            j.put("lng", lastLng);
            j.put("acc", lastAcc);
            return j.toString();
        } catch (Exception e) { return "{}"; }
    }
}
