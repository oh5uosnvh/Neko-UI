package io.nekohasekai.sagernet.group

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.withoutSettingsRowPress
import io.nekohasekai.sagernet.widget.SettingsCardAdapter
import io.nekohasekai.sagernet.widget.SettingsCardDecoration
import io.nekohasekai.sagernet.widget.SettingsCardStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsCardGeometryTest {
    private class Fixture(night: Boolean = false, configure: (PreferenceScreen) -> Unit) : AutoCloseable {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet) }
        val context: ContextThemeWrapper
        val screen: PreferenceScreen
        val adapter: SettingsCardAdapter
        val list: RecyclerView
        init {
            controller.setup().visible()
            val config = Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            context = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_SagerNet)
            val manager = PreferenceManager(context)
            screen = manager.createPreferenceScreen(context)
            manager.setPreferences(screen)
            configure(screen)
            adapter = SettingsCardAdapter(screen)
            list = RecyclerView(context).withoutSettingsRowPress().apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@Fixture.adapter
            }
            activity.setContentView(list)
            layout()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(40))
            layout()
        }
        fun layout(height: Int = 900) {
            list.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 600, height)
            list.viewTreeObserver.dispatchOnPreDraw()
        }
        fun bitmap(): Bitmap = Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888).also {
            list.draw(Canvas(it))
        }
        override fun close() { controller.pause().stop().destroy() }
    }
    private fun row(screen: PreferenceScreen, key: String) = Preference(screen.context).apply {
        this.key = key; title = key; isPersistent = false
    }

    @Test fun headingHasAboutInsetAndOnlySectionEndsHaveVerticalOffsets() {
        Fixture { screen ->
            val group = PreferenceCategory(screen.context).apply { title = "Category"; key = "category" }
            screen.addPreference(group)
            group.addPreference(row(screen, "one"))
            group.addPreference(row(screen, "two"))
        }.use { fixture ->
            val list = fixture.list
            val style = SettingsCardStyle(fixture.context)
            val heading = list.findViewHolderForAdapterPosition(0)!!.itemView
            val title = heading.findViewById<TextView>(android.R.id.title)
            val titleBounds = Rect()
            title.getDrawingRect(titleBounds)
            (heading as android.view.ViewGroup).offsetDescendantRectToMyCoords(title, titleBounds)
            assertEquals(fixture.context.resources.getDimensionPixelSize(R.dimen.settings_card_heading_inset), titleBounds.left)
            assertEquals(View.GONE, heading.findViewById<View>(android.R.id.icon).visibility)
            assertFalse(heading.isClickable)
            assertTrue(ViewCompat.isAccessibilityHeading(heading))
            val decoration = (0 until list.itemDecorationCount).map { list.getItemDecorationAt(it) }
                .filterIsInstance<SettingsCardDecoration>().single()
            fun offsets(position: Int) = Rect().also {
                decoration.getItemOffsets(it, list.findViewHolderForAdapterPosition(position)!!.itemView,
                    list, RecyclerView.State())
            }
            assertEquals(style.gap + style.contentPadding, offsets(0).top)
            assertEquals(0, offsets(0).bottom)
            assertEquals(0, offsets(1).top)
            assertEquals(0, offsets(1).bottom)
            assertEquals(style.contentPadding + style.gap, offsets(2).bottom)
            assertEquals(style.inset, offsets(1).left)
            val node = MaterialCardView(fixture.context)
            assertEquals(node.radius, style.radius, 0.01f)
            assertEquals(node.strokeWidth.toFloat(), style.strokeWidth, 0.01f)
            assertEquals(node.strokeColor, style.stroke)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun dayNightFillGapAndLongScrolledCardHaveNoFalseCapsOrRowLines() {
        for (night in listOf(false, true)) {
            Fixture(night) { screen ->
                screen.addPreference(row(screen, "leading"))
                val group = PreferenceCategory(screen.context).apply { title = "Long category" }
                screen.addPreference(group)
                repeat(80) { group.addPreference(row(screen, "row-$it")) }
            }.use { fixture ->
                val list = fixture.list
                val style = SettingsCardStyle(fixture.context)
                val high = if (night) Color.rgb(37, 38, 40) else Color.WHITE
                val palette = fixture.context.obtainStyledAttributes(intArrayOf(R.attr.colorLayerLow))
                val low = palette.getColor(0, Color.TRANSPARENT)
                palette.recycle()
                assertEquals(high, style.fill)
                val first = list.findViewHolderForAdapterPosition(0)!!.itemView
                val heading = list.findViewHolderForAdapterPosition(1)!!.itemView
                fixture.bitmap().also { bitmap ->
                    assertEquals(low, bitmap.getPixel(0, 100))
                    assertEquals(high, bitmap.getPixel(300, first.bottom + 2))
                    val gapY = first.bottom + style.contentPadding + style.gap / 2
                    assertEquals(low, bitmap.getPixel(300, gapY))
                    assertEquals(high, bitmap.getPixel(300, heading.bottom + 2))
                    bitmap.recycle()
                }
                val index = fixture.adapter.index
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(30, -20)
                fixture.layout(400)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                fixture.layout(400)
                assertNull(list.findViewHolderForAdapterPosition(1))
                assertNull(list.findViewHolderForAdapterPosition(fixture.adapter.itemCount - 1))
                repeat(3) {
                    fixture.bitmap().also { bitmap ->
                        val x = style.inset + style.strokeWidth.toInt() + 2
                        assertEquals("missing top must be straight, not a cap", high, bitmap.getPixel(x, 1))
                        assertEquals("missing bottom must be straight, not a cap", high, bitmap.getPixel(x, 398))
                        assertEquals(low, bitmap.getPixel(0, 200))
                        bitmap.recycle()
                    }
                }
                assertSame("drawing cannot rebuild full-list metadata", index, fixture.adapter.index)
                assertTrue(list.childCount < fixture.adapter.itemCount / 2)
            }
        }
    }

    @Test fun adapterReplacementRemovesItsDecorationAndCanReattach() {
        Fixture { it.addPreference(row(it, "single")) }.use { fixture ->
            val list = fixture.list
            assertEquals(1, list.itemDecorationCount)
            list.adapter = null
            assertEquals(0, list.itemDecorationCount)
            list.adapter = fixture.adapter
            fixture.layout()
            assertEquals(1, list.itemDecorationCount)
            fixture.screen.addPreference(row(fixture.screen, "later"))
            shadowOf(Looper.getMainLooper()).idle()
            fixture.layout()
            assertEquals(2, fixture.adapter.itemCount)
            assertEquals(1, fixture.adapter.index.sections.size)
        }
    }
}
