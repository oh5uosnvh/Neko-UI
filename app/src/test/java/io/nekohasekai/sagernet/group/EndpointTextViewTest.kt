package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.EndpointTextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class EndpointTextViewTest {
    private fun view(width: Int): EndpointTextView = EndpointTextView(
        ContextThemeWrapper(org.robolectric.RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet)
    ).apply {
        textSize = 14f
        layoutParams = android.widget.FrameLayout.LayoutParams(width, 40)
        measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY))
    }

    @Test fun narrowEndpointNeverDropsNumericPort() {
        val v = view(120)
        v.text = "nitinlinitinitingyinhjng.com:443"
        val fitted = v.fitForWidth(v.text.toString(), 1)
        assertTrue("fitted=$fitted", fitted.endsWith(":443"))
    }

    @Test fun shortHostAndIpv6KeepTheirCompletePort() {
        val v = view(400)
        v.text = "[2001:db8::1]:8443"
        v.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY))
        assertEquals("[2001:db8::1]:8443", v.text.toString())
    }

    @Test fun measuredWidthNotCharacterCountControlsTruncation() {
        val v = view(400)
        val value = "abcdefghijklmno.example.test:443"
        assertEquals(value, v.fitForWidth(value, 400))
    }

    @Test fun multiPortSuffixStaysIntactWhenHostIsShortened() {
        val v = view(120)
        val fitted = v.fitForWidth("very-long-host.example.test:443,8443", 120)
        assertTrue("fitted=$fitted", fitted.endsWith(":443,8443"))
    }
}
