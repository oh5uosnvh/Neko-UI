package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.ProbeDeadline
import io.nekohasekai.sagernet.bg.UrlTestTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class UrlTestTimeoutTest {
    @Test fun inputIsIntegerMillisecondsNotDecimalSecondsOrSuffixedText() {
        for (input in listOf("", " ", "0.1", "0.5", "100ms", "500 ms", "1s", "-500", "+500",
            "1e3", "NaN", "2147483648", "999999999999999999999", "５００")) {
            assertNull(input, UrlTestTimeout.parseMillis(input))
        }
        for (input in listOf("0", "1", "100", "499")) {
            assertNull(input, UrlTestTimeout.parseMillis(input))
        }
        assertEquals(500, UrlTestTimeout.parseMillis("500"))
        assertEquals(3000, UrlTestTimeout.parseMillis(" 3000 "))
        assertEquals(Int.MAX_VALUE, UrlTestTimeout.parseMillis(Int.MAX_VALUE.toString()))
    }

    @Test fun legacyValuesHaveOnePolicyAndValidBudgetsStayUnchanged() {
        for (input in listOf(Int.MIN_VALUE, -1, 0)) {
            assertEquals(3000, UrlTestTimeout.normalizeMillis(input))
        }
        for (input in listOf(1, 100, 499)) assertEquals(500, UrlTestTimeout.normalizeMillis(input))
        for (input in listOf(500, 501, 3000, 120_001, Int.MAX_VALUE)) {
            assertEquals(input, UrlTestTimeout.normalizeMillis(input))
            assertEquals(input, UrlTestTimeout.normalizeMillis(UrlTestTimeout.normalizeMillis(input)))
        }
    }

    @Test fun inputFloorIsNeverReappliedToRemainingDeadline() {
        var nanos = 0L
        val deadline = ProbeDeadline(UrlTestTimeout.normalizeMillis(3000)) { nanos }
        assertEquals(3000, deadline.remainingMillis())
        nanos = 2_900_000_000L
        assertEquals(100, deadline.remainingMillis())
        nanos = 3_000_000_000L
        try { deadline.remainingMillis(); fail("must expire, not renew to 500 ms") }
        catch (_: IOException) { }
        assertEquals(100, ProbeDeadline(100) { 0L }.remainingMillis())
    }

    @Test fun directControlBarAndBatchAllReadTheSameMillisecondPreference() {
        val app = sequenceOf(File("."), File("app")).first { File(it, "src/main/java").isDirectory }
        fun source(path: String) = File(app, "src/main/java/$path").readText()
        val direct = source("io/nekohasekai/sagernet/bg/proto/UrlTest.kt")
        val service = source("io/nekohasekai/sagernet/bg/BaseService.kt")
        val batch = source("io/nekohasekai/sagernet/ui/ConfigurationFragment.kt")
        val startup = source("io/nekohasekai/sagernet/bg/StartupConnectionProbe.kt")
        assertTrue(direct.contains("timeoutMillis: Int = DataStore.connectionTestTimeout"))
        assertTrue(service.contains("data!!.proxy!!.box, DataStore.connectionTestURL, DataStore.connectionTestTimeout"))
        assertTrue(batch.contains("val timeout = DataStore.connectionTestTimeout"))
        assertTrue(startup.contains("UrlTestTimeout.normalizeMillis(timeoutMillis)"))
        assertFalse(startup.contains("coerceIn(500, 120_000)"))
        val settings = source("io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt")
        assertTrue(settings.contains("preference is UrlTestPreference"))
        assertTrue(settings.contains("UrlTestPreferenceDialog.newInstance(preference.key, this)"))
    }
}
