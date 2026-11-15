package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.ProbeDeadline
import io.nekohasekai.sagernet.bg.runBatchProbe
import io.nekohasekai.sagernet.bg.runCancellableProbe
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CHAIN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.buildConfig
import kotlinx.coroutines.ensureActive
import java.lang.reflect.Method
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** One disposable, warmed sing-box core for a whole URL-test batch. */
internal class BatchTestInstance(
    profiles: List<ProxyEntity>,
    private val link: String,
    timeoutMillis: Int,
) : BoxInstance(profiles.first()) {
    private val timeout = io.nekohasekai.sagernet.bg.UrlTestTimeout.normalizeMillis(timeoutMillis)
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

    /**
     * Probe and publish each leaf as soon as its native call completes.  The
     * callback is deliberately part of the structured probe owner, rather
     * than replaying a List after awaitAll: a slow first leaf must not hide
     * later completions from the progress UI or persistence queue.
     */
    suspend fun doTest(context: CoroutineContext, onResult: suspend (Result) -> Unit) {
        runCancellableProbe(
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
                runBatchProbe(probeProfiles, concurrency = 10, probe = { profile ->
                    val tag = config.profileTagMap[profile.id]
                        ?: error("probe outbound tag missing")
                    // Core setup is guarded separately. Each leaf receives the
                    // configured request timeout, so waiting behind the batch
                    // gate does not turn queue time into a fake timeout.
                    outboundProbeMethod.invoke(null, box, tag, link, timeout) as Int
                }) { result ->
                    onResult(Result(result.profile, result.latency, result.error))
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
