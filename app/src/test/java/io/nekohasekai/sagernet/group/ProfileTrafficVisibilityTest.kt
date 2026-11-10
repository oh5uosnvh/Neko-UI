package io.nekohasekai.sagernet.group

import android.app.Application
import android.view.View
import android.widget.TextView
import io.nekohasekai.sagernet.ui.bindProfileTraffic
import io.nekohasekai.sagernet.ui.shouldShowProfileTraffic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ProfileTrafficVisibilityTest {
    @Test fun zeroCountersAreHiddenAndAnyDirectionWithBytesIsVisible() {
        assertFalse(shouldShowProfileTraffic(true, false, 0L, 0L))
        assertTrue(shouldShowProfileTraffic(true, false, 1L, 0L))
        assertTrue(shouldShowProfileTraffic(true, false, 0L, 1L))
        assertTrue(shouldShowProfileTraffic(true, false, 1234L, 5678L))
        assertFalse(shouldShowProfileTraffic(false, false, 1234L, 5678L))
        assertFalse(shouldShowProfileTraffic(true, true, 1234L, 5678L))
        // The test is independent of tx+rx overflow: inspect directions, not their sum.
        assertTrue(shouldShowProfileTraffic(true, false, Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun recycledViewAndZeroResetClearOldTextVisibilityAndTranslation() {
        val text = TextView(RuntimeEnvironment.getApplication())
        bindProfileTraffic(text, true, false, 1024L, 2048L)
        assertEquals(View.VISIBLE, text.visibility)
        assertTrue(text.text.isNotBlank())
        assertTrue(text.translationY > 0f)
        bindProfileTraffic(text, true, false, 0L, 0L)
        assertEquals(View.GONE, text.visibility)
        assertEquals("", text.text.toString())
        assertEquals(0f, text.translationY, 0f)
        bindProfileTraffic(text, true, false, 0L, 1L)
        assertEquals(View.VISIBLE, text.visibility)
        bindProfileTraffic(text, false, false, 0L, 1L)
        assertEquals(View.GONE, text.visibility)
        assertEquals("", text.text.toString())
    }

    @Test fun persistedSnapshotAndLiveSnapshotsUseTheSameBindingWithoutDoubleCounting() {
        val text = TextView(RuntimeEnvironment.getApplication())
        bindProfileTraffic(text, true, false, 2048L, 0L)
        val persisted = text.text.toString()
        bindProfileTraffic(text, true, false, 2048L, 0L)
        assertEquals(persisted, text.text.toString())
        bindProfileTraffic(text, true, false, 4096L, 0L)
        assertNotEquals(persisted, text.text.toString())
        assertEquals(View.VISIBLE, text.visibility)
        bindProfileTraffic(text, true, true, 4096L, 0L)
        assertEquals(View.GONE, text.visibility)
    }
}
