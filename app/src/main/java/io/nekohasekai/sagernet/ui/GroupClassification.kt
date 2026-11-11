package io.nekohasekai.sagernet.ui

/**
 * Pure ordering helpers for group classification.  Classification never changes an
 * entity: it only returns a stable permutation of the supplied card list.
 */
internal object GroupClassification {
    /** Stable partition used by the explicit custom-selection flow. */
    fun <T> stackSelected(source: List<T>, selected: (T) -> Boolean): List<T> {
        val first = source.indexOfFirst(selected)
        if (first < 0) return source.toList()
        val prefix = source.take(first)
        val chosen = source.drop(first).filter(selected)
        val rest = source.drop(first).filterNot(selected)
        return prefix + chosen + rest
    }

    /**
     * Groups only names with a conservative, explicit suffix pattern.  A trailing
     * numeric token is treated as an instance number (for example `Tokyo-01` and
     * `Tokyo-02`).  Names without that pattern are never guessed to be related;
     * exact duplicate names are safe to keep adjacent as well.
     */
    private fun similarNameKey(name: String): String? {
        val normalized = name.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return null
        val exact = normalized.replace(Regex("[\\s._:/\\\\|#-]+"), "")
        if (exact.isEmpty()) return null
        val suffix = Regex("^(.+?)(?:[\\s._:/\\\\|#-]+|)(\\d{1,4})$").matchEntire(normalized)
        if (suffix == null) return "cluster:$exact"
        val base = suffix.groupValues[1].trim().replace(
            Regex("[\\s._:/\\\\|#-]+$"), ""
        )
        val letters = base.count { it.isLetter() }
        // Two CJK characters are meaningful; latin names need three letters to
        // avoid turning short names such as A-1/A-2 into an accidental cluster.
        val meaningful = base.count { it.isLetterOrDigit() }
        if (meaningful < 2 || (letters < 3 && base.none { it.code in 0x2E80..0x9FFF })) {
            return "exact:$exact"
        }
        return "cluster:${base.lowercase().replace(Regex("[\\s._:/\\\\|#-]+"), "") }"
    }

    /** Return a stable order with repeated conservative name keys contiguous. */
    fun <T> stableClusterOrder(source: List<T>, name: (T) -> String): List<T> {
        if (source.size < 2) return source.toList()
        val keys = source.map { similarNameKey(name(it)) }
        val counts = keys.groupingBy { it }.eachCount()
        val order = LinkedHashMap<String, MutableList<T>>()
        source.forEachIndexed { index, item ->
            val key = keys[index]
            if (key != null && (counts[key] ?: 0) > 1) order.getOrPut(key) { ArrayList() }.add(item)
        }
        if (order.isEmpty()) return source.toList()
        val emitted = HashSet<String>()
        val result = ArrayList<T>(source.size)
        source.forEachIndexed { index, item ->
            val key = keys[index]
            if (key != null && (counts[key] ?: 0) > 1) {
                if (emitted.add(key)) result.addAll(order.getValue(key))
            } else result.add(item)
        }
        return result
    }

    /** ID-only variant used by the group adapter and easy to exercise in tests. */
    fun stableClusterIds(source: List<Pair<Long, String>>): List<Long> =
        stableClusterOrder(source) { it.second }.map { it.first }

    /** Keep only the requested subset in the order it was supplied. */
    fun mergeSubsetOrder(root: List<Long>, visibleOrder: List<Long>): List<Long> {
        val included = visibleOrder.toSet()
        val reordered = visibleOrder.filter { it in root }.distinct().iterator()
        return root.map { if (it in included && reordered.hasNext()) reordered.next() else it }
    }
}
