package dev.mkzk.manifold.hub

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class Access { ASK, ALLOWED, BLOCKED }

internal data class AppAccess(
    val packageName: String,
    val label: String,
    val access: Access,
    val certificateChanged: Boolean,
)

/** A different certificate later means the app is asked again. [Registry] calls it under its lock. */
internal class AccessBook(stored: String?, private val save: (String) -> Unit) {

    private class Entry(var label: String, var access: Access, var trustedCert: String, var seenCert: String) {
        val certificateChanged get() = access != Access.ASK && trustedCert != seenCert
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val flow = MutableStateFlow<List<AppAccess>>(emptyList())
    val apps: StateFlow<List<AppAccess>> = flow

    init {
        stored?.lineSequence()?.forEach(::parse)
        publish()
    }

    fun decide(owner: Owner): Access {
        val entry = entries[owner.packageName] ?: return Access.ASK
        return if (entry.certificateChanged) Access.ASK else entry.access
    }

    fun see(owner: Owner) {
        val label = clean(owner.label)
        val entry = entries[owner.packageName]
        if (entry == null) {
            entries[owner.packageName] = Entry(label, Access.ASK, owner.certSha256, owner.certSha256)
        } else if (entry.label == label && entry.seenCert == owner.certSha256) {
            return
        } else {
            entry.label = label
            entry.seenCert = owner.certSha256
        }
        changed()
    }

    fun set(packageName: String, access: Access) {
        val entry = entries.getOrPut(packageName) { Entry(packageName, Access.ASK, "", "") }
        entry.access = access
        entry.trustedCert = entry.seenCert
        changed()
    }

    fun remove(packageName: String) {
        if (entries.remove(packageName) != null) changed()
    }

    private fun changed() {
        publish()
        save(entries.entries.joinToString("\n") { (pkg, e) ->
            listOf(pkg, e.access.name, e.trustedCert, e.seenCert, e.label).joinToString("\t")
        })
    }

    private fun publish() {
        flow.value = entries.map { (pkg, e) -> AppAccess(pkg, e.label, e.access, e.certificateChanged) }
    }

    private fun parse(line: String) {
        val parts = line.split('\t')
        if (parts.size != 5) return
        val access = Access.entries.firstOrNull { it.name == parts[1] } ?: return
        entries[parts[0]] = Entry(label = parts[4], access = access, trustedCert = parts[2], seenCert = parts[3])
    }

    /** Labels come from other apps, so keep them from breaking the stored format. */
    private fun clean(label: String) = label.replace('\t', ' ').replace('\n', ' ').take(100)
}
