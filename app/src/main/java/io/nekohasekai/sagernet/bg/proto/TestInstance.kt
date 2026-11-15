package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.runCancellableProbe
import io.nekohasekai.sagernet.bg.ProbeDeadline
import io.nekohasekai.sagernet.bg.awaitProbeListeners
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.CoroutineContext

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    @Volatile private var probeContext: libcore.ProbeContext? = null
    @Volatile private var currentStage = ProbeFailure.Stage.CONFIG
    private lateinit var deadline: ProbeDeadline

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
                    // The deadline starts HERE, not when the profile was queued
                    // or the coroutine was submitted to the blocking executor.
                    deadline = ProbeDeadline(timeout)
                    probeContext = Libcore.newProbeContext(deadline.remainingMillis())
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
                    if (processes.processCount > 0) {
                        awaitProbeListeners(config.externalIndex.flatMap { it.chain.keys }, deadline) {
                            pluginFailure.get()?.let { throw it }
                        }
                    }
                    coroutineContext.ensureActive()
                    pluginFailure.get()?.let { throw it }
                    val remaining = deadline.remainingMillis()
                    probeContext?.ready()
                    currentStage = ProbeFailure.Stage.HTTP_REQUEST
                    Libcore.urlTest(box, link, remaining)
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
        // Configuration and plugin preparation already spent part of the same
        // deadline; don't construct a new context or silently apply a 500ms floor.
        deadline.remainingMillis()
        val native = checkNotNull(probeContext)
        coroutineContext.ensureActive()
        box = Libcore.newSingBoxProbe(config.config, LocalResolverImpl, native)
    }

    override fun launch() {
        currentStage = ProbeFailure.Stage.CORE_START
        deadline.remainingMillis()
        super.launch()
        deadline.remainingMillis()
        // Keep the setup watchdog active until plugin readiness/HTTP hand-off.
    }

}
