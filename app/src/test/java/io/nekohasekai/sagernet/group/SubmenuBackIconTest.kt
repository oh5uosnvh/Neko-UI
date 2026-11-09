package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.menu.MenuBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.FlMenuPopup
import io.nekohasekai.sagernet.widget.SubmenuBackIcon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubmenuBackIconTest {
    @Test fun actualInkMatchesCapitalUAndKeepsOriginalSlotInBothThemes() {
        val app = RuntimeEnvironment.getApplication()
        for (night in listOf(false, true)) {
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val ctx = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_SagerNet)
            val menu = MenuBuilder(ctx)
            val anchor = View(ctx)
            val popup = FlMenuPopup(anchor, menu, { true }, null, null)
            val method = FlMenuPopup::class.java.getDeclaredMethod("backRow", CharSequence::class.java).apply { isAccessible = true }
            val row = method.invoke(popup, "URL Test") as LinearLayout
            val icon = row.getChildAt(0) as SubmenuBackIcon
            val title = row.getChildAt(1) as TextView
            assertEquals(20, icon.layoutParams.width)
            assertEquals(24, icon.layoutParams.height)
            assertEquals(10, (title.layoutParams as ViewGroup.MarginLayoutParams).marginStart)
            assertEquals("URL Test", title.text.toString())
            val reference = Rect()
            title.paint.getTextBounds("U", 0, 1, reference)
            assertEquals(reference.height(), icon.referenceInkHeight())
            icon.measure(View.MeasureSpec.makeMeasureSpec(20, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(24, View.MeasureSpec.EXACTLY))
            icon.layout(0, 0, 20, 24)
            val bitmap = Bitmap.createBitmap(20, 24, Bitmap.Config.ARGB_8888)
            icon.draw(Canvas(bitmap))
            var minY = 24
            var maxY = -1
            val inkColors = ArrayList<Int>()
            for (y in 0 until 24) for (x in 0 until 20) {
                val color = bitmap.getPixel(x, y)
                if (Color.alpha(color) >= 100) {
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    inkColors += color
                }
            }
            assertTrue(inkColors.isNotEmpty())
            assertTrue("ink=${maxY - minY + 1} U=${reference.height()}",
                kotlin.math.abs(maxY - minY + 1 - reference.height()) <= 2)
            val brightness = inkColors.map { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 }.average()
            assertTrue("night=$night brightness=$brightness", if (night) brightness > 180 else brightness < 100)
            assertEquals(24, icon.height)
            bitmap.recycle()
        }
    }
}
