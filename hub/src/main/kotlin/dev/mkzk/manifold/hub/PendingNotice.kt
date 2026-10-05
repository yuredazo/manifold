package dev.mkzk.manifold.hub

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

internal object PendingNotice {
    private const val CHANNEL = "requests"
    private const val ID = 1

    private var shownFor: Set<String> = emptySet()

    fun update(context: Context, pending: List<PendingRow>) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (pending.isEmpty()) {
            shownFor = emptySet()
            notifications.cancel(ID)
            return
        }
        val packages = pending.mapTo(HashSet()) { it.packageName }
        // Quiet updates only: the first notice for a set of apps is the one that should alert.
        val alert = !shownFor.containsAll(packages)
        shownFor = packages

        ensureChannel(context, CHANNEL, context.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, HubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (pending.size == 1) {
            context.getString(R.string.notice_one, pending.single().app)
        } else {
            context.getString(R.string.notice_many, pending.size)
        }
        notifications.notify(
            ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_logo)
                .setContentTitle(title)
                .setContentText(pending.joinToString(", ") { it.app })
                .setContentIntent(open)
                .setOnlyAlertOnce(!alert)
                .build(),
        )
    }
}
