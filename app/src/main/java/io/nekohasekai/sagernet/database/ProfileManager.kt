package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.IOException
import java.sql.SQLException
import java.util.*


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: List<TrafficData>)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateProxy(profile)
        iterator { onUpdated(profile, false) }
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        SagerDatabase.proxyDao.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
    }

    suspend fun updateTraffic(profileId: Long, rx: Long, tx: Long) {
        SagerDatabase.proxyDao.updateTraffic(profileId, rx, tx)
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        if (profileIds.isNotEmpty()) {
            SagerDatabase.proxyDao.resetTraffic(profileIds)
        }
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: List<TrafficData>) {
        if (data.isEmpty()) return
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    /** Idempotently restores the fixed 19 resources without touching user-created routes. */
    suspend fun ensureDefaultRoutes(reset: Boolean = false): List<RuleEntity> {
        val dao = SagerDatabase.rulesDao
        val existing = dao.allRules()
        val byKey = existing.filter { it.builtinKey.isNotBlank() }.associateBy { it.builtinKey }.toMutableMap()
        val legacy = existing.filter { it.builtinKey.isBlank() }.toMutableList()
        val adoptedKeys = hashSetOf<String>()
        val missingCatalogItem = DefaultRouteCatalog.items.any { item -> byKey[item.key] == null }
        val restoreCatalogOrder = reset || missingCatalogItem
        // Upgrade the five historical resources by stable content/name, never by position alone.
        fun adopt(item: DefaultRouteCatalog.Item, predicate: (RuleEntity) -> Boolean) {
            if (byKey[item.key] != null) return
            val old = legacy.firstOrNull(predicate) ?: return
            old.builtinKey = item.key
            byKey[item.key] = old
            adoptedKeys += item.key
            legacy.remove(old)
        }
        adopt(DefaultRouteCatalog.items[0]) { it.port == "443" && it.network == "udp" && it.outbound == -2L }
        adopt(DefaultRouteCatalog.items[1]) { it.domains.contains("category-ads-all") && it.outbound == -2L }
        adopt(DefaultRouteCatalog.items[2]) { it.domains.contains("googleapis.cn") }
        adopt(DefaultRouteCatalog.items[17]) { it.domains.contains("geosite:cn") }
        adopt(DefaultRouteCatalog.items[18]) { it.ip.contains("geoip:cn") }
        val created = ArrayList<RuleEntity>()
        SagerDatabase.instance.runInTransaction {
            DefaultRouteCatalog.items.forEachIndexed { index, item ->
                val rule = byKey[item.key] ?: DefaultRouteCatalog.defaults(index, item).also {
                    it.id = dao.createRule(it)
                    created += it
                }
                val isNewOrAdopted = rule.id == 0L || rule.builtinKey in adoptedKeys
                // Names are fixed for new/adopted resources and on reset, but a normal
                // reload does not unexpectedly overwrite a user's custom built-in label.
                if (isNewOrAdopted || reset) rule.name = item.name
                // Built-ins are enabled by default; reset also restores this state.
                if (isNewOrAdopted || reset) rule.enabled = true
                if (reset) {
                    // Reset every fixed card's target, not only its visible name.
                    // In particular, a manually selected profile must return to the
                    // catalog default (proxy/direct/block) after reset.
                    rule.outbound = item.outbound
                    rule.sourceUrl = item.remoteUrl.orEmpty()
                    if (item.remoteUrl == null) {
                        rule.domains = item.domains
                        rule.ip = item.ip
                        rule.port = item.port
                        rule.sourcePort = ""
                        rule.network = item.network
                        rule.source = ""
                        rule.protocol = ""
                        rule.ruleset = item.ruleset
                        rule.packages = emptySet()
                        rule.cachedRuleCount = 0
                        rule.skippedRuleCount = 0
                        rule.subscriptionState = ""
                        rule.lastUpdateError = ""
                    } else {
                        // Keep parsed domains/IPs as the usable old cache, but reset
                        // provider state and never retain a manually selected outbound.
                        rule.ruleset = item.ruleset
                        rule.subscriptionState = if (rule.cachedRuleCount > 0) "stale" else ""
                        rule.lastUpdateError = ""
                    }
                }
                if (restoreCatalogOrder) rule.userOrder = index.toLong()
                rule.builtinKey = item.key
                rule.sourceUrl = item.remoteUrl.orEmpty()
                if (item.remoteUrl != null) rule.ruleset = item.ruleset
                // Preserve user-edited built-in conditions, but restore fixed subscription identity.
                dao.updateRule(rule)
            }
            if (restoreCatalogOrder) {
                legacy.sortedWith(compareBy<RuleEntity>({ it.userOrder }, { it.id })).forEachIndexed { index, rule ->
                    rule.userOrder = (DefaultRouteCatalog.items.size + index).toLong()
                    dao.updateRule(rule)
                }
            }
        }
        return dao.allRules()
    }

    suspend fun getRules(): List<RuleEntity> {
        return ensureDefaultRoutes()
    }

}
