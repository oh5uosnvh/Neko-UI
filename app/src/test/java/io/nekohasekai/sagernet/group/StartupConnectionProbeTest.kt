package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.StartupConnectionProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StartupConnectionProbeTest {
    @Test fun measuredRoundTripIsNotStartupTimeAndErrorsRemainReal()=runBlocking {
        val good=StartupConnectionProbe.measure(1000){73}
        assertEquals(73,good.elapsed);assertNull(good.error)
        val fail=StartupConnectionProbe.measure(1000){throw IllegalStateException("connection reset by peer")}
        assertEquals(-1,fail.elapsed);assertEquals("connection reset by peer",fail.error)
        val unavailable=StartupConnectionProbe.measure(1000){-1}
        assertNotNull(unavailable.error)
    }
    @Test fun userCancellationNeverPublishesSuccessOrAnAutomaticRetry()=runBlocking {
        var calls=0
        try {StartupConnectionProbe.measure(1000){calls++;throw CancellationException("cancel")};fail("must cancel")}
        catch(_:CancellationException){}
        assertEquals(1,calls)
    }
    @Test fun serviceStartupMeasuresBeforePublishingConnectedAndUiOnlyReceivesSnapshot() {
        val app=sequenceOf(File("."),File("app")).first{File(it,"src/main/java").isDirectory}
        val service=File(app,"src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt").readText()
        val startup=service.substringAfter("                    startProcesses()")
        assertTrue(startup.indexOf("StartupConnectionProbe.measure")<startup.indexOf("data.changeState(State.Connected)"))
        assertTrue(startup.contains("data.publishProbe(\"connected\", elapsed = result.elapsed)"))
        assertTrue(service.contains("data.connectingJob = runOnMainDispatcher"))
        val host=File(app,"src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
        val change=host.substringAfter("private fun changeState(").substringBefore("override fun snackbarInternal")
        assertFalse(change.contains("testControlLatency()"))
        assertTrue(host.contains("service.connectionProbe"))
    }
}
