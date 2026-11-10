package io.nekohasekai.sagernet.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.yaml.snakeyaml.Yaml
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
                val result = parse(payload)
                rule.cachedRuleCount = result.count
                rule.skippedRuleCount = result.skipped
                rule.domains = result.domains.joinToString("\n")
                rule.ip = result.ips.joinToString("\n")
                rule.lastUpdateError = ""
                rule.subscriptionState = "ready"
                SagerDatabase.rulesDao.updateRule(rule)
                result
            }
        } catch (error: Exception) {
            // Keep the card and URL. Existing cache remains usable; empty cache is explicitly pending.
            rule.subscriptionState = if (rule.cachedRuleCount > 0) "stale" else "waiting"
            rule.lastUpdateError = error.message.orEmpty().take(240)
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

    private fun parse(text: String): Result {
        val root = Yaml().load<Any?>(text) as? Map<*, *> ?: error("Invalid YAML document")
        val payload = root["payload"] as? List<*> ?: error("payload is missing")
        var count = 0
        var skipped = 0
        val reasons = linkedSetOf<String>()
        val domains = ArrayList<String>()
        val ips = ArrayList<String>()
        payload.forEach { raw ->
            val line = raw?.toString()?.trim().orEmpty()
            if (line.isBlank()) return@forEach
            val parts = line.split(',', limit = 3)
            when (parts.firstOrNull()?.trim()?.uppercase()) {
                "DOMAIN" -> parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { domains += "full:$it" }?.also { count++ }
                "DOMAIN-SUFFIX" -> parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { domains += "domain:$it" }?.also { count++ }
                "DOMAIN-KEYWORD" -> parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { domains += "keyword:$it" }?.also { count++ }
                "IP-CIDR", "IP-CIDR6" -> parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { ips += it }?.also { count++ }
                "IP-ASN", "PROCESS-NAME" -> {
                    // These are core-dependent and are not silently converted to another type.
                    skipped++; reasons += "unsupported ${parts.first().trim().uppercase()}"
                }
                else -> { skipped++; reasons += "unsupported rule" }
            }
        }
        return Result(count, skipped, reasons.joinToString(", "), domains, ips)
    }
}
