package dev.mkzk.manifold.probe

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import dev.mkzk.manifold.ManifoldReceiver
import dev.mkzk.manifold.ManifoldSender
import dev.mkzk.manifold.SenderInfo
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity() {

    private val imageThread = HandlerThread("probe-images").apply { start() }
    private val imageHandler = Handler(imageThread.looper)

    @Volatile private var senders: List<String> = emptyList()
    @Volatile private var connected = false

    private inner class Watch(withAudio: Boolean) {
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
            pipe?.let { p ->
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
