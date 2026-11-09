package io.nekohasekai.sagernet.group

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.CardLatencyBinding
import io.nekohasekai.sagernet.ui.CardLatencySession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CardLatencyBindingTest {
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @Test fun fastResultHasDottedSpinnerUntil150ThenStaysOpaqueOnEveryRebind() {
        val context = RuntimeEnvironment.getApplication()
        val label = TextView(context)
        val pending = View(context)
        var clock = 0L
        val binding = CardLatencyBinding(label, pending) { clock }
        val session = CardLatencySession()
        session.begin(listOf(91L), 1, clock)
        val entry = session.get(91L)!!
        binding.bind(entry, false)
        assertEquals("", label.text.toString())
        assertNotNull(label.compoundDrawables[2])
        clock = 20L
        advance(20)
        val profile = ProxyEntity(id = 91L, status = 1, ping = 20)
        session.complete(profile, 1)
        label.text = "20ms"
        binding.bind(entry, true)
        assertEquals("", label.text.toString())
        assertNotNull(label.compoundDrawables[2])
        clock = 149L
        advance(129)
        assertEquals("", label.text.toString())
        clock = 150L
        advance(1)
        assertEquals("20ms", label.text.toString())
        assertNull(label.compoundDrawables[2])
        repeat(5) {
            label.text = "20ms"
            binding.bind(entry, true)
            assertEquals("20ms", label.text.toString())
            assertEquals(1f, label.alpha, 0f)
            assertEquals(1f, label.scaleX, 0f)
            assertEquals(1f, label.scaleY, 0f)
            assertNull(label.compoundDrawables[2])
        }
        binding.reset()
    }
    @Test fun scrollingFinishedOrUntargetedRowsNeverStartsOrReplaysAnyAnimation() {
        val label = TextView(RuntimeEnvironment.getApplication()).apply { text = "82ms"; alpha = .2f; scaleX = .92f }
        val binding = CardLatencyBinding(label, View(label.context))
        repeat(20) {
            binding.bind(null, true)
            assertEquals("82ms", label.text.toString())
            assertEquals(1f, label.alpha, 0f)
            assertEquals(1f, label.scaleX, 0f)
            assertNull(label.compoundDrawables[2])
        }
        binding.reset()
    }
    @Test fun recycledHolderCannotReceiveDelayedResultForPreviousNode() {
        val label = TextView(RuntimeEnvironment.getApplication())
        val binding = CardLatencyBinding(label, View(label.context))
        val entry = CardLatencySession.Entry(1, SystemClock.uptimeMillis())
        label.text = "15ms"
        binding.bind(entry, true)
        advance(40)
        binding.reset()
        label.text = "210ms"
        binding.bind(null, true)
        advance(200)
        assertEquals("210ms", label.text.toString())
        assertNull(label.compoundDrawables[2])
    }
    @Test fun previousMeasurementGenerationCannotReplaceNewOne() {
        val session = CardLatencySession()
        session.begin(listOf(7L), 11)
        session.begin(listOf(7L), 12)
        session.complete(ProxyEntity(id = 7L, status = 1, ping = 1), 11)
        assertNull(session.get(7L)!!.result)
        session.complete(ProxyEntity(id = 7L, status = 1, ping = 2), 12)
        assertEquals(2, session.get(7L)!!.result!!.ping)
    }
}
