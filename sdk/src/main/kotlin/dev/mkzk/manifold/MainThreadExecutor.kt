package dev.mkzk.manifold

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/** Where listener callbacks run unless the app passes its own executor. */
internal object MainThreadExecutor : Executor {
    private val handler = Handler(Looper.getMainLooper())

    override fun execute(command: Runnable) {
        handler.post(command)
    }
}
