package com.earthwatch.face

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class InstanceRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.google.wear.services.watchface.action.INSTANCE_REMOVED") {
            Log.d("EarthWatchFace", "Instance removed: ${intent.data}")
        }
    }
}
