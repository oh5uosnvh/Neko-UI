package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    /** The batch cannot reuse this slot until native and plugin cleanup finish. */
    suspend fun doTest(context: CoroutineContext): Int {
        val pluginFailure = AtomicReference<Exception?>()
        var probeProcesses: GuardedProcessPool? = null
        try {
            return coroutineScope {
                val owner = this
                runUrlTestLifecycle(
                    context = context,
                    prepare = {
                        processes = GuardedProcessPool { failure ->
                            Logs.w(failure)
                            pluginFailure.compareAndSet(null, failure)
                            owner.cancel(CancellationException("URL test plugin failed", failure))
                        }.also { probeProcesses = it }
                        init()
                    },
                    start = { this@TestInstance.launch() },
                    probe = {
                        if (processes.processCount > 0) {
                            // Keep the existing startup grace period. A TCP accept
                            // alone does not establish readiness for every plugin.
                            delay(500)
                        }
                        kotlin.coroutines.coroutineContext.ensureActive()
                        Libcore.urlTest(box, link, timeout)
                    },
                    close = {
                        // start() enqueues plugin guards on Main. Drain that queue
                        // before cancelling: otherwise a not-yet-entered guard's
                        // finally block cannot reap its already-started process.
                        if ((probeProcesses?.processCount ?: 0) > 0) {
                            withContext(Dispatchers.Main) { Unit }
                        }
                        try {
                            close()
                        } finally {
                            // GuardedProcessPool.close cancels asynchronously. Do
                            // not release a slot while its processes are still alive.
                            probeProcesses?.coroutineContext?.get(Job)?.join()
                        }
                    },
                )
            }
        } catch (cancelled: CancellationException) {
            // Preserve plugin failures as node errors, not cancellation of the
            // whole batch. User cancellation still propagates unchanged.
            pluginFailure.get()?.let { throw it }
            throw cancelled
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
