package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildCache
import io.nekohasekai.sagernet.fmt.SelectorGeneration
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
        val generation = SelectorGeneration.current()
        val disableSelector = disableRuntimeSelector
        if (notTmp) {
            ConfigBuildCache.get(profile.id, generation, disableSelector)?.let {
                config = it
                lastSelectorGroupId = it.selectorGroupId
                Logs.d("selector config cache hit id=${profile.id} generation=$generation")
                return
            }
        }
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            if (disableSelector) config = buildConfig(profile, disableSelector = true)
            else super.buildConfig()
        } catch (e: Exception) {
            // An unrelated enabled group's malformed chain/custom outbound must not
            // block the chosen node. This is explicitly a single-node fallback,
            // never reported as a global hot switch.
            Logs.w("Global selector compilation failed; using selected-node config: ${e.message}")
            config = buildConfig(profile, disableSelector = true)
        }
        // A global build failure falls back to a single-node config; never cache
        // that fallback under the global-selector key.
        if (notTmp && (disableSelector || config.globalSelector)) ConfigBuildCache.put(config, disableSelector)
        Logs.d("selector config build id=${profile.id} generation=${config.selectorGeneration} elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms global=${config.globalSelector}")
        lastSelectorGroupId = config.selectorGroupId
        //
        // A 9k-member graph can be megabytes and contains credentials. Do not
        // synchronously format/write it into the tiny release log ring at startup.
        if (notTmp && BuildConfig.DEBUG) {
            Logs.d(config.config)
            Logs.d(JavaUtil.gson.toJson(config.trafficMap))
        }
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        val initStarted = android.os.SystemClock.elapsedRealtime()
        try {
            super.init()
            Logs.d("proxy init id=${profile.id} build+plugins+native elapsed=${android.os.SystemClock.elapsedRealtime() - initStarted}ms global=${config.globalSelector}")
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
        if (BuildConfig.DEBUG) pluginConfigs.forEach { (_, plugin) ->
            val (_, content) = plugin
            Logs.d(content)
        }
    }

    override suspend fun loadConfig() {
        val started = android.os.SystemClock.elapsedRealtime()
        super.loadConfig()
        if (notTmp) Logs.i("selector_perf phase=native_create elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started} members=${config.profileTagMap.size} config_chars=${config.config.length}")
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
