package com.hypeai.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import androidx.core.app.NotificationCompat;

// Vosk offline speech recognition imports (from com.alphacephei:vosk-android)
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;

/**
 * HypeAI Voice Service — works like Alexa / Google Assistant:
 *
 * STAGE 1 — Vosk offline (always-on, very low battery drain):
 *           Listens continuously for the wake word "hey hype"
 *           using a small on-device acoustic model.
 *
 * STAGE 2 — Google SpeechRecognizer (cloud, high accuracy):
 *           Activated ONLY after wake word is heard; captures
 *           the actual user command.
 *
 * STAGE 3 — Deliver command:
 *           Sends the command to the WebView via SharedPreferences
 *           and a static field, then returns to Stage 1.
 */
public class VoiceListenerService extends Service {

    private static final String TAG = "HypeVoice";
    private static final String CHANNEL_ID = "hypeai_voice";
    private static final int NOTIFICATION_ID = 1001;

    // Name of the Vosk model directory inside app assets / storage.
    // StorageService.unpack() will look for a zip in assets or download it.
    private static final String VOSK_MODEL = "vosk-model-small-en-us-0.15";

    // ── Vosk offline recognition (Stage 1: wake word) ────────────────────────
    private Model       voskModel;
    private SpeechService voskService;
    private boolean     voskReady = false;

    // ── Google SpeechRecognizer (Stage 2: command) ────────────────────────────
    private SpeechRecognizer googleRecognizer;

    private String  wakeWord        = "hey hype";
    private Handler handler         = new Handler(Looper.getMainLooper());
    private boolean isInCommandMode = false;

