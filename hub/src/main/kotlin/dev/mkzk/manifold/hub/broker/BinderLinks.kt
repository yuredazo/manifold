package dev.mkzk.manifold.hub.broker

import android.graphics.SurfaceTexture
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.Surface
import dev.mkzk.manifold.IManifoldReceiver
import dev.mkzk.manifold.IManifoldSender
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.SenderInfo

/** A callback may find the other side already gone. That is no error: the death recipient in [HubService] removes the entry. */
internal class BinderSender(private val callback: IManifoldSender) : SenderLink {
    override val key: Any = callback.asBinder()

    // A subscriber to a sound-only feed has no picture to show, but the sender's callback always receives a surface.
    private val placeholders = HashMap<String, SurfaceTexture>()

    override fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?) {
        val target = surface ?: placeholderFor(subscriptionId)
        try {
            callback.onSubscribe(subscriptionId, target, width, height, audioSink)
        } catch (_: RemoteException) {
        }
    }

    override fun revoke(subscriptionId: String) {
        synchronized(placeholders) { placeholders.remove(subscriptionId) }?.release()
        try {
            callback.onUnsubscribe(subscriptionId)
        } catch (_: RemoteException) {
        }
    }

    private fun placeholderFor(subscriptionId: String): Surface = synchronized(placeholders) {
        placeholders.getOrPut(subscriptionId) { SurfaceTexture(0).apply { setDefaultBufferSize(1, 1) } }
    }.let(::Surface)
}

internal class BinderReceiver(private val callback: IManifoldReceiver) : ReceiverLink {
    override val key: Any = callback.asBinder()

    override fun sendersChanged(senders: List<SenderInfo>) {
        try {
            callback.onSenders(senders)
        } catch (_: RemoteException) {
        }
    }

    override fun accessChanged(subscriptionId: String, access: Access) {
        val wire = when (access) {
            Access.ASK -> Manifold.ACCESS_PENDING
            Access.ALLOWED -> Manifold.ACCESS_ALLOWED
            Access.BLOCKED -> Manifold.ACCESS_BLOCKED
        }
        try {
            callback.onAccess(subscriptionId, wire)
        } catch (_: RemoteException) {
        }
    }
}
