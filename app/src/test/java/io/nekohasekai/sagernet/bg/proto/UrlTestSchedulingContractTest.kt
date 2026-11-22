package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Keep the Android integration on the exact lifecycle/concurrency policy tested on JVM. */
class UrlTestSchedulingContractTest {
    private fun source(path: String): String {
        val module = if (File("src/main").isDirectory) File(".") else File("app")
        return File(module, "src/main/java/io/nekohasekai/sagernet/$path").readText()
    }

    @Test fun poolAndWorkersUseOneBoundedPreferenceSnapshot() {
        val function = source("ui/ConfigurationFragment.kt").substringAfter("    fun urlTest(")
            .substringBefore("    private val cardLatencySession")
        assertEquals(1, Regex("DataStore\\.connectionTestConcurrent").findAll(function).count())
        assertTrue(function.contains("val effectiveConcurrency = DataStore.connectionTestConcurrent.coerceIn(1, 64)"))
        assertTrue(function.contains("val boxSemaphore = kotlinx.coroutines.sync.Semaphore(effectiveConcurrency.coerceAtMost(16))"))
        assertTrue(function.contains("newFixedThreadPool(effectiveConcurrency)"))
        assertTrue(function.contains("repeat(effectiveConcurrency)"))
        assertTrue(function.contains("launch(testPool"))
        assertTrue(function.contains("boxSemaphore.withPermit"))
        val cancel = function.substringAfter("activeLatencyCancel = {").substringBefore("activeLatencyMinimize = {")
        assertFalse(cancel.contains("testPool?.close()"))
        assertTrue(cancel.contains("mainJob.join()"))
        assertTrue(function.contains("catch (e: kotlinx.coroutines.CancellationException) { throw e }"))
    }

    @Test fun instanceUsesStructuredLifecycleAndWaitsForItsPluginJob() {
        val instance = source("bg/proto/TestInstance.kt")
        assertFalse(instance.contains("GlobalScope"))
        assertTrue(instance.contains("runUrlTestLifecycle("))
        assertTrue(instance.contains("probeProcesses?.coroutineContext?.get(Job)?.join()"))
        assertTrue(instance.contains("delay(500)"))
        val box = source("bg/proto/BoxInstance.kt")
        assertFalse(box.contains("SystemClock.elapsedRealtime()"))
        assertTrue(box.contains("cacheFiles.deleteOwned()"))
        assertEquals(6, Regex("cacheFiles\\.create\\(").findAll(box).count())
    }
}
