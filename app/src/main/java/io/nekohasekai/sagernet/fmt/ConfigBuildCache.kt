package io.nekohasekai.sagernet.fmt

/**
 * Deliberately disabled process cache.
 *
 * ConfigBuildResult owns mutable JSON and maps and the selector generation is
 * process-local. A result retained here can therefore outlive a Room write made
 * by another process (and can even reuse ports from a previous service). The
 * runtime path must compile from the current database snapshot instead. Keep
 * this API as a no-op so callers can be enabled later only with an immutable,
 * database-revision-keyed schema.
 */
object ConfigBuildCache {
    fun get(profileId: Long, generation: Long, disableSelector: Boolean): ConfigBuildResult? = null
    fun put(result: ConfigBuildResult, disableSelector: Boolean) = Unit
    fun clear() = Unit
}
