package dev.mkzk.manifold.hub.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import dev.mkzk.manifold.SenderInfo
import dev.mkzk.manifold.hub.R
import dev.mkzk.manifold.hub.broker.Owner
import dev.mkzk.manifold.hub.broker.Registry
import dev.mkzk.manifold.hub.broker.SenderLink
import dev.mkzk.manifold.hub.gl.SurfaceFanout
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "CameraShare"
private const val FEED_NAME = "Camera"
private const val FEED_FPS = 30
private const val LONG_EDGE_LIMIT = 1920
private const val TURN_MARGIN = 15

internal enum class Lens { Back, Front }

internal class FrameSize(val width: Int, val height: Int)

internal fun outputSize(choices: List<FrameSize>): FrameSize? {
    val fitting = choices.filter { max(it.width, it.height) <= LONG_EDGE_LIMIT }
    val wide = fitting.filter { it.width * 9 == it.height * 16 }
    return wide.ifEmpty { fitting }.maxByOrNull { it.width.toLong() * it.height }
}

internal fun quarterTurn(degrees: Int, current: Int): Int {
    if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return current
    val nearest = (degrees + 45) / 90 % 4 * 90
    val distance = abs(degrees - current).let { min(it, 360 - it) }
    return if (nearest != current && distance >= 45 + TURN_MARGIN) nearest else current
}

// Android turns camera buffers upright for the phone held in its natural orientation; the phone's own turn comes on top.
internal fun pictureSize(buffer: FrameSize, sensorTurn: Int, phoneTurn: Int): FrameSize =
    if ((sensorTurn + phoneTurn) % 180 == 90) FrameSize(buffer.height, buffer.width) else buffer

