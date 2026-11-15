package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.runCancellableProbe
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.CoroutineContext

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    @Volatile private var probeContext: libcore.ProbeContext? = null
    @Volatile private var currentStage = ProbeFailure.Stage.CONFIG

    private fun profileSnapshot(): ProxyEntity =
        KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(profile))

    private fun wrap(stage: ProbeFailure.Stage, error: Throwable): ProbeFailure {
        val vmess = profile.vmessBean
        val hint = if (vmess != null && !vmess.isVLESS && vmess.type == "ws" && vmess.path.isNullOrBlank())
            "VMess WS uses the default root path; if a legacy import lost its path, repair from the original config" else null
        return ProbeFailure.from(stage, error, hint)
    }

    /** The batch owns the worker and waits for cleanup before reusing its slot. */
    suspend fun doTest(context: CoroutineContext): Int {
        val pluginFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
        var probeProcesses: GuardedProcessPool? = null
        try {
            return runCancellableProbe(
                context = context,
                prepare = {
                    coroutineContext.ensureActive()
                    processes = GuardedProcessPool { failure ->
                        pluginFailure.compareAndSet(null, failure)
                        probeContext?.cancel()
                    }.also { probeProcesses = it }
                    init()
                    coroutineContext.ensureActive()
                    this@TestInstance.launch()
                },
                interrupt = { probeContext?.cancel() },
                close = {
                    probeContext?.cancel()
                    runCatching { close() }
                    probeProcesses?.coroutineContext?.get(Job)?.join()
                },
                probe = {
                    if (processes.processCount > 0) delay(500)
                    coroutineContext.ensureActive()
                    pluginFailure.get()?.let { throw it }
                    currentStage = ProbeFailure.Stage.HTTP_REQUEST
                    Libcore.urlTest(box, link, timeout)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val cause = pluginFailure.get() ?: if (probeContext?.timedOut() == true)
                java.io.IOException("probe setup timeout", error) else error
            throw wrap(currentStage, cause).also { Logs.w(it.message ?: "probe failed") }
        }
    }

    override fun buildConfig() {
        currentStage = ProbeFailure.Stage.CONFIG
        // Builders normalize beans in place. Never let a latency probe mutate the
        // live Room entity: a later recovery CAS must still see its original bytes.
        config = buildConfig(profileSnapshot(), true)
    }

    override suspend fun loadConfig() {
        currentStage = ProbeFailure.Stage.CORE_CREATE
        // don't call destroyAllJsi here; and never dump the full config (it has
        // UUIDs, endpoints and transport credentials) even in debug builds.
        // Setup is part of this node's URLTest budget. The old 5000 ms
        // floor made a 3000 ms setting silently wait another two seconds
        // before the actual HTTP deadline even started.
        val native = Libcore.newProbeContext(timeout.coerceAtLeast(500))
        probeContext = native
        coroutineContext.ensureActive()
        box = Libcore.newSingBoxProbe(config.config, LocalResolverImpl, native)
    }

    override fun launch() {
        currentStage = ProbeFailure.Stage.CORE_START
        super.launch()
        // Gobind exposes Go error returns as a throwing Java method.
        probeContext?.ready()
    }

}
