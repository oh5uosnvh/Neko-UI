package io.nekohasekai.sagernet.assets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class KaringResourceInstallTest {
    @Test fun continuesAfterFailureAndReportsProgress() = runBlocking {
        val saved = mutableListOf<String>()
        val steps = mutableListOf<Pair<Int, Int>>()
        val result = installKaringCodes(listOf("one", "broken", "three"), { code ->
            if (code == "broken") throw IOException("disk full")
            saved.add(code)
        }, { done, total -> steps.add(done to total) })
        assertEquals(listOf("one", "three"), saved)
        assertEquals(mapOf("broken" to "disk full"), result.failures)
        assertEquals(2, result.succeeded)
        assertEquals(3, result.total)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), steps)
    }

    @Test fun cancellationIsNotReportedAsAnItemFailure() = runBlocking {
        var calls = 0
        try {
            installKaringCodes(listOf("one", "two"), { calls++; throw CancellationException() }, { _, _ -> })
            fail("cancellation must escape")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }
}
