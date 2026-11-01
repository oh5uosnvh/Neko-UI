package io.nekohasekai.sagernet.fmt

import android.content.Context
import io.nekohasekai.sagernet.assets.KaringResourceManager
import io.nekohasekai.sagernet.assets.KaringRuleFamily
import io.nekohasekai.sagernet.database.KaringRuleSetItem
import java.io.File
import kotlinx.coroutines.runBlocking

/** Compiler adapter: indexed Karing references are installed and validated lazily. */
class KaringResourceResolver(context: Context) : KaringRuleSetResolver {
    private val resources = KaringResourceManager(context)

    override fun resolveBuiltIn(code: String): KaringRuleSetRef? {
        val separator = code.indexOf(':')
        if (separator <= 0) return null
        val family = KaringRuleFamily.parse(code.substring(0, separator)) ?: return null
        val name = code.substring(separator + 1)
        return try {
            val file = runBlocking { resources.ensure(family, name) }
            KaringRuleSetRef(code, "binary", file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    override fun resolveRemote(item: KaringRuleSetItem): KaringRuleSetRef {
        val url = item.url.substringBefore('?')
        val tag = "ruleset-${kotlin.math.abs(item.url.hashCode())}"
        val format = if (url.endsWith(".json", true)) "source" else "binary"
        return KaringRuleSetRef(tag, format, item.url, remote = true)
    }
}
