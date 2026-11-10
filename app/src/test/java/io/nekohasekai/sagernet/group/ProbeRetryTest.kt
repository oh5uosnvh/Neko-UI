package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.bg.retryColdStartProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeRetryTest {
    @Test fun transientFirstFailureGetsFreshSecondAttempt() = runBlocking {
        var calls = 0
        val result = retryColdStartProbe(backoffMillis = 0) {
            calls++
            if (calls == 1) throw IllegalStateException("cold")
            42
        }
        assertEquals(42, result)
        assertEquals(2, calls)
    }

    @Test fun cancellationIsNeverRetried() = runBlocking {
        var calls = 0
        try {
            retryColdStartProbe(backoffMillis = 0) {
                calls++
                throw CancellationException("cancel")
            }
            error("expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test fun negativeProbeResultGetsFreshSecondAttempt() = runBlocking {
        var calls = 0
        val result = retryColdStartProbe(backoffMillis = 0, isSuccess = { it >= 0 }) {
            calls++
            if (calls == 1) -1 else 7
        }
        assertEquals(7, result)
        assertEquals(2, calls)
    }
}
