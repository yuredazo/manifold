package dev.mkzk.manifold;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

/** Implemented by senders. The hub calls it when a receiver starts or stops watching. */
oneway interface IManifoldSender {
    /**
     * Render into [surface] at [width] x [height]. If [audioSink] is set, write
     * 48 kHz stereo signed 16-bit little-endian PCM into it.
     */
    void onSubscribe(String subscriptionId, in Surface surface, int width, int height,
            in @nullable ParcelFileDescriptor audioSink);

    /** Stop rendering into the surface delivered for [subscriptionId]. */
    void onUnsubscribe(String subscriptionId);
}
