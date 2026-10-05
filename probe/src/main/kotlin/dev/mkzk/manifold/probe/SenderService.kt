package dev.mkzk.manifold.probe

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import dev.mkzk.manifold.ManifoldSender
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

class SenderService : Service() {

    private val senders = HashMap<String, ManifoldSender>()
    private val streams = ConcurrentHashMap<String, Stream>()

    private val listener = object : ManifoldSender.Listener {
        override fun onRegistered(registeredName: String) {
            Log.i(TAG, "SENDER registered as '$registeredName'")
        }

        override fun onHubLost() {
            Log.i(TAG, "SENDER hub lost")
        }

        override fun onSubscribe(subscription: ManifoldSender.Subscription) {
            Log.i(TAG, "SENDER subscribe ${subscription.id.take(8)} ${subscription.width}x${subscription.height} audio=${subscription.audioSink != null}")
            streams.put(subscription.id, Stream(subscription))?.stop()
        }

        override fun onUnsubscribe(subscriptionId: String) {
            Log.i(TAG, "SENDER unsubscribe ${subscriptionId.take(8)}")
            streams.remove(subscriptionId)?.stop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME) ?: return START_NOT_STICKY
        if (intent.getBooleanExtra(EXTRA_STOP, false)) {
            senders.remove(name)?.stop()
            return START_NOT_STICKY
        }
        senders.getOrPut(name) {
            ManifoldSender(this, ManifoldSender.Config(name, width = 320, height = 240, fps = 30, hasAudio = true), listener)
                .also { it.start() }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        senders.values.forEach { it.stop() }
        streams.values.forEach { it.stop() }
        super.onDestroy()
    }

    private class Stream(private val subscription: ManifoldSender.Subscription) {
        @Volatile private var running = true

        init {
            Thread(::drawFrames, "probe-video").start()
            subscription.audioSink?.let { sink -> Thread({ writeTone(sink) }, "probe-audio").start() }
        }

        fun stop() {
            running = false
        }

        private fun drawFrames() {
            val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE)
            val surface = subscription.surface
            var frames = 0
            val startedAt = SystemClock.elapsedRealtime()
            try {
                while (running) {
                    val canvas = surface.lockCanvas(null)
                    canvas.drawColor(colors[frames % 3])
                    surface.unlockCanvasAndPost(canvas)
                    frames++
                    Thread.sleep(33)
                }
                Log.i(TAG, "SENDER video ${subscription.id.take(8)} stopped on request after $frames frames")
            } catch (t: Throwable) {
                val ms = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "SENDER video ${subscription.id.take(8)} lost its surface after $frames frames, ${ms}ms: ${t.javaClass.simpleName}")
            } finally {
                surface.release()
            }
        }

        private fun writeTone(sink: ParcelFileDescriptor) {
            val frames = 960
            val chunk = ByteArray(frames * 4)
            var phase = 0.0
            var written = 0L
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                    var next = SystemClock.elapsedRealtimeNanos()
                    while (running) {
                        for (i in 0 until frames) {
                            val sample = (sin(phase) * 8000).toInt().toShort()
                            phase += 2 * PI * 440 / 48_000
                            val at = i * 4
                            chunk[at] = (sample.toInt() and 0xFF).toByte()
                            chunk[at + 1] = (sample.toInt() shr 8).toByte()
                            chunk[at + 2] = chunk[at]
                            chunk[at + 3] = chunk[at + 1]
                        }
                        out.write(chunk)
                        written += chunk.size
                        next += 20_000_000L
                        val wait = (next - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
                Log.i(TAG, "SENDER audio ${subscription.id.take(8)} stopped on request after $written bytes")
            } catch (e: IOException) {
                Log.i(TAG, "SENDER audio ${subscription.id.take(8)} pipe closed after $written bytes: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PROBE"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_STOP = "stop"

        fun start(context: Context, name: String) {
            context.startService(Intent(context, SenderService::class.java).putExtra(EXTRA_NAME, name))
        }

        fun stop(context: Context, name: String) {
            context.startService(
                Intent(context, SenderService::class.java).putExtra(EXTRA_NAME, name).putExtra(EXTRA_STOP, true),
            )
        }
    }
}
