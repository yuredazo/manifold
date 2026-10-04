package dev.mkzk.manifold.hub

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.Surface
import dev.mkzk.manifold.IManifoldReceiver
import dev.mkzk.manifold.IManifoldSender
import dev.mkzk.manifold.SenderInfo

/**
 * Callbacks to other apps are one-way and may find the other side already
 * gone. That is not an error here: the death recipient set up in
 * [HubService] removes the entry a moment later.
 */
internal class BinderSender(private val callback: IManifoldSender) : SenderLink {
    override val key: Any = callback.asBinder()

    override fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?) {
        try {
            callback.onSubscribe(subscriptionId, surface, width, height, audioSink)
        } catch (_: RemoteException) {
        }
    }

    override fun revoke(subscriptionId: String) {
        try {
            callback.onUnsubscribe(subscriptionId)
        } catch (_: RemoteException) {
        }
    }
}

internal class BinderReceiver(private val callback: IManifoldReceiver) : ReceiverLink {
    override val key: Any = callback.asBinder()

    override fun sendersChanged(senders: List<SenderInfo>) {
        try {
            callback.onSenders(senders)
        } catch (_: RemoteException) {
        }
    }
}
