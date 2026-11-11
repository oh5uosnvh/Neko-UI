package io.nekohasekai.sagernet.fmt

/**
 * Process-local immutable build snapshots. A snapshot is reusable only for the
 * exact selector generation and build mode. Database/preference writers bump
 * SelectorGeneration, so a restart never consumes a stale graph.
 */
object ConfigBuildCache {
    private data class Key(val profileId: Long, val generation: Long, val disableSelector: Boolean)
    private val lock = Any()
    private val entries = LinkedHashMap<Key, ConfigBuildResult>(8, 0.75f, true)

    fun get(profileId: Long, generation: Long, disableSelector: Boolean): ConfigBuildResult? =
        synchronized(lock) { entries[Key(profileId, generation, disableSelector)] }

    fun put(result: ConfigBuildResult, disableSelector: Boolean) {
        val key = Key(result.mainEntId, result.selectorGeneration, disableSelector)
        synchronized(lock) {
            entries[key] = result
            while (entries.size > 8) entries.entries.iterator().run { next(); remove() }
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }
}
