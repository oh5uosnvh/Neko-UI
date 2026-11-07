package io.nekohasekai.sagernet.group

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static contract for the card row ownership described by the UI specification. */
class ProfileCardLayoutContractTest {
    private val layout = File("src/main/res/layout/layout_profile.xml").readText()

    @Test fun cardHasExplicitTitleTrafficAddressAndStatusRows() {
        for (id in listOf("title_row", "traffic_row", "address_row", "status_row")) {
            assertTrue("missing $id", layout.contains("android:id=\"@+id/$id\""))
        }
    }

    @Test fun trafficAndAddressAreSeparateFromStatus() {
        val traffic = layout.indexOf("android:id=\"@+id/traffic_row\"")
        val address = layout.indexOf("android:id=\"@+id/address_row\"")
        val status = layout.indexOf("android:id=\"@+id/status_row\"")
        assertTrue(traffic >= 0 && address > traffic && status > address)
        assertTrue(layout.indexOf("android:id=\"@+id/traffic_text\"") < address)
        assertTrue(layout.indexOf("android:id=\"@+id/profile_address\"") < status)
    }
}
