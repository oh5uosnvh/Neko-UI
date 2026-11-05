package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.app.Activity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.TitleBarLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Old-API loading must not resolve/instantiate RenderEffect or RenderNode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class TitleBarLifecycleTest {
    @Test fun detachReattachRestoresPaddingAndRebindsDynamicLayoutWithoutPreDrawLoop() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_SagerNet)
        controller.setup().visible()
        val activity = controller.get()
        val parent = FrameLayout(activity)
        val root = TitleBarLayout(activity)
        val header = FrameLayout(activity).apply { id = R.id.appbar }
        header.addView(Toolbar(activity).apply { id = R.id.toolbar; title = "Settings" },
            FrameLayout.LayoutParams(-1, 64))
        val content = FrameLayout(activity).apply { id = R.id.title_bar_content }
        val list = RecyclerView(activity).apply { setPadding(2, 8, 4, 91) }
        content.addView(list, FrameLayout.LayoutParams(-1, -1))
        root.addView(header, FrameLayout.LayoutParams(-1, -2))
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        parent.addView(root, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(parent)
        fun layout() {
            parent.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, 360, 640)
            root.viewTreeObserver.dispatchOnGlobalLayout()
        }
        try {
            layout()
            val inset = header.bottom - content.top
            assertEquals(8 + inset, list.paddingTop)
            assertFalse(root.useLocalBlur(true))
            assertFalse(root.useLocalBlur(false))
            val bitmap = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            bitmap.recycle()
            parent.removeView(root)
            assertEquals(8, list.paddingTop)
            assertEquals(91, list.paddingBottom)
            assertTrue(list.clipToPadding)
            parent.addView(root, FrameLayout.LayoutParams(-1, -1))
            layout()
            assertEquals(8 + inset, list.paddingTop)
            assertFalse(list.clipToPadding)
            repeat(4) { layout() }
            assertFalse(root.isLayoutRequested)
            content.removeView(list)
            val replacement = RecyclerView(activity).apply { setPadding(0, 3, 0, 73) }
            content.addView(replacement, FrameLayout.LayoutParams(-1, -1))
            layout()
            assertEquals(8, list.paddingTop)
            assertEquals(3 + inset, replacement.paddingTop)
            assertEquals(73, replacement.paddingBottom)
        } finally { controller.pause().stop().destroy() }
    }
}
