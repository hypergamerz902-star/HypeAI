package com.hypeai.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

/**
 * JavaScript ↔ Android bridge.
 * Accessible from JS via: window.AndroidBridge.methodName()
 */
public class AndroidBridge {

    private final MainActivity activity;

    public AndroidBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void showToast(String message) {
        activity.runOnUiThread(() ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void startVoiceListener() {
        activity.runOnUiThread(() -> activity.startVoiceService());
    }

    @JavascriptInterface
    public void stopVoiceListener() {
        activity.runOnUiThread(() -> activity.stopVoiceService());
    }

    @JavascriptInterface
    public void requestMicPermission() {
        activity.runOnUiThread(() ->
            ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.RECORD_AUDIO}, 200));
    }

    @JavascriptInterface
    public void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.runOnUiThread(() ->
                ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 201));
        }
    }

    @JavascriptInterface
    public void requestOverlayPermission() {
        activity.runOnUiThread(() -> activity.requestOverlayPermission());
    }

    @JavascriptInterface
    public void requestBatteryOptimization() {
        activity.runOnUiThread(() -> activity.requestBatteryOptimization());
    }

    @JavascriptInterface
    public String getPermissionStates() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("mic", ContextCompat.checkSelfPermission(activity,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
            obj.put("notif", Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(activity,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
            obj.put("overlay", Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(activity));
            obj.put("battery", Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ((android.os.PowerManager) activity.getSystemService(activity.POWER_SERVICE))
                    .isIgnoringBatteryOptimizations(activity.getPackageName()));
            return obj.toString();
        } catch (Exception e) {
            return "{\"mic\":false,\"notif\":false,\"overlay\":false,\"battery\":false}";
        }
    }

    @JavascriptInterface
    public void setWakeWord(String word) {
        // Pass to VoiceListenerService via SharedPreferences
        activity.getSharedPreferences("hypeai", 0).edit()
            .putString("wake_word", word).apply();
        showToast("Wake word: \"" + word + "\"");
    }

    @JavascriptInterface
    public void appResumed() {
        // Called when app comes to foreground
    }

    @JavascriptInterface
    public void toggleFloatingIcon(boolean enabled) {
        if (enabled) {
            activity.runOnUiThread(() -> activity.requestOverlayPermission());
        }
    }

    /**
     * HTTP POST via native Java (bypasses CORS in WebView).
     * Calls back to JS via window._nativeCbs[callbackId].
     */
    @JavascriptInterface
    public void nativePost(final String callbackId, final String url, final String headersJson, final String bodyStr) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(300000);

                JSONObject headers = new JSONObject(headersJson);
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    conn.setRequestProperty(key, headers.getString(key));
                }

                byte[] bodyBytes = bodyStr.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bodyBytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                }

                int code = conn.getResponseCode();
                java.io.InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                }

                JSONObject payload = new JSONObject()
                    .put("ok", code >= 200 && code < 400)
                    .put("status", code)
                    .put("body", sb.toString().trim());

                final String js = "if(window._nativeCbs&&window._nativeCbs['" + callbackId + "']){window._nativeCbs['" + callbackId + "'].resolve(" + payload.toString() + ");delete window._nativeCbs['" + callbackId + "']}";
                activity.runOnUiThread(() -> {
                    if (activity.getWebView() != null)
                        activity.getWebView().evaluateJavascript(js, null);
                });
            } catch (Exception e) {
                String msg = (e.getMessage() != null ? e.getMessage() : "unknown").replace("'", "\\'");
                final String js = "if(window._nativeCbs&&window._nativeCbs['" + callbackId + "']){window._nativeCbs['" + callbackId + "'].reject(new Error('" + msg + "'));delete window._nativeCbs['" + callbackId + "']}";
                activity.runOnUiThread(() -> {
                    if (activity.getWebView() != null)
                        activity.getWebView().evaluateJavascript(js, null);
                });
            }
        }).start();
    }

    /**
     * Native speech-to-text for chat mic (bypasses webkitSpeechRecognition CORS block in file:// origins).
     */
    @JavascriptInterface
    public void startChatListening(final String callbackId) {
        activity.runOnUiThread(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                String js = "if(window._nativeCbs&&window._nativeCbs['" + callbackId + "']){window._nativeCbs['" + callbackId + "'].reject(new Error('Speech recognition unavailable'));delete window._nativeCbs['" + callbackId + "']}";
                if (activity.getWebView() != null) activity.getWebView().evaluateJavascript(js, null);
                return;
            }

            SpeechRecognizer sr = SpeechRecognizer.createSpeechRecognizer(activity);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            sr.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float r) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(android.os.Bundle p) {}
                @Override public void onEvent(int t, android.os.Bundle p) {}

                @Override
                public void onResults(android.os.Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
                    text = text.replace("\\", "\\\\").replace("'", "\\'");
                    String js = "if(window._nativeCbs&&window._nativeCbs['" + callbackId + "']){window._nativeCbs['" + callbackId + "'].resolve('" + text + "');delete window._nativeCbs['" + callbackId + "']}";
                    if (activity.getWebView() != null) activity.getWebView().evaluateJavascript(js, null);
                    sr.destroy();
                }

                @Override
                public void onError(int error) {
                    String js = "if(window._nativeCbs&&window._nativeCbs['" + callbackId + "']){window._nativeCbs['" + callbackId + "'].reject(new Error('STT error'));delete window._nativeCbs['" + callbackId + "']}";
                    if (activity.getWebView() != null) activity.getWebView().evaluateJavascript(js, null);
                    sr.destroy();
                }
            });

            sr.startListening(intent);
        });
    }
}
