package dev.mkzk.manifold;

import dev.mkzk.manifold.SenderInfo;

/** Implemented by receivers. The hub sends the full sender list on every change. */
oneway interface IManifoldReceiver {
    void onSenders(in List<SenderInfo> senders);
}
