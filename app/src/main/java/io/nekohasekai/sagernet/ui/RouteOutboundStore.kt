package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase

/** A quick outbound choice must not write a recycled card's stale rule fields back. */
object RouteOutboundStore {
    fun set(db: SagerDatabase, ruleId: Long, outbound: Long): RuleEntity? {
        require(outbound >= -2) { "Invalid route outbound" }
        var saved: RuleEntity? = null
        db.runInTransaction {
            if (outbound > 0 && db.proxyDao().getById(outbound) == null) return@runInTransaction
            if (db.rulesDao().updateOutbound(ruleId, outbound) == 1) saved = db.rulesDao().getById(ruleId)
        }
        return saved
    }
}
