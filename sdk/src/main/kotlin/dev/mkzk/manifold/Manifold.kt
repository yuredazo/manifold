package dev.mkzk.manifold

/**
 * Protocol constants shared by the hub, senders and receivers. Apps that only
 * use [ManifoldSender] or [ManifoldReceiver] rarely need these directly.
 */
public object Manifold {
    public const val PROTOCOL_VERSION: Int = 1
    public const val HUB_PACKAGE: String = "dev.mkzk.manifold"
    public const val ACTION_BIND: String = "dev.mkzk.manifold.BIND"

    /** Audio written to a subscription's pipe is signed 16-bit little-endian PCM at this rate. */
    public const val AUDIO_SAMPLE_RATE: Int = 48_000
    public const val AUDIO_CHANNELS: Int = 2

    public const val MAX_NAME_LENGTH: Int = 64
    public const val MAX_DIMENSION: Int = 8192
    public const val MAX_FPS: Int = 240

    /** Whether [name] can be used for a sender: 1 to [MAX_NAME_LENGTH] characters after trimming, no control characters. */
    public fun isValidName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && trimmed.length <= MAX_NAME_LENGTH && trimmed.none { it.isISOControl() }
    }
}
