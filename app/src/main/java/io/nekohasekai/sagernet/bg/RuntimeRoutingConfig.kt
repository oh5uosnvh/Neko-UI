package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.fmt.SelectorFingerprint

/** Canonical comparison excludes only native routing snapshot fields. */
internal object RuntimeRoutingConfig {
    fun compatible(old: Map<String, Any?>, oldPorts: Map<Int, String>, fresh: Map<String, Any?>, newPorts: Map<Int, String>): Boolean {
        fun static(config: Map<String, Any?>): Map<String, Any?> = config.mapValues { (key, value) ->
            if (key == "route" && value is Map<*, *>) value.filterKeys { it !in setOf("rules", "rule_set", "final") }
            else if (key == "dns" && value is Map<*, *>) value.filterKeys { it !in setOf("rules", "final", "strategy") }
            else value
        }
        return SelectorFingerprint.policy(static(old), oldPorts) == SelectorFingerprint.policy(static(fresh), newPorts)
    }

    @Suppress("UNCHECKED_CAST")
    fun remap(config: Map<String, Any?>, newPorts: Map<Int, String>, oldPorts: Map<Int, String>): Map<String, Any?> {
        val byIdentity = oldPorts.entries.associate { it.value to it.key }
        fun visit(value: Any?, key: String = ""): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { it.key to visit(it.value, it.key.toString()) }
            is List<*> -> value.map { visit(it, key) }
            is Number -> if (key == "server_port" || key == "listen_port")
                newPorts[value.toInt()]?.let { byIdentity[it] ?: error("Runtime port identity missing") } ?: value else value
            else -> value
        }
        return visit(config) as Map<String, Any?>
    }
}
