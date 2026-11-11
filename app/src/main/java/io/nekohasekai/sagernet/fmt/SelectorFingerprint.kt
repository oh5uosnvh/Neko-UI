package io.nekohasekai.sagernet.fmt

import java.security.MessageDigest

/**
 * Stable identity for a compiled selector. Inputs must contain only persisted
 * membership/policy/config data; runtime tags, ports and selected/default state
 * are deliberately excluded.
 */
internal object SelectorFingerprint {
    /** Hash every generated policy field, excluding only selector's initial choice. */
    fun policy(config: Map<String, Any?>): String {
        val normalized = config.mapValues { (key, value) ->
            if (key == "outbounds" && value is List<*>) value.map { outbound ->
                if (outbound is Map<*, *> && outbound["type"] == "selector" && outbound["tag"] == "proxy")
                    outbound.filterKeys { it != "default" }
                else outbound
            } else value
        }
        fun canonical(value: Any?): String = when (value) {
            null -> "null"
            is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") {
                canonical(it.key.toString()) + ":" + canonical(it.value)
            }
            is List<*> -> value.joinToString(",", "[", "]") { canonical(it) }
            is String -> "s${value.length}:$value"
            else -> value.toString()
        }
        return of(listOf(canonical(normalized)))
    }

    fun of(parts: Collection<String>): String {
        val canonical = parts.toList().sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
