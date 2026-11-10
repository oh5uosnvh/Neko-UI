package io.nekohasekai.sagernet.database

import android.os.Parcelable
import androidx.room.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import kotlinx.parcelize.Parcelize

@Entity(tableName = "rules")
@Parcelize
@TypeConverters(StringCollectionConverter::class)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var name: String = "",
    @ColumnInfo(defaultValue = "")
    var config: String = "",
    var userOrder: Long = 0L,
    var enabled: Boolean = false,
    var domains: String = "",
    var ip: String = "",
    var port: String = "",
    var sourcePort: String = "",
    var network: String = "",
    var source: String = "",
    var protocol: String = "",
    @ColumnInfo(name = "ruleset", defaultValue = "")
    var ruleset: String = "",
    @ColumnInfo(name = "builtinKey", defaultValue = "")
    var builtinKey: String = "",
    @ColumnInfo(name = "sourceUrl", defaultValue = "")
    var sourceUrl: String = "",
    @ColumnInfo(name = "subscriptionState", defaultValue = "")
    var subscriptionState: String = "",
    @ColumnInfo(name = "lastUpdateError", defaultValue = "")
    var lastUpdateError: String = "",
    @ColumnInfo(name = "cachedRuleCount", defaultValue = "0")
    var cachedRuleCount: Int = 0,
    @ColumnInfo(name = "skippedRuleCount", defaultValue = "0")
    var skippedRuleCount: Int = 0,
    var outbound: Long = 0,
    var packages: Set<String> = emptySet(),
) : Parcelable {

    fun displayName(): String {
        return name.takeIf { it.isNotBlank() } ?: "Rule $id"
    }

    /**
     * List cards intentionally expose only the kind and amount of configuration.
     * The actual domains, IPs, ports and rule text remain available in RouteSettingsActivity.
     */
    fun mkSummary(): String {
        if (packages.isNotEmpty()) return app.getString(R.string.apps_message, packages.size)
        if (builtinKey.startsWith("remote_")) {
            return if (cachedRuleCount > 0) app.getString(R.string.route_configured_rules, cachedRuleCount)
            else app.getString(R.string.route_waiting_update)
        }
        val count = listOf(config, domains, ip, source, sourcePort, port, network, protocol, ruleset)
            .sumOf { value -> value.lineSequence().count { it.trim().isNotEmpty() } }
        return if (count > 0) app.getString(R.string.route_configured_rules, count)
        else app.getString(R.string.route_configured)
    }

    fun displayOutbound(): String {
        return when (outbound) {
            0L -> app.getString(R.string.route_proxy)
            -1L -> app.getString(R.string.route_bypass)
            -2L -> app.getString(R.string.route_block)
            else -> ProfileManager.getProfile(outbound)?.displayName()
                ?: app.getString(R.string.error_title)
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * from rules WHERE (packages != '') AND enabled = 1")
        fun checkVpnNeeded(): List<RuleEntity>

        @Query("SELECT * FROM rules ORDER BY userOrder, id")
        fun allRules(): List<RuleEntity>

        @Query("SELECT * FROM rules WHERE builtinKey != '' ORDER BY userOrder, id")
        fun builtinRules(): List<RuleEntity>

        @Query("SELECT * FROM rules WHERE builtinKey = :key LIMIT 1")
        fun builtin(key: String): RuleEntity?

        @Query("UPDATE rules SET userOrder = :order WHERE id = :id")
        fun updateBuiltinOrder(id: Long, order: Long): Int

        @Query("SELECT * FROM rules WHERE enabled = :enabled ORDER BY userOrder")
        fun enabledRules(enabled: Boolean = true): List<RuleEntity>

        @Query("SELECT MAX(userOrder) + 1 FROM rules")
        fun nextOrder(): Long?

        @Query("SELECT * FROM rules WHERE id = :ruleId")
        fun getById(ruleId: Long): RuleEntity?

        @Query("DELETE FROM rules WHERE id = :ruleId")
        fun deleteById(ruleId: Long): Int

        @Delete
        fun deleteRule(rule: RuleEntity)

        @Delete
        fun deleteRules(rules: List<RuleEntity>)

        @Insert
        fun createRule(rule: RuleEntity): Long

        @Update
        fun updateRule(rule: RuleEntity)

        @Query("UPDATE rules SET outbound = :outbound WHERE id = :ruleId")
        fun updateOutbound(ruleId: Long, outbound: Long): Int

        @Query("UPDATE rules SET enabled = :enabled WHERE id = :ruleId")
        fun updateEnabled(ruleId: Long, enabled: Boolean): Int

        @Query("UPDATE rules SET userOrder = :order WHERE id = :ruleId")
        fun updateOrder(ruleId: Long, order: Long): Int

        @Update
        fun updateRules(rules: List<RuleEntity>)

        @Query("DELETE FROM rules")
        fun reset()

        @Insert
        fun insert(rules: List<RuleEntity>)

    }


}
