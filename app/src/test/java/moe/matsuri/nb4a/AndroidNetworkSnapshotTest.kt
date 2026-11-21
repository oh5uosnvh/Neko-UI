package moe.matsuri.nb4a

import org.junit.Assert.*
import org.junit.Test

class AndroidNetworkSnapshotTest {
    private fun network(
        id: String = "100", name: String = "wlan0", index: Int = 7,
        type: String = "wifi", metered: Boolean = false, vpn: Boolean = false,
        validated: Boolean = true, addresses: List<String> = listOf("192.0.2.2/24", "2001:db8::2/64"),
    ) = PlatformNetworkRecord(id, name, index, 1500,
        AndroidNetworkSnapshot.UP or AndroidNetworkSnapshot.RUNNING or AndroidNetworkSnapshot.MULTICAST,
        addresses, type, listOf("2001:db8::53", "192.0.2.53"), listOf("example.test"),
        listOf("192.0.2.1"), metered, vpn, true, validated)

    @Test fun bothProcessesBootstrapSynchronouslyWithoutListener() {
        val records = listOf(network("102", "rmnet_data0", 8, "cellular", true), network())
        repeat(2) { // process-local provider: UI URLTest and :bg before VPN listener
            assertEquals("wlan0", AndroidNetworkSnapshot.selectDefault(records, null, null)?.name)
            assertTrue(AndroidNetworkSnapshot.encode(records, null, null).contains("\"defaultIndex\":7"))
        }
    }
    @Test fun listenerWinsOverVpnAndOtherPhysicalNetwork() {
        val cellular = network("102", "rmnet_data0", 8, "cellular", true)
        val vpn = network("200", "tun0", 9, "other", vpn = true)
        val records = listOf(network(), cellular, vpn)
        assertEquals(cellular, AndroidNetworkSnapshot.selectDefault(records, "102", "200"))
        assertEquals("wlan0", AndroidNetworkSnapshot.selectDefault(records, "200", "200")?.name)
        assertNull(AndroidNetworkSnapshot.selectDefault(listOf(vpn), "200", "200"))
    }
    @Test fun activePhysicalAndStaleListenerResolvedFromCurrentSnapshot() {
        val cellular = network("102", "rmnet_data0", 8, "cellular", true)
        assertEquals(cellular, AndroidNetworkSnapshot.selectDefault(listOf(network(), cellular), "gone", "102"))
        assertEquals(cellular, AndroidNetworkSnapshot.selectDefault(listOf(cellular), "100", "100"))
    }
    @Test fun loopbackDownOrVpnNamedInterfaceNeverDefault() {
        val lo = network(name = "lo").copy(flags = AndroidNetworkSnapshot.UP or AndroidNetworkSnapshot.LOOPBACK)
        val down = network().copy(flags = AndroidNetworkSnapshot.MULTICAST)
        assertNull(AndroidNetworkSnapshot.selectDefault(listOf(lo, down, network(name = "tun5")), null, null))
    }
    @Test fun fullMetadataIPv6FlagsAndStableJson() {
        val wifi = network()
        val cellular = network("102", "rmnet_data0", 8, "cellular", true)
        val json = AndroidNetworkSnapshot.encode(listOf(wifi, cellular), "100", null)
        assertEquals(json, AndroidNetworkSnapshot.encode(listOf(cellular, wifi), "100", null))
        assertTrue(json.contains("\"flags\":49")) // Go UP|RUNNING|MULTICAST, not Linux IFF
        assertTrue(json.contains("\"addresses\":[\"192.0.2.2/24\",\"2001:db8::2/64\"]"))
        assertTrue(json.contains("\"type\":\"cellular\""))
        assertTrue(json.contains("\"metered\":true"))
        assertTrue(json.contains("\"dnsSearchDomains\":[\"example.test\"]"))
    }
    @Test fun recreatedSameNameUsesNewIndexAndAddresses() {
        val first = AndroidNetworkSnapshot.encode(listOf(network()), "100", null)
        val second = AndroidNetworkSnapshot.encode(
            listOf(network("110", index = 19, addresses = listOf("2001:db8::3/64"))), "110", null)
        assertNotEquals(first, second)
        assertTrue(second.contains("\"defaultIndex\":19"))
        assertFalse(second.contains("192.0.2.2"))
    }
    @Test fun sharedInterfaceChoosesSelectedNetworkMetadata() {
        val old = network("100")
        val fresh = network("110", index = 19).copy(dnsServers = listOf("198.51.100.53"))
        val json = AndroidNetworkSnapshot.encode(listOf(old, fresh), "110", null)
        assertTrue(json.contains("\"defaultIndex\":19"))
        assertTrue(json.contains("198.51.100.53"))
        assertFalse(json.contains("\"index\":7"))
    }
    @Test fun unvalidatedNetworkIsNotBootstrapGuessButExplicitDefaultWorks() {
        val captive = network(validated = false)
        assertNull(AndroidNetworkSnapshot.selectDefault(listOf(captive), null, null))
        assertEquals(captive, AndroidNetworkSnapshot.selectDefault(listOf(captive), "100", null))
        assertEquals(captive, AndroidNetworkSnapshot.selectDefault(listOf(captive), null, "100"))
    }

    @Test fun protectionRequiredUsesServiceAndCrossProcessVPNState() {
        assertTrue(AndroidNetworkSnapshot.protectionRequired(true) { error("service presence is authoritative") })
        assertTrue(AndroidNetworkSnapshot.protectionRequired(false) { listOf(false, true) })
        assertFalse(AndroidNetworkSnapshot.protectionRequired(false) { listOf(false, false) })
        assertFalse(AndroidNetworkSnapshot.protectionRequired(false) { emptyList() })
        val error = SecurityException("state denied")
        assertSame(error, assertThrows(SecurityException::class.java) {
            AndroidNetworkSnapshot.protectionRequired(false) { throw error }
        })
    }

    @Test fun emptyOrIncompleteSnapshotFailsNotSuccess() {
        assertThrows(IllegalArgumentException::class.java) { AndroidNetworkSnapshot.encode(emptyList(), null, null) }
        for (bad in listOf(network(index = 0), network(addresses = emptyList()), network().copy(mtu = 0))) {
            assertThrows(IllegalArgumentException::class.java) { AndroidNetworkSnapshot.encode(listOf(bad), null, null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidNetworkSnapshot.encode(listOf(network(), network("102", "rmnet_data0", 7)), null, null)
        }
    }
}
