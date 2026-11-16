package dev.mkzk.manifold.hub.live.control

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.mkzk.manifold.hub.network.RemoteAction
import dev.mkzk.manifold.hub.network.protocol.PadState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal const val PAD_UP = 0x0001
internal const val PAD_DOWN = 0x0002
internal const val PAD_LEFT = 0x0004
internal const val PAD_RIGHT = 0x0008
internal const val PAD_START = 0x0010
internal const val PAD_BACK = 0x0020
internal const val PAD_LEFT_THUMB = 0x0040
internal const val PAD_RIGHT_THUMB = 0x0080
internal const val PAD_LEFT_SHOULDER = 0x0100
internal const val PAD_RIGHT_SHOULDER = 0x0200
internal const val PAD_GUIDE = 0x0400
internal const val PAD_A = 0x1000
internal const val PAD_B = 0x2000
internal const val PAD_X = 0x4000
internal const val PAD_Y = 0x8000
// Outside XInput's 16 bits: pads without analog triggers press these as keys.
internal const val PAD_LEFT_TRIGGER_KEY = 0x10000
internal const val PAD_RIGHT_TRIGGER_KEY = 0x20000
private const val MAX_PADS = 4
internal const val PAD_REPEAT_MS = 200L
// The on-screen controller takes a pad slot like a real one; no input device has this id.
internal const val SCREEN_PAD = Int.MIN_VALUE
// The PC repeats the motor levels every 250 ms, so this much quiet means the link or the PC went away.
private const val RUMBLE_SILENCE_MS = 1_000L
// A waveform this long, looped, holds a level until the next one replaces it.
private const val RUMBLE_STEP_MS = 1_000L

private fun buttonOf(event: KeyEvent): Int? = when (event.keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> PAD_A
    KeyEvent.KEYCODE_BUTTON_B -> PAD_B
    KeyEvent.KEYCODE_BUTTON_X -> PAD_X
    KeyEvent.KEYCODE_BUTTON_Y -> PAD_Y
    KeyEvent.KEYCODE_BUTTON_L1 -> PAD_LEFT_SHOULDER
    KeyEvent.KEYCODE_BUTTON_R1 -> PAD_RIGHT_SHOULDER
    KeyEvent.KEYCODE_BUTTON_L2 -> PAD_LEFT_TRIGGER_KEY
    KeyEvent.KEYCODE_BUTTON_R2 -> PAD_RIGHT_TRIGGER_KEY
    KeyEvent.KEYCODE_BUTTON_THUMBL -> PAD_LEFT_THUMB
    KeyEvent.KEYCODE_BUTTON_THUMBR -> PAD_RIGHT_THUMB
    KeyEvent.KEYCODE_BUTTON_START -> PAD_START
    KeyEvent.KEYCODE_BUTTON_SELECT -> PAD_BACK
    KeyEvent.KEYCODE_BUTTON_MODE -> PAD_GUIDE
    // A keyboard's arrows are DPAD keys too, so only a controller's count.
    KeyEvent.KEYCODE_DPAD_UP -> PAD_UP.takeIf { event.isFromSource(InputDevice.SOURCE_GAMEPAD) }
    KeyEvent.KEYCODE_DPAD_DOWN -> PAD_DOWN.takeIf { event.isFromSource(InputDevice.SOURCE_GAMEPAD) }
    KeyEvent.KEYCODE_DPAD_LEFT -> PAD_LEFT.takeIf { event.isFromSource(InputDevice.SOURCE_GAMEPAD) }
    KeyEvent.KEYCODE_DPAD_RIGHT -> PAD_RIGHT.takeIf { event.isFromSource(InputDevice.SOURCE_GAMEPAD) }
    else -> null
}

private class Pad(val index: Int) {
    var keys = 0
    var hat = 0
    var leftTrigger = 0
    var rightTrigger = 0
    var leftX = 0
    var leftY = 0
    var rightX = 0
    var rightY = 0
    var sent: PadState? = null
    var rumble = 0 to 0
    var rumbleAt = 0L

    fun state() = PadState(
        buttons = (keys or hat) and 0xFFFF,
        leftTrigger = if (keys and PAD_LEFT_TRIGGER_KEY != 0) 255 else leftTrigger,
        rightTrigger = if (keys and PAD_RIGHT_TRIGGER_KEY != 0) 255 else rightTrigger,
        leftX = leftX,
        leftY = leftY,
        rightX = rightX,
        rightY = rightY,
    )
}

