package io.nekohasekai.sagernet.ui

/** Pick by stable IDs, never by a position captured before an async callback. */
internal object GroupTabPolicy {
    fun afterRemoval(ids: List<Long>, removedId: Long, selectedId: Long): Long? {
        val index = ids.indexOf(removedId)
        if (index < 0) return selectedId.takeIf { it in ids } ?: ids.firstOrNull()
        val remaining = ids.filter { it != removedId }
        if (selectedId != removedId && selectedId in remaining) return selectedId
        return ids.take(index).lastOrNull() ?: remaining.firstOrNull()
    }
}
