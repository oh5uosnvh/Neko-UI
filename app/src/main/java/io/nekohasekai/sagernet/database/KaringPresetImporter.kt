package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.fmt.KaringRuleSetValidation
import org.json.JSONObject
import java.net.URI

class KaringPresetException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class KaringImportResult(
    /** The complete ordered view after applying the plan. */
    val rules: List<RuleEntity>,
    /** Rows which the caller must insert, in their preset order. */
    val newRules: List<RuleEntity> = emptyList(),
    val skippedDuplicates: Int = 0,
)

/**
 * Pure parser/import planner.  It deliberately does not touch Room: callers can
 * insert [KaringImportResult.newRules] in the same transaction as their own
 * initialization work.
 */
object KaringPresetImporter {
    const val CHINA_PRESET_ASSET = "karing/cn.json"
    const val CHINA_PRESET_IMPORT_ID = "karing-cn-v1"
    const val DEFAULT_SWITCH_MIGRATION = "cn-v2-default-switches"
    val DEFAULT_ENABLED_NAMES = setOf(
        "📹 油管视频", "♊️ Google Gemini", "🌏 Google Play", "🌏 Google",
        "📲 Facebook", "🎧 TikTok", "📸 Instagram", "🎥 奈飞视频",
        "📲 WhatsApp", "📲 电报消息", "💬 Claude", "💬 OpenAI", "🐱 GitHub",
        "📺 哔哩哔哩", "🎯 国内直连", "🌏 国外穿墙",
    )

    fun parse(json: String): KaringPreset {
        val preset = KaringRuleJson.parsePreset(json)
        if (preset.rules.any { it.ruleSetBuildIn.any { code -> !KaringRuleSetValidation.builtIn(code) } }) {
            throw KaringPresetException("invalid Karing built-in rule-set")
        }
        if (preset.rules.any { it.ruleSetItems.any { item -> !validRemote(item.url) } }) {
            throw KaringPresetException("Karing rule-set URL must be HTTPS .srs/.json")
        }
        return preset
    }

    fun importInto(
        existing: List<RuleEntity>,
        preset: KaringPreset,
        importId: String,
        policy: KaringImportPolicy = KaringImportPolicy.APPEND,
        excludedEntries: Set<String> = emptySet(),
    ): KaringImportResult {
        val current = if (policy == KaringImportPolicy.REPLACE_IMPORTED) {
            existing.filterNot { it.karingImportMarker() == importId }
        } else existing
        val seen = current.mapTo(mutableSetOf()) { fingerprint(it.toKaringRule()) }
        val result = mutableListOf<RuleEntity>()
        var skipped = 0
        val existingEntries = current.mapNotNull { rule -> rule.karingPresetEntry() }.toMutableSet()
        existingEntries += excludedEntries
        preset.rules.forEachIndexed { index, model ->
            val entry = "$importId:$index"
            val id = fingerprint(model)
            if (entry in existingEntries || !seen.add(id)) {
                skipped++
                return@forEachIndexed
            }
            existingEntries.add(entry)
            result += RuleEntity(
                name = model.name,
                enabled = model.switch,
                outbound = when (model.outbound) {
                    "direct" -> -1L
                    "block" -> -2L
                    else -> 0L // currentSelected is resolved by the caller/profile context
                },
                config = JSONObject().apply {
                    put("karing_preset_entry", entry)
                    put(KARING_CONFIG_KEY, JSONObject().apply { put("import_id", importId) })
                }.toString(),
            ).apply { applyKaringRule(model) }
        }
        return KaringImportResult(current + result, result, skipped)
    }

    private fun validRemote(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            (uri.path.endsWith(".srs", true) || uri.path.endsWith(".json", true))
    } catch (_: Exception) { false }

    private fun fingerprint(model: KaringRuleModel): String = listOf(
        model.ruleSetBuildIn, model.ruleSetItems.map { it.url }, model.packageNames,
        model.processName, model.processPath, model.processDir, model.domainSuffix,
        model.domain, model.domainKeyword, model.domainRegex, model.ipCidr, model.networkType,
        model.network, model.sourceIpCidr, model.sourcePorts, model.wifiSsid, model.wifiBssid, model.port, model.portRange,
        model.protocol, model.or, model.outbound, model.rawPresetJson,
    ).toString()
}

private fun RuleEntity.karingPresetEntry(): String? = runCatching {
    JSONObject(config).optString("karing_preset_entry").takeIf { it.isNotBlank() }
}.getOrNull()

enum class KaringImportPolicy { APPEND, REPLACE_IMPORTED }
