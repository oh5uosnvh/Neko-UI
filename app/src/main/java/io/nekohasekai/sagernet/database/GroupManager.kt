package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.fmt.SelectorGeneration

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        /** One committed order snapshot, not a node/DNS change. Legacy listeners remain compatible. */
        suspend fun groupsReordered(groups: List<ProxyGroup>, changed: List<ProxyGroup>) {
            for (group in changed) groupUpdated(group)
        }

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
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

    suspend fun clearGroup(groupId: Long) {
        SelectorGeneration.invalidate()
        DataStore.selectedProxy = 0L
        SagerDatabase.proxyDao.deleteAll(groupId)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        SelectorGeneration.invalidate()
        val entities = SagerDatabase.proxyDao.getByGroup(groupId)
        for (index in entities.indices) {
            entities[index].userOrder = (index + 1).toLong()
        }
        SagerDatabase.proxyDao.updateProxy(entities)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup, tagId: Long? = null): ProxyGroup =
        createGroup(group, tagId, emptyList())

    /** Clipboard/import path: profiles, root group and optional classification commit together. */
    suspend fun createGroup(group: ProxyGroup, tagId: Long?, profiles: List<ProxyEntity>): ProxyGroup {
        SelectorGeneration.invalidate()
        SagerDatabase.instance.tagStore.createRootGroup(group, tagId, profiles)
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        SelectorGeneration.invalidate()
        SagerDatabase.groupDao.updateGroup(group)
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        SelectorGeneration.invalidate()
        SagerDatabase.instance.tagStore.deleteRootGroups(listOf(groupId))
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        if (group.isNotEmpty()) SelectorGeneration.invalidate()
        SagerDatabase.instance.tagStore.deleteRootGroups(group.map { it.id })
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}