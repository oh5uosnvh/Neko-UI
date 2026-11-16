package io.nekohasekai.sagernet.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Downloads only the fixed, built-in Clash sources and atomically replaces their cache. */
object RemoteRouteUpdater {
    data class Result(
        val count: Int,
        val skipped: Int,
        val skippedReason: String,
        val domains: List<String>,
        val ips: List<String>,
    )

    data class BatchResult(
        val succeeded: List<Pair<String, Int>>,
        val failed: List<Pair<String, String>>,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val inFlight = HashSet<String>()

    private fun begin(key: String): Boolean = synchronized(inFlight) { inFlight.add(key) }
    private fun end(key: String) = synchronized(inFlight) { inFlight.remove(key) }

    suspend fun update(key: String): Result = updateWith(key, SagerDatabase.rulesDao, client)

    /** Test seam keeps the production source allow-list and real HTTP/parse/update path intact. */
    internal suspend fun updateWith(key: String, dao: RuleEntity.Dao, httpClient: OkHttpClient): Result = withContext(Dispatchers.IO) {
        check(begin(key)) { "update already in progress" }
        try {
            val item = DefaultRouteCatalog.remoteItems.firstOrNull { it.key == key }
                ?: error("Unknown built-in route")
            val rule = dao.builtin(key) ?: error("Missing built-in route")
            try {
                val request = Request.Builder().url(item.remoteUrl!!).build()
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.body ?: error("Empty response body")
                    require(body.contentLength() <= RemoteRuleParser.MAX_BYTES) { "built-in rule file too large" }
                    val source = body.source()
                    val buffer = okio.Buffer()
                    while (true) {
                        val read = source.read(buffer, minOf(8192L, RemoteRuleParser.MAX_BYTES + 1L - buffer.size))
                        if (read == -1L) break
                        require(buffer.size <= RemoteRuleParser.MAX_BYTES) { "built-in rule file too large" }
                    }
                    val parsed = RemoteRuleParser.parse(buffer.readUtf8())
                    // Only replace cache fields after validation; concurrent edits to
                    // enabled/outbound/name/order must survive this network request.
                    dao.updateRemoteCache(rule.id, key,
                        parsed.domains.joinToString("\n"), parsed.ips.joinToString("\n"),
                        parsed.count, parsed.skipped, parsed.skippedReason.take(240))
                    parsed
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Keep domains/IPs and the source URL intact; only diagnostics change.
                val diagnostic = when {
                    error.message?.startsWith("HTTP ") == true -> "remote update failed: HTTP error"
                    error.message?.contains("too large") == true -> "remote update failed: file too large"
                    error.message?.contains("no usable") == true -> "remote update failed: no usable rules"
                    error.message?.contains("not YAML") == true || error.message?.contains("no payload") == true ->
                        "remote update failed: invalid rule document"
                    else -> "remote update failed"
                }
                dao.updateRemoteError(rule.id, key, diagnostic)
                throw error
            }
        } finally {
            end(key)
        }
    }

    /** Updates all built-ins sequentially so each result can be reported visibly. */
    suspend fun updateAllDetailed(): BatchResult = withContext(Dispatchers.IO) {
        val succeeded = ArrayList<Pair<String, Int>>()
        val failed = ArrayList<Pair<String, String>>()
        DefaultRouteCatalog.remoteItems.forEach { item ->
            try {
                succeeded += item.name to update(item.key).count
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failed += item.name to (error.message ?: "update failed")
            }
        }
        BatchResult(succeeded, failed)
    }

    /** Compatibility helper for callers that only need the failed names. */
    suspend fun updateAll(): List<Pair<String, String>> = updateAllDetailed().failed
}
