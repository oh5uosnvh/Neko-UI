package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.CoroutineContext

/** One attempt only; the batch verifies transient failures in a quieter pass. */
class UrlTest(
    private val context: CoroutineContext,
    private val initialization: Semaphore,
) {
    val link = DataStore.connectionTestURL
    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int =
        TestInstance(profile, link, timeout).doTest(context, initialization)

}
