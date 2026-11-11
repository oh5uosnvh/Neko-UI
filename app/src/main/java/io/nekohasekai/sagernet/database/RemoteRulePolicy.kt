package io.nekohasekai.sagernet.database

/** Remote data is additive to manual conditions and never mutates the user's editor fields. */
internal object RemoteRulePolicy {
    fun effective(rule: RuleEntity): RuleEntity = rule.copy(
        domains = (rule.domains.lineSequence() + rule.remoteDomains.lineSequence())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n"),
        ip = (rule.ip.lineSequence() + rule.remoteIps.lineSequence())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
    )
    fun waitingWithoutConditions(rule: RuleEntity): Boolean = rule.sourceUrl.isNotBlank() &&
        rule.cachedRuleCount == 0 && listOf(rule.domains, rule.ip, rule.config, rule.port,
            rule.sourcePort, rule.network, rule.source, rule.protocol, rule.ruleset).all { it.isBlank() } &&
        rule.packages.isEmpty()
}
