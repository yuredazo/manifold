package dev.mkzk.manifold.hub.screen

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.mkzk.manifold.hub.app.hubGraph
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.app.HubActivity
import dev.mkzk.manifold.hub.notify.ensureChannel

private const val CHANNEL = "screen"
private const val ID = 3
private const val ACTION_STOP = "dev.mkzk.manifold.hub.STOP_SCREEN"
private const val EXTRA_CONSENT = "consent"
private const val EXTRA_SOUND = "sound"

/** Android only lets an app keep capturing the screen from a foreground service, and shows its notification. */
class ScreenShareService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // The consent is single use, so a restart by the system could not capture again.
        val consent = IntentCompat.getParcelableExtra(intent, EXTRA_CONSENT, Intent::class.java)
        if (consent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel(this, CHANNEL, getString(R.string.channel_screen), NotificationManager.IMPORTANCE_LOW)
        val open = PendingIntent.getActivity(this, 0, Intent(this, HubActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(getString(R.string.screen_notice_title))
            .setContentText(getString(R.string.screen_notice_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.screen_stop), stop)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projection = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
        } catch (e: SecurityException) {
            null
        }
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        hubGraph.screenShare.start(this, projection, intent.getBooleanExtra(EXTRA_SOUND, false), onStopped = ::stopSelf)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hubGraph.screenShare.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context, consent: Intent, sound: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenShareService::class.java).putExtra(EXTRA_CONSENT, consent).putExtra(EXTRA_SOUND, sound),
            )
        }
    }
}
