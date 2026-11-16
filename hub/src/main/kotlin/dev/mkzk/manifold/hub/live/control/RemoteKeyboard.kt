package dev.mkzk.manifold.hub.live.control

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mkzk.manifold.hub.network.RemoteAction

private const val CTRL = 0xE0
private const val SHIFT = 0xE1
private const val ALT = 0xE2
private const val WIN = 0xE3
private const val RIGHT_CTRL = 0xE4
private const val RIGHT_SHIFT = 0xE5
private const val RIGHT_ALT = 0xE6

// width is in key units, as keyboards are drawn: a letter is 1, Backspace 2.
private class KeyCap(val label: String, val usage: Int, val width: Float = 1f)

private fun letters(text: String) = text.map { KeyCap(it.toString(), 0x04 + (it - 'A')) }

private val TopRow = listOf(KeyCap("Esc", 0x29)) +
    (1..12).map { KeyCap("F$it", 0x3A + it - 1) } +
    listOf(KeyCap("Del", 0x4C))

private val MainRows = listOf(
    listOf(KeyCap("`", 0x35)) + (1..9).map { KeyCap("$it", 0x1E + it - 1) } +
        listOf(KeyCap("0", 0x27), KeyCap("-", 0x2D), KeyCap("=", 0x2E), KeyCap("⌫", 0x2A, 2f)),
    listOf(KeyCap("Tab", 0x2B, 1.5f)) + letters("QWERTYUIOP") + listOf(KeyCap("[", 0x2F), KeyCap("]", 0x30), KeyCap("\\", 0x31, 1.5f)),
    listOf(KeyCap("Caps", 0x39, 1.75f)) + letters("ASDFGHJKL") + listOf(KeyCap(";", 0x33), KeyCap("'", 0x34), KeyCap("Enter", 0x28, 2.25f)),
    listOf(KeyCap("Shift", SHIFT, 2.25f)) + letters("ZXCVBNM") + listOf(KeyCap(",", 0x36), KeyCap(".", 0x37), KeyCap("/", 0x38), KeyCap("Shift", RIGHT_SHIFT, 2.75f)),
    listOf(
        KeyCap("Ctrl", CTRL, 1.25f), KeyCap("Win", WIN, 1.25f), KeyCap("Alt", ALT, 1.25f), KeyCap("", 0x2C, 4.5f), KeyCap("Alt", RIGHT_ALT, 1.25f),
        KeyCap("Ctrl", RIGHT_CTRL, 1.25f), KeyCap("←", 0x50), KeyCap("↑", 0x52), KeyCap("↓", 0x51), KeyCap("→", 0x4F),
    ),
)

private val KeyNames: Map<Int, String> = (TopRow + MainRows.flatten()).associate { it.usage to it.label } +
    mapOf(0x2C to "Space", 0x2A to "Backspace")

internal fun keyName(usage: Int): String = KeyNames[usage] ?: "Key $usage"

internal fun usageOf(keyCode: Int): Int? = when (keyCode) {
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + keyCode - KeyEvent.KEYCODE_A
    in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + keyCode - KeyEvent.KEYCODE_1
    KeyEvent.KEYCODE_0 -> 0x27
    KeyEvent.KEYCODE_ENTER -> 0x28
    KeyEvent.KEYCODE_ESCAPE -> 0x29
    KeyEvent.KEYCODE_DEL -> 0x2A
    KeyEvent.KEYCODE_TAB -> 0x2B
    KeyEvent.KEYCODE_SPACE -> 0x2C
    KeyEvent.KEYCODE_MINUS -> 0x2D
    KeyEvent.KEYCODE_EQUALS -> 0x2E
    KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
    KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
    KeyEvent.KEYCODE_BACKSLASH -> 0x31
    KeyEvent.KEYCODE_SEMICOLON -> 0x33
    KeyEvent.KEYCODE_APOSTROPHE -> 0x34
    KeyEvent.KEYCODE_GRAVE -> 0x35
    KeyEvent.KEYCODE_COMMA -> 0x36
    KeyEvent.KEYCODE_PERIOD -> 0x37
    KeyEvent.KEYCODE_SLASH -> 0x38
    KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3A + keyCode - KeyEvent.KEYCODE_F1
    KeyEvent.KEYCODE_SYSRQ -> 0x46
    KeyEvent.KEYCODE_SCROLL_LOCK -> 0x47
    KeyEvent.KEYCODE_INSERT -> 0x49
    KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
    KeyEvent.KEYCODE_PAGE_UP -> 0x4B
    KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
    KeyEvent.KEYCODE_MOVE_END -> 0x4D
    KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
    KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
    KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
    KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
    KeyEvent.KEYCODE_DPAD_UP -> 0x52
    KeyEvent.KEYCODE_NUM_LOCK -> 0x53
    KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x54
    KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x55
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x56
    KeyEvent.KEYCODE_NUMPAD_ADD -> 0x57
    KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x58
    in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> 0x59 + keyCode - KeyEvent.KEYCODE_NUMPAD_1
    KeyEvent.KEYCODE_NUMPAD_0 -> 0x62
    KeyEvent.KEYCODE_NUMPAD_DOT -> 0x63
    KeyEvent.KEYCODE_MENU -> 0x65
    KeyEvent.KEYCODE_CTRL_LEFT -> CTRL
    KeyEvent.KEYCODE_SHIFT_LEFT -> SHIFT
    KeyEvent.KEYCODE_ALT_LEFT -> ALT
    KeyEvent.KEYCODE_META_LEFT -> WIN
    // Android keeps the Windows key for itself, so Right Ctrl stands in for it.
    KeyEvent.KEYCODE_CTRL_RIGHT -> WIN
    KeyEvent.KEYCODE_SHIFT_RIGHT -> RIGHT_SHIFT
    KeyEvent.KEYCODE_ALT_RIGHT -> RIGHT_ALT
    KeyEvent.KEYCODE_META_RIGHT -> 0xE7
    else -> null
}

internal fun forwardKey(event: KeyEvent, send: (RemoteAction) -> Unit): Boolean {
    val usage = usageOf(event.keyCode) ?: return false
    when (event.action) {
        KeyEvent.ACTION_DOWN -> send(RemoteAction.Key(usage, true))
        KeyEvent.ACTION_UP -> send(RemoteAction.Key(usage, false))
    }
    return true
}

// Keys name physical positions, so the PC's own layout decides what they type.
@Composable
internal fun RemoteKeyboard(send: (RemoteAction) -> Unit, modifier: Modifier = Modifier) {
    val pressed = remember { mutableStateListOf<Int>() }
    val haptics = LocalHapticFeedback.current
    DisposableEffect(Unit) { onDispose { pressed.toList().forEach { send(RemoteAction.Key(it, false)) } } }

    @Composable
    fun Key(cap: KeyCap, modifier: Modifier, small: Boolean = false) {
        val on = cap.usage in pressed
        Box(
            modifier = modifier
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (on) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.22f), RoundedCornerShape(8.dp))
                .pointerInput(cap.usage) {
                    detectTapGestures(onPress = {
                        pressed.add(cap.usage)
                        send(RemoteAction.Key(cap.usage, true))
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        tryAwaitRelease()
                        pressed.remove(cap.usage)
                        send(RemoteAction.Key(cap.usage, false))
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                cap.label,
                style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }

    @Composable
    fun RowScope.Keys(row: List<KeyCap>, small: Boolean = false) {
        row.forEach { cap -> Key(cap, Modifier.weight(cap.width), small) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) { Keys(TopRow, small = true) }
        MainRows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { Keys(row) }
        }
    }
}
