package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.SelectorApplyCoalescer
import io.nekohasekai.sagernet.fmt.SelectorGeneration
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.JavaUtil
import java.util.Collections
import java.util.WeakHashMap

/** A request is latest-wins; its generation is checked again after compilation. */
private data class RuntimeRoutingRequest(val epoch: Long, val generation: Long)

private val runtimeRoutingQueues = Collections.synchronizedMap(
    WeakHashMap<BaseService.Data, SelectorApplyCoalescer<RuntimeRoutingRequest>>()
)

/** Applies database-backed routing only to the existing instance; never starts a stopped VPN. */
fun BaseService.Interface.applyRuntimeRouting() {
    val queue = synchronized(runtimeRoutingQueues) {
        runtimeRoutingQueues.getOrPut(data) { SelectorApplyCoalescer() }
    }
    val request = RuntimeRoutingRequest(
        ServiceStartPolicy.fence.snapshot(), SelectorGeneration.current()
    )
    if (!queue.submit(request)) return
    runOnDefaultDispatcher { runRuntimeRoutingWorker(queue) }
}

private suspend fun BaseService.Interface.runRuntimeRoutingWorker(
    queue: SelectorApplyCoalescer<RuntimeRoutingRequest>
) {
    // One debounce per burst, rather than one sleep/build per broadcast/request.
    delay(120)
    while (true) {
        val request = queue.take()
        if (request == null) {
            if (!queue.finish()) return
            continue
        }
        if (!ServiceStartPolicy.fence.permitsAutomatic(request.epoch)) {
            if (!queue.finish()) return
            continue
        }
        while (data.state == BaseService.State.Connecting) {
            if (!ServiceStartPolicy.fence.permitsAutomatic(request.epoch)) {
                queue.finish()
                return
            }
            if (queue.hasPending()) break
            delay(200)
        }
        if (queue.hasPending()) {
            if (!queue.finish()) return
            continue
        }
        if (data.state != BaseService.State.Connected) {
            if (!queue.finish()) return
            continue
        }

        // Only immutable references are read while the selector lock is held.
        // Room, buildConfig, JSON parsing, canonical comparison and remapping all
        // happen below without that lock, so Binder selector taps stay responsive.
        val snapshot = synchronized(data.selectorMutationLock) {
            if (!ServiceStartPolicy.fence.permitsAutomatic(request.epoch) ||
                data.state != BaseService.State.Connected) null
            else data.proxy?.let { Triple(it, it.config, DataStore.currentProfile) }
        }
        if (snapshot == null) {
            if (!queue.finish()) return
            continue
        }
        val running = snapshot.first
        val old = snapshot.second
        val targetId = snapshot.third

        val target = SagerDatabase.proxyDao.getById(targetId)
        if (target == null) {
            if (!queue.finish()) return
            continue
        }
        val fresh = try {
            buildConfig(target, disableSelector = !old.globalSelector)
        } catch (_: Exception) {
            Logs.w("Runtime routing configuration rejected before applying")
            if (!queue.finish()) return
            continue
        }

        // A local DB writer supersedes this candidate. Do not let an old build
        // install a route with stale selector membership or reused ports.
        if (fresh.selectorGeneration != SelectorGeneration.current() || queue.hasPending()) {
            if (!queue.finish()) return
            continue
        }

        val decoded: Pair<Map<String, Any?>, Map<String, Any?>>? = try {
            fun decode(text: String): Map<String, Any?> =
                JavaUtil.gson.fromJson(text, Map::class.java) as Map<String, Any?>
            decode(old.config) to decode(fresh.config)
        } catch (_: Exception) {
            null
        }
        if (decoded == null) {
            if (!queue.finish()) return
            continue
        }
        val (oldMap, newMap) = decoded
        if (oldMap["route"] !is Map<*, *> || newMap["route"] !is Map<*, *>) {
            if (!queue.finish()) return
            continue
        }
        if (!RuntimeRoutingConfig.compatible(oldMap, old.runtimePorts, newMap, fresh.runtimePorts) ||
            old.profileTagMap != fresh.profileTagMap) {
            // Node/transport/listener changes are rebuilds, not false hot ACKs.
            synchronized(data.selectorMutationLock) {
                if (ServiceStartPolicy.fence.permitsAutomatic(request.epoch) &&
                    data.proxy === running) stopRunner(restart = true)
            }
            if (!queue.finish()) return
            continue
        }
        val nativeConfig = try {
            RuntimeRoutingConfig.remap(newMap, fresh.runtimePorts, old.runtimePorts)
        } catch (_: Exception) {
            null
        }
        if (nativeConfig == null) {
            if (!queue.finish()) return
            continue
        }
        // Serialize/canonicalize outside selectorMutationLock.
        val routeJson = JavaUtil.gson.toJson(nativeConfig["route"])
        val dnsJson = JavaUtil.gson.toJson(nativeConfig["dns"] ?: emptyMap<String, Any>())
        val updatedConfig = JavaUtil.gson.toJson(nativeConfig)

        synchronized(data.selectorMutationLock) {
            // Stop and newer DB revisions win before the native ACK. This is the
            // only region containing updateRouting and the small state publish.
            if (!ServiceStartPolicy.fence.permitsAutomatic(request.epoch) ||
                SelectorGeneration.current() != fresh.selectorGeneration ||
                queue.hasPending() || data.state != BaseService.State.Connected ||
                data.proxy !== running) {
                // Candidate is intentionally discarded, never applied as a stale ACK.
            } else {
                try {
                    val method = running.box.javaClass.getMethod(
                        "updateRouting", String::class.java, String::class.java
                    )
                    val generation = (method.invoke(running.box, routeJson, dnsJson) as? Number)?.toLong() ?: 0L
                    if (generation <= 0) error("No native routing acknowledgement")
                    if (data.proxy === running && ServiceStartPolicy.fence.permitsAutomatic(request.epoch)) {
                        running.config = io.nekohasekai.sagernet.fmt.ConfigBuildResult(
                            updatedConfig, old.externalIndex, old.mainEntId, old.trafficMap,
                            old.profileTagMap, old.selectorGroupId, old.globalSelector,
                            fresh.selectorApplicationDnsFingerprint, fresh.selectorFingerprint,
                            old.runtimePorts, fresh.selectorGeneration
                        )
                        Logs.d("Runtime routing atomically applied, generation=$generation")
                    }
                } catch (_: Exception) {
                    Logs.w("Runtime routing needs an automatic core rebuild")
                    if (ServiceStartPolicy.fence.permitsAutomatic(request.epoch) && data.proxy === running) {
                        stopRunner(restart = true)
                    }
                }
            }
        }
        if (!queue.finish()) return
    }
}
