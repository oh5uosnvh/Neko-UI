package io.nekohasekai.sagernet.group

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ProfileCardGeometryTest {
    @Test fun titleTrafficNeverAddsARowOrConsumesButtonWidth() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_SagerNet)
        val activity = controller.setup().get()
        try {
            val card = LayoutInflater.from(activity).inflate(R.layout.layout_profile, null)
            val name = card.findViewById<TextView>(R.id.profile_name)
            val traffic = card.findViewById<TextView>(R.id.traffic_text)
            val menu = card.findViewById<View>(R.id.double_column_menu)
            val density = activity.resources.displayMetrics.density
            for (twoColumn in listOf(true, false)) {
                for (showAddress in listOf(true, false)) {
                    for (enabled in listOf(true, false)) {
                        val showTraffic = !twoColumn && enabled
                        name.text = "日本04-IEPL-TX（备用线路）"
                        name.minLines = if (showTraffic) 1 else 2
                        name.maxLines = if (showTraffic) 1 else 2
                        traffic.text = "0 B ↓ 0 B ↑"
                        traffic.visibility = if (showTraffic) View.VISIBLE else View.GONE
                        menu.visibility = if (twoColumn) View.VISIBLE else View.GONE
                        for (id in listOf(R.id.edit, R.id.share, R.id.remove)) {
                            card.findViewById<View>(id).visibility = if (twoColumn) View.GONE else View.VISIBLE
                        }
                        card.findViewById<View>(R.id.address_row).visibility = if (showAddress) View.VISIBLE else View.GONE
                        val height = activity.resources.getDimensionPixelSize(if (showAddress) R.dimen.profile_card_height else R.dimen.profile_card_height_without_address)
                        val width = ((if (twoColumn) 190 else 380) * density).toInt()
                        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        card.layout(0, 0, width, height)
                        assertEquals(height, card.measuredHeight)
                        assertTrue(name.width > 0)
                        assertSame(name.parent, traffic.parent)
                        if (showTraffic) {
                            assertTrue(traffic.top >= name.bottom)
                            assertTrue(traffic.bottom <= (traffic.parent as View).height)
                        }
                        if (twoColumn) {
                            assertEquals(View.GONE, traffic.visibility)
                            assertTrue(menu.height >= (48 * density).toInt())
                        }
                        assertTrue(card.findViewById<View>(R.id.status_row).bottom <= height)
                    }
                }
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
