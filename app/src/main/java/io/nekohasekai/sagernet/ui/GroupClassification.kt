package io.nekohasekai.sagernet.ui

/** Stable partition used by group search classification. Selected items keep their order. */
internal object GroupClassification {
    /** Empty search means all groups, not a new name/type-based classification rule. */
    fun <T> matches(source: List<T>, query: String, name: (T) -> String): List<T> {
        val normalized = query.trim()
        return if (normalized.isEmpty()) source.toList()
        else source.filter { name(it).contains(normalized, ignoreCase = true) }
    }

    fun <T> stackSelected(source: List<T>, selected: (T) -> Boolean): List<T> {
        // Evaluate each predicate once; both selected and unselected retain their relative order.
        val flags = source.map(selected)
        val first = flags.indexOfFirst { it }
        if (first < 0) return source.toList()
        val chosen = ArrayList<T>()
        val rest = ArrayList<T>()
        for (index in first until source.size) {
            if (flags[index]) chosen.add(source[index]) else rest.add(source[index])
        }
        return source.take(first) + chosen + rest
    }
}
