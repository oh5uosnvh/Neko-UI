package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
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

    @Test fun actualLayoutWidthChangeRefitsFromOriginalEndpoint() {
        val context = ContextThemeWrapper(
            org.robolectric.RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet
        )
        val v = EndpointTextView(context).apply {
            textSize = 14f
            text = "nitinlinitinitingyinhjng.com:443"
        }
        val parent = FrameLayout(context)
        parent.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 40
        ))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 320, 40)
        assertEquals("nitinlinitinitingyinhjng.com:443", v.text.toString())

        // Simulates a RecyclerView column change/rebind where the first measure
        // was wider than the final laid-out child.
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY)
        )
        parent.layout(0, 0, 120, 40)
        assertTrue("text=${v.text}", v.text.toString().contains("…"))
        assertFalse("text=${v.text}", v.text.toString().endsWith(":4"))
        assertTrue("text=${v.text} width=${v.width} measured=${v.measuredWidth}", v.text.toString().contains("…"))
    }

    @Test fun rebindingShortEndpointRestoresFullSourceAfterTruncatedEndpoint() {
        val v = view(120)
        v.text = "very-long-host.example.test:321"
        v.layout(0, 0, 120, 40)
        assertTrue(v.text.toString().endsWith(":321"))

        v.text = "short.example:8443"
        v.layout(0, 0, 120, 40)
        assertEquals("short.example:8443", v.text.toString())
    }
}
