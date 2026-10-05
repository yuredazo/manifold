package dev.mkzk.manifold;

import android.os.ParcelFileDescriptor;
import android.view.Surface;

oneway interface IManifoldSender {
    /** Draw into [surface]; with an [audioSink], write 48 kHz stereo 16-bit little-endian PCM into it. */
    void onSubscribe(String subscriptionId, in Surface surface, int width, int height,
            in @nullable ParcelFileDescriptor audioSink);

    void onUnsubscribe(String subscriptionId);
}
