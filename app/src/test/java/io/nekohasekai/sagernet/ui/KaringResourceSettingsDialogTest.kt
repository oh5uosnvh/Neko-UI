package io.nekohasekai.sagernet.ui

import android.app.Application
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class KaringResourceSettingsDialogTest {
    @Test
    fun switchRowMeasuresLabelAndVisibleControl() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val changes = mutableListOf<Boolean>()
        val row = createKaringResourceSwitchRow(
            context,
            R.string.karing_resource_enable,
            checked = false,
            change = changes::add,
        )
        val label = row.findViewById<TextView>(R.id.karing_resource_switch_label)
        val control = row.findViewById<SwitchCompat>(R.id.karing_resource_switch_control)
        assertNotNull(label)
        assertNotNull(control)
        assertFalse(control.showText)
        assertEquals("", control.textOn.toString())
        assertEquals("", control.textOff.toString())
    }

    @Test
    fun threeRowsPreserveInitialAndChangedStates() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val values = booleanArrayOf(true, false, true)
        val rows = values.mapIndexed { index, checked ->
            createKaringResourceSwitchRow(
                context,
                R.string.karing_resource_enable,
                checked,
            ) { value -> values[index] = value }
        }
        val controls = rows.map { it.findViewById<SwitchCompat>(R.id.karing_resource_switch_control) }
        assertEquals(values.toList(), controls.map { it.isChecked })
        controls[1].performClick()
        assertEquals(listOf(true, true, true), values.toList())
        controls[0].performClick()
        assertEquals(listOf(false, true, true), values.toList())
    }

    @Test
    fun infoIconInRemoteSwitchRow() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val remote = createKaringResourceSwitchRow(context, R.string.karing_resource_remote, false) {} as LinearLayout
        remote.addView(View(context), 1, LinearLayout.LayoutParams(40, 48))
        val label = remote.findViewById<TextView>(R.id.karing_resource_switch_label)
        val control = remote.findViewById<SwitchCompat>(R.id.karing_resource_switch_control)
        assertNotNull(label)
        assertNotNull(control)
        assertEquals(remote.getChildAt(1)::class.java, View::class.java)
    }

}
