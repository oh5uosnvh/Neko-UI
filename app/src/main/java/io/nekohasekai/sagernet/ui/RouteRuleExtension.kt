package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.toKaringRule
import org.json.JSONArray
import org.json.JSONObject

internal fun String.linesClean(): List<String> = split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** The UI-only v1 envelope. It deliberately uses RuleEntity.config, not a Room column. */
data class RouteRuleExtension(
    var logic: String = "or",
    var builtin: List<String> = emptyList(),
    var external: List<String> = emptyList(),
    var packages: List<String> = emptyList(),
    var domain: List<String> = emptyList(),
    var domainSuffix: List<String> = emptyList(),
    var domainKeyword: List<String> = emptyList(),
    var domainRegex: List<String> = emptyList(),
    var ipCidr: List<String> = emptyList(),
    var networkType: List<String> = emptyList(),
    var wifiSsid: List<String> = emptyList(),
    var wifiBssid: List<String> = emptyList(),
    var protocols: List<String> = emptyList(),
    var ports: List<String> = emptyList(),
    var portRanges: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("logic", if (logic == "and") "and" else "or")
        put("builtin_rule_sets", JSONArray(builtin))
        put("external_rule_sets", JSONArray(external))
        put("packages", JSONArray(packages))
        put("domain", JSONArray(domain))
        put("domain_suffix", JSONArray(domainSuffix))
        put("domain_keyword", JSONArray(domainKeyword))
        put("domain_regex", JSONArray(domainRegex))
        put("ip_cidr", JSONArray(ipCidr))
        put("network_type", JSONArray(networkType))
        put("wifi_ssid", JSONArray(wifiSsid))
        put("wifi_bssid", JSONArray(wifiBssid))
        put("protocol", JSONArray(protocols))
        put("ports", JSONArray(ports))
        put("port_ranges", JSONArray(portRanges))
    }

    fun writeTo(rule: RuleEntity) {
        val root = if (rule.config.isBlank()) JSONObject() else try { JSONObject(rule.config) }
            catch (e: Exception) { throw IllegalArgumentException("Custom route config is not a JSON object; refusing to overwrite it", e) }
        // Merge only editor-owned keys. Import metadata, process_name/path/dir,
        // and future envelope keys must survive a UI round trip unchanged.
        val envelope = root.optJSONObject(ROOT_KEY)?.let { JSONObject(it.toString()) } ?: JSONObject()
        val edited = toJson()
        edited.keys().forEach { key -> envelope.put(key, edited.get(key)) }
        root.put(ROOT_KEY, envelope)
        rule.config = root.toString()
    }

    companion object {
        const val ROOT_KEY = "nekobox_route_v1"
        fun from(rule: RuleEntity): RouteRuleExtension {
            val model = rule.toKaringRule()
            return RouteRuleExtension(
                logic = if (model.or) "or" else "and",
                builtin = model.ruleSetBuildIn, external = model.ruleSetItems.map { it.url },
                packages = model.packageNames, domain = model.domain, domainSuffix = model.domainSuffix,
                domainKeyword = model.domainKeyword, domainRegex = model.domainRegex, ipCidr = model.ipCidr,
                networkType = model.networkType, wifiSsid = model.wifiSsid, wifiBssid = model.wifiBssid,
                protocols = model.protocol, ports = model.port, portRanges = model.portRange,
            )
        }
    }
}
