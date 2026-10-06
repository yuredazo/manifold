package dev.mkzk.manifold.hub.broker

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.Surface
import dev.mkzk.manifold.IManifoldHub
import dev.mkzk.manifold.IManifoldReceiver
import dev.mkzk.manifold.IManifoldSender
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.SenderInfo
import dev.mkzk.manifold.hub.app.hubGraph
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A caller can only change what it registered itself. */
class HubService : Service() {

    private enum class Role { SENDER, RECEIVER }

    private data class WatchKey(val binder: IBinder, val role: Role)

    private val registry by lazy { hubGraph.registry }
    private val watched = HashMap<WatchKey, IBinder.DeathRecipient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val binder = object : IManifoldHub.Stub() {
        override fun protocolVersion() = Manifold.PROTOCOL_VERSION

        override fun registerSender(info: SenderInfo?, callback: IManifoldSender?): String? {
            if (info == null || callback == null) return null
            val link = BinderSender(callback)
            val key = callback.asBinder()
            if (!watch(key, Role.SENDER) { registry.removeSender(key) }) return null
            val name = registry.addSender(link, info, callerOwner())
            if (name == null) unwatch(key, Role.SENDER)
            return name
        }

        override fun updateSender(info: SenderInfo?, callback: IManifoldSender?) {
            if (info == null || callback == null) return
            registry.updateSender(callback.asBinder(), info, Binder.getCallingUid())
        }

        override fun unregisterSender(callback: IManifoldSender?) {
            if (callback == null) return
            val key = callback.asBinder()
            registry.removeSender(key, Binder.getCallingUid())
            unwatch(key, Role.SENDER)
        }

        override fun registerReceiver(callback: IManifoldReceiver?) {
            if (callback == null) return
            val key = callback.asBinder()
            if (!watch(key, Role.RECEIVER) { registry.removeReceiver(key) }) return
            if (!registry.addReceiver(BinderReceiver(callback), callerOwner())) {
                unwatch(key, Role.RECEIVER)
                throw IllegalStateException("too many receivers")
            }
        }

        override fun unregisterReceiver(callback: IManifoldReceiver?) {
            if (callback == null) return
            val key = callback.asBinder()
            registry.removeReceiver(key, Binder.getCallingUid())
            unwatch(key, Role.RECEIVER)
        }

        override fun subscribe(
            receiver: IManifoldReceiver?,
            senderName: String?,
            surface: Surface?,
            width: Int,
            height: Int,
            audioSink: ParcelFileDescriptor?,
        ): String? {
            val id = if (receiver == null || senderName == null || surface == null || !surface.isValid) {
                null
            } else {
                registry.subscribe(receiver.asBinder(), Binder.getCallingUid(), senderName, surface, width, height, audioSink)
            }
            if (id == null) {
                surface?.release()
                audioSink?.close()
            }
            return id
        }

        override fun unsubscribe(subscriptionId: String?) {
            if (subscriptionId == null) return
            registry.unsubscribe(subscriptionId, Binder.getCallingUid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            registry.state.map { it.pending }.distinctUntilChanged().collect { PendingNotice.update(this@HubService, it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        PendingNotice.update(this, emptyList())
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == Manifold.ACTION_BIND) binder else null

    private fun watch(target: IBinder, role: Role, onDeath: () -> Unit): Boolean {
        val key = WatchKey(target, role)
        val recipient = IBinder.DeathRecipient {
            synchronized(watched) { watched.remove(key) }
            onDeath()
        }
        try {
            target.linkToDeath(recipient, 0)
        } catch (_: RemoteException) {
            return false
        }
        val previous = synchronized(watched) { watched.put(key, recipient) }
        previous?.let { runCatching { target.unlinkToDeath(it, 0) } }
        return true
    }

    private fun unwatch(target: IBinder, role: Role) {
        val recipient = synchronized(watched) { watched.remove(WatchKey(target, role)) } ?: return
        runCatching { target.unlinkToDeath(recipient, 0) }
    }

    private fun callerOwner(): Owner {
        val uid = Binder.getCallingUid()
        val packages = packageManager
        val packageName = packages.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        val label = try {
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
        return Owner(uid, packageName, label, signingCertificate(packageName))
    }

    @Suppress("DEPRECATION")
    private fun signingCertificate(packageName: String): String = try {
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        signature?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } } ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }
}
