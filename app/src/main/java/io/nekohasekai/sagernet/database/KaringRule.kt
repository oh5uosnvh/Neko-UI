package io.nekohasekai.sagernet.database

import com.google.gson.annotations.SerializedName
import moe.matsuri.nb4a.utils.JavaUtil
import org.json.JSONArray
import org.json.JSONObject

/** JSON-compatible Karing preset rule. Room stores the v1 extension in RuleEntity.config. */
data class KaringRuleSetItem(
    val url: String = "",
    val type: String = "binary",
)

data class KaringRuleModel(
    @SerializedName("rule_set_build_in") val ruleSetBuildIn: List<String> = emptyList(),
    @SerializedName("rule_set_items") val ruleSetItems: List<KaringRuleSetItem> = emptyList(),
    @SerializedName("package") val packageNames: List<String> = emptyList(),
    @SerializedName(value = "process_name", alternate = ["processName"]) val processName: List<String> = emptyList(),
    @SerializedName("process_path") val processPath: List<String> = emptyList(),
    @SerializedName("process_dir") val processDir: List<String> = emptyList(),
    @SerializedName("domain_suffix") val domainSuffix: List<String> = emptyList(),
    val domain: List<String> = emptyList(),
    @SerializedName("domain_keyword") val domainKeyword: List<String> = emptyList(),
    @SerializedName("domain_regex") val domainRegex: List<String> = emptyList(),
    @SerializedName("ip_cidr") val ipCidr: List<String> = emptyList(),
    @SerializedName("network_type") val networkType: List<String> = emptyList(),
    val network: List<String> = emptyList(),
    @SerializedName("source_ip_cidr") val sourceIpCidr: List<String> = emptyList(),
    @SerializedName("source_ports") val sourcePorts: List<String> = emptyList(),
    @SerializedName("wifi_ssid") val wifiSsid: List<String> = emptyList(),
    @SerializedName("wifi_bssid") val wifiBssid: List<String> = emptyList(),
    val port: List<String> = emptyList(),
    @SerializedName("port_range") val portRange: List<String> = emptyList(),
    val protocol: List<String> = emptyList(),
    val outbound: String = "currentSelected",
    val name: String = "",
    val switch: Boolean = true,
    val or: Boolean = false,
    @Transient val rawPresetJson: String? = null,
)

data class KaringPreset(val version: Int = 1, val rules: List<KaringRuleModel> = emptyList())

object KaringRuleJson {
    fun parsePreset(json: String): KaringPreset {
        val rules = JSONObject(json).getJSONArray("rules")
        val preset = JavaUtil.gson.fromJson(json, KaringPreset::class.java)
        return preset.copy(rules = preset.rules.mapIndexed { i, rule ->
            val raw = rules.getJSONObject(i)
            require(!(raw.has("processName") && raw.has("process_name"))) { "Conflicting processName and process_name" }
            rule.copy(or = if (raw.has("or")) raw.getBoolean("or") else true, rawPresetJson = raw.toString())
        })
    }
    fun toJson(model: KaringRuleModel): String {
        val root = model.rawPresetJson?.let { JSONObject(it) } ?: JSONObject()
        root.remove("processName")
        val known = JSONObject(JavaUtil.gson.toJson(model))
        val oldItems = root.optJSONArray("rule_set_items")
        val mergedItems = JSONArray(model.ruleSetItems.map { item ->
            val old = (0 until (oldItems?.length() ?: 0)).mapNotNull { oldItems?.optJSONObject(it) }
                .firstOrNull { it.optString("url") == item.url }
            (old?.let { JSONObject(it.toString()) } ?: JSONObject()).apply {
                put("url", item.url)
                put("type", item.type)
            }
        })
        known.put("rule_set_items", mergedItems)
        known.keys().forEach { root.put(it, known.get(it)) }
        return root.toString()
    }
    fun toJson(preset: KaringPreset): String = JSONObject(JavaUtil.gson.toJson(preset)).apply {
        put("rules", JSONArray(preset.rules.map { JSONObject(toJson(it)) }))
    }.toString()
}

private fun String.karingLines(): List<String> = split('\n', ',').asSequence()
    .map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

private fun List<String>.karingLines(): String = map { it.trim() }
    .filter { it.isNotEmpty() }.distinct().joinToString("\n")

private fun JSONArray.toKaringList(): List<String> = (0 until length()).mapNotNull {
    optString(it).trim().takeIf(String::isNotEmpty)
}.distinct()

private fun JSONObject.karingList(key: String): List<String> = optJSONArray(key)?.toKaringList() ?: emptyList()

/**
 * Remove desktop-only matchers before compiling a preset for Android.
 * Karing shares presets with desktop clients, where process names and Wi-Fi
 * matchers are meaningful; Android routes use package/UID and rule-set data.
 */
fun KaringRuleModel.androidCompatible(): KaringRuleModel = copy(
    processName = emptyList(),
    processPath = emptyList(),
    processDir = emptyList(),
    networkType = emptyList(),
    wifiSsid = emptyList(),
    wifiBssid = emptyList(),
)

