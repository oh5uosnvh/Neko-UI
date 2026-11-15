package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.ProbeDeadline
import io.nekohasekai.sagernet.bg.runCancellableProbe
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CHAIN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.buildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.lang.reflect.Method
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** One disposable, warmed sing-box core for a whole URL-test batch. */
internal class BatchTestInstance(
    profiles: List<ProxyEntity>,
    private val link: String,
    private val timeout: Int,
) : BoxInstance(profiles.first()) {
    private val probeProfiles = profiles.map {
        KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(it))
    }
    private lateinit var deadline: ProbeDeadline
    private var probeContext: libcore.ProbeContext? = null
    private val outboundProbeMethod: Method by lazy {
        Libcore::class.java.getMethod(
            "urlTestOutbound", libcore.BoxInstance::class.java,
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
    }

    data class Result(val profile: ProxyEntity, val latency: Int?, val error: Exception?)

    override fun buildConfig() {
        // The builder creates one selector and gives every requested root profile
        // a stable tag in profileTagMap. It also preserves each node's resolver
        // and transport instead of converting the batch to a generic proxy.
        config = buildConfig(probeProfiles.first(), forTest = true, probeProfiles = probeProfiles)
    }

    override suspend fun loadConfig() {
        val native = checkNotNull(probeContext)
        box = Libcore.newSingBoxProbe(config.config, LocalResolverImpl, native)
    }

    override fun launch() {
        super.launch()
    }

    suspend fun doTest(context: CoroutineContext): List<Result> {
        return runCancellableProbe(
            context = context,
            prepare = {
                coroutineContext.ensureActive()
                deadline = ProbeDeadline(timeout)
                probeContext = Libcore.newProbeContext(deadline.remainingMillis())
                init()
                coroutineContext.ensureActive()
                launch()
            },
            interrupt = { probeContext?.cancel() },
            close = {
                probeContext?.cancel()
                runCatching { close() }
            },
            probe = {
                coroutineContext.ensureActive()
                probeContext?.ready()
                // Setup has its own watchdog and is stopped at ready(). Each
                // leaf then gets the configured URLTest timeout, just like the
                // official batch implementation; queueing behind the ten-slot
                // gate must not make later nodes inherit a nearly expired timer.
                deadline.remainingMillis()
                val gate = Semaphore(10) // sing-box URLTest batch concurrency
                coroutineScope {
                    probeProfiles.map { profile ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                val tag = config.profileTagMap[profile.id]
                                    ?: error("probe outbound tag missing")
                                try {
                                    // Core setup is guarded separately. Each leaf
                                    // receives the configured request timeout, so
                                    // waiting behind the ten-slot batch gate does
                                    // not turn queue time into a fake timeout.
                                    val value = outboundProbeMethod.invoke(null, box, tag, link, timeout) as Int
                                    Result(profile, value, null)
                                } catch (error: Exception) {
                                    Result(profile, null, error)
                                }
                            }
                        }
                    }.awaitAll()
                }
            },
        )
    }
}

internal fun canUseBatchProbe(profiles: List<ProxyEntity>): Boolean {
    if (profiles.size < 2) return false
    // Chains and full custom documents can contain process-backed or unrelated
    // graphs. Keep those on the proven isolated fallback; built-in leaf nodes
    // are safe to place in one selector core.
    return profiles.all { profile ->
        val rawOutbound = profile.type == TYPE_CONFIG && profile.configBean?.type == 1
        (profile.type != TYPE_CONFIG || rawOutbound) &&
            profile.type != TYPE_CHAIN && !profile.needExternal()
    }
}
