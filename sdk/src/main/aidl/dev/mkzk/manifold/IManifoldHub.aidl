package dev.mkzk.manifold;

import android.os.ParcelFileDescriptor;
import android.view.Surface;
import dev.mkzk.manifold.IManifoldReceiver;
import dev.mkzk.manifold.IManifoldSender;
import dev.mkzk.manifold.SenderInfo;

/** Frames never pass through the hub; it only forwards the receiver's Surface and audio pipe to the sender. */
interface IManifoldHub {
    int protocolVersion();

    /** Returns the registered name, which has a suffix if another app holds it. */
    String registerSender(in SenderInfo info, IManifoldSender callback);
    void updateSender(in SenderInfo info, IManifoldSender callback);
    void unregisterSender(IManifoldSender callback);

    void registerReceiver(IManifoldReceiver callback);
    void unregisterReceiver(IManifoldReceiver callback);

    /** Returns a subscription id, or null if refused. [senderName] need not be running yet. */
    String subscribe(IManifoldReceiver receiver, String senderName, in Surface surface,
            int width, int height, in @nullable ParcelFileDescriptor audioSink);
    void unsubscribe(String subscriptionId);
}
