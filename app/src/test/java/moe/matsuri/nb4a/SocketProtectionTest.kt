package moe.matsuri.nb4a

import org.junit.Assert.*
import org.junit.Test

class SocketProtectionTest {
    @Test fun missingVpnServiceFails() {
        assertThrows(IllegalStateException::class.java) { requireSocketProtection(17, null) }
    }
    @Test fun rejectedProtectionFails() {
        assertThrows(IllegalStateException::class.java) { requireSocketProtection(17) { false } }
    }
    @Test fun successfulProtectionReceivesActualFd() {
        var observed = -1
        requireSocketProtection(17) { observed = it; true }
        assertEquals(17, observed)
    }
    @Test fun androidProtectionExceptionPropagates() {
        val error = SecurityException("denied")
        val actual = assertThrows(SecurityException::class.java) {
            requireSocketProtection(17) { throw error }
        }
        assertSame(error, actual)
    }
}
