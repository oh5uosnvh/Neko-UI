package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    private val closed = AtomicBoolean(false)

    // 013: 取消时最多关一次。close() 自带全套守卫（processes/box 未初始化自动跳过），
    // 在测的 Go urlTest 会被 close 打断并立即报错返回。
    private fun closeOnce() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { close() }
    }

    /**
     * 013 连接测试提速 + 取消即停：
     * - context 为每轮测试专用的阻塞线程池（ConfigurationFragment.urlTest 创建）。
     *   旧实现把 init/launch/Libcore.urlTest 整条链跑在 Dispatchers.Default 上：
     *   每个在测实例钉死一个 Default 线程直到 HTTP 完成/超时，有效并发被 CPU 核数
     *   封顶 —— 这就是"并发设置开再高，测速还是慢"的根因。
     * - suspendCancellableCoroutine + invokeOnCancellation：取消瞬间关闭实例，
     *   Go 侧 urlTest 立刻返回错误。旧实现取消后实例继续跑到 connectionTestTimeout，
     *   僵尸实例霸占全部 Default 线程 —— 这就是"取消后重测 2~3 秒空档"的根因。
     *   真实 HTTP 延迟测量路径（经代理 GET 测试 URL）零改动。
     * - resume 用 resumeWith + 吃 IllegalStateException（协程已取消时静默丢弃结果），
     *   不碰 CancellableContinuation 自带的内部 API tryResume。
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun doTest(context: CoroutineContext): Int {
        return suspendCancellableCoroutine { c ->
            c.invokeOnCancellation { closeOnce() }
            processes = GuardedProcessPool {
                Logs.w(it)
                runCatching { c.resumeWith(Result.failure(it)) }
            }
            // 外层跑 Default，阻塞段用 withContext 进专用池：池被提前关闭时
            // withContext 抛 RejectedExecutionException，走 catch 干净失败，
            // 不在 dispatch 阶段炸协程。
            GlobalScope.launch(Dispatchers.Default) {
                use {
                    try {
                        withContext(context) {
                            init()
                            launch()
                        }
                        if (processes.processCount > 0) {
                            // wait for plugin start
                            delay(500)
                        }
                        val latency = withContext(context) {
                            Libcore.urlTest(box, link, timeout)
                        }
                        c.resumeWith(Result.success(latency))
                    } catch (e: Exception) {
                        runCatching { c.resumeWith(Result.failure(e)) }
                    }
                }
            }
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
