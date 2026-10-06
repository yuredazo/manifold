package dev.mkzk.manifold.probe

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import android.view.View
import dev.mkzk.manifold.ManifoldReceiver
import dev.mkzk.manifold.ManifoldSender
import dev.mkzk.manifold.SenderInfo
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : Activity() {

    private val imageThread = HandlerThread("probe-images").apply { start() }
    private val imageHandler = Handler(imageThread.looper)

    @Volatile private var senders: List<String> = emptyList()
    @Volatile private var connected = false

    private inner class Watch(withAudio: Boolean, readAudio: Boolean = true) {
        val frames = AtomicInteger()
        val audioBytes = AtomicLong()
        val seen = BooleanArray(3)
        val reader: ImageReader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 4)
        private val pipe = if (withAudio) ParcelFileDescriptor.createPipe() else null
        val audioSink: ParcelFileDescriptor? = pipe?.get(1)

        init {
            reader.setOnImageAvailableListener({ r ->
                val image = try { r.acquireLatestImage() } catch (_: Throwable) { null }
                image?.use {
                    val buf = it.planes[0].buffer
                    val red = buf.get(0).toInt() and 0xFF
                    val green = buf.get(1).toInt() and 0xFF
                    val blue = buf.get(2).toInt() and 0xFF
                    when {
                        red > 200 && green < 50 && blue < 50 -> seen[0] = true
                        green > 200 && red < 50 && blue < 50 -> seen[1] = true
                        blue > 200 && red < 50 && green < 50 -> seen[2] = true
                    }
                    frames.incrementAndGet()
                }
            }, imageHandler)
            pipe?.takeIf { readAudio }?.let { p ->
                Thread({
                    val buffer = ByteArray(4096)
                    try {
                        ParcelFileDescriptor.AutoCloseInputStream(p[0]).use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                audioBytes.addAndGet(n.toLong())
                            }
                        }
                    } catch (_: IOException) {
                    }
                }, "probe-audio-reader").start()
            }
        }

        val colours get() = seen.all { it }

        fun summary() = "frames=${frames.get()} colours=${seen.joinToString(",")} audioBytes=${audioBytes.get()}"

        fun close() {
            try { reader.close() } catch (_: Throwable) {}
            try { audioSink?.close() } catch (_: IOException) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phase = intent.getStringExtra("phase") ?: "basic"
        // The activity can be recreated mid-run; one scenario at a time.
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                when (phase) {
                    "basic" -> basic()
                    "hubkill" -> hubKill()
                    "senderkill" -> senderKill()
                    "consumerlost" -> consumerLost()
                    "misuse" -> misuse()
                    "screensound" -> screenSound()
                    "screensync" -> screenSync()
                    "alpha" -> alpha()
                    // Leaves a sender running for manual tests of other apps.
                    "sender" -> SenderService.start(this, intent.getStringExtra("name") ?: "probe-a")
                    else -> Log.i(TAG, "unknown phase '$phase'")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "phase '$phase' crashed", t)
            }
            Log.i(TAG, "PHASE $phase DONE")
            running.set(false)
        }.start()
    }

    private fun startReceiver(): ManifoldReceiver {
        val receiver = ManifoldReceiver(applicationContext, object : ManifoldReceiver.Listener {
            override fun onSenders(senders: List<SenderInfo>) {
                this@MainActivity.senders = senders.map { it.name }
                Log.i(TAG, "RECEIVER senders=${this@MainActivity.senders}")
            }

            override fun onConnectionChanged(connected: Boolean) {
                this@MainActivity.connected = connected
                Log.i(TAG, "RECEIVER connected=$connected")
            }
        })
        receiver.start()
        check("connects to the hub") { waitFor(5000) { connected } }
        return receiver
    }

    private fun basic() {
        val receiver = startReceiver()

        SenderService.start(this, "probe-a")
        check("sender is discovered") { waitFor(5000) { "probe-a" in senders } }

        val a = Watch(withAudio = true)
        val subA = receiver.subscribe("probe-a", a.reader.surface, 320, 240, a.audioSink)
        Thread.sleep(3000)
        report("video arrives intact", a.frames.get() >= 60 && a.colours, a.summary())
        report("audio arrives", a.audioBytes.get() >= 300_000, "bytes=${a.audioBytes.get()} of ~576000 expected")

        val b = Watch(withAudio = false)
        val c = Watch(withAudio = false)
        val subB = receiver.subscribe("probe-a", b.reader.surface, 320, 240)
        val subC = receiver.subscribe("probe-a", c.reader.surface, 320, 240)
        Thread.sleep(3000)
        report("fan-out to three surfaces", b.frames.get() >= 60 && c.frames.get() >= 60, "b: ${b.summary()}  c: ${c.summary()}")

        receiver.unsubscribe(subA)
        receiver.unsubscribe(subB)
        receiver.unsubscribe(subC)
        Thread.sleep(1000)
        val settled = a.frames.get()
        Thread.sleep(1000)
        report("unsubscribe stops the video", a.frames.get() - settled <= 2, "frames after unsubscribe: ${a.frames.get() - settled}")

        val late = Watch(withAudio = false)
        val subLate = receiver.subscribe("probe-late", late.reader.surface, 320, 240)
        Thread.sleep(1500)
        report("waits while the sender is absent", late.frames.get() == 0, late.summary())
        SenderService.start(this, "probe-late")
        Thread.sleep(3500)
        report("delivered once the sender appears", late.frames.get() >= 60, late.summary())

        SenderService.stop(this, "probe-late")
        check("sender leaving updates the list") { waitFor(5000) { "probe-late" !in senders } }

        receiver.unsubscribe(subLate)
        listOf(a, b, c, late).forEach { it.close() }
        SenderService.stop(this, "probe-a")
        receiver.stop()
    }

    // Needs the hub's own "Screen" share running, with sound, and this app allowed in the hub.
    private fun screenSound() {
        val receiver = startReceiver()
        check("the screen share is announced") { waitFor(60_000) { "Screen" in senders } }

        val reading = Watch(withAudio = true)
        val stalled = Watch(withAudio = true, readAudio = false)
        receiver.subscribe("Screen", reading.reader.surface, 320, 240, reading.audioSink)
        receiver.subscribe("Screen", stalled.reader.surface, 320, 240, stalled.audioSink)
        // The owner may take a while to allow this app in the hub.
        check("sound arrives once the app is allowed") { waitFor(90_000) { reading.audioBytes.get() > 0 } }
        Thread.sleep(2000)
        val before = reading.audioBytes.get()
        Thread.sleep(10_000)
        val gained = reading.audioBytes.get() - before
        report("sound keeps flowing while another watcher stops reading", gained >= 1_500_000, "bytes in 10 s=$gained of ~1920000 expected")

        listOf(reading, stalled).forEach { it.close() }
        receiver.stop()
    }

    // Flashes this screen and plays a click at the same moment, then compares when the hub's screen share delivers each.
    // The screen share has to be running with sound, and this activity has to stay in front.
    private fun screenSync() {
        val receiver = startReceiver()
        check("the screen share is announced") { waitFor(60_000) { "Screen" in senders } }

        val view = View(this)
        runOnUiThread {
            view.setBackgroundColor(Color.BLACK)
            setContentView(view)
        }
        val flashedAt = AtomicLong()
        val pictureAt = AtomicLong()
        val soundAt = AtomicLong()
        val reader = ImageReader.newInstance(108, 240, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Throwable) { null }
            image?.use {
                val buffer = it.planes[0].buffer
                val middle = (it.planes[0].rowStride * it.height / 2) + it.planes[0].pixelStride * it.width / 2
                val bright = (buffer.get(middle).toInt() and 0xFF) > 200
                if (bright && flashedAt.get() != 0L) pictureAt.compareAndSet(0, System.nanoTime())
            }
        }, imageHandler)
        val pipe = ParcelFileDescriptor.createPipe()
        Thread({
            val chunk = ByteArray(1024)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        var loudest = 0
                        for (i in 0 until n - 1 step 2) {
                            val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                            loudest = maxOf(loudest, abs(sample.toShort().toInt()))
                        }
                        if (loudest > 3000 && flashedAt.get() != 0L) soundAt.compareAndSet(0, System.nanoTime())
                    }
                }
            } catch (_: IOException) {
            }
        }, "probe-sync-audio").start()
        receiver.subscribe("Screen", reader.surface, 108, 240, pipe[1])
        Thread.sleep(3000)

        val rate = 48_000
        val tone = ShortArray(rate / 10) { (sin(2 * PI * 1000 * it / rate) * 20_000).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(tone.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(tone, 0, tone.size)

        val offsets = mutableListOf<Long>()
        val pictureDelays = mutableListOf<Long>()
        val soundDelays = mutableListOf<Long>()
        repeat(10) {
            runOnUiThread { view.setBackgroundColor(Color.BLACK) }
            Thread.sleep(1200)
            pictureAt.set(0)
            soundAt.set(0)
            val start = System.nanoTime()
            flashedAt.set(start)
            runOnUiThread { view.setBackgroundColor(Color.WHITE) }
            track.stop()
            track.reloadStaticData()
            track.play()
            waitFor(1500) { pictureAt.get() != 0L && soundAt.get() != 0L }
            flashedAt.set(0)
            if (pictureAt.get() != 0L && soundAt.get() != 0L) {
                pictureDelays += (pictureAt.get() - start) / 1_000_000
                soundDelays += (soundAt.get() - start) / 1_000_000
                offsets += (pictureAt.get() - soundAt.get()) / 1_000_000
            }
        }
        track.release()
        report(
            "picture and sound of the screen share arrive close together",
            offsets.size >= 6,
            "picture minus sound in ms: $offsets  (picture ${pictureDelays}, sound $soundDelays after the flash)",
        )
        receiver.stop()
        reader.close()
    }

    // A sender on this phone draws with transparency into the surface a receiver on this phone gave the hub.
    private fun alpha() {
        val receiver = startReceiver()
        SenderService.start(this, "probe-alpha", transparent = true)
        check("sender is discovered") { waitFor(5000) { "probe-alpha" in senders } }

        val seen = AtomicReference<IntArray>()
        val reader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Throwable) { null }
            image?.use {
                val plane = it.planes[0]
                fun alphaAt(x: Int, y: Int) = plane.buffer.get(y * plane.rowStride + x * plane.pixelStride + 3).toInt() and 0xFF
                seen.set(intArrayOf(alphaAt(40, 120), alphaAt(280, 120)))
            }
        }, imageHandler)
        val subscription = receiver.subscribe("probe-alpha", reader.surface, 320, 240)
        // The owner may take a while to allow this app in the hub.
        waitFor(90_000) { seen.get() != null }
        Thread.sleep(1000)
        val alphas = seen.get()
        // The sender draws the right half at 50% alpha, which is 128 out of 255.
        report("alpha survives between a sender and a receiver on the phone", alphas != null && alphas[0] == 0 && alphas[1] in 120..136, "alpha left/right=${alphas?.toList()} (expected 0 and about 128)")

        receiver.unsubscribe(subscription)
        reader.close()
        SenderService.stop(this, "probe-alpha")
        receiver.stop()
    }

    private fun hubKill() {
        val receiver = startReceiver()
        SenderService.start(this, "probe-a")
        check("sender is discovered") { waitFor(5000) { "probe-a" in senders } }

        val watch = Watch(withAudio = false)
        receiver.subscribe("probe-a", watch.reader.surface, 320, 240)
        Log.i(TAG, "STREAMING, crash the hub now")
        val perSecond = sampleFrames(watch, seconds = intent.getIntExtra("seconds", 14))

        val tail = perSecond.takeLast(3)
        report("video resumes after the hub restarts", tail.all { it >= 20 }, "frames per second: $perSecond")
        report("receiver is connected again", connected, "connected=$connected")
        report("sender is announced again", "probe-a" in senders, "senders=$senders")
        watch.close()
        SenderService.stop(this, "probe-a")
        receiver.stop()
    }

    private fun senderKill() {
        val receiver = startReceiver()
        SenderService.start(this, "probe-a")
        check("sender is discovered") { waitFor(5000) { "probe-a" in senders } }

        val watch = Watch(withAudio = false)
        receiver.subscribe("probe-a", watch.reader.surface, 320, 240)
        Thread.sleep(3000)
        val pid = senderPid()
        Log.i(TAG, "killing the sender process, pid=$pid")
        if (pid != null) Process.killProcess(pid)
        val perSecond = sampleFrames(watch, seconds = intent.getIntExtra("seconds", 10))

        val tail = perSecond.takeLast(3)
        report("video resumes after the sender restarts", tail.all { it >= 20 }, "frames per second: $perSecond")
        watch.close()
        SenderService.stop(this, "probe-a")
        receiver.stop()
    }

    private fun consumerLost() {
        val receiver = startReceiver()
        SenderService.start(this, "probe-a")
        check("sender is discovered") { waitFor(5000) { "probe-a" in senders } }

        val watch = Watch(withAudio = true)
        val subscription = receiver.subscribe("probe-a", watch.reader.surface, 320, 240, watch.audioSink)
        Thread.sleep(2000)
        Log.i(TAG, "closing the consumer now, ${watch.summary()}")
        watch.close()
        Thread.sleep(2000)
        receiver.unsubscribe(subscription)
        Thread.sleep(500)
        SenderService.stop(this, "probe-a")
        receiver.stop()
        Log.i(TAG, "see the SENDER lines above for how it noticed")
    }

    private fun misuse() {
        val quiet = object : ManifoldSender.Listener {
            override fun onSubscribe(subscription: ManifoldSender.Subscription) {}
            override fun onUnsubscribe(subscriptionId: String) {}
        }
        val sender = ManifoldSender(this, ManifoldSender.Config("probe-misuse"), quiet)
        sender.start()
        expectThrows<IllegalStateException>("starting twice") { sender.start() }
        expectThrows<IllegalArgumentException>("renaming a running sender") { sender.update(ManifoldSender.Config("other")) }
        sender.stop()
        sender.stop()
        report("stopping twice is harmless", true, "")
        expectThrows<IllegalStateException>("updating a stopped sender") { sender.update(ManifoldSender.Config("probe-misuse")) }

        val watch = Watch(withAudio = false)
        val receiver = startReceiver()
        expectThrows<IllegalArgumentException>("a blank sender name") { receiver.subscribe("  ", watch.reader.surface, 320, 240) }
        expectThrows<IllegalArgumentException>("a zero width") { receiver.subscribe("probe-a", watch.reader.surface, 0, 240) }
        receiver.stop()
        expectThrows<IllegalStateException>("subscribing after stop") { receiver.subscribe("probe-a", watch.reader.surface, 320, 240) }
        watch.close()

        val callbackThread = HandlerThread("probe-callbacks").apply { start() }
        val handler = Handler(callbackThread.looper)
        val ranOn = AtomicReference<String?>()
        val custom = ManifoldSender(
            this,
            ManifoldSender.Config("probe-executor"),
            object : ManifoldSender.Listener {
                override fun onRegistered(registeredName: String) {
                    ranOn.set(Thread.currentThread().name)
                }

                override fun onSubscribe(subscription: ManifoldSender.Subscription) {}
                override fun onUnsubscribe(subscriptionId: String) {}
            },
            Executor { handler.post(it) },
        )
        custom.start()
        report("callbacks run on the given executor", waitFor(5000) { ranOn.get() == "probe-callbacks" }, "ran on ${ranOn.get()}")
        custom.stop()
        callbackThread.quitSafely()
    }

    private inline fun <reified T : Throwable> expectThrows(name: String, block: () -> Unit) {
        val thrown = try {
            block()
            null
        } catch (t: Throwable) {
            t
        }
        report("$name throws ${T::class.java.simpleName}", thrown is T, thrown?.javaClass?.simpleName ?: "nothing was thrown")
    }

    private fun senderPid(): Int? {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses?.firstOrNull { it.processName.endsWith(":sender") }?.pid
    }

    private fun sampleFrames(watch: Watch, seconds: Int): List<Int> {
        val perSecond = ArrayList<Int>()
        var last = watch.frames.get()
        repeat(seconds) {
            Thread.sleep(1000)
            val now = watch.frames.get()
            perSecond += now - last
            last = now
        }
        return perSecond
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun check(name: String, test: () -> Boolean) = report(name, test(), "")

    private fun report(name: String, ok: Boolean, detail: String) {
        Log.i(TAG, "RESULT ${if (ok) "PASS" else "FAIL"}  $name  $detail")
    }

    private companion object {
        const val TAG = "PROBE"
        val running = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
