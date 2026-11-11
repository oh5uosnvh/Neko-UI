package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues

/** DNS settings use the existing group BLOB for both basic and subscription groups. */
object GroupDnsStore {
    fun source(settings: SubscriptionBean): String = settings.importedDnsSource.orEmpty().ifBlank {
        when {
            settings.link.orEmpty().startsWith("content://") -> "file"
            settings.link.orEmpty().isNotBlank() -> "subscription"
            settings.importedDnsConfig.orEmpty().isNotBlank() -> "file"
            else -> ""
        }
    }

    fun prepare(group: ProxyGroup, db: SagerDatabase = SagerDatabase.instance): SubscriptionBean {
        val settings = (group.subscription ?: SubscriptionBean()).applyDefaultValues()
        val nodes = if (group.id > 0) db.proxyDao().getByGroup(group.id) else emptyList()
        val origins = nodes.map { node ->
            val bean = node.requireBean()
            val original = bean.mappedOriginalAddress.orEmpty()
            if (original.isNotBlank() && (bean.serverAddress == original || bean.serverAddress == bean.mappedTargetAddress)) {
                original
            } else bean.serverAddress
        }.distinct()
        val cleaned = hashMapOf<String, String>()
        fun automatic(text: String): String = cleaned.getOrPut(text) {
            SubscriptionDns.fromImportedSnapshot(text, origins).snapshot()
        }
        // Remove old automatic defaults and unrelated hosts from both group and node
        // BLOBs. Manual resolver/policy/hosts fields are never touched by migration.
        if (nodes.isNotEmpty()) {
            settings.importedDnsConfig = automatic(settings.importedDnsConfig.orEmpty())
            val changed = nodes.filter { node ->
                val bean = node.requireBean()
                val snapshot = automatic(bean.subscriptionDns.orEmpty())
                if (snapshot == bean.subscriptionDns.orEmpty()) false else {
                    bean.subscriptionDns = snapshot
                    node.putBean(bean)
                    true
                }
            }
            // Existing import/save callers already hold a Room transaction; nesting
            // is supported. Settings-page preparation must not leave half a migration.
            if (changed.isNotEmpty()) db.runInTransaction {
                changed.chunked(256).forEach { db.proxyDao().updateProxy(it) }
            }
        } else {
            settings.importedDnsConfig = SubscriptionDns.fromImportedSnapshot(settings.importedDnsConfig.orEmpty()).snapshot()
        }
        if (group.type == GroupType.BASIC && settings.importedDnsConfig.isNullOrBlank()) {
            val snapshots = nodes.map { it.requireBean().subscriptionDns.orEmpty() }.filter { it.isNotBlank() }.distinct()
            if (snapshots.isNotEmpty()) {
                settings.importedDnsConfig = mergeSnapshots(snapshots)
                settings.importedDnsSource = "file"
            }
        }
        if (settings.importedDnsSource.isNullOrBlank()) settings.importedDnsSource = source(settings)
        group.subscription = settings
        return settings
    }

    fun importedForNode(bean: AbstractBean, settings: SubscriptionBean?): String = when {
        settings == null -> bean.subscriptionDns.orEmpty()
        source(settings) == "file" && (bean.subscriptionDnsSource == "file" ||
            (bean.subscriptionDnsSource.isNullOrBlank() && !bean.subscriptionDns.isNullOrBlank())) -> bean.subscriptionDns.orEmpty()
        else -> settings.importedDnsConfig.orEmpty()
    }

    fun config(bean: AbstractBean, settings: SubscriptionBean?): SubscriptionDns.Config = SubscriptionDns.effective(
        importedForNode(bean, settings), settings?.privateDnsConfig.orEmpty(), settings?.hostsConfig.orEmpty(), settings?.serverDnsResolver.orEmpty(),
    )

    fun mergeSnapshots(snapshots: List<String>): String {
        val merged = SubscriptionDns.Config()
        snapshots.filter { it.isNotBlank() }.distinct().forEach { text ->
            val current = SubscriptionDns.fromImportedSnapshot(text)
            merged.hosts.putAll(current.hosts)
            merged.policy.putAll(current.policy)
            merged.serverPolicy.putAll(current.serverPolicy)
            if (current.bootstrap.isNotEmpty()) { merged.bootstrap.clear(); merged.bootstrap.addAll(current.bootstrap) }
        }
        // Validate aggregate aliases rather than writing a cyclic merged hosts table.
        return SubscriptionDns.fromDocument(merged.asDocument()).snapshot()
    }

    fun applyMappings(db: SagerDatabase, group: ProxyGroup): Int {
        val settings = group.subscription ?: return 0
        val nodes = db.proxyDao().getByGroup(group.id)
        val configs = hashMapOf<String, SubscriptionDns.Config>()
        val changed = nodes.filter { node ->
            val bean = node.requireBean()
            val input = importedForNode(bean, settings)
            val config = configs.getOrPut(input) { config(bean, settings) }
            val applied = NodeHostMapping.apply(bean, config.hosts, settings.applyHosts != false)
            if (applied) node.putBean(bean)
            applied
        }
        changed.chunked(256).forEach { db.proxyDao().updateProxy(it) }
        return changed.size
    }

    /** DNS-only update under the same transaction as node rewrites; preserves current link/update data. */
    fun saveDnsAndMappings(db: SagerDatabase, groupId: Long, overrides: SubscriptionBean): Int {
        var count = 0
        db.runInTransaction {
            val group = db.groupDao().getById(groupId) ?: error("Group no longer exists")
            val settings = prepare(group, db)
            settings.serverDnsResolver = overrides.serverDnsResolver
            settings.directDnsResolver = overrides.directDnsResolver
            settings.privateDnsConfig = overrides.privateDnsConfig
            settings.hostsConfig = overrides.hostsConfig
            settings.applyHosts = overrides.applyHosts
            count = applyMappings(db, group)
            db.groupDao().updateGroup(group)
        }
        return count
    }
}
