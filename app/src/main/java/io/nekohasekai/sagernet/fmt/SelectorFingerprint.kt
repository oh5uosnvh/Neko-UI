package io.nekohasekai.sagernet.fmt

import java.security.MessageDigest

/**
 * Stable identity for a compiled selector. Inputs must contain only persisted
 * membership/policy/config data; runtime tags, ports and selected/default state
 * are deliberately excluded.
 */
internal object SelectorFingerprint {
    /** Hash every generated policy field, excluding only selector's initial choice. */
    fun policy(config: Map<String, Any?>, runtimePorts: Map<Int, String> = emptyMap()): String {
        // Only ports allocated by THIS build may be normalized. Persisted listener,
        // remote server and user-route ports remain reload boundaries.
        fun normalize(value: Any?, key: String = ""): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { it.key to normalize(it.value, it.key.toString()) }
            is List<*> -> value.map { normalize(it, key) }
            is Number -> if (key == "server_port" || key == "listen_port")
                runtimePorts[value.toInt()]?.let { "runtime-port:$it" } ?: value else value
            else -> value
        }
        @Suppress("UNCHECKED_CAST")
        val normalizedPorts = normalize(config) as Map<String, Any?>
        val normalized = normalizedPorts.mapValues { (key, value) ->
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
