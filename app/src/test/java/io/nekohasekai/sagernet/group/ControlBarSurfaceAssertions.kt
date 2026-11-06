package io.nekohasekai.sagernet.group

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ControlBar
import org.junit.Assert.*

/** Persistent surface fill and transient touch feedback are different user requirements. */
internal fun assertThemedCapsuleWithoutPressFill(bar: ControlBar) {
    val capsule = bar.findViewById<View>(R.id.capsule)
    assertTrue("the theme capsule must remain visible", capsule.background is GradientDrawable)
    val background = capsule.background as GradientDrawable
    // HTML 参考（spec 7.3）：断开态 = 固定灰蓝双阶渐变（随日夜切换），连接态 = 主题色渐变；
    // 渐变色不在按压/选中/禁用时改变。
    val night = bar.context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val colors = background.colors
    assertNotNull("capsule uses the two-stop HTML gradient", colors)
    assertEquals(2, colors!!.size)
    if (capsule.isSelected) { // Connected: theme gradient (highlight top, theme bottom).
        val theme = TypedValue()
        assertTrue(bar.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, theme, true))
        val primary = if (theme.resourceId != 0)
            bar.resources.getColor(theme.resourceId, bar.context.theme) else theme.data
        assertEquals("capsule gradient bottom follows the theme", primary, colors[1])
        assertEquals("capsule gradient top is the highlight step",
            androidx.core.graphics.ColorUtils.blendARGB(primary, android.graphics.Color.WHITE, .22f),
            colors[0])
    } else {
        assertEquals("capsule gradient top", if (night) 0xFF3A4351.toInt() else 0xFFC7CFDB.toInt(),
            colors[0])
        assertEquals("capsule gradient bottom",
            if (night) 0xFF2E3644.toInt() else 0xFFB5BFCE.toInt(), colors[1])
    }
    assertFalse("press/selected/disabled must not recolor the capsule", background.isStateful)
    assertNull("no ripple/foreground on press or focus", capsule.foreground)
}
