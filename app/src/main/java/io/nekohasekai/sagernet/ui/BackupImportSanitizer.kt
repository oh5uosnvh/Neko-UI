package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.RuleEntity

/** Normalize legacy/null Parcelable fields before Room binds non-null columns. */
internal fun RuleEntity.normalizedForImport(): RuleEntity = apply {
    name = name.orEmpty()
    config = config.orEmpty()
    domains = domains.orEmpty()
    ip = ip.orEmpty()
    port = port.orEmpty()
    sourcePort = sourcePort.orEmpty()
    network = network.orEmpty()
    source = source.orEmpty()
    protocol = protocol.orEmpty()
    ruleset = ruleset.orEmpty()
    builtinKey = builtinKey.orEmpty()
    sourceUrl = sourceUrl.orEmpty()
    remoteDomains = remoteDomains.orEmpty()
    remoteIps = remoteIps.orEmpty()
    subscriptionState = subscriptionState.orEmpty()
    lastUpdateError = lastUpdateError.orEmpty()
    packages = packages.orEmpty()
}
