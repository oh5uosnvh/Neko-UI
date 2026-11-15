package io.nekohasekai.sagernet.group

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Lossless outbound extraction, separate from importing an application's full configuration. */
internal class SingBoxJsonImportException : IllegalArgumentException(
    "Invalid sing-box JSON or unresolved outbound/DNS dependency; no nodes were imported"
)

internal object SingBoxJsonImport {
    data class Profile(val name: String, val config: String, val context: String = "", val full: Boolean = false)
    data class Prepared(val outbound: String, val outbounds: List<String>, val dnsServers: List<String>)
    private val auxiliary = setOf("direct", "block", "dns")

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString?.takeIf { it.isNotBlank() }

    /** null means a legacy JSON format, not a sing-box document. */
    fun parse(text: String): List<Profile>? {
        val value = JsonParser.parseString(text)
        if (value.isJsonArray) {
            val entries = value.asJsonArray
            if (entries.size() > 0 && entries.all { it.isJsonObject && it.asJsonObject.string("type") != null }) {
                return document(JsonObject().apply { add("outbounds", entries) })
            }
            return null
        }
        if (!value.isJsonObject) return null
        val json = value.asJsonObject
        val full = listOf("endpoints", "services").any { key ->
            json.has(key) && (!json[key].isJsonArray || json[key].asJsonArray.size() > 0)
        } || json.getAsJsonArray("inbounds")?.any {
            it.isJsonObject && it.asJsonObject.string("type") == "cloudflared"
        } == true
        if (full) return listOf(Profile(json.string("tag") ?: "sing-box 1.14 config", json.toString(), full = true))
        if (json.has("outbounds") && json.string("type") == null) return document(json)
        if (json.string("type") != null) {
            // A standalone typed outbound must never enter a legacy method/server parser.
            val context = JsonObject().apply { add("outbounds", JsonArray().apply { add(json) }) }.toString()
            val closure = prepare(json.toString(), context, "", json.string("tag") ?: "__selected_")
            return listOf(Profile(json.string("tag") ?: json.string("type")!!, json.toString(), capture(closure)))
        }
        return null
    }

    private fun document(json: JsonObject): List<Profile> {
        require(json["outbounds"]?.isJsonArray == true) { "Invalid sing-box outbounds array" }
        val entries = json.getAsJsonArray("outbounds")
        require(entries.all { it.isJsonObject && it.asJsonObject.string("type") != null }) {
            "Invalid sing-box outbound entry (type required)"
        }
        val seen = hashSetOf<String>()
        entries.forEach { entry -> entry.asJsonObject.string("tag")?.let {
            require(seen.add(it)) { "Duplicate sing-box outbound tag" }
        } }
        val context = json.toString()
        // Recognized auxiliary entries are not cards, but their references must
        // still be validated rather than hiding malformed source graphs.
        entries.forEach { entry ->
            val outbound = entry.asJsonObject
            prepare(outbound.toString(), context, "", outbound.string("tag") ?: "__selected_")
        }
        return entries.mapIndexedNotNull { index, entry ->
            val outbound = entry.asJsonObject
            if (outbound.string("type") in auxiliary) null else {
                // Validate references now, before a partial subscription update can be committed.
                val closure = prepare(outbound.toString(), context, "", outbound.string("tag") ?: "__selected_")
                Profile(outbound.string("tag") ?: "${outbound.string("type")} #${index + 1}", closure.outbound, capture(closure))
            }
        }
    }

    private fun capture(closure: Prepared): String {
        if (closure.outbounds.isEmpty() && closure.dnsServers.isEmpty()) {
            val root = JsonParser.parseString(closure.outbound).asJsonObject
            if (!root.has("detour") && !root.has("outbounds") && !root.has("domain_resolver")) return ""
        }
        return JsonObject().apply {
            JsonParser.parseString(closure.outbound).asJsonObject.string("tag")?.let { addProperty("_neko_root", it) }
            add("outbounds", JsonArray().apply {
                add(JsonParser.parseString(closure.outbound))
                closure.outbounds.forEach { add(JsonParser.parseString(it)) }
            })
            if (closure.dnsServers.isNotEmpty()) add("dns", JsonObject().apply {
                add("servers", JsonArray().apply { closure.dnsServers.forEach { add(JsonParser.parseString(it)) } })
            })
        }.toString()
    }

