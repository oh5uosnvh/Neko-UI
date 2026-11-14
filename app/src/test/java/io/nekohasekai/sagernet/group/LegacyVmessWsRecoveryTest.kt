package io.nekohasekai.sagernet.group

import android.app.Application
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic identities only. No network, private fixtures, DataStore or native core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LegacyVmessWsRecoveryTest {
    private fun old() = VMessBean().apply {
        initializeDefaultValues()
        name = "old-name"; serverAddress = "node.example"; serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000071"
        type = "ws"; path = ""; host = "gateway.example"; security = "tls"
        sni = "tls.example"; encryption = "chacha20-poly1305"; alterId = 0
    }
    private fun source(old: VMessBean = old()) = old.clone().apply { path = "/recovered" }
    private fun replace(old: VMessBean, vararg source: AbstractBean) =
        LegacyVmessWsRecovery.replacement(old, source.toList())

    @Test fun onlyLegacyEmptyWsVmessWithoutCustomJsonIsEligible() {
        assertTrue(LegacyVmessWsRecovery.eligible(old()))
        val excluded: List<VMessBean.() -> Unit> = listOf(
            { path = "/" }, { path = "/user-choice" }, { type = "tcp" },
            { type = "httpupgrade" }, { alterId = -1 }, { alterId = -2 },
            { customOutboundJson = "{}" }, { customConfigJson = "{}" }
        )
        for (change in excluded) {
            val b = old().apply(change)
            val before = KryoConverters.serialize(b)
            assertFalse(LegacyVmessWsRecovery.eligible(b))
            assertNull(replace(b, source()))
            assertArrayEquals(before, KryoConverters.serialize(b))
        }
    }

    @Test fun identityMismatchNeverFallsBackToNameOrGuessesPath() {
        val b = old()
        val changes: List<VMessBean.() -> Unit> = listOf(
            { uuid = "00000000-0000-4000-8000-000000000072" },
            { serverPort = 80 }, { serverAddress = "other.example" },
            { host = "other.example" }, { security = "none" }, { alterId = 4 },
            { alterId = -1 }, { type = "tcp" }, { path = "" }, { path = "   " }
        )
        for (change in changes) assertNull(replace(b, source(b).apply(change)))
        assertNull(replace(b))
        assertNull(replace(b, SOCKSBean().apply { initializeDefaultValues(); name = b.name }))
    }

    @Test fun explicitOriginalAddressPermitsMappedTargetButNotUnrelatedAddress() {
        val b = old().apply {
            serverAddress = "192.0.2.71"; mappedOriginalAddress = "NODE.EXAMPLE"
            mappedTargetAddress = serverAddress; mappedIdentityFields = "synthetic-marker"
        }
        val s = source().apply { serverAddress = "node.example"; host = "GATEWAY.EXAMPLE" }
        val repaired = replace(b, s)!!
        assertEquals("/recovered", repaired.path)
        assertEquals("192.0.2.71", repaired.serverAddress)
        assertEquals("NODE.EXAMPLE", repaired.mappedOriginalAddress)
        assertNull(replace(b, s.clone().apply { serverAddress = "unrelated.example" }))
    }

    @Test fun sharedMappedTargetCannotSubstituteForDifferentOriginalIdentity() {
        val b = old().apply {
            serverAddress = "192.0.2.71"; mappedOriginalAddress = "first.example"
            mappedTargetAddress = serverAddress
        }
        val s = source(b).apply { mappedOriginalAddress = "second.example" }
        assertNull("Shared mapped IP is not proof of original endpoint identity", replace(b, s))
    }

    @Test fun matchingNameDisambiguatesOnlyWhenUniqueAndIdentityStillMatches() {
        val b = old()
        val same = source(b)
        val renamed = source(b).apply { name = "renamed"; path = "/other" }
        assertEquals("/recovered", replace(b, same, renamed)!!.path)
        assertNull(replace(b, same, same.clone()))
        assertNull(replace(b, renamed, renamed.clone().apply { name = "another" }))
        assertNull(replace(b, same.clone().apply { uuid = "wrong" }))
    }

    @Test fun renamedUniqueIdentityIsAllowedWithoutRenamingStoredProfile() {
        val b = old()
        val s = source(b).apply { name = "subscription-new-name" }
        assertEquals(b.name, replace(b, s)!!.name)
        assertEquals("/recovered", replace(b, s)!!.path)
    }

    @Test fun clonedRepairPreservesEverySerializedOldFieldExceptRecoveredTransportData() {
        val b = old().apply {
            subscriptionDns = "nameserver: [https://dns.example/dns-query]"
            subscriptionDnsSource = "synthetic-source"
            mappedOriginalAddress = "origin.example"; mappedTargetAddress = "node.example"
            mappedIdentityFields = "identity-sentinel"
            allowInsecure = false; alpn = "h2,http/1.1"; certificates = "certificate-sentinel"
            utlsFingerprint = "chrome"; realityPubKey = "public-key"; realityShortId = "ab"
            enableECH = true; echConfig = "ech-sentinel"; packetEncoding = 2
            wsMaxEarlyData = 1024; earlyDataHeaderName = "Sec-WebSocket-Protocol"
            enableMux = true; muxPadding = true; muxType = 2; muxConcurrency = 23
            muxMode = 1; muxMaxConnections = 7; muxMinStreams = 3
            muxBrutal = true; muxBrutalUpMbps = 17; muxBrutalDownMbps = 29
        }
        val before = KryoConverters.serialize(b)
        val s = source(b).apply {
            encryption = "none"; sni = "different.example"; allowInsecure = true
            subscriptionDns = "wrong"; enableMux = false; wsExtraHeaders = "{\"X-Test\":\"synthetic\"}"
        }
        val repaired = replace(b, s)!!
        val expected = b.clone().apply { path = s.path }
        assertNotSame(b, repaired)
        assertArrayEquals(KryoConverters.serialize(expected), KryoConverters.serialize(repaired))
        assertArrayEquals(before, KryoConverters.serialize(b))
    }

    @Test fun sourceWithoutHeadersMustNotEraseExistingAuthenticationHeaders() {
        val b = old().apply { wsExtraHeaders = "{\"Authorization\":\"synthetic-token\",\"X-Keep\":\"keep\"}" }
        val s = source(b).apply { wsExtraHeaders = "" }
        val repaired = replace(b, s)
        // Conservative rejection is also safe; silent deletion is not.
        if (repaired != null) assertEquals(b.wsExtraHeaders, repaired.wsExtraHeaders)
    }
}
