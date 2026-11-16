package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.isIpAddress
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Parses the allow-listed built-in Clash payload without making one bad line fatal. */
internal object RemoteRuleParser {
    const val MAX_BYTES = 4 * 1024 * 1024

    fun parse(text: String): RemoteRouteUpdater.Result {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "built-in rule file too large" }
        val options = LoaderOptions().apply {
            maxAliasesForCollections = 0
            codePointLimit = MAX_BYTES
        }
        val root = Yaml(SafeConstructor(options)).load<Any?>(text) as? Map<*, *>
            ?: error("built-in rule document is not YAML")
        val payload = root["payload"] as? List<*>
            ?: error("built-in rule document has no payload")
        val domains = linkedSetOf<String>()
        val ips = linkedSetOf<String>()
        val reasons = linkedSetOf<String>()
        var skipped = 0

        fun skip(reason: String) {
            skipped++
            // Reasons are fixed categories, never a URL or source payload.
            reasons += reason
        }

        fun addDomain(raw: String, kind: String): Boolean {
            val value = raw.trim().lowercase().trimEnd('.')
            if (value.isBlank() || value.length > 253 ||
                value.any { it.isWhitespace() || it in ",/:" } ||
                value.startsWith(".") || value.endsWith(".")
            ) return false
            domains += "$kind:$value"
            return true
        }

        fun addIp(raw: String): Boolean {
            val value = raw.trim()
            val slash = value.lastIndexOf('/')
            if (slash <= 0 || slash == value.lastIndex) return false
            val address = value.substring(0, slash)
            val prefix = value.substring(slash + 1).toIntOrNull() ?: return false
            if (!address.isIpAddress()) return false
            val max = if (address.contains(':')) 128 else 32
            if (prefix !in 0..max) return false
            ips += value
            return true
        }

        payload.forEach { raw ->
            if (raw !is String) {
                skip("invalid record")
                return@forEach
            }
            val line = raw.trim()
            if (line.isBlank()) return@forEach
            val fields = line.split(',').map(String::trim)
            if (fields.size == 1) {
                val ok = when {
                    '/' in line -> addIp(line)
                    line.startsWith("+.") -> addDomain(line.removePrefix("+."), "domain")
                    line.startsWith("*.") -> addDomain(line.removePrefix("*."), "domain")
                    line.startsWith('.') -> addDomain(line.removePrefix("."), "domain")
                    else -> addDomain(line, "full")
                }
                if (!ok) skip("invalid record")
                return@forEach
            }
            val kind = fields.first().uppercase()
            val value = fields.getOrNull(1)
            if (value == null) {
                skip("invalid $kind")
                return@forEach
            }
            val ok = when (kind) {
                "DOMAIN" -> addDomain(value, "full")
                "DOMAIN-SUFFIX" -> addDomain(value, "domain")
                "DOMAIN-KEYWORD" -> addDomain(value, "keyword")
                "IP-CIDR", "IP-CIDR6" -> addIp(value)
                else -> {
                    skip("unsupported $kind")
                    true
                }
            }
            if (!ok) skip("invalid $kind")
        }
        require(domains.isNotEmpty() || ips.isNotEmpty()) {
            "built-in rule document has no usable rules"
        }
        return RemoteRouteUpdater.Result(
            domains.size + ips.size,
            skipped,
            reasons.joinToString(", "),
            domains.toList(),
            ips.toList(),
        )
    }
}
