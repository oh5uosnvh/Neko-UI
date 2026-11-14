package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.runCancellableProbe
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    /** Full lifecycle is bounded; native close is awaited before the slot is reused. */
    suspend fun doTest(context: CoroutineContext, initialization: Semaphore): Int {
        val pluginFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
        var probeProcesses: GuardedProcessPool? = null
        try {
            return runCancellableProbe(
                context = context,
                prepare = {
                    initialization.withPermit {
                        coroutineContext.ensureActive()
                        processes = GuardedProcessPool { failure ->
                            Logs.w(failure)
                            pluginFailure.compareAndSet(null, failure)
                            if (isInitialized()) runCatching { box.close() }
                        }.also { probeProcesses = it }
                        init()
                        coroutineContext.ensureActive()
                        launch()
                    }
                },
                close = {
                    runCatching { close() }
                    probeProcesses?.coroutineContext?.get(Job)?.join()
                },
                probe = {
                    // Native outbounds start synchronously. External plugins still
                    // need their historical readiness allowance, outside RTT time.
                    if (processes.processCount > 0) delay(500)
                    coroutineContext.ensureActive()
                    pluginFailure.get()?.let { throw it }
                    Libcore.urlTest(box, link, timeout)
                },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw (pluginFailure.get() ?: failure)
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
