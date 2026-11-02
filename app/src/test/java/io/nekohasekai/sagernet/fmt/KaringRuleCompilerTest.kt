package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.KaringRuleModel
import io.nekohasekai.sagernet.database.KaringRuleSetItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class KaringRuleCompilerTest {
    @Test fun validPresetExclamationAndUnsafePaths() {
        assertTrue(KaringRuleSetValidation.builtIn("geosite:geolocation-!cn"))
        assertFalse(KaringRuleSetValidation.builtIn("geosite:../secret"))
        assertFalse(KaringRuleSetValidation.builtIn("geosite:foo/bar"))
    }
    private val resolver = object : KaringRuleSetResolver {
        override fun resolveBuiltIn(code: String) = KaringRuleSetRef(code, "binary", "/tmp/$code.srs")
        override fun resolveRemote(item: io.nekohasekai.sagernet.database.KaringRuleSetItem) =
            KaringRuleSetRef("remote", "source", item.url, remote = true)
    }

    @Test fun andDoesNotMergeAddressFieldsIntoOneOrRule() {
        val result = KaringRuleCompiler.compile(
            KaringRuleModel(domainSuffix = listOf("example.com"), ipCidr = listOf("10.0.0.0/8")),
            resolver, "direct"
        ).toJsonForKaringTest()
        assertTrue(result.contains("\"type\":\"logical\""))
        assertTrue(result.contains("\"mode\":\"and\""))
    }

    @Test fun orCreatesLogicalGroupAndKeepsEveryCriterion() {
        val result = KaringRuleCompiler.compile(
            KaringRuleModel(or = true, domainSuffix = listOf("example.com"), protocol = listOf("tcp")),
            resolver, "direct"
        ).toJsonForKaringTest()
        assertTrue(result.contains("\"mode\":\"or\""))
        assertTrue(result.contains("example.com"))
        assertTrue(result.contains("tcp"))
    }

    @Test fun invalidPortIsNotSilentlyDropped() {
        assertThrows(IllegalArgumentException::class.java) {
            KaringRuleCompiler.compile(KaringRuleModel(port = listOf("0", "70000")), resolver, "direct")
        }
    }

    @Test fun emptyRuleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KaringRuleCompiler.compile(KaringRuleModel(), resolver, "direct")
        }
    }

    @Test fun desktopOnlyConditionsStillRejectAnEmptyAndroidRule() {
        val model = KaringRuleModel(processName = listOf("Telegram.exe"))
        assertTrue(model.processName.isNotEmpty())
        assertFalse(KaringRuleCompiler.hasMatchCondition(model))
        assertThrows(IllegalArgumentException::class.java) {
            KaringRuleCompiler.compile(model, resolver, "direct")
        }
    }

    @Test fun desktopConditionsAreIgnoredWhenAndroidCriteriaRemain() {
        val result = KaringRuleCompiler.compile(
            KaringRuleModel(
                ruleSetBuildIn = listOf("geosite:telegram"),
                packageNames = listOf("org.telegram.messenger"),
                processName = listOf("Telegram.exe"),
                networkType = listOf("wifi"),
                wifiSsid = listOf("office"),
            ), resolver, "direct"
        ).toJsonForKaringTest()
        assertTrue(result.contains("telegram"))
        assertTrue(result.contains("org.telegram.messenger"))
        assertFalse(result.contains("Telegram.exe"))
    }

    @Test fun resolvedUidIsPreferredAndPackageNameIsNotDuplicated() {
        val result = KaringRuleCompiler.compile(
            KaringRuleModel(packageNames = listOf("com.example.app")), resolver, "direct",
            userIds = listOf(10042, 10042)
        ).toJsonForKaringTest()
        assertTrue(result.contains("\"user_id\":[10042]"))
        assertFalse(result.contains("\"package_name\""))
    }

    @Test fun malformedPackageNameIsRejectedClearly() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            KaringRuleCompiler.compile(KaringRuleModel(packageNames = listOf("not a package")), resolver, "direct")
        }
        assertTrue(error.message!!.contains("package name"))
    }

    @Test fun unknownPresetConditionIsRejectedClearly() {
        val model = KaringRuleModel(
            domainSuffix = listOf("example.com"),
            rawPresetJson = "{\"domain_suffix\":[\"example.com\"],\"match_everything\":true}"
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            KaringRuleCompiler.compile(model, resolver, "direct")
        }
        assertTrue(error.message!!.contains("match_everything"))
    }

    @Test fun remoteRuleSetStillRequiresHttpsAndSupportedExtension() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            KaringRuleCompiler.compile(
                KaringRuleModel(ruleSetItems = listOf(KaringRuleSetItem("http://example.test/rules.txt"))),
                resolver, "direct"
            )
        }
        assertTrue(error.message!!.contains("HTTPS"))
    }
}
