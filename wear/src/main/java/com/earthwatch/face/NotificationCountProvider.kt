package com.earthwatch.face

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Reads active notification count.
 *
 * On Wear OS, [NotificationManager.getActiveNotifications] returns the
 * notifications currently posted (including phone-bridged notifications).
 * This does NOT require a NotificationListenerService — just the normal
 * runtime permission is sufficient on the watch.
 */
class NotificationCountProvider(private val context: Context) {

    /** Number of active (visible) notifications, or 0. */
    @Volatile var count: Int = 0
        private set

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var lastRefreshMs = 0L

    /**
     * Call from the render thread (cheap — reads cached status).
     *
     * The actual Binder IPC ([NotificationManager.getActiveNotifications]) is
     * throttled to at most once every [THROTTLE_MS] because it is a
     * cross-process call. Between refreshes the last known [count] is returned
     * immediately with zero overhead.
     */
    fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs < THROTTLE_MS) return
        lastRefreshMs = now
        try {
            val statusBarNotifications = notificationManager.activeNotifications
            count = statusBarNotifications?.size ?: 0
        } catch (_: SecurityException) {
            Log.w("NotifProvider", "Notification access denied — count stays at 0")
        }
    }

    companion object {
        private const val THROTTLE_MS = 5000L
    }
}
