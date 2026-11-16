package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private val cacheFiles = UrlTestTempFiles()
    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildConfig(profile)
        DataStore.mixedInboundAuthed = DataStore.mixedInboundHasAuth
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    open suspend fun init() {
        buildConfig()
        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                when (val bean = profile.requireBean()) {
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(port)
                    }

                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = profile.type to bean.buildMieruConfig(port)
                    }

                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port)
                    }

                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteria1Config(port) {
                            cacheFiles.create(app.cacheDir, "hysteria_", ".ca")
                        }
                    }
                }
            }
        }
        loadConfig()
    }

    override fun launch() {
        // TODO move, this is not box
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()

        for ((chain) in config.externalIndex) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = index != chain.size - 1
                val (profileType, config) = pluginConfigs[port] ?: (0 to "")

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }

                    bean is TrojanGoBean -> {
                        val configFile = cacheFiles.create(cacheDir, "trojan_go_", ".json")
                        configFile.writeText(config)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }

                    bean is MieruBean -> {
                        val configFile = cacheFiles.create(cacheDir, "mieru_", ".json")
                        configFile.writeText(config)

                        val envMap = mutableMapOf<String, String>()
                        envMap["MIERU_CONFIG_JSON_FILE"] = configFile.absolutePath
                        envMap["MIERU_PROTECT_PATH"] = "protect_path"

                        val commands = mutableListOf(
                            initPlugin("mieru-plugin").path, "run",
                        )

                        processes.start(commands, envMap)
                    }

                    bean is NaiveBean -> {
                        val configFile = cacheFiles.create(cacheDir, "naive_", ".json")
                        configFile.writeText(config)

                        val envMap = mutableMapOf<String, String>()

                        if (bean.certificates.isNotBlank()) {
                            val certFile = cacheFiles.create(cacheDir, "naive_", ".crt")
                            certFile.writeText(bean.certificates)

                            envMap["SSL_CERT_FILE"] = certFile.absolutePath
                        }

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands, envMap)
                    }

                    bean is HysteriaBean -> {
                        val configFile = cacheFiles.create(cacheDir, "hysteria_", ".json")
                        configFile.writeText(config)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client"
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands)
                    }
                }
            }
        }

        box.start()
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.deleteOwned()

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::box.isInitialized) {
            box.close()
        }
    }

}
