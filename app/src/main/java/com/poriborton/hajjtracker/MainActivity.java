package com.poriborton.hajjtracker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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

    // ── Your website URL ──────────────────────────────────────────────
    private static final String WEBSITE_URL = "https://www.poribortonkf.com";
    // You can deep-link straight to the tracker page:
    // private static final String WEBSITE_URL = "https://www.poribortonkf.com/hajj-tracker";
    // ─────────────────────────────────────────────────────────────────

    private static final int PERM_FINE_LOCATION   = 101;
    private static final int PERM_BG_LOCATION     = 102;
    private static final int PERM_NOTIFICATION    = 103;

    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout layoutOffline;
    private LinearLayout layoutPermission;
    private TextView tvPermMsg;

    // Holds the geolocation callback until the user grants permission
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView       = findViewById(R.id.webView);
        progressBar   = findViewById(R.id.progressBar);
        layoutOffline = findViewById(R.id.layoutOffline);
        layoutPermission = findViewById(R.id.layoutPermission);
        tvPermMsg     = findViewById(R.id.tvPermMsg);

        findViewById(R.id.btnRetry).setOnClickListener(v -> retryLoad());
        findViewById(R.id.btnGrantPerm).setOnClickListener(v -> startPermissionFlow());
        findViewById(R.id.btnOpenSettings).setOnClickListener(v -> openAppSettings());

        setupWebView();
        startPermissionFlow();   // ask permissions, then load website
    }

    // ═══════════════════════════════════════════════════════════════
    // WEBVIEW SETUP
    // ═══════════════════════════════════════════════════════════════
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void setupWebView() {
        WebSettings s = webView.getSettings();

        // JavaScript must be enabled for your React/Firebase web app
        s.setJavaScriptEnabled(true);

        // Allow the website to call geolocation from JS
        s.setGeolocationEnabled(true);
        s.setGeolocationDatabasePath(getFilesDir().getAbsolutePath());

        // Better performance
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // User agent — lets your website detect it is running inside the app
        // Your website JS can check: navigator.userAgent.includes('HajjTrackerApp')
        s.setUserAgentString(s.getUserAgentString() + " HajjTrackerApp/1.0");

        // Keep WebView alive when switching tabs / backgrounding
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        // Inject a JS bridge — the website can call
        //   Android.startTracking(name, role, groupId)
        //   Android.stopTracking()
        //   Android.showToast(message)
        // from its JavaScript code
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        // ── WebViewClient: handle page events ──
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                layoutOffline.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                progressBar.setVisibility(View.GONE);
                // Show offline screen only for main page errors
                if (failingUrl != null && failingUrl.startsWith(WEBSITE_URL)) {
                    showOfflineScreen();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Keep navigation inside WebView for your domain
                if (url.startsWith("https://www.poribortonkf.com")
                        || url.startsWith("https://poribortonkf.com")) {
                    return false;  // WebView handles it
                }
                // Open external links (Google Maps, WhatsApp, etc.) in external browser
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }
        });

        // ── WebChromeClient: handle JS alerts, geolocation, file picker ──
        webView.setWebChromeClient(new WebChromeClient() {

            // Show loading progress bar
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
            }

            // Allow JS alert() / confirm() dialogs from the website
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setCancelable(false)
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("হ্যাঁ", (d, w) -> result.confirm())
                        .setNegativeButton("না", (d, w) -> result.cancel())
                        .show();
                return true;
            }

            // ── THIS IS KEY: allows navigator.geolocation.watchPosition() ──
            // to work inside the WebView — the website's JS gets real GPS
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                if (ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    // Already have native permission — grant to WebView immediately
                    callback.invoke(origin, true, false);
                } else {
                    // Store callback and ask for native permission first
                    pendingGeoCallback = callback;
                    pendingGeoOrigin   = origin;
                    requestFineLocation();
                }
            }

            // Handle HTML file inputs (e.g. photo upload if you add that later)
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                filePathCallback.onReceiveValue(null);
                return true;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // JS BRIDGE — called from your website's JavaScript
    // ═══════════════════════════════════════════════════════════════
    private class AndroidBridge {

        // Called by website JS when user logs in as a pilgrim:
        //   Android.startTracking("Abdul Rahman", "pilgrim", "GRP-1234")
        @android.webkit.JavascriptInterface
        public void startTracking(String name, String role, String groupId) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, LocationForegroundService.class);
                i.setAction(LocationForegroundService.ACTION_START);
                i.putExtra("name",    name);
                i.putExtra("role",    role);
                i.putExtra("groupId", groupId);
                ContextCompat.startForegroundService(MainActivity.this, i);
            });
        }

        // Called by website JS when user logs out:
        //   Android.stopTracking()
        @android.webkit.JavascriptInterface
        public void stopTracking() {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, LocationForegroundService.class);
                i.setAction(LocationForegroundService.ACTION_STOP);
                startService(i);
            });
        }

        // Called by website JS to show a native Android toast:
        //   Android.showToast("Location shared!")
        @android.webkit.JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        // Returns true if the native background location permission is granted
        // Website can call: Android.hasBackgroundLocation()
        @android.webkit.JavascriptInterface
        public boolean hasBackgroundLocation() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        // Returns current GPS coords as JSON string for the website to use
        @android.webkit.JavascriptInterface
        public String getLastLocation() {
            return LocationForegroundService.getLastLocationJson();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PERMISSION FLOW — fine → background → notification
    // ═══════════════════════════════════════════════════════════════
    private void startPermissionFlow() {
        layoutPermission.setVisibility(View.GONE);

        if (!hasFineLocation()) {
            requestFineLocation();
        } else if (!hasBackgroundLocation()) {
            requestBackgroundLocation();
        } else {
            // All permissions granted — load website
            loadWebsite();
        }
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestFineLocation() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.ACCESS_FINE_LOCATION)) {
            showPermissionScreen(
                "লোকেশন অনুমতি দিন",
                "পিলগ্রিম ট্র্যাকিংয়ের জন্য GPS লোকেশন প্রয়োজন। পরের স্ক্রিনে 'Allow' বাটনে ট্যাপ করুন।",
                false
            );
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, PERM_FINE_LOCATION);
        }
    }

    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            loadWebsite();
            return;
        }
        // Android 10+ requires a separate explicit request for background location
        // Show explanation first — Android requires the user to consciously choose
        // "Allow all the time" on the next screen
        new AlertDialog.Builder(this)
            .setTitle("সবসময় লোকেশন দরকার")
            .setMessage(
                "ফোন ব্যাগে থাকলেও ট্র্যাকিং চলানোর জন্য:\n\n" +
                "পরের স্ক্রিনে  ➜  'Allow all the time'  বেছে নিন।\n\n" +
                "এটি না করলে স্ক্রিন লক হলেই ট্র্যাকিং বন্ধ হবে।"
            )
            .setPositiveButton("সেটিংসে যান", (d, w) ->
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                            PERM_BG_LOCATION))
            .setNegativeButton("এখন না", (d, w) -> loadWebsite())
            .setCancelable(false)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == PERM_FINE_LOCATION) {
            if (granted) {
                // Fine location granted — now ask for background
                if (pendingGeoCallback != null) {
                    pendingGeoCallback.invoke(pendingGeoOrigin, true, false);
                    pendingGeoCallback = null;
                }
                requestBackgroundLocation();
            } else {
                showPermissionScreen(
                    "লোকেশন অনুমতি প্রয়োজন",
                    "GPS ছাড়া পিলগ্রিম ট্র্যাকিং সম্ভব নয়। 'অনুমতি দিন' বাটনে ট্যাপ করুন বা সেটিংসে গিয়ে চালু করুন।",
                    true
                );
            }

        } else if (requestCode == PERM_BG_LOCATION) {
            if (granted) {
                loadWebsite();
            } else {
                // Background location denied — still load website, just warn
                Toast.makeText(this,
                    "সতর্কতা: স্ক্রিন লক হলে ট্র্যাকিং বন্ধ হতে পারে। সেটিংসে গিয়ে 'Allow all the time' চালু করুন।",
                    Toast.LENGTH_LONG).show();
                loadWebsite();
            }
        } else if (requestCode == PERM_NOTIFICATION) {
            // Regardless of result, proceed
            startPermissionFlow();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LOAD WEBSITE
    // ═══════════════════════════════════════════════════════════════
    private void loadWebsite() {
        layoutPermission.setVisibility(View.GONE);

        // Request notification permission (Android 13+) — non-blocking
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERM_NOTIFICATION);
            }
        }

        if (!isOnline()) {
            showOfflineScreen();
            return;
        }

        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(WEBSITE_URL);
    }

    // ═══════════════════════════════════════════════════════════════
    // OFFLINE SCREEN
    // ═══════════════════════════════════════════════════════════════
    private void showOfflineScreen() {
        webView.setVisibility(View.GONE);
        layoutOffline.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
    }

    private void retryLoad() {
        layoutOffline.setVisibility(View.GONE);
        if (isOnline()) {
            webView.setVisibility(View.VISIBLE);
            webView.reload();
        } else {
            Toast.makeText(this, "ইন্টারনেট সংযোগ নেই। আবার চেষ্টা করুন।", Toast.LENGTH_SHORT).show();
            showOfflineScreen();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    // ═══════════════════════════════════════════════════════════════
    // PERMISSION EXPLANATION SCREEN
    // ═══════════════════════════════════════════════════════════════
    private void showPermissionScreen(String title, String message, boolean showSettings) {
        webView.setVisibility(View.GONE);
        layoutPermission.setVisibility(View.VISIBLE);
        tvPermMsg.setText(title + "\n\n" + message);
        findViewById(R.id.btnGrantPerm).setVisibility(showSettings ? View.GONE : View.VISIBLE);
        findViewById(R.id.btnOpenSettings).setVisibility(showSettings ? View.VISIBLE : View.GONE);
    }

    private void openAppSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    // ═══════════════════════════════════════════════════════════════
    // BACK BUTTON — navigate within WebView history
    // ═══════════════════════════════════════════════════════════════
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            // Ask before exiting — service keeps running in background
            new AlertDialog.Builder(this)
                .setTitle("অ্যাপ বন্ধ করবেন?")
                .setMessage("অ্যাপ বন্ধ হলেও ব্যাকগ্রাউন্ড ট্র্যাকিং চলতে থাকবে। লগআউট করুন ট্র্যাকিং বন্ধ করতে।")
                .setPositiveButton("বন্ধ করুন", (d, w) -> super.onBackPressed())
                .setNegativeButton("থাকুন", null)
                .show();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // LIFECYCLE — pause/resume WebView properly
    // ═══════════════════════════════════════════════════════════════
    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // Check if we just came back from settings with new permissions
        if (hasFineLocation() && webView.getVisibility() != View.VISIBLE
                && layoutOffline.getVisibility() != View.VISIBLE) {
            loadWebsite();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webView.destroy();
    }
}
