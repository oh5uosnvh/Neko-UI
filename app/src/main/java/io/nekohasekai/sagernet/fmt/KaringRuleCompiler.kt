package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.KaringRuleModel
import io.nekohasekai.sagernet.database.KaringRuleSetItem
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.toKaringRule
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.RuleSet
import moe.matsuri.nb4a.utils.JavaUtil
import java.net.URI
import org.json.JSONObject

interface KaringRuleSetResolver {
    fun resolveBuiltIn(code: String): KaringRuleSetRef?
    fun resolveRemote(item: KaringRuleSetItem): KaringRuleSetRef
    fun isKnownBuiltIn(code: String): Boolean = resolveBuiltIn(code) != null
}

data class KaringRuleSetRef(val tag: String, val format: String, val pathOrUrl: String, val remote: Boolean = false)

object KaringRuleSetValidation {
    private val builtin = Regex("^(geosite|geoip|acl):[A-Za-z0-9][A-Za-z0-9@._-]{0,127}$")
    fun builtIn(value: String): Boolean = builtin.matches(value.trim())
    fun remote(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            (uri.path.endsWith(".srs", true) || uri.path.endsWith(".json", true))
    } catch (_: Exception) { false }

    fun port(value: String): Int? = value.trim().takeIf { it.matches(Regex("[0-9]{1,5}")) }
        ?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun portRange(value: String): String? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null
        val start = port(parts[0]) ?: return null
        val end = port(parts[1]) ?: return null
        return "$start:$end".takeIf { start <= end }
    }
}

/** Compiles the v1 envelope without relying on sing-box's address-field OR semantics. */
object KaringRuleCompiler {
    /** Conditions that the Android route backend cannot evaluate safely. */
    private fun unsupportedConditions(model: KaringRuleModel): List<String> = buildList {
        if (model.processName.any { it.isNotBlank() }) add("process_name")
        if (model.processPath.any { it.isNotBlank() }) add("process_path")
        if (model.processDir.any { it.isNotBlank() }) add("process_dir")
        if (model.networkType.any { it.isNotBlank() }) add("network_type")
        if (model.wifiSsid.any { it.isNotBlank() }) add("wifi_ssid")
        if (model.wifiBssid.any { it.isNotBlank() }) add("wifi_bssid")
    }

    private val knownPresetKeys = setOf(
        "rule_set_build_in", "rule_set_items", "package", "process_name", "processName",
        "process_path", "process_dir", "domain_suffix", "domain", "domain_keyword",
        "domain_regex", "ip_cidr", "network_type", "network", "source_ip_cidr",
        "source_ports", "wifi_ssid", "wifi_bssid", "port", "port_range", "protocol",
        "outbound", "name", "switch", "or"
    )

    private fun unknownPresetKeys(model: KaringRuleModel): List<String> {
        val raw = model.rawPresetJson ?: return emptyList()
        val json = try { JSONObject(raw) } catch (error: Exception) {
            throw IllegalArgumentException("Invalid Karing raw rule JSON", error)
        }
        return json.keys().asSequence().filterNot { it in knownPresetKeys }.toList()
    }

    fun hasMatchCondition(model: KaringRuleModel, userIds: List<Int> = emptyList()): Boolean =
        model.ruleSetBuildIn.isNotEmpty() || model.ruleSetItems.isNotEmpty() ||
            model.packageNames.isNotEmpty() || model.domain.isNotEmpty() || model.domainSuffix.isNotEmpty() ||
            model.domainKeyword.isNotEmpty() || model.domainRegex.isNotEmpty() || model.ipCidr.isNotEmpty() ||
            model.network.isNotEmpty() || model.sourceIpCidr.isNotEmpty() || model.sourcePorts.isNotEmpty() ||
            model.port.isNotEmpty() || model.portRange.isNotEmpty() || model.protocol.isNotEmpty() ||
            userIds.isNotEmpty()

    fun compile(entity: RuleEntity, resolver: KaringRuleSetResolver, outbound: String,
                ruleSets: MutableList<RuleSet> = mutableListOf(), userIds: List<Int> = emptyList()): SingBoxOptions.Rule =
        compile(entity.toKaringRule(), resolver, outbound, ruleSets, userIds)

