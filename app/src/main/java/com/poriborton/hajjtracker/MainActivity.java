package com.poriborton.hajjtracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    // ── Your website URL ──────────────────────────────────────────
    private static final String WEBSITE_URL = "https://www.poribortonkf.com";
    // ─────────────────────────────────────────────────────────────

    private static final int PERM_FINE_LOCATION = 101;
    private static final int PERM_BG_LOCATION   = 102;
    private static final int PERM_NOTIFICATION  = 103;

    private WebView      webView;
    private ProgressBar  progressBar;
    private LinearLayout layoutOffline;
    private LinearLayout layoutPermission;
    private TextView     tvPermMsg;

    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView          = findViewById(R.id.webView);
        progressBar      = findViewById(R.id.progressBar);
        layoutOffline    = findViewById(R.id.layoutOffline);
        layoutPermission = findViewById(R.id.layoutPermission);
        tvPermMsg        = findViewById(R.id.tvPermMsg);
        findViewById(R.id.btnRetry).setOnClickListener(v -> retryLoad());
        findViewById(R.id.btnGrantPerm).setOnClickListener(v -> startPermissionFlow());
        findViewById(R.id.btnOpenSettings).setOnClickListener(v -> openAppSettings());
        setupWebView();
        startPermissionFlow();
        restoreTrackingIfNeeded();
    }

    // ════════════════════════════════════════════════════════════
    // WEBVIEW SETUP
    // FIX 1: SYNC — force no-cache so website always loads fresh
    // Firebase WebSocket (used by Realtime DB) is never cached —
    // only the page HTML/JS/CSS was being cached causing stale UI.
    // LOAD_NO_CACHE forces a fresh fetch every time.
    // ════════════════════════════════════════════════════════════
    @SuppressLint({"SetJavaScriptEnabled","JavascriptInterface"})
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);

        // FIX 1: Always load fresh — no caching of website pages
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        // Storage needed for Firebase Auth to persist login
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);

        // FIX 2: Geolocation — required for account creation & tracking in WebView
        s.setGeolocationEnabled(true);
        s.setGeolocationDatabasePath(getFilesDir().getAbsolutePath());

        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);

        // App identifier — website JS can detect it's running in the app
        s.setUserAgentString(s.getUserAgentString() + " HajjTrackerApp/1.0");

        // JS Bridge — website calls these methods to start/stop background GPS
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        // ── WebViewClient ──
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                progressBar.setVisibility(View.VISIBLE);
                layoutOffline.setVisibility(View.GONE);
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                progressBar.setVisibility(View.GONE);
                // Inject bridge vars after every page load
                injectBridgeReady();
            }
            @Override
            public void onReceivedError(WebView v, int code, String d, String url) {
                progressBar.setVisibility(View.GONE);
                if (url != null && url.startsWith(WEBSITE_URL)) showOfflineScreen();
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                // Keep your domain inside WebView
                if (url.startsWith("https://www.poribortonkf.com") ||
                    url.startsWith("https://poribortonkf.com")) return false;
                // Open external links in browser
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception ignored) {}
                return true;
            }
        });

        // ── WebChromeClient ──
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progressBar.setProgress(p);
                if (p == 100) progressBar.setVisibility(View.GONE);
            }

            // FIX 2: Grant geolocation permission to website automatically
            // This is what allows the website to call navigator.geolocation
            // and create accounts with location data
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback cb) {
                if (ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    // Grant and retain — don't ask again
                    cb.invoke(origin, true, true);
                } else {
                    pendingGeoCallback = cb;
                    pendingGeoOrigin   = origin;
                    requestFineLocation();
                }
            }

            // Pass JS alert() dialogs through to user
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("OK", (d,w) -> r.confirm())
                    .setCancelable(false).show();
                return true;
            }
            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("হ্যাঁ", (d,w) -> r.confirm())
                    .setNegativeButton("না",  (d,w) -> r.cancel()).show();
                return true;
            }
            @Override
            public boolean onShowFileChooser(WebView v,
                    ValueCallback<Uri[]> cb, FileChooserParams p) {
                cb.onReceiveValue(null); return true;
            }
        });
    }

    // ════════════════════════════════════════════════════════════
    // BRIDGE INJECTION
    // After every page load, tell the website it's inside the app
    // and restore any saved tracking state.
    // The website JS should call window.onAndroidReady() to pick this up.
    // ════════════════════════════════════════════════════════════
    private void injectBridgeReady() {
        SharedPreferences p = getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE);
        boolean wasTracking = p.getBoolean("was_tracking", false);
        String js =
            "window.__isAndroidApp  = true;" +
            "window.__wasTracking   = " + wasTracking + ";" +
            "window.__savedUid      = '" + esc(p.getString("uid",""))     + "';" +
            "window.__savedName     = '" + esc(p.getString("name",""))    + "';" +
            "window.__savedRole     = '" + esc(p.getString("role",""))    + "';" +
            "window.__savedGroup    = '" + esc(p.getString("groupId","")) + "';" +
            "window.__savedToken    = '" + esc(p.getString("token",""))   + "';" +
            // Tell the website to auto-start tracking if it was previously running
            "if(typeof window.onAndroidReady==='function'){window.onAndroidReady();}";
        webView.evaluateJavascript(js, null);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'","\\'").replace("\n","").replace("\r","");
    }

    // ════════════════════════════════════════════════════════════
    // JS BRIDGE — called from your website JavaScript
    //
    // FIX 2: Account creation works because geolocation is already
    // granted via onGeolocationPermissionsShowPrompt above.
    //
    // Add this to your website JS after Firebase login succeeds:
    //   if(window.Android) {
    //     firebase.auth().currentUser.getIdToken().then(token => {
    //       Android.startTracking(uid, name, role, groupId, token);
    //     });
    //   }
    //
    // Add this to your logout function:
    //   if(window.Android) Android.stopTracking();
    // ════════════════════════════════════════════════════════════
    private class AndroidBridge {

        @android.webkit.JavascriptInterface
        public void startTracking(String uid, String name, String role,
                                  String groupId, String token) {
            runOnUiThread(() -> {
                // Save state for BootReceiver (survives reboot)
                BootReceiver.saveTrackingState(
                        MainActivity.this, uid, name, role, groupId, token);
                // Start the foreground service
                Intent i = new Intent(MainActivity.this, LocationForegroundService.class);
                i.setAction(LocationForegroundService.ACTION_START);
                i.putExtra("uid",     uid);
                i.putExtra("name",    name);
                i.putExtra("role",    role);
                i.putExtra("groupId", groupId);
                i.putExtra("token",   token != null ? token : "");
                ContextCompat.startForegroundService(MainActivity.this, i);
            });
        }

        @android.webkit.JavascriptInterface
        public void stopTracking() {
            runOnUiThread(() -> {
                BootReceiver.clearTrackingState(MainActivity.this);
                Intent i = new Intent(MainActivity.this, LocationForegroundService.class);
                i.setAction(LocationForegroundService.ACTION_STOP);
                startService(i);
            });
        }

        @android.webkit.JavascriptInterface
        public void showToast(String msg) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }

        @android.webkit.JavascriptInterface
        public boolean hasBackgroundLocation() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @android.webkit.JavascriptInterface
        public String getLastLocation() {
            return LocationForegroundService.getLastLocationJson();
        }

        @android.webkit.JavascriptInterface
        public boolean isApp() { return true; }
    }

    // ════════════════════════════════════════════════════════════
    // RESTORE TRACKING — if user was tracking before app was closed
    // ════════════════════════════════════════════════════════════
    private void restoreTrackingIfNeeded() {
        if (LocationForegroundService.isRunning) return;
        SharedPreferences p = getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE);
        if (!p.getBoolean("was_tracking", false)) return;
        String uid = p.getString("uid", null);
        if (uid == null || uid.isEmpty()) return;
        Intent i = new Intent(this, LocationForegroundService.class);
        i.setAction(LocationForegroundService.ACTION_START);
        i.putExtra("uid",     uid);
        i.putExtra("name",    p.getString("name",    "Unknown"));
        i.putExtra("role",    p.getString("role",    "pilgrim"));
        i.putExtra("groupId", p.getString("groupId", ""));
        i.putExtra("token",   p.getString("token",   ""));
        ContextCompat.startForegroundService(this, i);
    }

    // ════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ════════════════════════════════════════════════════════════
    private void startPermissionFlow() {
        layoutPermission.setVisibility(View.GONE);
        if (!hasFineLocation()) {
            requestFineLocation();
        } else if (!hasBackgroundLocation()) {
            requestBackgroundLocation();
        } else {
            loadWebsite();
        }
        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERM_NOTIFICATION);
            }
        }
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFineLocation() {
        new AlertDialog.Builder(this)
            .setTitle("লোকেশন অনুমতি প্রয়োজন")
            .setMessage("পিলগ্রিম ট্র্যাকিং ও অ্যাকাউন্ট তৈরির জন্য লোকেশন অনুমতি প্রয়োজন।\n\n" +
                        "পরের স্ক্রিনে 'Allow' বা 'Allow all the time' বেছে নিন।")
            .setPositiveButton("ঠিক আছে", (d,w) ->
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                 Manifest.permission.ACCESS_COARSE_LOCATION},
                    PERM_FINE_LOCATION))
            .setCancelable(false)
            .show();
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { loadWebsite(); return; }
        new AlertDialog.Builder(this)
            .setTitle("সবসময় লোকেশন চালু রাখুন")
            .setMessage("ফোন বন্ধ বা ব্যাগে থাকলেও ট্র্যাকিং চালু রাখতে:\n\n" +
                        "পরের স্ক্রিনে 'Allow all the time' বেছে নিন।\n\n" +
                        "এটি না করলে অ্যাপ বন্ধ হলে ট্র্যাকিং বন্ধ হবে।")
            .setPositiveButton("Allow all the time →", (d,w) ->
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    PERM_BG_LOCATION))
            .setNegativeButton("পরে", (d,w) -> loadWebsite())
            .setCancelable(false)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean granted = results.length > 0 &&
                          results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == PERM_FINE_LOCATION) {
            if (granted) {
                if (pendingGeoCallback != null) {
                    pendingGeoCallback.invoke(pendingGeoOrigin, true, true);
                    pendingGeoCallback = null;
                }
                requestBackgroundLocation();
            } else {
                showPermissionScreen(
                    "লোকেশন অনুমতি দিন",
                    "GPS ছাড়া ট্র্যাকিং ও অ্যাকাউন্ট তৈরি সম্ভব নয়। সেটিংসে গিয়ে অনুমতি দিন।",
                    true);
            }
        } else if (code == PERM_BG_LOCATION) {
            if (!granted) {
                Toast.makeText(this,
                    "সতর্কতা: সেটিংস > অ্যাপ > Hajj Tracker > লোকেশন > 'Allow all the time' চালু করুন।",
                    Toast.LENGTH_LONG).show();
            }
            loadWebsite();
        } else if (code == PERM_NOTIFICATION) {
            // Proceed regardless
            if (!hasFineLocation()) requestFineLocation();
            else if (!hasBackgroundLocation()) requestBackgroundLocation();
            else loadWebsite();
        }
    }

    // ════════════════════════════════════════════════════════════
    // LOAD WEBSITE
    // ════════════════════════════════════════════════════════════
    private void loadWebsite() {
        layoutPermission.setVisibility(View.GONE);
        if (!isOnline()) { showOfflineScreen(); return; }
        webView.setVisibility(View.VISIBLE);
        // FIX 1: Always load fresh URL — no browser cache
        webView.loadUrl(WEBSITE_URL + "?v=" + System.currentTimeMillis());
    }

    private void retryLoad() {
        layoutOffline.setVisibility(View.GONE);
        if (isOnline()) {
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(WEBSITE_URL + "?v=" + System.currentTimeMillis());
        } else {
            Toast.makeText(this, "ইন্টারনেট সংযোগ নেই।", Toast.LENGTH_SHORT).show();
            showOfflineScreen();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void showOfflineScreen() {
        webView.setVisibility(View.GONE);
        layoutOffline.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void showPermissionScreen(String title, String msg, boolean showSettings) {
        webView.setVisibility(View.GONE);
        layoutPermission.setVisibility(View.VISIBLE);
        tvPermMsg.setText(title + "\n\n" + msg);
        findViewById(R.id.btnGrantPerm).setVisibility(showSettings ? View.GONE  : View.VISIBLE);
        findViewById(R.id.btnOpenSettings).setVisibility(showSettings ? View.VISIBLE : View.GONE);
    }

    private void openAppSettings() {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    // ════════════════════════════════════════════════════════════
    // BACK BUTTON — navigate WebView history
    // ════════════════════════════════════════════════════════════
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            new AlertDialog.Builder(this)
                .setTitle("অ্যাপ বন্ধ করবেন?")
                .setMessage("অ্যাপ বন্ধ হলেও ব্যাকগ্রাউন্ড ট্র্যাকিং চলতে থাকবে।\nট্র্যাকিং বন্ধ করতে ওয়েবসাইটে লগআউট করুন।")
                .setPositiveButton("বন্ধ করুন", (d,w) -> super.onBackPressed())
                .setNegativeButton("থাকুন", null)
                .show();
        }
    }

    @Override protected void onResume()  { super.onResume();  webView.onResume();  }
    @Override protected void onPause()   { super.onPause();   webView.onPause();   }
    @Override protected void onDestroy() { super.onDestroy(); webView.destroy();   }
}
