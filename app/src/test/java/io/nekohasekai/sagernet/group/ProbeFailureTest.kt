package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.proto.ProbeFailure
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProbeFailureTest {
    @Test fun stageAndVersionSurviveWhileEndpointAndCredentialsAreRedacted() {
        val original=IllegalStateException("dial gateway.example.invalid:443 192.0.2.9:80 [2001:db8::2]:443 GET https://server.example.invalid/ws?token=PRIVATE uuid=00000000-0000-4000-8000-000000000001 path=/private-ws password=private-password: unexpected EOF")
        for(stage in ProbeFailure.Stage.values()) {
            val failure=ProbeFailure.from(stage,original)
            val message=failure.message!!
            assertTrue(message.contains(stage.value))
            assertTrue(message.contains(BuildConfig.VERSION_NAME))
            assertTrue(message.contains("unexpected EOF"))
            listOf("gateway.example.invalid","192.0.2.9","2001:db8","server.example.invalid","PRIVATE","00000000-0000-4000-8000-000000000001","/private-ws","private-password").forEach { assertFalse(it,message.contains(it)) }
            assertSame(original,failure.cause)
        }
    }
    @Test fun cancellationIsControlFlowNotProbeFailure() {
        val original=CancellationException("synthetic cancellation")
        try { ProbeFailure.from(ProbeFailure.Stage.HTTP_REQUEST,original);fail("must propagate cancellation") }
        catch(actual:CancellationException) { assertSame(original,actual) }
    }
    @Test fun existingStageIsNotLostByOuterWrapping() {
        val failure=ProbeFailure.from(ProbeFailure.Stage.CONFIG,IllegalArgumentException("invalid transport"))
        assertSame(failure,ProbeFailure.from(ProbeFailure.Stage.HTTP_REQUEST,failure))
    }
}
