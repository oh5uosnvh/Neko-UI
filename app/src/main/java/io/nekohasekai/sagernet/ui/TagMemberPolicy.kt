package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyGroup

/** Explicit root/member/selection state; labels never copy or independently reorder root entities. */
internal object TagMemberPolicy {
    fun visible(root: List<ProxyGroup>, memberIds: Set<Long>, searching: Boolean,
                manual: Boolean, removing: Boolean, query: String): List<ProxyGroup> {
        val source = when {
            !searching || removing -> root.filter { it.id in memberIds }
            !manual && query.isBlank() -> emptyList()
            else -> root
        }
        return if (manual || query.isBlank()) source else source.filter {
            it.displayName().contains(query.trim(), ignoreCase = true)
        }
    }
    fun canSelect(id: Long, members: Set<Long>, removing: Boolean) = if (removing) id in members else id !in members
    fun newMatches(root: List<ProxyGroup>, members: Set<Long>, query: String): List<Long> =
        if (query.isBlank()) emptyList() else root.filter {
            it.id !in members && it.displayName().contains(query.trim(), ignoreCase = true)
        }.map { it.id }
}
