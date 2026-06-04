package com.hypeai.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.*;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private WebView webView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private static final int PERMISSION_REQUEST = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive with cutout support
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        webView = new WebView(this);
        setContentView(webView);
        setupWebView();
        requestPermissions();
        webView.loadUrl("file:///android_asset/index.html");

        // Check for voice command that launched the app
        checkVoiceCommand(getIntent());

        // Poll for voice commands from background service
        startCommandPoller();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkVoiceCommand(intent);
    }

    private void checkVoiceCommand(Intent intent) {
        if (intent == null) return;
        String command = intent.getStringExtra("voice_command");
        if (command != null && !command.isEmpty()) {
            // Wait for WebView to be ready, then inject the command
            handler.postDelayed(() -> sendCommandToWebView(command), 1500);
            // Clear so it doesn't fire again
            intent.removeExtra("voice_command");
        }
    }

    /**
     * Poll SharedPreferences every 2 seconds for commands from VoiceListenerService.
     * This is the bridge between the background service and the WebView.
     */
    private void startCommandPoller() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = getSharedPreferences("hypeai", 0);
                String cmd = prefs.getString("pending_command", null);
                long time = prefs.getLong("command_time", 0);

                if (cmd != null && !cmd.isEmpty() && 
                    System.currentTimeMillis() - time < 10000) { // within last 10s
                    // Clear immediately so we don't process twice
                    prefs.edit()
                        .remove("pending_command")
                        .remove("command_time")
                        .apply();

                    sendCommandToWebView(cmd);
                }

                // Also check static field (faster path)
                if (VoiceListenerService.pendingCommand != null) {
                    String staticCmd = VoiceListenerService.pendingCommand;
                    VoiceListenerService.pendingCommand = null;
                    sendCommandToWebView(staticCmd);
                }

                handler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    /**
     * Inject voice command into the WebView JavaScript.
     */
    private void sendCommandToWebView(String command) {
        if (webView == null) return;
        String escaped = command.replace("\\", "\\\\")
                               .replace("'", "\\'")
                               .replace("\"", "\\\"")
                               .replace("\n", "\\n");
        String js = "if(typeof handleVoiceInput==='function'){handleVoiceInput('" + escaped + "')}" +
                    "else{console.log('handleVoiceInput not ready, retrying...');" +
                    "setTimeout(function(){if(typeof handleVoiceInput==='function')handleVoiceInput('" + escaped + "')},2000)}";
        webView.evaluateJavascript(js, null);
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " HypeAI-Android/1.0");

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http") && !url.contains("android_asset")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
    }

    private void requestPermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            perms = new String[]{Manifest.permission.RECORD_AUDIO};
        }
        ActivityCompat.requestPermissions(this, perms, PERMISSION_REQUEST);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true); // Minimize, don't kill
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript(
                "if(typeof handleAppResume==='function')handleAppResume()", null);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    // Called from AndroidBridge
    public void startVoiceService() {
        Intent intent = new Intent(this, VoiceListenerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "🎤 Voice listener started", Toast.LENGTH_SHORT).show();
    }

    public void stopVoiceService() {
        stopService(new Intent(this, VoiceListenerService.class));
        Toast.makeText(this, "🔇 Voice listener stopped", Toast.LENGTH_SHORT).show();
    }

    public void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }
    }

    public void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    public WebView getWebView() { return webView; }
}
