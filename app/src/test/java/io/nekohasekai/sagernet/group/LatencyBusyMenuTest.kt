package io.nekohasekai.sagernet.group

import android.app.Application
import android.view.ContextThemeWrapper
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.LatencyTestMenuPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class LatencyBusyMenuTest {
    @Test fun runningAndReservedHideTestsAndBothCleanupActionsThenRestoreTheSameOrder() {
        val c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet)
        val menu = MenuBuilder(c)
        SupportMenuInflater(c).inflate(R.menu.add_profile_menu, menu)
        val more = menu.findItem(R.id.action_misc).subMenu!!
        val ids = listOf(R.id.action_connection_tcp_ping, R.id.action_connection_url_test,
            R.id.action_connection_test_clear_results, R.id.action_connection_test_delete_unavailable)
        val original = ids.associateWith { more.findItem(it).order }
        repeat(3) {
            LatencyTestMenuPolicy.apply(menu, true)
            ids.forEach { id -> assertFalse("busy hides $id", more.findItem(id).isVisible) }
            assertTrue(more.findItem(R.id.action_order_layout).isVisible)
            LatencyTestMenuPolicy.apply(menu, false)
            ids.forEach { id ->
                assertTrue("finished restores $id", more.findItem(id).isVisible)
                assertEquals(original[id], more.findItem(id).order)
            }
        }
        assertTrue(LatencyTestMenuPolicy.isCleanup(R.id.action_connection_test_clear_results))
        assertTrue(LatencyTestMenuPolicy.isCleanup(R.id.action_connection_test_delete_unavailable))
        assertFalse(LatencyTestMenuPolicy.isCleanup(R.id.action_order_layout))
    }
    @Test fun bothTestMethodsNavigateToTheThreeExactScopeRows() {
        val c = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_SagerNet)
        val menu = MenuBuilder(c)
        SupportMenuInflater(c).inflate(R.menu.add_profile_menu, menu)
        for (id in listOf(R.id.action_connection_tcp_ping, R.id.action_connection_url_test)) {
            val sub = menu.findItem(id).subMenu!!
            assertEquals(3, sub.size())
            assertEquals(listOf("全部测试", "测试超时", "测试未测"),
                (0 until sub.size()).map { sub.getItem(it).title.toString() })
        }
    }
}
