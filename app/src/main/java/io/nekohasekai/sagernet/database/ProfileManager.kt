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

    /**
     * Reads routes and performs the built-in initialization/reconciliation before
     * returning them. The defaults and the CN preset are inserted in one Room
     * transaction; existing rows are never updated or reordered.
     */
    suspend fun getRules(): List<RuleEntity> {
        try {
            SagerDatabase.instance.runInTransaction {
                val db = SagerDatabase.instance.openHelper.writableDatabase
                // Stored in the same database as rules: marker and inserts commit together.
                db.execSQL("CREATE TABLE IF NOT EXISTS `karing_initialization` (`key` TEXT NOT NULL PRIMARY KEY)")
                val completed = db.query("SELECT 1 FROM `karing_initialization` WHERE `key` = 'cn-v1'").use { it.moveToFirst() }
                if (!completed) {
                    val preset = app.assets.open(KaringPresetImporter.CHINA_PRESET_ASSET).bufferedReader()
                        .use { KaringPresetImporter.parse(it.readText()) }
                    require(preset.rules.size == 28) { "CN preset must contain 28 rules" }
                    val dao = SagerDatabase.rulesDao
                    val existing = dao.allRules()
                    val defaults = mutableListOf<RuleEntity>()
                    @Suppress("DEPRECATION")
                    val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
                    // The old rulesFirstCreate lives in an in-memory database and is
                    // lost on restart; do not recreate legacy defaults on upgrade.
                    val freshInstall = packageInfo.firstInstallTime == packageInfo.lastUpdateTime
                    if (existing.isEmpty() && freshInstall && !DataStore.rulesFirstCreate) {
                        defaults += RuleEntity(
                            name = app.getString(R.string.route_opt_block_quic),
                            port = "443", network = "udp", outbound = -2
                        )
                        defaults += RuleEntity(
                            name = app.getString(R.string.route_opt_block_ads),
                            domains = "geosite:category-ads-all", outbound = -2
                        )
                        val countries = mutableListOf("cn:中国")
                        if (Locale.getDefault().country != Locale.CHINA.country) {
                            countries += "ir:Iran"
                            countries += "ru:Russia"
                        }
                        countries.forEach { countryEntry ->
                            val country = countryEntry.substringBefore(":")
                            val displayCountry = countryEntry.substringAfter(":")
                            if (country == "cn") defaults += RuleEntity(
                                name = app.getString(R.string.route_play_store, displayCountry),
                                domains = "domain:googleapis.cn\ndomain:xn--ngstr-lra8j.com\ndomain:xn--ngstr-cn-8za9o.com"
                            )
                            defaults += RuleEntity(
                                name = app.getString(R.string.route_bypass_domain, displayCountry),
                                domains = "geosite:$country", outbound = -1
                            )
                            defaults += RuleEntity(
                                name = app.getString(R.string.route_bypass_ip, displayCountry),
                                ip = "geoip:$country", outbound = -1
                            )
                        }
                    }
                    var order = dao.nextOrder() ?: 1L
                    defaults.forEach { rule ->
                        rule.userOrder = order++
                        rule.id = dao.createRule(rule)
                    }
                    val plan = KaringPresetImporter.importInto(
                        dao.allRules(), preset, KaringPresetImporter.CHINA_PRESET_IMPORT_ID
                    )
                    plan.newRules.forEach { rule ->
                        rule.userOrder = order++
                        rule.id = dao.createRule(rule)
                    }
                    db.execSQL("INSERT INTO `karing_initialization` (`key`) VALUES ('cn-v1')")
                }
            }
        } catch (error: Exception) {
            Logs.e("Karing CN preset initialization failed; retry on next route refresh", error)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(app, "CN 预设初始化失败，下次打开路由时重试: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        return SagerDatabase.rulesDao.allRules()
    }

}
