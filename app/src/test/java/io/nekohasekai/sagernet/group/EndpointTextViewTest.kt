package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.EndpointTextView
import io.nekohasekai.sagernet.widget.TwoColumnProtocolLabel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EndpointTextViewTest {
    private val context get() = ContextThemeWrapper(
        org.robolectric.RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet)

    private fun view(width: Int): EndpointTextView = EndpointTextView(context).apply {
        textSize = 14f
        size(this, width)
    }

    private fun size(view: View, width: Int, height: Int = 48) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun render(v: EndpointTextView, suffix: String, truncated: Boolean? = null) {
        val source = v.text.toString()
        val image = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(image))
        val layout = requireNotNull(v.renderedLayout)
        // Render the same shaped text on a larger UNCLIPPED canvas. Every ink
        // pixel must also exist in the actual view, including the last digit's
        // italic overhang and top/bottom marks (caret checks alone miss these).
        val guard = 64
        val reference = Bitmap.createBitmap(v.width + guard * 2, v.height + guard * 2,
            Bitmap.Config.ARGB_8888)
        val referenceCanvas = Canvas(reference)
        referenceCanvas.translate(guard + v.renderedX, guard + v.renderedY)
        referenceCanvas.scale(v.renderedScale, v.renderedVerticalScale)
        layout.draw(referenceCanvas)
        try {
            var inkPixels = 0
            for (y in 0 until reference.height) for (x in 0 until reference.width) {
                val expected = reference.getPixel(x, y)
                if (android.graphics.Color.alpha(expected) == 0) continue
                inkPixels++
                val localX = x - guard
                val localY = y - guard
                assertTrue("ink outside view at $localX,$localY text=${layout.text}",
                    localX in 0 until image.width && localY in 0 until image.height)
                assertEquals("clipped ink at $localX,$localY text=${layout.text}",
                    expected, image.getPixel(localX, localY))
            }
            assertTrue("native drawing produced no ink", inkPixels > 0)
        } finally {
            reference.recycle()
            image.recycle()
        }
        val fitted = layout.text.toString()
        assertTrue("fitted=$fitted", fitted.endsWith(suffix))
        truncated?.let { assertEquals("fitted=$fitted", it, fitted.contains("…")) }
        assertEquals("source must not be overwritten by rendering", source, v.text.toString())
        assertEquals(1, layout.lineCount)
        assertEquals(0, layout.getEllipsisCount(0))
        val visibleWidth = v.width - v.compoundPaddingLeft - v.compoundPaddingRight
        assertTrue("fitted=$fitted line=${layout.getLineWidth(0)} width=$visibleWidth",
            layout.getLineWidth(0) * v.renderedScale <= visibleWidth + 0.01f)
        // Inspect every port character's actual layout position, not just endsWith().
        for (offset in (fitted.length - suffix.length)..fitted.length) {
            val x = layout.getPrimaryHorizontal(offset) * v.renderedScale
            assertTrue("port offset=$offset x=$x width=$visibleWidth fitted=$fitted",
                x >= -0.01f && x <= visibleWidth + 0.01f)
        }
    }

    @Test fun sameWidthLongToLongAndIdenticalRebindNeedNoMeasureOrLayout() {
        val v = view(120)
        repeat(30) { i ->
            val suffix = if (i % 2 == 0) ":443" else ":65535"
            v.text = "bgp01.dn-sefan04851.com$suffix"
            // Intentionally NO measure/layout after the bind: old pre11 fails here.
            render(v, suffix, true)
            v.text = "bgp01.dn-sefan04851.com$suffix"
            render(v, suffix, true)
        }
    }

    @Test fun controlCharactersStaySingleLineAndItalicPortInkIsVisible() {
        val v = view(180)
        v.typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        for (size in listOf(14f, 28f, 56f)) {
            v.textSize = size
            for (suffix in listOf(":7", ":65537")) {
                v.text = "bgp01\n.dn-sefan\r04851\t.com$suffix"
                render(v, suffix)
                assertEquals(1, v.renderedLayout!!.lineCount)
                assertTrue(v.text.contains('\n'))
            }
        }
    }

    @Test fun finalLayoutWidthOverridesProvisionalMeasuredWidth() {
        val v = view(400)
        v.text = "bgp01.dn-sefan04851.com:443"
        render(v, ":443", false)
        v.layout(0, 0, 120, 48)
        render(v, ":443", true)
        size(v, 400)
        render(v, ":443", false)
    }

    @Test fun shortAndBracketedIpv6KeepFullSourceAndPort() {
        val v = view(400)
        v.text = "short.example:8443"
        render(v, ":8443", false)
        v.text = "[2001:db8:ffff::1234]:8443"
        size(v, 120)
        render(v, ":8443", true)
        assertTrue(v.renderedLayout!!.text.toString().endsWith("]:8443"))
    }

    @Test fun listsAndRangesRemainVisibleEvenInPortOnlyFallback() {
        val v = view(120)
        for (ports in listOf("443,8443", "1000-2000", "1000:2000", "443,1000-2000,3000:4000")) {
            v.text = "very-long-host.example.test:$ports"
            render(v, ":$ports")
        }
        size(v, 12)
        v.text = "very-long-host.example.test:443,8443"
        render(v, ":443,8443")
        assertTrue(v.renderedScale < 1f)
    }

    @Test fun finalPaintSpacingPaddingAndRtlAreRespected() {
        val v = view(180)
        v.text = "bgp01.dn-sefan04851.com:443"
        for (font in listOf(Typeface.DEFAULT, Typeface.MONOSPACE, Typeface.DEFAULT_BOLD)) {
            v.typeface = font
            for (spacing in listOf(0f, 0.12f)) {
                v.letterSpacing = spacing
                v.setPadding(13, 0, 17, 0)
                v.layoutDirection = View.LAYOUT_DIRECTION_RTL
                for (size in listOf(14f, 21f, 28f)) {
                    v.textSize = size
                    render(v, ":443", true)
                }
            }
        }
    }

    @Test fun hiddenClearedThenReboundDoesNotRetainOldEndpoint() {
        val v = view(120)
        v.text = "first-long-host.example.test:443"
        render(v, ":443", true)
        v.text = ""
        v.visibility = View.GONE
        v.visibility = View.VISIBLE
        v.text = "second-long-host.example.test:321"
        render(v, ":321", true)
        v.text = "a.io:80"
        render(v, ":80", false)
    }

    @Test fun realCardRendersAddressAndAbbreviatedProtocolsWithoutClipping() {
        val card = LayoutInflater.from(context).inflate(R.layout.layout_profile, null)
        val address = card.findViewById<EndpointTextView>(R.id.profile_address)
        val protocol = card.findViewById<TextView>(R.id.profile_type)
        val status = card.findViewById<TextView>(R.id.profile_status)
        status.text = "331ms"
        address.text = "bgp01.dn-sefan04851.com:443"
        val density = context.resources.displayMetrics.density
        for (full in listOf("Shadowsocks", "ShadowsocksR")) {
            protocol.text = TwoColumnProtocolLabel.display(full, true)
            val width = (190 * density).toInt()
            val height = context.resources.getDimensionPixelSize(R.dimen.profile_card_height)
            size(card, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            card.draw(Canvas(bitmap))
            val expected = if (full == "Shadowsocks") "SS" else "SSR"
            assertEquals(expected, protocol.text.toString())
            assertEquals(0, protocol.layout.getEllipsisCount(0))
            render(address, ":443", true)
            val output = File("build/reports/ui-pre12").apply { mkdirs() }
            File(output, "$expected-card.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
