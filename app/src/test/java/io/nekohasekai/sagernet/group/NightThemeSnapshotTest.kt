package io.nekohasekai.sagernet.group

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import io.nekohasekai.sagernet.ui.theme.NightThemeSnapshot
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NightThemeSnapshotTest {
    @Test fun oldFrameAlphaOnlyProducesExactLinearMidpointOverNewFrame() {
        val old = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val layer = NightThemeSnapshot.SnapshotDrawable(old).apply { setBounds(0, 0, 20, 20) }
        fun pixel(alpha: Float): Int {
            val result = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            layer.opacity = alpha
            layer.draw(Canvas(result))
            return result.getPixel(10, 10).also { result.recycle() }
        }
        assertEquals(Color.WHITE, pixel(1f))
        assertEquals(Color.BLACK, pixel(0f))
        val middle = pixel(.5f)
        assertTrue(Color.red(middle) in 126..129)
        assertEquals(Color.red(middle), Color.green(middle))
        assertEquals(Color.red(middle), Color.blue(middle))
        old.recycle()
        assertEquals(420L, NightThemeSnapshot.DURATION_MS)
    }

    @Test fun realSessionWaitsForNewDrawThenFadesAndCleansUp() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val root = activity.window.decorView
        val content = FrameLayout(activity).apply { setBackgroundColor(Color.BLUE) }
        activity.setContentView(content)
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 320, 640)
        val old = NightThemeSnapshot.capture(root)!!
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val session = NightThemeSnapshot.Session(100, activity, old, night)
        try {
            session.mount(activity, ready = true)
            assertEquals(NightThemeSnapshot.Phase.WAITING_FOR_DRAW, session.phase)
            assertEquals(1f, session.drawable!!.opacity, 0f)
            root.viewTreeObserver.dispatchOnPreDraw()
            val output = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(output)); output.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertEquals(NightThemeSnapshot.Phase.FADING, session.phase)
            val fade = session.animator!!
            fade.currentPlayTime = 210
            assertEquals(.5f, session.drawable!!.opacity, .02f)
            fade.end()
            assertEquals(NightThemeSnapshot.Phase.FINISHED, session.phase)
            assertNull(session.drawable)
            assertNull(session.animator)
        } finally {
            session.finish()
            controller.pause().stop().destroy()
        }
    }
    @Test fun disabledAnimationsAndSameThemeNeverCreateOverlay() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val current = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        try {
            assertNull(NightThemeSnapshot.begin(activity, current))
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            assertNull(NightThemeSnapshot.begin(activity,
                if (current == Configuration.UI_MODE_NIGHT_YES) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES))
        } finally {
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            controller.pause().stop().destroy()
        }
    }
    @Test fun cancellingOrDestroyingSessionReleasesOverlayAndPendingCallbacks() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val bitmap = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        val session = NightThemeSnapshot.Session(1, activity, bitmap, Configuration.UI_MODE_NIGHT_YES)
        session.mount(activity, ready = true)
        session.finish()
        session.finish()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(NightThemeSnapshot.Phase.FINISHED, session.phase)
        assertNull(session.drawable)
        assertFalse(session.owns(activity))
        controller.pause().stop().destroy()
    }
}