    fun compile(model: KaringRuleModel, resolver: KaringRuleSetResolver, outbound: String,
                ruleSets: MutableList<RuleSet> = mutableListOf(), userIds: List<Int> = emptyList()): SingBoxOptions.Rule {
        val normalized = model.copy(
            ruleSetBuildIn = model.ruleSetBuildIn.map(String::trim).filter(String::isNotEmpty).distinct(),
            ruleSetItems = model.ruleSetItems.map { it.copy(url = it.url.trim()) }
                .filter { it.url.isNotEmpty() }.distinctBy { it.url },
            packageNames = model.packageNames.map(String::trim).filter(String::isNotEmpty).distinct(),
            processName = model.processName.map(String::trim).filter(String::isNotEmpty).distinct(),
            processPath = model.processPath.map(String::trim).filter(String::isNotEmpty).distinct(),
            processDir = model.processDir.map(String::trim).filter(String::isNotEmpty).distinct(),
            networkType = model.networkType.map(String::trim).filter(String::isNotEmpty).distinct(),
            wifiSsid = model.wifiSsid.map(String::trim).filter(String::isNotEmpty).distinct(),
            wifiBssid = model.wifiBssid.map(String::trim).filter(String::isNotEmpty).distinct(),
            port = model.port.map(String::trim).filter(String::isNotEmpty).distinct(),
            portRange = model.portRange.map(String::trim).filter(String::isNotEmpty).distinct(),
            sourcePorts = model.sourcePorts.map(String::trim).filter(String::isNotEmpty).distinct(),
        )
        require(outbound.isNotBlank()) { "Karing route has no valid outbound" }
        val unknown = unknownPresetKeys(normalized)
        require(unknown.isEmpty()) {
            "Unsupported/unknown Karing condition(s) for Android backend: ${unknown.joinToString(", ")}"
        }
        val unsupported = unsupportedConditions(normalized)
        require(unsupported.isEmpty()) {
            "Unsupported Karing condition(s) for Android backend: ${unsupported.joinToString(", ")}"
        }
        normalized.packageNames.forEach { packageName ->
            require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
                "Invalid Android package name: $packageName"
            }
        }
        val setTags = mutableListOf<String>()
        val setTagSeen = mutableSetOf<String>()
        normalized.ruleSetBuildIn.forEach { code ->
            require(KaringRuleSetValidation.builtIn(code)) { "Invalid Karing built-in rule-set: $code" }
            val ref = resolver.resolveBuiltIn(code) ?: error("Unknown Karing built-in rule-set: $code")
            if (setTagSeen.add(ref.tag)) {
                setTags += ref.tag
                ruleSets += RuleSet().apply {
                    type = if (ref.remote) "remote" else "local"; tag = ref.tag; format = ref.format
                    if (ref.remote) url = ref.pathOrUrl else path = ref.pathOrUrl
                }
            }
        }
        normalized.ruleSetItems.forEach { item ->
            require(KaringRuleSetValidation.remote(item.url)) { "Karing remote rule-set must be HTTPS .srs/.json" }
            val ref = resolver.resolveRemote(item)
            if (setTagSeen.add(ref.tag)) {
                setTags += ref.tag
                ruleSets += RuleSet().apply {
                    type = "remote"; tag = ref.tag; format = ref.format; url = ref.pathOrUrl; update_interval = "1d"
                }
            }
        }

        val atoms = mutableListOf<SingBoxOptions.Rule_DefaultOptions>()
        fun atom(block: SingBoxOptions.Rule_DefaultOptions.() -> Unit) {
            val candidate = SingBoxOptions.Rule_DefaultOptions().apply(block)
            if (!candidate.checkEmpty()) atoms += candidate
        }
        // Every populated field is an atom. This is important: sing-box ORs the
        // address fields of one default rule, even when Karing requested AND.
        normalized.domain.takeIf { it.isNotEmpty() }?.let { values -> atom { domain = values } }
        normalized.domainSuffix.takeIf { it.isNotEmpty() }?.let { values -> atom { domain_suffix = values } }
        normalized.domainKeyword.takeIf { it.isNotEmpty() }?.let { values -> atom { domain_keyword = values } }
        normalized.domainRegex.takeIf { it.isNotEmpty() }?.let { values -> atom { domain_regex = values } }
        normalized.ipCidr.takeIf { it.isNotEmpty() }?.let { values -> atom { ip_cidr = values } }
        // ConfigBuilder resolves Android packages to UIDs. Emitting both package_name
        // and user_id makes the same criterion appear twice and changes logical groups.
        // Keep package_name only as a fallback for unresolved IDs.
        if (userIds.isEmpty()) {
            normalized.packageNames.takeIf { it.isNotEmpty() }?.let { values -> atom { package_name = values } }
        }
        normalized.network.takeIf { it.isNotEmpty() }?.let { values -> atom { network = values } }
        // process_name/process_path/process_dir, network_type and Wi-Fi matchers are
        // retained in the envelope but rejected above: silently dropping them would
        // broaden a rule and could release traffic to the wrong outbound.
        normalized.sourceIpCidr.takeIf { it.isNotEmpty() }?.let { values -> atom { source_ip_cidr = values } }
        normalized.sourcePorts.takeIf { it.isNotEmpty() }?.let { values ->
            atom { source_port = values.map { value -> KaringRuleSetValidation.port(value) ?: error("Invalid Karing source port: $value") } }
        }
        normalized.protocol.takeIf { it.isNotEmpty() }?.let { values -> atom { protocol = values } }
        normalized.port.takeIf { it.isNotEmpty() }?.let { values ->
            atom { port = values.map { value -> KaringRuleSetValidation.port(value) ?: error("Invalid Karing port: $value") } }
        }
        normalized.portRange.takeIf { it.isNotEmpty() }?.let { values ->
            atom { port_range = values.map { value -> KaringRuleSetValidation.portRange(value) ?: error("Invalid Karing port range: $value") } }
        }
        setTags.takeIf { it.isNotEmpty() }?.let { values -> atom { rule_set = values } }
        userIds.distinct().takeIf { it.isNotEmpty() }?.let { values -> atom { user_id = values } }
        require(atoms.isNotEmpty()) { "Karing rule has no match condition" }

        val node: SingBoxOptions.Rule = if (atoms.size == 1) atoms.single()
        else logicalNode(if (normalized.or) "or" else "and", atoms)
        return withOutbound(node, outbound)
    }

    private fun logicalNode(mode: String, children: List<SingBoxOptions.Rule>): SingBoxOptions.Rule =
        SingBoxOptions.Rule_DefaultOptions().apply {
            type = "logical"; _hack_config_map["mode"] = mode
            _hack_config_map["rules"] = children.map { it.asMap() }
        }

    private fun withOutbound(rule: SingBoxOptions.Rule, outbound: String): SingBoxOptions.Rule {
        if (rule is SingBoxOptions.Rule_DefaultOptions) rule.outbound = outbound
        return rule
    }
}

fun SingBoxOptions.Rule_DefaultOptions.checkEmpty(): Boolean = asMap().keys.none {
    it != "type" && it != "outbound" && it != "action" && it != "mode" && it != "rules"
}
fun SingBoxOptions.Rule.toJsonForKaringTest(): String = JavaUtil.gson.toJson(this)
