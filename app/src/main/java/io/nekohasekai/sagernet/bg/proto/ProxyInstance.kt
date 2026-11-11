package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true
    private var disableRuntimeSelector = false

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        try {
            if (disableRuntimeSelector) config = buildConfig(profile, disableSelector = true)
            else super.buildConfig()
        } catch (e: Exception) {
            // An unrelated enabled group's malformed chain/custom outbound must not
            // block the chosen node. This is explicitly a single-node fallback,
            // never reported as a global hot switch.
            Logs.w("Global selector compilation failed; using selected-node config: ${e.message}")
            config = buildConfig(profile, disableSelector = true)
        }
        lastSelectorGroupId = config.selectorGroupId
        //
        if (notTmp) Logs.d(config.config)
        if (notTmp && BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap))
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        try {
            super.init()
        } catch (e: Exception) {
            if (disableRuntimeSelector || !runCatching { config.globalSelector }.getOrDefault(false)) throw e
            // Includes unavailable plugins or native-invalid custom members in OTHER
            // groups. No started service is claimed: retry the chosen node only.
            Logs.w("Global selector initialization failed; reconnecting selected node only: ${e.message}")
            super.close()
            pluginPath.clear()
            pluginConfigs.clear()
            externalInstances.clear()
            disableRuntimeSelector = true
            super.init()
        }
        pluginConfigs.forEach { (_, plugin) ->
            val (_, content) = plugin
            Logs.d(content)
        }
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        if (config.globalSelector) {
            // sing-box's cache may restore a different default. Startup follows the
            // user's chosen profile, not an old cache-file selection.
            val selectedTag = config.profileTagMap[profile.id]
                ?: error("Selected profile missing from compiled selector")
            check(box.selectOutbound(selectedTag)) { "Native selector rejected startup profile" }
        }
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    override fun close() {
        super.close()
        runBlocking {
            looper?.stop()
            looper = null
        }
    }
}