private fun KaringRuleModel.normalized(): KaringRuleModel = copy(
    ruleSetBuildIn = ruleSetBuildIn.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    ruleSetItems = ruleSetItems.filter { it.url.isNotBlank() }.distinctBy { it.url.trim() }
        .map { it.copy(url = it.url.trim()) },
    packageNames = packageNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    processName = processName.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    processPath = processPath.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    processDir = processDir.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    domainSuffix = domainSuffix.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    domain = domain.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    domainKeyword = domainKeyword.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    domainRegex = domainRegex.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    ipCidr = ipCidr.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    networkType = networkType.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    network = network.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    sourceIpCidr = sourceIpCidr.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    sourcePorts = sourcePorts.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    wifiSsid = wifiSsid.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    wifiBssid = wifiBssid.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    port = port.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    portRange = portRange.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    protocol = protocol.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    outbound = outbound.trim().ifEmpty { "currentSelected" },
)

/** Reads the named v1 envelope first; non-envelope config remains opaque custom config. */
fun RuleEntity.toKaringRule(): KaringRuleModel {
    val envelope = runCatching { JSONObject(config).optJSONObject(KARING_CONFIG_KEY) }.getOrNull()
    if (envelope != null) {
        val legacy = copy(config = "").toKaringRule()
        fun values(key: String, fallback: List<String>) = if (envelope.has(key)) envelope.karingList(key) else fallback
        return KaringRuleModel(
            ruleSetBuildIn = values("builtin_rule_sets", legacy.ruleSetBuildIn),
            ruleSetItems = if (!envelope.has("external_rule_sets")) legacy.ruleSetItems else envelope.karingList("external_rule_sets").map { url ->
                val items = envelope.optJSONArray("external_rule_set_items")
                val item = (0 until (items?.length() ?: 0)).mapNotNull { items?.optJSONObject(it) }
                    .firstOrNull { it.optString("url") == url }
                KaringRuleSetItem(url, item?.optString("type", "binary") ?: "binary")
            },
            // Older envelopes did not have packages/process fields. Prefer the
            // envelope when present, but retain the legacy mirror for those rows.
            packageNames = if (envelope.has("packages")) envelope.karingList("packages") else packages.toList(),
            processName = envelope.karingList("process_name"),
            processPath = envelope.karingList("process_path"),
            processDir = envelope.karingList("process_dir"),
            domain = values("domain", legacy.domain),
            domainSuffix = values("domain_suffix", legacy.domainSuffix),
            domainKeyword = values("domain_keyword", legacy.domainKeyword),
            domainRegex = values("domain_regex", legacy.domainRegex),
            ipCidr = values("ip_cidr", legacy.ipCidr),
            networkType = envelope.karingList("network_type"),
            network = values("network", legacy.network),
            sourceIpCidr = values("source_ip_cidr", legacy.sourceIpCidr),
            sourcePorts = values("source_ports", legacy.sourcePorts),
            wifiSsid = envelope.karingList("wifi_ssid"),
            wifiBssid = envelope.karingList("wifi_bssid"),
            protocol = values("protocol", legacy.protocol),
            port = values("ports", legacy.port),
            portRange = values("port_ranges", legacy.portRange),
            outbound = when (outbound) { -1L -> "direct"; -2L -> "block"; else -> "currentSelected" },
            name = name,
            switch = enabled,
            or = envelope.optString("logic", "or").equals("or", true),
            rawPresetJson = envelope.optJSONObject("preset_raw")?.toString(),
        ).normalized()
    }

    val domain = mutableListOf<String>()
    val suffix = mutableListOf<String>()
    val keyword = mutableListOf<String>()
    val regex = mutableListOf<String>()
    val builtIn = mutableListOf<String>()
    domains.karingLines().forEach { value ->
        when {
            value.startsWith("geosite:") || value.startsWith("acl:") -> builtIn += value
            value.startsWith("full:") -> domain += value.removePrefix("full:")
            value.startsWith("domain:") -> suffix += value.removePrefix("domain:")
            value.startsWith("keyword:") -> keyword += value.removePrefix("keyword:")
            value.startsWith("regexp:") -> regex += value.removePrefix("regexp:")
            else -> suffix += value
        }
    }
    val cidr = ip.karingLines().filterNot { it.startsWith("geoip:") }.toMutableList()
    ip.karingLines().filter { it.startsWith("geoip:") }.forEach { builtIn += it }
    val remote = ruleset.karingLines().filter { it.startsWith("rsip:") || it.startsWith("rssite:") }
        .map { KaringRuleSetItem(it.substringAfter(':')) }
    return KaringRuleModel(
        ruleSetBuildIn = builtIn, ruleSetItems = remote, packageNames = packages.toList(),
        processName = emptyList(), processPath = emptyList(), domainSuffix = suffix,
        domain = domain, domainKeyword = keyword, domainRegex = regex, ipCidr = cidr,
        networkType = emptyList(), network = network.karingLines(),
        sourceIpCidr = source.karingLines(), sourcePorts = sourcePort.karingLines(),
        wifiSsid = emptyList(), wifiBssid = emptyList(), port = port.karingLines().filterNot { ':' in it },
        portRange = port.karingLines().filter { ':' in it },
        protocol = protocol.karingLines(), name = name, switch = enabled,
        outbound = when (outbound) { -1L -> "direct"; -2L -> "block"; else -> "currentSelected" },
        or = false,
    ).normalized()
}

