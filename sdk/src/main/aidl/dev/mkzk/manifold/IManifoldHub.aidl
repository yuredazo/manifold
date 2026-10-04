package dev.mkzk.manifold;

import android.os.ParcelFileDescriptor;
import android.view.Surface;
import dev.mkzk.manifold.IManifoldReceiver;
import dev.mkzk.manifold.IManifoldSender;
import dev.mkzk.manifold.SenderInfo;

/**
 * Introduces senders to receivers. Frames and audio never pass through the
 * hub: it only forwards the receiver's Surface (and audio pipe) to the sender.
 */
interface IManifoldHub {
    int protocolVersion();

    /** Announces a sender. Returns the name it was registered under (suffixed if taken by another app). */
    String registerSender(in SenderInfo info, IManifoldSender callback);
    void updateSender(in SenderInfo info, IManifoldSender callback);
    void unregisterSender(IManifoldSender callback);

    /** Registers for sender-list updates; the current list is pushed straight away. */
    void registerReceiver(IManifoldReceiver callback);
    void unregisterReceiver(IManifoldReceiver callback);

    /**
     * Watches [senderName]. The sender does not have to be running yet: the
     * subscription is delivered when it announces itself. Returns a subscription
     * id, or null if the request was refused.
     */
    String subscribe(IManifoldReceiver receiver, String senderName, in Surface surface,
            int width, int height, in @nullable ParcelFileDescriptor audioSink);
    void unsubscribe(String subscriptionId);
}
