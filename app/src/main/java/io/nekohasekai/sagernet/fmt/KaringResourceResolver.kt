package io.nekohasekai.sagernet.fmt

import android.content.Context
import io.nekohasekai.sagernet.assets.KaringResourceManager
import io.nekohasekai.sagernet.assets.KaringResourceSettings
import io.nekohasekai.sagernet.assets.KaringRuleFamily
import io.nekohasekai.sagernet.database.KaringRuleSetItem
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.zip.ZipInputStream

/** One instance per config build: settings are never re-read halfway through a build. */
class KaringResourceResolver(
    context: Context,
    settings: KaringResourceSettings = KaringResourceSettings(context),
) : KaringRuleSetResolver {
    private val app = context.applicationContext
    // Take one Room-backed snapshot per config build. The settings object reads
    // SQLite directly, so this is current even when UI and VPN are separate
    // processes; keeping the snapshot preserves consistency during the build.
    private val options = KaringRuleFamily.values().associateWith(settings::get)
    private val resources by lazy { KaringResourceManager(app) }
    // Do not call list(): it validates every installed SRS via native code.
    private val indexes = mutableMapOf<KaringRuleFamily, Set<String>>()

    private fun index(family: KaringRuleFamily): Set<String> = indexes.getOrPut(family) {
        app.assets.open("karing/${family.archiveName}").use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == family.indexName) {
                        return@getOrPut zip.readBytes().toString(Charsets.UTF_8).lineSequence()
                            .map(String::trim).filter(String::isNotEmpty).toSet().also {
                                check(it.isNotEmpty()) { "Empty Karing ${family.wireName} index" }
                            }
                    }
                    entry = zip.nextEntry
                }
                error("Missing Karing ${family.wireName} index")
            }
        }
    }

    fun requireEnabledReference(code: String) {
        val family = KaringRuleFamily.parse(code.substringBefore(':')) ?: return
        check(options.getValue(family).enabled) {
            "Karing resource family ${family.wireName} is disabled but referenced by $code; enable it or remove the reference"
        }
    }

    override fun resolveBuiltIn(code: String): KaringRuleSetRef? {
        val separator = code.indexOf(':')
        if (separator <= 0) return null
        val family = KaringRuleFamily.parse(code.substring(0, separator)) ?: return null
        val name = code.substring(separator + 1)
        requireEnabledReference(code)
        check(KaringRuleSetValidation.builtIn(code) && name in index(family)) {
            "Karing resource is not in the packaged index: $code"
        }
        return try {
            if (options.getValue(family).useRemote) {
                KaringRuleSetRef("karing:$code", "binary", KaringResourceSettings.remoteUrl(family, name), remote = true)
            } else {
                val file = runBlocking { resources.ensure(family, name) }
                KaringRuleSetRef("karing:$code", "binary", file.absolutePath)
            }
        } catch (error: Exception) {
            throw IllegalStateException("Cannot resolve Karing resource $code: ${error.message}", error)
        }
    }

    /** No stored route-region setting exists; use the device default locale's country, or none. */
    fun regionalReferences(country: String): List<String> {
        val code = country.trim().lowercase(Locale.ROOT)
        if (!code.matches(Regex("[a-z]{2}"))) return emptyList()
        return listOf(KaringRuleFamily.GEOSITE, KaringRuleFamily.GEOIP).mapNotNull { family ->
            val setting = options.getValue(family)
            if (!setting.enabled || !setting.autoAppendRegion ||
                (family == KaringRuleFamily.GEOSITE && code !in setOf("af", "br", "cn", "id", "ru", "ir", "tr")) ||
                code !in index(family)) null else "${family.wireName}:$code"
        }
    }

    override fun resolveRemote(item: KaringRuleSetItem): KaringRuleSetRef {
        val url = item.url.substringBefore('?')
        val tag = "ruleset-${kotlin.math.abs(item.url.hashCode())}"
        val format = if (url.endsWith(".json", true)) "source" else "binary"
        return KaringRuleSetRef(tag, format, item.url, remote = true)
    }
}
