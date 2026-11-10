package io.nekohasekai.sagernet.ui

/** Stable partition used by group search classification. Selected items keep their order. */
internal object GroupClassification {
    fun mergeSubsetOrder(root: List<Long>, visibleOrder: List<Long>): List<Long> {
        val included = visibleOrder.toSet()
        val reordered = visibleOrder.filter { it in root }.distinct().iterator()
        return root.map { if (it in included && reordered.hasNext()) reordered.next() else it }
    }

    fun <T> stackSelected(source: List<T>, selected: (T) -> Boolean): List<T> {
        val first = source.indexOfFirst(selected)
        if (first < 0) return source.toList()
        val prefix = source.take(first)
        val chosen = source.drop(first).filter(selected)
        val rest = source.drop(first).filterNot(selected)
        return prefix + chosen + rest
    }
}
