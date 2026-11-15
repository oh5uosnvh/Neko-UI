package io.nekohasekai.sagernet.group

import android.app.Application
import android.widget.EditText
import io.nekohasekai.sagernet.R
import moe.matsuri.nb4a.ui.UrlTestPreference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android-resource test for the visible URLTest timeout validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UrlTestTimeoutPreferenceTest {
    private fun preference(): UrlTestPreference = UrlTestPreference(RuntimeEnvironment.getApplication()).apply {
        timeout = EditText(context)
    }

    @Test fun subMinimumAndDecimalSecondsAreRejectedWithVisibleMillisecondsError() {
        val preference = preference()
        for (input in listOf("0.1", "100", "499", "", "0", "-1", "2147483648", "100ms")) {
            preference.timeout!!.setText(input)
            assertFalse("invalid timeout $input", preference.validateTimeout())
            val error = preference.timeout!!.error?.toString()
            assertNotNull("visible error for $input", error)
            assertTrue(error!!.contains("500"))
            assertTrue(error.contains("ms"))
        }
    }

    @Test fun minimumAndNormalIntegerMillisecondsAreAccepted() {
        val preference = preference()
        for (input in listOf("500", "501", "3000", "120000")) {
            preference.timeout!!.setText(input)
            assertTrue("valid timeout $input", preference.validateTimeout())
            assertNull(preference.timeout!!.error)
        }
    }

    @Test fun errorTextComesFromAndroidResources() {
        val preference = preference()
        preference.timeout!!.setText("100")
        assertFalse(preference.validateTimeout())
        assertEquals(
            preference.context.getString(R.string.connection_test_timeout_invalid, 500),
            preference.timeout!!.error.toString(),
        )
    }
}
