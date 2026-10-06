package dev.mkzk.manifold.hub.network

import android.os.SystemClock
import dev.mkzk.manifold.hub.network.protocol.Control
import dev.mkzk.manifold.hub.network.protocol.FeedInfo
import dev.mkzk.manifold.hub.network.stream.StreamSnapshot

internal sealed interface PairingUi {
    data object None : PairingUi

    data class Connecting(val address: String) : PairingUi

    /** Set once the owner said the codes match and the other device has yet to answer. */
    data class Code(val remoteName: String, val code: String, val confirmed: Boolean = false) : PairingUi

    data class Failed(val reason: String) : PairingUi
}

internal data class NetworkState(
    val listening: Boolean = false,
    val port: Int = LISTEN_PORT,
    val addresses: List<String> = emptyList(),
    /** Time on the [SystemClock.elapsedRealtime] clock until which other devices may start pairing. */
    val pairingOpenUntil: Long = 0,
    val pairing: PairingUi = PairingUi.None,
    val online: Set<String> = emptySet(),
    val latencyMs: Map<String, Int> = emptyMap(),
    val remoteFeeds: Map<String, List<FeedInfo>> = emptyMap(),
    /** What a feed from another device is called now, by the name the registry gave it. Only feeds with a title of their own are here. */
    val feedTitles: Map<String, String> = emptyMap(),
    val streams: List<StreamSnapshot> = emptyList(),
    val refused: Map<String, Map<String, Control.Refusal>> = emptyMap(),
)
