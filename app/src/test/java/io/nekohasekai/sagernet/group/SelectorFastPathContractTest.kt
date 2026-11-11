package io.nekohasekai.sagernet.group

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract: a selector tap must not rebuild the global graph. */
class SelectorFastPathContractTest {
    private fun source(path: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(20) {
            val file = File(dir, path)
            if (file.isFile) return file.readText()
            dir = dir.parentFile ?: return@repeat
        }
        throw java.io.FileNotFoundException(path)
    }

    @Test fun binderHotPathDoesNotRunConfigBuilderOrPreflight() {
        val service = source("app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt")
        val click = service.substringAfter("override fun selectSelectorNode")
            .substringBefore("/** Acceptance of a restart request")
            .replace(Regex("(?s)/\\*.*?\\*/|//[^\\n]*"), "")
        assertFalse(click.contains("isCompiledSelectorCurrent"))
        assertFalse(click.contains("buildConfig("))
        assertTrue(click.contains("running.box.selectOutbound(tag)"))
    }

    @Test fun nativeSelectorCallbackUsesRunningMembershipOnly() {
        val native = source("app/src/main/java/moe/matsuri/nb4a/NativeInterface.kt")
        val callback = native.substringAfter("override fun selector_OnProxySelected")
        assertFalse(callback.contains("isCompiledSelectorCurrent"))
        assertTrue(callback.contains("running.config.trafficMap[tag]"))
    }

    @Test fun reconnectFallbackDoesNotPreflightOnBinderThread() {
        val service = source("app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt")
        val fallback = service.substringAfter("private fun reconnectSelector")
            .substringBefore("private fun selectorError")
        assertFalse(fallback.contains("buildConfig("))
        assertTrue(fallback.contains("stopRunner(restart = true)"))
    }
}
