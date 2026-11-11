package io.nekohasekai.sagernet.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream

/** Both catalog and user rule sources share bounded downloads and transactional cache publication. */
object RemoteRouteUpdater {
    data class Result(val count: Int, val skipped: Int, val skippedReason: String,
                      val domains: List<String>, val ips: List<String>)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun update(key: String): Result {
        val rule = withContext(Dispatchers.IO) { SagerDatabase.rulesDao.builtin(key) }
            ?: error("Missing built-in route")
        return updateRule(rule.id)
    }

    /** Only cache/status fields of a fresh row are touched. Edited/deleted source results are discarded. */
    internal fun publish(db: SagerDatabase, snapshot: RuleEntity, result: Result?, error: Exception?) {
        db.runInTransaction {
            val fresh = db.rulesDao().getById(snapshot.id) ?: return@runInTransaction
            check(fresh.sourceUrl == snapshot.sourceUrl && fresh.builtinKey == snapshot.builtinKey) {
                "规则链接已变更，本次下载结果已丢弃"
            }
            if (result != null) {
                if (fresh.builtinKey.startsWith("remote_")) {
                    // Historical catalog cache lives in domains/ip. Preserve that storage contract.
                    fresh.domains = result.domains.joinToString("\n")
                    fresh.ip = result.ips.joinToString("\n")
                } else {
                    fresh.remoteDomains = result.domains.joinToString("\n")
                    fresh.remoteIps = result.ips.joinToString("\n")
                }
                fresh.cachedRuleCount = result.count; fresh.skippedRuleCount = result.skipped
                fresh.subscriptionState = "ready"
                fresh.lastUpdateError = result.skippedReason.take(240)
            } else {
                fresh.subscriptionState = if (fresh.cachedRuleCount > 0) "stale" else "waiting"
                fresh.lastUpdateError = error?.message.orEmpty().take(240)
            }
            db.rulesDao().updateRule(fresh)
        }
    }

    private val updateLock = Mutex()

    suspend fun updateRule(id: Long): Result = updateLock.withLock {
        withContext(Dispatchers.IO) { updateLocked(id) }
    }

    private fun updateLocked(id: Long): Result {
        val snapshot = SagerDatabase.rulesDao.getById(id) ?: error("规则已删除")
        try {
            val url = RemoteRuleParser.url(snapshot.sourceUrl)
            val request = Request.Builder().url(url).build()
            val result = client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("规则响应为空")
                require(body.contentLength() <= RemoteRuleParser.MAX_BYTES) { "规则文件超过 4 MiB" }
                val bytes = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        require(bytes.size() + n <= RemoteRuleParser.MAX_BYTES) { "规则文件超过 4 MiB" }
                        bytes.write(buffer, 0, n)
                    }
                }
                RemoteRuleParser.parse(bytes.toString("UTF-8"))
            }
            publish(SagerDatabase.instance, snapshot, result, null)
            return result
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            try { publish(SagerDatabase.instance, snapshot, null, error) } catch (_: Exception) { }
            throw error
        }
    }

    suspend fun updateAll(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val snapshot = SagerDatabase.rulesDao.allRules().filter { it.sourceUrl.isNotBlank() }
        buildList {
            snapshot.forEach { rule ->
                try { updateRule(rule.id) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    add(rule.displayName() to (error.message ?: "更新失败"))
                }
            }
        }
    }
}
