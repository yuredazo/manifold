package dev.mkzk.manifold.hub

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import java.io.IOException

private const val APK_NAME = "manifold-hub.apk"

internal class SessionInstaller(private val context: Context) : ApkInstaller {
    override fun begin(sizeBytes: Long): ApkSession {
        val packages = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (sizeBytes > 0) setSize(sizeBytes)
        }
        try {
            val id = packages.createSession(params)
            val session = packages.openSession(id)
            val out = session.openWrite(APK_NAME, 0, if (sizeBytes > 0) sizeBytes else -1)
            return object : ApkSession {
                override val stream = out

                override fun commit() {
                    session.fsync(out)
                    out.close()
                    session.commit(resultIntent(id).intentSender)
                    session.close()
                }

                override fun abandon() {
                    runCatching { out.close() }
                    runCatching { session.abandon() }
                    session.close()
                }
            }
        } catch (error: SecurityException) {
            throw IOException(error)
        }
    }

    private fun resultIntent(sessionId: Int): PendingIntent {
        // The system fills in the status, so the intent has to be mutable from Android 12 on.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, Intent(context, InstallResultReceiver::class.java), flags)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.instance.installFinished(InstallOutcome.Cancelled)
            else -> Updater.instance.installFinished(
                if (message?.contains("UPDATE_INCOMPATIBLE") == true || message?.contains("signature", ignoreCase = true) == true) {
                    InstallOutcome.DifferentSignature
                } else {
                    InstallOutcome.Failed(message)
                },
            )
        }
    }
}
