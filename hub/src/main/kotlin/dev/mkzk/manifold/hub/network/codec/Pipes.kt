package dev.mkzk.manifold.hub.network.codec

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor

// Linux asks for F_SETPIPE_SZ by number; Android's constants do not list it.
private const val F_SETPIPE_SZ = 1031

// fcntl is public from Android 11. On Android 10 a pipe stays blocking at its default size, so a writer asks for room first.
private val canSetFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

internal fun FileDescriptor.makeNonBlocking(size: Int? = null) {
    if (!canSetFlags) return
    Os.fcntlInt(this, OsConstants.F_SETFL, Os.fcntlInt(this, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
    if (size != null) Os.fcntlInt(this, F_SETPIPE_SZ, size)
}

// Returns 0 when the pipe is full, on Android 10 as well as on a non-blocking pipe.
internal fun FileDescriptor.writeNow(bytes: ByteArray, offset: Int, length: Int): Int =
    try {
        if (!canSetFlags && !hasRoom()) 0 else Os.write(this, bytes, offset, length)
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.EAGAIN) 0 else throw e
    }

private fun FileDescriptor.hasRoom(): Boolean {
    val poll = StructPollfd().apply {
        fd = this@hasRoom
        events = OsConstants.POLLOUT.toShort()
    }
    return Os.poll(arrayOf(poll), 0) > 0 && poll.revents.toInt() and OsConstants.POLLOUT != 0
}
