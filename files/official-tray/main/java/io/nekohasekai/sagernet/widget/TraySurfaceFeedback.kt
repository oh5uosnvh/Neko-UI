package io.nekohasekai.sagernet.widget

import android.graphics.drawable.RippleDrawable
import android.view.View
import com.google.android.material.shape.MaterialShapeDrawable

/** Use the SAME themed selectable drawable as upstream layout_main.xml.
 * Foreground placement extends it over BottomAppBar's inset padding without
 * replacing its shaped background. Android owns hotspots, states and animation. */
internal class TraySurfaceFeedback(private val root: View) {
    private val mask = MaterialShapeDrawable().apply {
        fillColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
    }
    init {
        val attrs=root.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val drawable=try { attrs.getDrawable(0)?.mutate() } finally { attrs.recycle() }
        (drawable as? RippleDrawable)?.setDrawableByLayerId(android.R.id.mask,mask)
        if (drawable is RippleDrawable && drawable.findDrawableByLayerId(android.R.id.mask)==null) {
            drawable.addLayer(mask).also { drawable.setId(it,android.R.id.mask) }
        }
        root.foreground=drawable
        root.foregroundGravity=android.view.Gravity.FILL
    }
    fun syncShape() {
        val background=root.background as? MaterialShapeDrawable ?: return
        mask.shapeAppearanceModel=background.shapeAppearanceModel
        mask.interpolation=background.interpolation
    }
}
