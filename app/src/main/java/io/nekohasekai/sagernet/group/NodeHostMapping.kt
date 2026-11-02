package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import moe.matsuri.nb4a.utils.JavaUtil.gson

/** Reversible offline address replacement. No DNS lookup and no guessed reverse table. */
object NodeHostMapping {
    private fun clearIdentity(bean: AbstractBean) {
        if (!bean.mappedIdentityFields.isNullOrBlank()) {
            val saved = runCatching {
                @Suppress("UNCHECKED_CAST")
                gson.fromJson(bean.mappedIdentityFields, Map::class.java) as Map<String, String>
            }.getOrDefault(emptyMap())
            saved.forEach { (name, inserted) ->
                runCatching {
                    val field = bean.javaClass.getField(name)
                    // Do not erase a later manual change of SNI or transport Host.
                    if (field.get(bean) == inserted) field.set(bean, "")
                }
            }
        }
        bean.mappedIdentityFields = ""
    }

    private fun pinIdentity(bean: AbstractBean, original: String) {
        if (SubscriptionDns.isIp(original)) return
        val inserted = linkedMapOf<String, String>()
        fun fill(name: String) {
            runCatching {
                val field = bean.javaClass.getField(name)
                if ((field.get(bean) as? String).isNullOrBlank()) {
                    field.set(bean, original)
                    inserted[name] = original
                }
            }
        }
        // Protocol classes with an explicit SNI field use the original dial hostname by default.
        fill("sni")
        if (bean is StandardV2RayBean && bean.type in setOf("ws", "http", "httpupgrade", "xhttp", "splithttp")) fill("host")
        if (bean.javaClass.simpleName == "TrojanGoBean") fill("host")
        bean.mappedIdentityFields = if (inserted.isEmpty()) "" else gson.toJson(inserted)
    }

    fun apply(bean: AbstractBean, hosts: Map<String, List<String>>, enabled: Boolean): Boolean {
        val before = listOf(bean.serverAddress, bean.mappedOriginalAddress, bean.mappedTargetAddress, bean.mappedIdentityFields)
        var original = bean.mappedOriginalAddress.orEmpty()
        val savedTarget = bean.mappedTargetAddress.orEmpty()
        if (original.isNotEmpty()) {
            if (bean.serverAddress != original && bean.serverAddress != savedTarget) {
                // Node edited by the user since the last mapping: the edited address is the new origin.
                clearIdentity(bean)
                original = bean.serverAddress
                bean.mappedOriginalAddress = ""
                bean.mappedTargetAddress = ""
            } else {
                clearIdentity(bean)
                bean.serverAddress = original
            }
        } else original = bean.serverAddress
        if (!enabled || SubscriptionDns.isIp(original)) {
            return before != listOf(bean.serverAddress, bean.mappedOriginalAddress, bean.mappedTargetAddress, bean.mappedIdentityFields)
        }
        var target = original
        val seen = hashSetOf<String>()
        var matched = false
        repeat(16) {
            if (SubscriptionDns.isIp(target)) return@repeat
            if (!seen.add(target)) return@repeat
            val key = SubscriptionDns.bestMatch(hosts, target) ?: return@repeat
            val values = hosts[key].orEmpty()
            if (values.isEmpty()) return@repeat
            matched = true
            // Display a concrete mapped address; runtime keeps the entire RRset for multi-IP hosts.
            target = values.firstOrNull { SubscriptionDns.isIp(it) && !it.contains(':') } ?: values.first()
        }
        if (matched && target != original) {
            bean.mappedOriginalAddress = original
            bean.mappedTargetAddress = target
            pinIdentity(bean, original)
            bean.serverAddress = target
        } else {
            bean.mappedOriginalAddress = ""
            bean.mappedTargetAddress = ""
        }
        return before != listOf(bean.serverAddress, bean.mappedOriginalAddress, bean.mappedTargetAddress, bean.mappedIdentityFields)
    }

    fun hasMultipleAddresses(bean: AbstractBean, hosts: Map<String, List<String>>): Boolean {
        var name = bean.mappedOriginalAddress.orEmpty()
        if (name.isEmpty()) return false
        repeat(16) {
            if (SubscriptionDns.isIp(name)) return false
            val values = hosts[SubscriptionDns.bestMatch(hosts, name)] ?: return false
            if (values.size > 1) return true
            name = values.firstOrNull() ?: return false
        }
        return false
    }
}