internal class PadReader(context: Context, private val send: (RemoteAction) -> Unit) {
    private val phone: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }
    private val asGame = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
    private val pads = HashMap<Int, Pad>()

    private fun padFor(deviceId: Int): Pad? = pads[deviceId]
        ?: (0 until MAX_PADS).firstOrNull { slot -> pads.values.none { it.index == slot } }?.let { slot -> Pad(slot).also { pads[deviceId] = it } }

    private fun Pad.push() {
        val state = state()
        if (state == sent) return
        sent = state
        send(RemoteAction.Pad(index, state))
    }

    fun onKey(event: KeyEvent): Boolean {
        val bit = buttonOf(event) ?: return false
        val pad = padFor(event.deviceId) ?: return true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> pad.keys = pad.keys or bit
            KeyEvent.ACTION_UP -> pad.keys = pad.keys and bit.inv()
        }
        pad.push()
        return true
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val pad = padFor(event.deviceId) ?: return true
        val device = event.device
        fun axis(axis: Int): Float {
            val value = event.getAxisValue(axis)
            val flat = device?.getMotionRange(axis, event.source)?.flat ?: 0f
            return if (abs(value) <= flat) 0f else value
        }
        // Most controllers put the right stick on Z and RZ; a few use RX and RY.
        val zStick = device?.getMotionRange(MotionEvent.AXIS_Z, event.source) != null
        pad.leftX = stick(axis(MotionEvent.AXIS_X))
        pad.leftY = stick(-axis(MotionEvent.AXIS_Y))
        pad.rightX = stick(axis(if (zStick) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX))
        pad.rightY = stick(-axis(if (zStick) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY))
        pad.leftTrigger = trigger(max(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)))
        pad.rightTrigger = trigger(max(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)))
        val hatX = axis(MotionEvent.AXIS_HAT_X)
        val hatY = axis(MotionEvent.AXIS_HAT_Y)
        pad.hat = (if (hatX < -0.5f) PAD_LEFT else if (hatX > 0.5f) PAD_RIGHT else 0) or (if (hatY < -0.5f) PAD_UP else if (hatY > 0.5f) PAD_DOWN else 0)
        pad.push()
        return true
    }

    // Having no motors, the on-screen pad rumbles through the phone, which setRumble falls back to.
    fun screen(keys: Int, leftX: Int, leftY: Int, rightX: Int, rightY: Int) {
        val pad = padFor(SCREEN_PAD) ?: return
        pad.keys = keys
        pad.leftX = leftX
        pad.leftY = leftY
        pad.rightX = rightX
        pad.rightY = rightY
        pad.push()
    }

    fun repeat() {
        pads.values.forEach { pad -> pad.sent?.let { send(RemoteAction.Pad(pad.index, it)) } }
        val now = SystemClock.uptimeMillis()
        pads.forEach { (deviceId, pad) -> if (pad.rumble != 0 to 0 && now - pad.rumbleAt > RUMBLE_SILENCE_MS) pad.setRumble(deviceId, 0, 0) }
    }

    fun rumble(index: Int, large: Int, small: Int) {
        val (deviceId, pad) = pads.entries.firstOrNull { it.value.index == index } ?: return
        pad.rumbleAt = SystemClock.uptimeMillis()
        pad.setRumble(deviceId, large, small)
    }

    fun stopRumble() {
        pads.forEach { (deviceId, pad) -> pad.setRumble(deviceId, 0, 0) }
    }

    private fun Pad.setRumble(deviceId: Int, large: Int, small: Int) {
        if (rumble == large to small) return
        rumble = large to small
        val device = InputDevice.getDevice(deviceId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val motors = device?.vibratorManager
            val ids = motors?.vibratorIds ?: intArrayOf()
            when {
                motors == null || ids.isEmpty() -> {}
                large == 0 && small == 0 -> return motors.cancel()
                ids.size == 1 -> return motors.vibrate(CombinedVibration.createParallel(hold(max(large, small))))
                else -> return motors.vibrate(
                    CombinedVibration.startParallel().apply {
                        if (large > 0) addVibrator(ids[0], hold(large))
                        if (small > 0) addVibrator(ids[1], hold(small))
                    }.combine(),
                )
            }
        } else {
            @Suppress("DEPRECATION")
            val motor = device?.vibrator
            if (motor != null && motor.hasVibrator()) {
                return if (large == 0 && small == 0) motor.cancel() else motor.vibrate(hold(max(large, small)))
            }
        }
        // A controller without a motor, as many are over Bluetooth, rumbles through the phone in the hands instead.
        val phone = phone?.takeIf { it.hasVibrator() } ?: return
        @Suppress("DEPRECATION")
        if (large == 0 && small == 0) phone.cancel() else phone.vibrate(hold(max(large, small)), asGame)
    }

    private fun hold(level: Int) = VibrationEffect.createWaveform(longArrayOf(RUMBLE_STEP_MS), intArrayOf(level.coerceIn(1, 255)), 0)

    fun forget(deviceId: Int) {
        val pad = pads.remove(deviceId) ?: return
        if (pad.sent != null && pad.sent != PadState()) send(RemoteAction.Pad(pad.index, PadState()))
    }

    private fun stick(value: Float) = (value * 32767f).roundToInt().coerceIn(-32768, 32767)

    private fun trigger(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255)
}
