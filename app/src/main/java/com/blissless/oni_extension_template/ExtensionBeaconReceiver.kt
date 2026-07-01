package com.blissless.oni_extension_template

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ExtensionBeaconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Exists only to be discoverable by the Main App
    }
}