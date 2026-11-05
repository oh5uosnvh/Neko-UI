package io.nekohasekai.sagernet.group

import android.app.Application
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.ToolbarFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Restoring a cached settings page must not schedule another activity-level child. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsOwnershipTest {
    @Test fun settingsUsesOwnRestoredChildAndBindsBeforeFirstSlideFrame() {
        QuickPopupTestEnvironment().use {
            val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            val activity = controller.get().apply { setTheme(R.style.Theme_SagerNet) }
            controller.setup().visible()
            try {
                val host = android.widget.FrameLayout(activity).apply { id = View.generateViewId() }
                activity.setContentView(host)
                val settings = SettingsFragment()
                activity.supportFragmentManager.beginTransaction().add(host.id, settings, "settings").commitNow()
                val child = settings.childFragmentManager.findFragmentById(R.id.settings)
                assertTrue(child is PreferenceFragmentCompat)
                assertNull(activity.supportFragmentManager.findFragmentById(R.id.settings))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
                host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 360, 800)
                host.draw(Canvas())
                assertTrue((child as PreferenceFragmentCompat).listView.adapter!!.itemCount > 0)
                activity.supportFragmentManager.beginTransaction().hide(settings).commitNow()
                activity.supportFragmentManager.beginTransaction().show(settings).commitNow()
                assertSame(child, settings.childFragmentManager.findFragmentById(R.id.settings))
                assertEquals(1, activity.supportFragmentManager.fragments.size)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
