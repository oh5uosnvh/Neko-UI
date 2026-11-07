package io.nekohasekai.sagernet.group

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static contract for the card row ownership described by the UI specification. */
class ProfileCardLayoutContractTest {
    private val layout = File("src/main/res/layout/layout_profile.xml").readText()

    @Test fun cardHasTitleAddressAndStatusRows() {
        for (id in listOf("title_row", "address_row", "status_row")) {
            assertTrue("missing $id", layout.contains("android:id=\"@+id/$id\""))
        }
    }

    @Test fun trafficIsInlineAndAddressPrecedesStatus() {
        val traffic = layout.indexOf("android:id=\"@+id/traffic_text\"")
        val address = layout.indexOf("android:id=\"@+id/address_row\"")
        val status = layout.indexOf("android:id=\"@+id/status_row\"")
        val title = layout.indexOf("android:id=\"@+id/title_row\"")
        assertTrue(title >= 0 && traffic > title && address > title && status > address)
        assertTrue(layout.indexOf("android:id=\"@+id/profile_address\"") < status)
        assertTrue(!layout.contains("android:id=\"@+id/traffic_row\""))
    }
}
