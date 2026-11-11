package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.SelectorFingerprint
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.JavaUtil

/** Applies database-backed routing only to the existing instance; never starts a stopped VPN. */
fun BaseService.Interface.applyRuntimeRouting() {
    val epoch = ServiceStartPolicy.fence.snapshot()
    runOnDefaultDispatcher {
        // Collapse quick database/UI updates and wait for an in-progress user start.
        delay(120)
        var waits = 0
        while (data.state == BaseService.State.Connecting && waits++ < 150) {
            if (!ServiceStartPolicy.fence.permitsAutomatic(epoch)) return@runOnDefaultDispatcher
            delay(200)
        }
        synchronized(data.selectorMutationLock) {
            if (!ServiceStartPolicy.fence.permitsAutomatic(epoch) || data.state != BaseService.State.Connected) return@synchronized
            val running = data.proxy ?: return@synchronized
            val target = SagerDatabase.proxyDao.getById(DataStore.currentProfile) ?: return@synchronized
            val fresh = try { buildConfig(target, disableSelector = !running.config.globalSelector) }
            catch (_: Exception) {
                // Preserve a working connection if the new configuration cannot compile.
                Logs.w("Runtime routing configuration rejected before applying")
                return@synchronized
            }
            val old = running.config
            fun decode(text: String): Map<String, Any?> = JavaUtil.gson.fromJson(text, Map::class.java) as Map<String, Any?>
            val oldMap = decode(old.config)
            val newMap = decode(fresh.config)
            if (oldMap["route"] !is Map<*, *> || newMap["route"] !is Map<*, *>) return@synchronized
            if (!RuntimeRoutingConfig.compatible(oldMap, old.runtimePorts, newMap, fresh.runtimePorts) ||
                old.profileTagMap != fresh.profileTagMap) {
                // Node/transport/listener changes are rebuilds, not false hot acknowledgements.
                if (ServiceStartPolicy.fence.permitsAutomatic(epoch)) stopRunner(restart = true)
                return@synchronized
            }
            val nativeConfig = RuntimeRoutingConfig.remap(newMap, fresh.runtimePorts, old.runtimePorts)
            val routeJson = JavaUtil.gson.toJson(nativeConfig["route"])
            val dnsJson = JavaUtil.gson.toJson(nativeConfig["dns"] ?: emptyMap<String, Any>())
            try {
                val method = running.box.javaClass.getMethod("updateRouting", String::class.java, String::class.java)
                val generation = (method.invoke(running.box, routeJson, dnsJson) as? Number)?.toLong() ?: 0L
                if (generation <= 0) error("No native routing acknowledgement")
                if (data.proxy !== running || !ServiceStartPolicy.fence.permitsAutomatic(epoch)) return@synchronized
                // Preserve the running sockets, traffic map, external ports and native tag map.
                running.config = io.nekohasekai.sagernet.fmt.ConfigBuildResult(
                    JavaUtil.gson.toJson(nativeConfig), old.externalIndex, old.mainEntId,
                    old.trafficMap, old.profileTagMap, old.selectorGroupId, old.globalSelector,
                    fresh.selectorApplicationDnsFingerprint, fresh.selectorFingerprint, old.runtimePorts,
                )
                Logs.d("Runtime routing atomically applied, generation=$generation")
            } catch (_: Exception) {
                // Old cached cores and genuinely uncompiled dependencies take a safe rebuild.
                // This path is never labelled a native hot switch.
                Logs.w("Runtime routing needs an automatic core rebuild")
                if (ServiceStartPolicy.fence.permitsAutomatic(epoch) && data.proxy === running) stopRunner(restart = true)
            }
        }
    }
}
