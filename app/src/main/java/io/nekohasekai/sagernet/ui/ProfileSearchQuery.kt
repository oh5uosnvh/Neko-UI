package io.nekohasekai.sagernet.ui

/** Query scope belongs to the request, never to a mutable adapter cache. */
internal data class ProfileSearchQuery(val text: String, val groupId: Long, val global: Boolean) {
    val isGlobal: Boolean get() = global && text.isNotBlank()
    fun <T> select(source: List<T>, group: (T) -> Long,
                   matches: (T, String) -> Boolean, pendingDelete: (T) -> Boolean): List<T> {
        val lower = text.lowercase(java.util.Locale.ROOT)
        return source.filter { value ->
            !pendingDelete(value) && (isGlobal || group(value) == groupId) &&
                (lower.isBlank() || matches(value, lower))
        }
    }
}

/** A new same-text scope/reload supersedes every older response, including deletion/load races. */
internal class ProfileSearchGeneration {
    data class Request(val generation: Long, val query: ProfileSearchQuery)
    @Volatile private var generation = 0L
    @Volatile private var current: Request? = null
    val currentQuery: ProfileSearchQuery? get() = current?.query
    @Synchronized fun begin(query: ProfileSearchQuery): Request =
        Request(++generation, query).also { current = it }
    @Synchronized fun accepts(request: Request): Boolean = current == request
    @Synchronized fun invalidate() { generation++; current = null }
}
