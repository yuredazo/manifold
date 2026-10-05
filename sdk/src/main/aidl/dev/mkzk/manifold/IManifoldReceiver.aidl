package dev.mkzk.manifold;

import dev.mkzk.manifold.SenderInfo;

oneway interface IManifoldReceiver {
    /** The full sender list, on every change. */
    void onSenders(in List<SenderInfo> senders);

    /** 0 pending, 1 allowed, 2 blocked. Added after the first release; older receivers ignore it. */
    void onAccess(String subscriptionId, int access);
}