internal class CameraShare(
    private val registry: Registry,
    private val cameras: CameraManager,
    savedLocked: Boolean,
    savedMirrored: Set<Lens>,
    private val save: (locked: Boolean, mirrored: Set<Lens>) -> Unit,
) {
    data class State(
        val sharing: Boolean = false,
        val lens: Lens = Lens.Back,
        val locked: Boolean = false,
        val mirrored: Boolean = false,
        val taken: Boolean = false,
        val failed: Boolean = false,
        val feed: String? = null,
    )

    private class Source(val id: String, val buffer: FrameSize, val sensorTurn: Int, val front: Boolean, val fps: Range<Int>?)

    private var mirroredLenses = savedMirrored
    private val current = MutableStateFlow(State(locked = savedLocked, mirrored = Lens.Back in savedMirrored))
    val state: StateFlow<State> = current

    val canFlip: Boolean by lazy { Lens.entries.all { sourceFor(it) != null } }

    private var session: Session? = null
    private var onStopped: () -> Unit = {}

    @Synchronized
    fun start(context: Context, onStopped: () -> Unit) {
        if (session != null) return
        this.onStopped = onStopped
        try {
            val chosen = current.value
            val started = Session(context.applicationContext, chosen.lens, chosen.locked, chosen.mirrored)
            session = started
            current.update { it.copy(sharing = true, taken = false, feed = started.feed) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot share the camera", e)
            current.update { it.copy(sharing = false, taken = false, feed = null, failed = true) }
            finish()
        }
    }

    @Synchronized
    fun stop() {
        val ended = session ?: return
        session = null
        ended.close()
        current.update { it.copy(sharing = false, taken = false, feed = null) }
        finish()
    }

    @Synchronized
    fun flip() {
        val lens = if (current.value.lens == Lens.Back) Lens.Front else Lens.Back
        val mirrored = lens in mirroredLenses
        current.update { it.copy(lens = lens, mirrored = mirrored) }
        session?.switchTo(lens, mirrored)
    }

    @Synchronized
    fun setLocked(locked: Boolean) {
        current.update { it.copy(locked = locked) }
        save(locked, mirroredLenses)
        session?.lock(locked)
    }

    @Synchronized
    fun setMirrored(mirrored: Boolean) {
        val lens = current.value.lens
        mirroredLenses = if (mirrored) mirroredLenses + lens else mirroredLenses - lens
        current.update { it.copy(mirrored = mirrored) }
        save(current.value.locked, mirroredLenses)
        session?.mirror(mirrored)
    }

    fun clearFailure() {
        current.update { it.copy(failed = false) }
    }

    @Synchronized
    private fun fail(ended: Session) {
        if (session !== ended) return
        stop()
        current.update { it.copy(failed = true) }
    }

    private fun finish() {
        val callback = onStopped
        onStopped = {}
        callback()
    }

    private fun sourceFor(lens: Lens): Source? {
        val facing = if (lens == Lens.Front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return try {
            cameras.cameraIdList.firstNotNullOfOrNull { id ->
                val traits = cameras.getCameraCharacteristics(id)
                if (traits.get(CameraCharacteristics.LENS_FACING) != facing) return@firstNotNullOfOrNull null
                val sizes = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
                val buffer = outputSize(sizes.map { FrameSize(it.width, it.height) }) ?: return@firstNotNullOfOrNull null
                val fps = traits.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                    .filter { FEED_FPS in it }
                    .maxWithOrNull(compareBy({ it.upper == FEED_FPS }, { it.lower }))
                Source(id, buffer, traits.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, lens == Lens.Front, fps)
            }
        } catch (e: CameraAccessException) {
            Log.w(TAG, "cannot read the cameras", e)
            null
        }
    }

    // Everything that touches the camera or the fields below runs on the session's own thread.
    private inner class Session(context: Context, lens: Lens, private var locked: Boolean, private var mirrored: Boolean) : SenderLink {
        override val key = Any()

        private val ownUid = Process.myUid()
        private var source = checkNotNull(sourceFor(lens)) { "no $lens camera" }
        private var phoneTurn = 0
        private var seenTurn = 0
        private var tiltSeen = false
        private val fanout = picture().let { SurfaceFanout(it.width, it.height, FEED_FPS) }
        private val thread = HandlerThread("manifold-camera").apply { start() }
        private val handler = Handler(thread.looper)
        private val onCamera = Executor { handler.post(it) }
        private val watching = HashSet<String>()
        private var device: CameraDevice? = null
        private var opening = false
        @Volatile private var closed = false

        val feed: String

        private val tilt = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                handler.post { tilted(degrees) }
            }
        }

        // Called for every free camera when registered, and again when another app lets go of one.
        private val availability = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                if (cameraId == source.id && watching.isNotEmpty()) open()
            }
        }

        private val deviceState = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opening = false
                if (closed || watching.isEmpty() || camera.id != source.id) {
                    camera.close()
                    if (!closed && watching.isNotEmpty()) open()
                    return
                }
                device = camera
                setTaken(false)
                startCapture(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                opening = false
                if (lost(camera)) setTaken(true)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                opening = false
                if (!lost(camera)) return
                when (error) {
                    ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE, ERROR_CAMERA_DISABLED -> setTaken(true)
                    else -> {
                        Log.w(TAG, "camera ${camera.id} failed with error $error")
                        fail(this@Session)
                    }
                }
            }
        }

        init {
            feed = try {
                orientFanout()
                cameras.registerAvailabilityCallback(availability, handler)
                val owner = Owner(ownUid, context.packageName, context.getString(R.string.app_name))
                checkNotNull(registry.addSender(this, info(), owner)) { "the hub refused the feed" }
            } catch (e: RuntimeException) {
                close()
                throw e
            }
            tilt.enable()
        }

        override fun deliver(subscriptionId: String, surface: Surface?, width: Int, height: Int, audioSink: ParcelFileDescriptor?) {
            if (surface == null) return
            fanout.add(subscriptionId, surface, width, height)
            handler.post {
                watching.add(subscriptionId)
                if (!closed) open()
            }
        }

        override fun revoke(subscriptionId: String) {
            fanout.remove(subscriptionId)
            handler.post {
                if (watching.remove(subscriptionId) && watching.isEmpty()) closeCamera()
            }
        }

        fun switchTo(lens: Lens, mirrored: Boolean) {
            handler.post {
                val next = sourceFor(lens) ?: return@post
                closeCamera()
                source = next
                this.mirrored = mirrored
                setTaken(false)
                resize()
                if (watching.isNotEmpty()) open()
            }
        }

        fun lock(locked: Boolean) {
            handler.post {
                this.locked = locked
                if (!locked) turnTo(seenTurn)
            }
        }

        fun mirror(mirrored: Boolean) {
            handler.post {
                this.mirrored = mirrored
                orientFanout()
            }
        }

        fun close() {
            closed = true
            tilt.disable()
            cameras.unregisterAvailabilityCallback(availability)
            registry.removeSender(key, ownUid)
            handler.post {
                closeCamera()
                fanout.release()
            }
            thread.quitSafely()
        }

        private fun open() {
            if (device != null || opening) return
            opening = true
            try {
                cameras.openCamera(source.id, onCamera, deviceState)
            } catch (e: CameraAccessException) {
                // Busy or turned off: the availability callback opens it once it is free.
                Log.w(TAG, "cannot open camera ${source.id}: ${e.message}")
                opening = false
                setTaken(true)
            } catch (e: SecurityException) {
                Log.w(TAG, "camera access was taken away", e)
                opening = false
                fail(this)
            }
        }

        private fun startCapture(camera: CameraDevice) {
            val sessionState = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (device !== camera) {
                        session.close()
                        return
                    }
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(fanout.surface)
                            source.fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }
                        session.setRepeatingRequest(request.build(), null, handler)
                    } catch (e: CameraAccessException) {
                        Log.w(TAG, "camera ${camera.id} went away while starting: ${e.message}")
                        if (lost(camera)) setTaken(true)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.w(TAG, "camera ${camera.id} refused the stream")
                    fail(this@Session)
                }
            }
            try {
                val outputs = listOf(OutputConfiguration(fanout.surface))
                camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, onCamera, sessionState))
            } catch (e: CameraAccessException) {
                Log.w(TAG, "camera ${camera.id} went away while starting: ${e.message}")
                if (lost(camera)) setTaken(true)
            }
        }

        // False for a camera this session already let go of, such as the other lens after a flip.
        private fun lost(camera: CameraDevice): Boolean {
            camera.close()
            if (device !== camera) return false
            device = null
            return true
        }

        private fun closeCamera() {
            device?.close()
            device = null
        }

        // A locked share still takes the first reading, so it keeps the way the phone was held when it started.
        private fun tilted(degrees: Int) {
            if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return
            val first = !tiltSeen
            tiltSeen = true
            seenTurn = quarterTurn(degrees, seenTurn)
            if (!locked || first) turnTo(seenTurn)
        }

        private fun turnTo(turn: Int) {
            if (turn == phoneTurn || closed) return
            phoneTurn = turn
            resize()
        }

        private fun resize() {
            orientFanout()
            registry.updateSender(key, info(), ownUid)
        }

        // The front camera comes mirrored from Android, so its plain picture is the flipped one.
        private fun orientFanout() {
            val size = picture()
            fanout.resize(size.width, size.height, source.buffer.width, source.buffer.height, phoneTurn, mirror = source.front != mirrored)
        }

        private fun setTaken(taken: Boolean) {
            if (!closed) current.update { it.copy(taken = taken) }
        }

        private fun picture() = pictureSize(source.buffer, source.sensorTurn, phoneTurn)

        private fun info() = SenderInfo().also {
            val size = picture()
            it.name = FEED_NAME
            it.width = size.width
            it.height = size.height
            it.fps = FEED_FPS
            it.hasAudio = false
        }
    }
}
