package io.nekohasekai.sagernet.ui

import java.text.Normalizer
import java.util.Locale

/**
 * Pure ordering helpers for group classification. Classification never changes an
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

    /** A named family used by the preview; member order is always source order. */
    data class NameCluster<T>(val label: String, val members: List<T>)

    private data class NameSignature(
        val compact: String,
        val folded: String,
        val family: String,
        val letters: Int,
        val han: Int,
    )

    private fun compact(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    /*
     * Keep the punctuation out of the comparison, but do not use a bag of
     * characters: a family must have a bounded prefix. NFKC also makes full
     * width and compatibility forms compare as users expect.
     */
    private fun signature(name: String): NameSignature {
        val compact = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
        val folded = buildString(compact.length) {
            var previous = '\u0000'
            compact.forEach { current ->
                // Repeated Latin letters are usually decorative name variants
                // (iku/ikuu/ikuuu). Do not collapse repeated CJK characters.
                if (current in 'a'..'z' && current == previous) return@forEach
                append(current)
                previous = current
            }
        }
        val normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val firstToken = normalized.takeWhile { it.isLetterOrDigit() }
        // Compare descriptive suffix variants even if no bare family spelling
        // exists in the data. Strip one known generic suffix, not arbitrary text.
        val family = compact(firstToken)
            .replace(Regex("(?i)(vpn|cloud|network|机场|网络|云)$"), "")
            .replace(Regex("([a-z])\\1+"), "$1")
        return NameSignature(
            compact = compact,
            folded = folded,
            family = family,
            letters = compact.count { it in 'a'..'z' },
            han = compact.count { it.code in 0x2E80..0x9FFF },
        )
    }

    private fun meaningful(value: NameSignature): Boolean =
        value.han >= 2 || value.letters >= 2

    /**
     * Compare two names directly, rather than linking a graph transitively.
     * Thus a candidate must match the first name of a family, not merely some
     * other candidate that happened to be accepted earlier.
     */
    private fun related(anchor: NameSignature, candidate: NameSignature): Boolean {
        if (!meaningful(anchor) || !meaningful(candidate)) return false
        if (anchor.family.length >= 2 && anchor.family == candidate.family) return true
        // Numeric instance suffixes compare by their shared first token. Other
        // names use the bounded full normalized prefix below, preventing a
        // short anchor from absorbing a longer descriptor chain.
        if (anchor.compact.any { it.isDigit() } && candidate.compact.any { it.isDigit() } &&
            anchor.family.length >= 2 && anchor.family == candidate.family
        ) return true
        val shorter: String
        val longer: String
        if (anchor.folded.length <= candidate.folded.length) {
            shorter = anchor.folded
            longer = candidate.folded
        } else {
            shorter = candidate.folded
            longer = anchor.folded
        }
        if (shorter.length < 2 || !longer.startsWith(shorter)) return false
        // A prefix is deliberately bounded by the normalized token. This is
        // stronger than a shared-character test, while still accepting both
        // CJK descriptors and Latin abbreviations such as ik/iku/ikuuu.
        val extension = longer.length - shorter.length
        return extension in 0..4 && shorter.length >= 2 &&
            (shorter.any { it in 'a'..'z' } || shorter.any { it.code in 0x2E80..0x9FFF })
    }

    /** Contiguous Han sequences only: punctuation/Latin gaps cannot manufacture a match. */
    private fun hanTerms(value: String): Set<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        val terms = linkedSetOf<String>()
        Regex("[\\p{IsHan}]{2,}").findAll(normalized).forEach { match ->
            val run = match.value
            for (start in run.indices) for (length in 2..minOf(24, run.length - start)) {
                val term = run.substring(start, start + length)
                if (term !in genericHanTerms) terms += term
            }
        }
        return terms
    }

    // Shared descriptors are not brands. Single-character matches are never considered.
    private val genericHanTerms = setOf("机场", "网络", "配置", "订阅", "直连", "检测",
        "备用", "节点", "自建", "聚合", "高速", "流量", "到期", "套餐", "剩余")

    /**
     * Indexed two-or-more-character brand matching, including middle and suffix positions.
     * Every member matches the SAME term directly, never a transitive graph edge.
     * Coverage wins over a longer descriptive suffix (蓝猫 vs 蓝猫直连); on equal
     * coverage retain the longest shared brand. Root order and duplicate IDs survive.
     */
    private fun <T> hanClusters(source: List<T>, name: (T) -> String): List<NameCluster<T>> {
        val terms = source.map { hanTerms(name(it)) }
        val index = linkedMapOf<String, MutableList<Int>>()
        terms.forEachIndexed { i, values -> values.forEach { index.getOrPut(it) { ArrayList() } += i } }
        val consumed = BooleanArray(source.size)
        val result = ArrayList<NameCluster<T>>()
        source.indices.forEach { anchor ->
            if (consumed[anchor]) return@forEach
            val options = terms[anchor].mapNotNull { term ->
                val members = index[term].orEmpty().filterNot { consumed[it] }
                if (members.size < 2) null else term to members
            }
            val chosen = options.maxWithOrNull(compareBy<Pair<String, List<Int>>> { it.second.size }
                .thenBy { it.first.length }.thenBy { -name(source[anchor]).indexOf(it.first) })
                ?: return@forEach
            chosen.second.forEach { consumed[it] = true }
            val canonical = chosen.second.firstOrNull { compact(name(source[it])) == chosen.first }
            val label = canonical?.let { name(source[it]).trim() } ?: chosen.first
            result += NameCluster(label, chosen.second.map { source[it] })
        }
        return result
    }

    /** Find directly shared Chinese brands first, then the existing bounded Latin families. */
    fun <T> namedClusters(source: List<T>, name: (T) -> String): List<NameCluster<T>> {
        if (source.size < 2) return emptyList()
        val chinese = hanClusters(source, name)
        val chineseMembers = chinese.flatMap { it.members }.toSet()
        val remaining = source.filterNot { it in chineseMembers }
        return (chinese + boundedClusters(remaining, name)).sortedBy { cluster ->
            source.indexOfFirst { it in cluster.members }
        }
    }

    private fun <T> boundedClusters(source: List<T>, name: (T) -> String): List<NameCluster<T>> {
        if (source.size < 2) return emptyList()
        val signatures = source.map { signature(name(it)) }
        val consumed = BooleanArray(source.size)
        val result = ArrayList<NameCluster<T>>()
        source.indices.forEach { anchorIndex ->
            if (consumed[anchorIndex]) return@forEach
            val initialAnchor = signatures[anchorIndex]
            val candidates = source.indices.filter { index ->
                !consumed[index] && related(initialAnchor, signatures[index])
            }
            if (candidates.size < 2) return@forEach
            // Use the shortest directly-related spelling as the family anchor;
            // this lets `良心|描述` discover the canonical `良心` later in the
            // source while still keeping blackstone VPN extensions separate.
            val canonicalIndex = candidates.minWithOrNull(
                compareBy<Int> { signatures[it].folded.length }.thenBy { it }
            ) ?: return@forEach
            val anchor = signatures[canonicalIndex]
            val memberIndexes = source.indices.filter { index ->
                !consumed[index] && related(anchor, signatures[index])
            }
            if (memberIndexes.size < 2) return@forEach
            memberIndexes.forEach { consumed[it] = true }
            result += NameCluster(
                label = name(source[canonicalIndex]).trim().ifEmpty { "(unnamed)" },
                members = memberIndexes.map { source[it] },
            )
        }
        return result
    }

    /** Show named families and a counted remainder instead of a flat card list. */
    fun <T> previewText(source: List<T>, name: (T) -> String): String {
        if (source.isEmpty()) return ""
        val clusters = namedClusters(source, name)
        val members = clusters.flatMap { it.members }.toSet()
        val lines = ArrayList<String>()
        clusters.forEach { cluster ->
            lines += "${cluster.label} (${cluster.members.size})"
            cluster.members.forEach { lines += "  · ${name(it)}" }
        }
        val remainder = source.filterNot { it in members }
        if (remainder.isNotEmpty()) {
            lines += "Other (${remainder.size})"
            remainder.forEach { lines += "  · ${name(it)}" }
        }
        return lines.joinToString("\n")
    }

    /** Return a stable order with repeated, directly-related families contiguous. */
    fun <T> stableClusterOrder(source: List<T>, name: (T) -> String): List<T> {
        if (source.size < 2) return source.toList()
        val clusters = namedClusters(source, name)
        if (clusters.isEmpty()) return source.toList()
        val byItem = HashMap<T, NameCluster<T>>()
        clusters.forEach { cluster -> cluster.members.forEach { byItem[it] = cluster } }
        val emitted = HashSet<NameCluster<T>>()
        val result = ArrayList<T>(source.size)
        source.forEach { item ->
            val cluster = byItem[item]
            if (cluster == null) result += item
            else if (emitted.add(cluster)) result.addAll(cluster.members)
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
