package dev.mkzk.manifold.probe

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import dev.mkzk.manifold.Manifold
import dev.mkzk.manifold.ManifoldReceiver
import dev.mkzk.manifold.SenderInfo
import java.io.FileInputStream
import java.io.IOException

private const val TAG = "PROBE"

class WatchActivity : Activity(), SurfaceHolder.Callback {

    private var receiver: ManifoldReceiver? = null
    private var subscription: ManifoldReceiver.Subscription? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var audioPipe: Array<ParcelFileDescriptor>? = null
    private var width = 0
    private var height = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(SurfaceView(this).also { it.holder.addCallback(this) })
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, newWidth: Int, newHeight: Int) {
        width = newWidth
        height = newHeight
        if (receiver != null) return
        val prefix = intent.getStringExtra("match").orEmpty()
        receiver = ManifoldReceiver(
            this,
            object : ManifoldReceiver.Listener {
                override fun onSenders(senders: List<SenderInfo>) {
                    if (subscription != null) return
                    val feed = senders.firstOrNull { it.name.startsWith(prefix) } ?: return
                    Log.i(TAG, "watching '${feed.name}' at ${width}x$height")
                    val pipe = if (intent.getBooleanExtra("audio", false)) ParcelFileDescriptor.createPipe() else null
                    audioPipe = pipe
                    pipe?.let { Thread({ play(it[0]) }, "probe-play").start() }
                    subscription = receiver?.subscribe(feed.name, holder.surface, width, height, pipe?.get(1))
                }

                override fun onAccessChanged(subscription: ManifoldReceiver.Subscription, access: ManifoldReceiver.Access) {
                    Log.i(TAG, "access for ${subscription.senderName}: $access")
                }
            },
        ).also { it.start() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val current = receiver ?: return
        subscription?.let(current::unsubscribe)
        current.stop()
        receiver = null
        subscription = null
        audioPipe?.forEach { runCatching { it.close() } }
        audioPipe = null
    }

    private fun play(source: ParcelFileDescriptor) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Manifold.AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val chunk = ByteArray(4096)
        var total = 0L
        try {
            val input = FileInputStream(source.fileDescriptor)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                track.write(chunk, 0, read)
                if (total == 0L) Log.i(TAG, "first sound arrived")
                total += read
            }
        } catch (_: IOException) {
            // The pipe was closed.
        } finally {
            Log.i(TAG, "sound stopped after $total bytes")
            track.release()
        }
    }
}
