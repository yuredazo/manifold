package dev.mkzk.manifold.hub.network

import dev.mkzk.manifold.hub.network.stream.StreamProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class StreamPreference(saved: String?, private val save: (String) -> Unit) {
    private val current = MutableStateFlow(StreamProfile.of(saved))

    val profile: StateFlow<StreamProfile> = current

    fun choose(profile: StreamProfile) {
        current.value = profile
        save(profile.id)
    }
}
