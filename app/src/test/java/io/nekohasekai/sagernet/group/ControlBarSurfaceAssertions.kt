package io.nekohasekai.sagernet.group

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ControlBar
import org.junit.Assert.*

/** Persistent theme fill and transient touch feedback are different user requirements. */
internal fun assertThemedCapsuleWithoutPressFill(bar: ControlBar) {
    val capsule = bar.findViewById<View>(R.id.capsule)
    assertTrue("the theme capsule must remain visible", capsule.background is GradientDrawable)
    val background = capsule.background as GradientDrawable
    val value = TypedValue()
    assertTrue(bar.context.theme.resolveAttribute(R.attr.colorMaterial300, value, true))
    val expected = if (value.resourceId != 0)
        bar.resources.getColor(value.resourceId, bar.context.theme) else value.data
    assertEquals("capsule follows the active theme, not a neutral fill", expected, background.color!!.defaultColor)
    assertFalse("press/selected/disabled must not recolor the capsule", background.isStateful)
    assertNull("no ripple/foreground on press or focus", capsule.foreground)
}
