package io.nekohasekai.sagernet.group

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** JVM-only wiring checks complement the executable coroutine regressions. */
class BatchProgressContractTest {
    private val app = sequenceOf(File("."), File("app")).first {
        File(it, "src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").isFile
    }
    private fun source(path: String) = File(app, "src/main/java/io/nekohasekai/sagernet/$path.kt").readText()
    private fun dialog() = source("ui/ConfigurationFragment")
        .substringAfter("inner class TestDialog(").substringBefore("@Volatile private var activeLatencyGeneration")

    @Test fun sharedCorePublishesDirectlyInsteadOfReplayingResults() {
        val batch = source("bg/proto/BatchTestInstance")
        assertTrue(batch.contains("onResult: suspend (Result) -> Unit"))
        assertTrue(batch.contains("runBatchProbe(probeProfiles, concurrency = 10"))
        assertFalse(batch.contains(".awaitAll()"))
        val fragment = source("ui/ConfigurationFragment")
        val callback = fragment.substringAfter("batch.doTest(testPool!!) { result ->").substringBefore("} else {\n                    val urlTest")
        assertTrue(callback.contains("test.update(profile)"))
        assertFalse(fragment.contains("batch.doTest(testPool!!).forEach"))
    }

    @Test fun everyAcceptedCompletionFeedsCountsCardsAndThrottledPersistence() {
        val dialog = dialog()
        val update = dialog.substringAfter("fun update(profile: ProxyEntity)").substringBefore("fun finish(")
        assertTrue(update.contains("dialogStatus.get() == 2 || !ownsSession() || terminal.get()"))
        assertTrue(update.contains("profile.id !in targetIds || !completedIds.add(profile.id)"))
        assertTrue(update.contains("cardLatencySession.complete(profile, generation)"))
        assertTrue(update.contains("finishedN.incrementAndGet()"))
        assertTrue(update.contains("successN.incrementAndGet()"))
        assertTrue(update.contains("failedN.incrementAndGet()"))
        assertTrue(update.contains("scheduleFlush()"))
        assertTrue(update.contains("delay(32)"))
        assertTrue(update.contains("latencyOverlay()?.running(progress, proxyN, failedN.get())"))
        assertTrue(update.contains("refreshVisibleCardLatency()"))
        assertTrue(dialog.contains("delay(250)"))
    }

    @Test fun finalizationAndFlushNeverReplayCountsOrDropGenerationFences() {
        val dialog = dialog()
        val flush = dialog.substringAfter("suspend fun flushPending()").substringBefore("fun update(")
        assertTrue(flush.contains("results.clear()"))
        assertTrue(flush.contains("!ownsSession()"))
        assertFalse(flush.contains("completedIds.clear()"))
        assertFalse(flush.contains("incrementAndGet()"))
        val finish = dialog.substringAfter("fun finish(interrupted: Boolean)")
        assertTrue(finish.contains("terminal.compareAndSet(false, true)"))
        assertTrue(finish.contains("if (!ownsSession())"))
        assertFalse(finish.contains("incrementAndGet()"))
    }
}
