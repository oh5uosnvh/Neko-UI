package io.nekohasekai.sagernet.group

/** One request per UA; never merge several clients into a misleading header. */
internal object SubscriptionUserAgents {
    fun attempts(custom: String, global: String, enabled: List<Boolean>, candidates: List<String>): List<String> {
        custom.trim().takeIf { it.isNotEmpty() }?.let { return listOf(it) }
        global.trim().takeIf { it.isNotEmpty() }?.let { return listOf(it) }
        val selected = candidates.dropLast(1).filterIndexed { index, _ -> enabled.getOrElse(index) { false } }
        return (selected + candidates.last()).distinct()
    }
}
