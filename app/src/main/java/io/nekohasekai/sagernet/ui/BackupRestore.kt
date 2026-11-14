package io.nekohasekai.sagernet.ui

import android.os.Parcelable
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import org.json.JSONObject

/** Original-format restore only: unknown backup extensions are never interpreted. */
internal object BackupRestore {
    fun restore(
        content: JSONObject,
        profile: Boolean,
        rule: Boolean,
        setting: Boolean,
        appendConfigurations: Boolean,
        database: SagerDatabase,
        settingsDatabase: PublicDatabase? = null,
    ) {
        require(content.optInt("version", 0) == 1) { "Unsupported NekoBox backup version" }
        fun <T> read(name: String, creator: Parcelable.Creator<T>): List<T> =
            content.getJSONArray(name).let { array ->
                (0 until array.length()).map { BackupCompatibility.decode(array.getString(it), creator) }
            }
        val importProfiles = profile && content.has("profiles")
        val profiles = if (importProfiles) read("profiles", ProxyEntity.CREATOR) else emptyList()
        val groups = if (importProfiles) read("groups", ProxyGroup.CREATOR) else emptyList()
        // Validate every selected section and reference BEFORE touching either database.
        profiles.forEach { it.requireBean() }
        val rules = if (rule && content.has("rules")) {
            content.getJSONArray("rules").let { array ->
                (0 until array.length()).map { BackupCompatibility.decodeRule(array.getString(it)) }
            }
        } else null
        val settings = if (setting && content.has("settings")) {
            read("settings", KeyValuePair.CREATOR).filterNot { BackupCompatibility.isTagSetting(it.key) }
        } else null
        val append = importProfiles && appendConfigurations
        if (append) {
            val groupIds = groups.map { it.id }.toSet()
            val profileIds = profiles.map { it.id }.toSet()
            require(groupIds.size == groups.size && groupIds.all { it > 0 }) { "Invalid backup group IDs" }
            require(profileIds.size == profiles.size && profileIds.all { it > 0 }) { "Invalid backup profile IDs" }
            profiles.forEach { source ->
                require(source.groupId in groupIds) { "Missing backup profile group" }
                (source.requireBean() as? ChainBean)?.proxies.orEmpty().forEach {
                    require(it in profileIds) { "Missing backup chain profile" }
                }
            }
            groups.forEach { source ->
                listOf(source.frontProxy, source.landingProxy).filter { it > 0 }.forEach {
                    require(it in profileIds) { "Missing backup group proxy" }
                }
            }
            rules?.filter { it.outbound > 0 }?.forEach {
                require(it.outbound in profileIds) { "Missing backup rule outbound" }
            }
        }
        database.runInTransaction {
            val profileIdMap = mutableMapOf<Long, Long>()
            if (importProfiles) {
                if (append) {
                    val groupIdMap = mutableMapOf<Long, Long>()
                    val importedGroups = mutableListOf<ProxyGroup>()
                    var order = database.groupDao().nextOrder() ?: 1L
                    groups.sortedWith(compareBy<ProxyGroup> { it.userOrder }.thenBy { it.id }).forEach { source ->
                        val copy = source.copy(id = 0L, userOrder = order)
                        order = Math.addExact(order, 1L)
                        copy.id = database.groupDao().createGroup(copy)
                        groupIdMap[source.id] = copy.id
                        importedGroups += copy
                    }
                    val importedProfiles = profiles.map { source ->
                        source.copy(id = 0L, groupId = groupIdMap.getValue(source.groupId)).also { copy ->
                            copy.id = database.proxyDao().addProxy(copy)
                            profileIdMap[source.id] = copy.id
                        }
                    }
                    // Two passes also cover forward/cyclic chain references without ever
                    // binding a backup ID to an unrelated local profile. Room rolls back both.
                    importedProfiles.forEach { copy ->
                        val chain = copy.requireBean() as? ChainBean ?: return@forEach
                        copy.putBean(chain.clone().apply {
                            proxies = chain.proxies.orEmpty().map { profileIdMap.getValue(it) }
                        })
                        database.proxyDao().updateProxy(copy)
                    }
                    importedGroups.forEach { copy ->
                        if (copy.frontProxy > 0) copy.frontProxy = profileIdMap.getValue(copy.frontProxy)
                        if (copy.landingProxy > 0) copy.landingProxy = profileIdMap.getValue(copy.landingProxy)
                        database.groupDao().updateGroup(copy)
                    }
                } else {
                    database.proxyDao().reset()
                    // Deletion cascades old classification links; never recreate stale links
                    // against reused group IDs. Local classification definitions stay local.
                    database.groupDao().reset()
                    database.groupDao().insert(groups)
                    database.proxyDao().insert(profiles)
                }
            }
            if (rules != null) {
                database.rulesDao().reset()
                database.rulesDao().insert(if (append) rules.map {
                    if (it.outbound > 0) it.copy(outbound = profileIdMap.getValue(it.outbound)) else it
                } else rules)
            }
        }
        if (settings != null) {
            val target = settingsDatabase ?: PublicDatabase.instance
            target.runInTransaction {
                target.keyValuePairDao().reset()
                target.keyValuePairDao().insert(settings)
            }
        }
    }
}
