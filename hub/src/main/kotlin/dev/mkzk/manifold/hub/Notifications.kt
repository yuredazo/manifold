package dev.mkzk.manifold.hub

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Channels only exist from Android 8. Older versions ignore the channel id and show the notification
 * anyway.
 */
internal fun ensureChannel(context: Context, id: String, name: String, importance: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id, name, importance))
}