fun RuleEntity.karingImportMarker(): String? = runCatching {
    JSONObject(config).optJSONObject(KARING_CONFIG_KEY)?.optString("import_id")?.takeIf { it.isNotBlank() }
}.getOrNull()

/** Writes only the v1 envelope and mirrors simple fields for old readers. */
fun RuleEntity.applyKaringRule(input: KaringRuleModel): RuleEntity {
    val model = input.normalized()
    val root = if (config.isBlank()) JSONObject() else try { JSONObject(config) }
        catch (e: Exception) { throw IllegalArgumentException("Custom route config is not a JSON object; refusing to overwrite it", e) }
    // Start from the previous envelope so importer/UI extensions unknown to this
    // version survive an edit. The surrounding non-envelope config is opaque too.
    val envelope = (root.optJSONObject(KARING_CONFIG_KEY)?.let { JSONObject(it.toString()) } ?: JSONObject()).apply {
        put("logic", if (model.or) "or" else "and")
        put("builtin_rule_sets", JSONArray(model.ruleSetBuildIn))
        put("external_rule_sets", JSONArray(model.ruleSetItems.map { it.url }))
        val previousItems = optJSONArray("external_rule_set_items")
        put("external_rule_set_items", JSONArray(model.ruleSetItems.map { item ->
            val previous = (0 until (previousItems?.length() ?: 0)).mapNotNull { previousItems?.optJSONObject(it) }
                .firstOrNull { it.optString("url") == item.url }
            (previous?.let { JSONObject(it.toString()) } ?: JSONObject()).apply {
                put("url", item.url)
                put("type", item.type)
            }
        }))
        model.rawPresetJson?.let { put("preset_raw", JSONObject(it)) }
        put("packages", JSONArray(model.packageNames))
        put("domain", JSONArray(model.domain))
        put("process_name", JSONArray(model.processName))
        put("process_path", JSONArray(model.processPath))
        put("process_dir", JSONArray(model.processDir))
        put("domain_suffix", JSONArray(model.domainSuffix))
        put("domain_keyword", JSONArray(model.domainKeyword))
        put("domain_regex", JSONArray(model.domainRegex))
        put("ip_cidr", JSONArray(model.ipCidr))
        put("network_type", JSONArray(model.networkType))
        put("network", JSONArray(model.network))
        put("source_ip_cidr", JSONArray(model.sourceIpCidr))
        put("source_ports", JSONArray(model.sourcePorts))
        put("wifi_ssid", JSONArray(model.wifiSsid))
        put("wifi_bssid", JSONArray(model.wifiBssid))
        put("protocol", JSONArray(model.protocol))
        put("ports", JSONArray(model.port))
        put("port_ranges", JSONArray(model.portRange))
    }
    runCatching { JSONObject(config).optJSONObject(KARING_CONFIG_KEY)?.optString("import_id") }
        .getOrNull()?.takeIf { it.isNotBlank() }?.let { envelope.put("import_id", it) }
    root.put(KARING_CONFIG_KEY, envelope)
    config = root.toString()
    domains = (model.domain.map { "full:$it" } + model.domainSuffix.map { "domain:$it" } +
        model.domainKeyword.map { "keyword:$it" } + model.domainRegex.map { "regexp:$it" }).karingLines()
    ip = model.ipCidr.karingLines()
    port = (model.port + model.portRange).karingLines()
    network = model.network.karingLines()
    source = model.sourceIpCidr.karingLines()
    sourcePort = model.sourcePorts.karingLines()
    protocol = model.protocol.karingLines()
    ruleset = model.ruleSetItems.map { "rssite:${it.url}" }.karingLines()
    packages = model.packageNames.toSet()
    return this
}

const val KARING_CONFIG_KEY = "nekobox_route_v1"

fun RuleEntity.hasKaringEnvelope(): Boolean = runCatching {
    JSONObject(config).optJSONObject(KARING_CONFIG_KEY) != null
}.getOrDefault(false)

/** Old imports can contain an AND generated before missing preset logic defaulted to OR.
 * There is no reliable way to distinguish it from a later manual AND. */
fun RuleEntity.hasAmbiguousImportedAnd(): Boolean = runCatching {
    val env = JSONObject(config).optJSONObject(KARING_CONFIG_KEY) ?: return@runCatching false
    val raw = env.optJSONObject("preset_raw")
    !env.optString("import_id").isNullOrBlank() &&
        env.optString("logic").equals("and", true) &&
        !env.optBoolean("logic_reviewed", false) &&
        (raw == null || !raw.has("or"))
}.getOrDefault(false)

fun RuleEntity.markKaringLogicReviewed() {
    val root = JSONObject(config)
    root.getJSONObject(KARING_CONFIG_KEY).put("logic_reviewed", true)
    config = root.toString()
}
