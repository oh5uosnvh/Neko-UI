package io.nekohasekai.sagernet.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Fixed, allow-listed Clash payload updater; arbitrary URL import is deliberately unsupported. */
object RemoteRouteUpdater {
    data class Result(
        val count: Int,
        val skipped: Int,
        val skippedReason: String,
        val domains: List<String>,
        val ips: List<String>,
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun update(key: String): Result = withContext(Dispatchers.IO) {
        val item = DefaultRouteCatalog.remoteItems.firstOrNull { it.key == key }
            ?: error("Unknown built-in route")
        val rule = SagerDatabase.rulesDao.builtin(key) ?: error("Missing built-in route")
        try {
            val request = Request.Builder().url(item.remoteUrl!!).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val payload = response.body?.string().orEmpty()
                val result = RemoteRuleParser.parse(payload)
                rule.cachedRuleCount = result.count
                rule.skippedRuleCount = result.skipped
                rule.domains = result.domains.joinToString("\n")
                rule.ip = result.ips.joinToString("\n")
                rule.lastUpdateError = result.skippedReason.take(240)
                rule.subscriptionState = "ready"
                SagerDatabase.rulesDao.updateRule(rule)
                result
            }
        } catch (error: Exception) {
            // Keep the card and URL. Existing cache remains usable; empty cache is explicitly pending.
            rule.subscriptionState = if (rule.cachedRuleCount > 0) "stale" else "waiting"
            // Keep diagnostics category-only: no response body, URL, or exception internals.
            rule.lastUpdateError = when {
                error.message?.startsWith("HTTP ") == true -> "remote update failed: HTTP error"
                error.message?.contains("too large") == true -> "remote update failed: file too large"
                error.message?.contains("no usable") == true -> "remote update failed: no usable rules"
                error.message?.contains("not YAML") == true || error.message?.contains("no payload") == true ->
                    "remote update failed: invalid rule document"
                else -> "remote update failed"
            }
            SagerDatabase.rulesDao.updateRule(rule)
            throw error
        }
    }

    suspend fun updateAll(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        buildList {
            DefaultRouteCatalog.remoteItems.forEach { item ->
                try { update(item.key) } catch (error: Exception) {
                    add(item.name to (error.message ?: "update failed"))
                }
            }
        }
    }
}
