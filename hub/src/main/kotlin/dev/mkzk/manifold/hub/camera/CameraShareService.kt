package dev.mkzk.manifold.hub.camera

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.app.HubActivity
import dev.mkzk.manifold.hub.app.hubGraph
import dev.mkzk.manifold.hub.notify.ensureChannel

private const val CHANNEL = "camera"
private const val ID = 4
private const val ACTION_STOP = "dev.mkzk.manifold.hub.STOP_CAMERA"

// Android lets an app use the camera in the background only from a foreground service started while the app was in view.
class CameraShareService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A restart by the system would come from the background, where the camera cannot be opened.
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel(this, CHANNEL, getString(R.string.channel_camera), NotificationManager.IMPORTANCE_LOW)
        val open = PendingIntent.getActivity(this, 0, Intent(this, HubActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(getString(R.string.camera_notice_title))
            .setContentText(getString(R.string.camera_notice_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.camera_stop), stop)
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }
        hubGraph.cameraShare.start(this, onStopped = ::stopSelf)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hubGraph.cameraShare.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CameraShareService::class.java))
        }
    }
}