    /** Only schema-defined references are rewritten; credentials/unknown fields are not touched. */
    fun prepare(raw: String, context: String, namespace: String, rootTag: String): Prepared {
        val root = JsonParser.parseString(raw).asJsonObject.deepCopy()
        if (context.isBlank()) return Prepared(root.toString(), emptyList(), emptyList())
        val source = JsonParser.parseString(context).asJsonObject
        val defaultResolver = source.getAsJsonObject("route")?.get("default_domain_resolver")
        fun dialingOptions(entry: JsonObject): JsonObject = entry.deepCopy().apply {
            if (!has("domain_resolver") && has("server") && defaultResolver != null) {
                add("domain_resolver", defaultResolver.deepCopy())
            }
        }
        val out = linkedMapOf<String, JsonObject>()
        source.getAsJsonArray("outbounds")?.forEach { value ->
            val entry = dialingOptions(value.asJsonObject)
            entry.string("tag")?.let { out[it] = entry }
        }
        val dns = linkedMapOf<String, JsonObject>()
        source.getAsJsonObject("dns")?.getAsJsonArray("servers")?.forEach { value ->
            require(value.isJsonObject) { "Invalid sing-box DNS server" }
            val entry = value.asJsonObject
            entry.string("tag")?.let {
                require(!dns.containsKey(it)) { "Duplicate sing-box DNS tag" }
                dns[it] = entry
            }
        }
        val originalRoot = source.string("_neko_root") ?: root.string("tag")
        // The source default resolver is part of node dialing, not source traffic routing.
        if (!root.has("domain_resolver") && root.has("server")) {
            source.getAsJsonObject("route")?.get("default_domain_resolver")?.let {
                root.add("domain_resolver", it.deepCopy())
            }
        }
        val chosenOut = linkedSetOf<String>()
        val chosenDns = linkedSetOf<String>()
        val pending = java.util.ArrayDeque<Pair<Boolean, String>>()
        fun ref(isDns: Boolean, tag: String) {
            if (tag.isBlank()) return
            if (!isDns && tag == originalRoot) return
            val entries = if (isDns) dns else out
            require(entries.containsKey(tag)) { "Unresolved sing-box ${if (isDns) "DNS" else "outbound"} reference" }
            val selected = if (isDns) chosenDns else chosenOut
            if (selected.add(tag)) pending.add(isDns to tag)
        }
        fun resolver(value: JsonElement?, callback: (String) -> Unit) {
            when {
                value == null || value.isJsonNull -> Unit
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> callback(value.asString)
                value.isJsonObject -> value.asJsonObject.string("server")?.let(callback)
                else -> error("Invalid sing-box domain resolver")
            }
        }
        fun references(entry: JsonObject, isDns: Boolean) {
            entry.string("detour")?.let { ref(false, it) }
            resolver(entry["domain_resolver"]) { ref(true, it) }
            if (isDns) {
                // DNS batch servers (and future composites) use DNS tags, not outbound tags.
                entry.getAsJsonArray("servers")?.forEach { ref(true, it.asString) }
                // Legacy DNS server address_resolver also refers to a DNS server.
                entry.string("address_resolver")?.let { ref(true, it) }
            } else {
                entry.getAsJsonArray("outbounds")?.forEach { ref(false, it.asString) }
                entry.string("default")?.let { ref(false, it) }
            }
        }
        references(root, false)
        while (pending.isNotEmpty()) {
            val (isDns, tag) = pending.removeFirst()
            references((if (isDns) dns else out).getValue(tag), isDns)
        }
        fun outTag(tag: String) = when {
            tag.isBlank() -> tag
            tag == originalRoot -> rootTag
            namespace.isEmpty() -> tag
            else -> namespace + "o_" + tag
        }
        fun dnsTag(tag: String) = if (tag.isBlank() || namespace.isEmpty()) tag else namespace + "d_" + tag
        fun rewrite(entry: JsonObject, isDns: Boolean): JsonObject {
            val result = entry.deepCopy()
            result.string("tag")?.let { result.addProperty("tag", if (isDns) dnsTag(it) else outTag(it)) }
            result.string("detour")?.let { result.addProperty("detour", outTag(it)) }
            resolver(result["domain_resolver"]) { tag ->
                if (result["domain_resolver"].isJsonObject) result["domain_resolver"].asJsonObject.addProperty("server", dnsTag(tag))
                else result.addProperty("domain_resolver", dnsTag(tag))
            }
            if (isDns) {
                result.getAsJsonArray("servers")?.let { list -> result.add("servers", JsonArray().apply {
                    list.forEach { add(dnsTag(it.asString)) }
                }) }
                result.string("address_resolver")?.let { result.addProperty("address_resolver", dnsTag(it)) }
            } else {
                result.getAsJsonArray("outbounds")?.let { list -> result.add("outbounds", JsonArray().apply {
                    list.forEach { add(outTag(it.asString)) }
                }) }
                result.string("default")?.let { result.addProperty("default", outTag(it)) }
            }
            return result
        }
        return Prepared(rewrite(root, false).apply { addProperty("tag", rootTag) }.toString(),
            chosenOut.map { rewrite(out.getValue(it), false).toString() },
            chosenDns.map { rewrite(dns.getValue(it), true).toString() })
    }
}
