package com.hypeai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Restarts the voice listener service after device reboot.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Check if voice listener was enabled
            SharedPreferences prefs = context.getSharedPreferences("hypeai", 0);
            boolean voiceEnabled = prefs.getBoolean("voice_listener_enabled", false);

            if (voiceEnabled) {
                Intent serviceIntent = new Intent(context, VoiceListenerService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
