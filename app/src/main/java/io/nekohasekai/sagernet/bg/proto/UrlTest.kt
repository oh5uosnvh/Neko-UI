package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlin.coroutines.CoroutineContext

// 013: context = 每轮测试专用的阻塞线程池（ConfigurationFragment.urlTest 创建，
// 测完/取消后关闭）。不再占用 Dispatchers.Default。
class UrlTest(private val context: CoroutineContext) {

    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest(context)
    }

}