    /**
     * Static field so MainActivity can read commands without IPC overhead.
     * Set before SharedPreferences write; MainActivity clears after reading.
     */
    public static volatile String pendingCommand   = null;
    public static volatile long   commandTimestamp = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Service lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        loadWakeWord();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Initializing Vosk…"));
        initVosk();
        getSharedPreferences("hypeai", 0).edit()
            .putBoolean("voice_listener_enabled", true)
            .apply();
        return START_STICKY; // Restart automatically if the OS kills the service
    }

    @Override
    public void onDestroy() {
        getSharedPreferences("hypeai", 0).edit()
            .putBoolean("voice_listener_enabled", false)
            .apply();
        stopVosk();
        stopGoogleRecognizer();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ═══════════════════════════════════════════════════════════════════════════
    // FALLBACK — Google STT wake word loop (when Vosk unavailable)
    // ═══════════════════════════════════════════════════════════════════════════

    private void startGoogleWakeWordLoop() {
        if (isInCommandMode) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition unavailable");
            return;
        }
        handler.post(() -> {
            if (googleRecognizer != null) {
                try { googleRecognizer.cancel(); googleRecognizer.destroy(); } catch (Exception ignored) {}
                googleRecognizer = null;
            }
            googleRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L);

            googleRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle p) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float r) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(android.os.Bundle p) {
                    ArrayList<String> partial = p.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partial != null && !partial.isEmpty()) checkForWakeWord("{\"partial\":\"" + partial.get(0) + "\"}");
                }
                @Override public void onEvent(int t, android.os.Bundle p) {}
                @Override
                public void onResults(android.os.Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String text = matches.get(0);
                        checkForWakeWord("{\"text\":\"" + text + "\"}");
                    }
                    if (!isInCommandMode) handler.postDelayed(() -> startGoogleWakeWordLoop(), 300);
                }
                @Override
                public void onError(int error) {
                    // Errors 6/7 are normal end-of-speech — restart quietly
                    if (!isInCommandMode) handler.postDelayed(() -> startGoogleWakeWordLoop(), 800);
                }
            });
            googleRecognizer.startListening(intent);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STAGE 1 — Vosk offline wake word detection
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Download (first run) or open the Vosk model, then start listening.
     * The model is ~50 MB and is cached in the app's internal storage after
     * the first download — no re-download on subsequent launches.
     */
    private int voskRetryCount = 0;

    private void initVosk() {
        StorageService.unpack(
            this,
            VOSK_MODEL,
            "model",
            new StorageService.Callback<Model>() {
                @Override
                public void onComplete(Model model) {
                    voskModel = model;
                    voskReady = true;
                    voskRetryCount = 0;
                    Log.i(TAG, "Vosk model loaded");
                    updateNotification("Listening for \"" + wakeWord + "\"");
                    startVoskListener();
                }
            },
            new StorageService.Callback<IOException>() {
                @Override
                public void onComplete(IOException e) {
                    voskRetryCount++;
                    Log.e(TAG, "Vosk model failed (attempt " + voskRetryCount + "): " + e.getMessage());
                    if (voskRetryCount >= 2) {
                        // Vosk unavailable — fall back to Google STT loop for wake word
                        Log.w(TAG, "Falling back to Google STT wake word detection");
                        updateNotification("Listening for \"" + wakeWord + "\" (Google)");
                        startGoogleWakeWordLoop();
                    } else {
                        updateNotification("Vosk model failed - retrying in 30s");
                        handler.postDelayed(new Runnable(){
                            @Override public void run(){ initVosk(); }
                        }, 30000);
                    }
                }
            }
        );
    }

    
    private void startVoskListener() {
        if (!voskReady || voskModel == null) return;
        try {
            // Use a constrained grammar so only wake-word variants are decoded
            // (much faster and more accurate than open-vocabulary mode).
            Recognizer rec = new Recognizer(
                voskModel, 16000.0f,
                "[\"hey hype\", \"hi hype\", \"ok hype\", \"hype ai\", " +
                "\"hey type\", \"hey pipe\", \"hey hyp\", \"[unk]\"]"
            );

            voskService = new SpeechService(rec, 16000.0f);
            voskService.startListening(new org.vosk.android.RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    checkForWakeWord(hypothesis);
                }

                @Override
                public void onResult(String hypothesis) {
                    checkForWakeWord(hypothesis);
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    checkForWakeWord(hypothesis);
                }

                @Override
                public void onError(Exception exception) {
                    Log.e(TAG, "Vosk recognition error: " + exception.getMessage());
                    // Brief delay then restart to avoid a tight error loop
                    handler.postDelayed(new Runnable(){@Override public void run(){startVoskListener();}}, 2000);
                }

                @Override
                public void onTimeout() {
                    // Shouldn't happen in continuous mode, but restart just in case
                    startVoskListener();
                }
            });

            Log.i(TAG, "Vosk listener started — waiting for wake word");

        } catch (IOException e) {
            Log.e(TAG, "Failed to start Vosk SpeechService: " + e.getMessage());
            handler.postDelayed(new Runnable(){@Override public void run(){startVoskListener();}}, 5000);
        }
    }

    private void stopVosk() {
        if (voskService != null) {
            try {
                voskService.stop();
                voskService.shutdown();
            } catch (Exception ignored) {}
            voskService = null;
        }
    }

    /**
     * Vosk returns results as JSON strings, e.g.:
     *   {"partial":"hey hype"} or {"text":"hey hype open youtube"}
     * Parse the lowercase text and look for any wake-word variant.
     */
    private void checkForWakeWord(String json) {
        if (isInCommandMode || json == null) return;

        String lower = json.toLowerCase(Locale.ROOT);
        String[] variants = { wakeWord, "hey type", "hey pipe", "hi hype", "ok hype", "hype ai" };

        for (String variant : variants) {
            if (lower.contains(variant)) {
                Log.i(TAG, "🎤 WAKE WORD DETECTED: " + json);

                // Extract any words spoken after the wake word in the same utterance
                int idx = lower.indexOf(variant) + variant.length();
                String afterWake = json.substring(Math.min(idx, json.length()))
                    .replace("\"", "").replace("}", "").trim();

                onWakeWordDetected(afterWake);
                return;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STAGE 2 — Google SpeechRecognizer (command capture)
    // ═══════════════════════════════════════════════════════════════════════════

    private void onWakeWordDetected(String partialCommand) {
        isInCommandMode = true;
        updateNotification("🟢 Listening for command…");

        // Pause Vosk while Google STT captures the command (avoids mic contention)
        stopVosk();

        // If the user already spoke the command in the same utterance as the
        // wake word ("hey hype set a timer") use it directly.
        if (partialCommand != null && partialCommand.length() > 3) {
            onCommandReceived(partialCommand);
            return;
        }

        startGoogleListening();

        // Safety timeout: if no result within 8 s, fall back to wake-word mode
        handler.postDelayed(() -> {
            if (isInCommandMode) {
                Log.d(TAG, "Command timeout — returning to wake word mode");
                isInCommandMode = false;
                stopGoogleRecognizer();
                startVoskListener();
                updateNotification("Listening for \"" + wakeWord + "\"");
            }
        }, 8_000);
    }

    private void startGoogleListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Google SpeechRecognizer unavailable — falling back to Vosk");
            isInCommandMode = false;
            startVoskListener();
            return;
        }

        // SpeechRecognizer must be created and used on the main thread
        handler.post(() -> {
            googleRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L);

            googleRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(android.os.Bundle params) {
                    Log.d(TAG, "Google STT ready — listening for command");
                }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}

                @Override
                public void onError(int error) {
                    Log.d(TAG, "Google STT error code: " + error);
                    isInCommandMode = false;
                    stopGoogleRecognizer();
                    startVoskListener();
                    updateNotification("Listening for \"" + wakeWord + "\"");
                }

                @Override
                public void onResults(android.os.Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        onCommandReceived(matches.get(0));
                    } else {
                        isInCommandMode = false;
                        startVoskListener();
                        updateNotification("Listening for \"" + wakeWord + "\"");
                    }
                }

                @Override public void onPartialResults(android.os.Bundle partial) {}
                @Override public void onEvent(int eventType, android.os.Bundle params) {}
            });

            googleRecognizer.startListening(intent);
        });
    }

    private void stopGoogleRecognizer() {
        handler.post(() -> {
            if (googleRecognizer != null) {
                try {
                    googleRecognizer.stopListening();
                    googleRecognizer.cancel();
                    googleRecognizer.destroy();
                } catch (Exception ignored) {}
                googleRecognizer = null;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STAGE 3 — Deliver command → WebView, return to Stage 1
    // ═══════════════════════════════════════════════════════════════════════════

    private void onCommandReceived(String command) {
        Log.i(TAG, "✅ COMMAND RECEIVED: " + command);
        isInCommandMode = false;

        // 1. Fast path: static field (read by MainActivity.startCommandPoller)
        pendingCommand   = command;
        commandTimestamp = System.currentTimeMillis();

        // 2. Durable path: SharedPreferences (survives process recreation)
        getSharedPreferences("hypeai", 0).edit()
            .putString("pending_command", command)
            .putLong("command_time", System.currentTimeMillis())
            .apply();

        // 3. Bring the app to the foreground with the command as an Intent extra
        Intent launchIntent = getPackageManager()
            .getLaunchIntentForPackage(getPackageName());
        if (launchIntent != null) {
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_SINGLE_TOP |
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            );
            launchIntent.putExtra("voice_command", command);
            startActivity(launchIntent);
        }

        // Brief pause then restart wake word listener
        handler.postDelayed(() -> {
            stopGoogleRecognizer();
            startVoskListener();
            updateNotification("Listening for \"" + wakeWord + "\"");
        }, 1_000);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Notification helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private void loadWakeWord() {
        wakeWord = getSharedPreferences("hypeai", 0)
            .getString("wake_word", "hey hype")
            .toLowerCase(Locale.ROOT);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "HypeAI Voice",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("HypeAI always-listening voice assistant");
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent tapIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(
            this, 0,
            tapIntent != null ? tapIntent : new Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HypeAI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
