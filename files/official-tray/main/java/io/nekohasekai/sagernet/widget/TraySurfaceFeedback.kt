package io.nekohasekai.sagernet.widget

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.view.View
import com.google.android.material.shape.MaterialShapeDrawable

/** Paint through the same shape as the official BottomAppBar background.
 * This includes navigation inset padding but never fills the FAB cradle hole. */
internal class TraySurfaceFeedback(private val root: View) {
    private val overlay = MaterialShapeDrawable()
    private val color: Int = root.context.obtainStyledAttributes(intArrayOf(android.R.attr.colorControlHighlight)).let {
        val color=it.getColor(0,0x33ffffff);it.recycle();color
    }
    fun draw(canvas: Canvas) {
        if (!root.isPressed) return
        val background=root.background as? MaterialShapeDrawable ?: return
        overlay.shapeAppearanceModel=background.shapeAppearanceModel
        overlay.interpolation=background.interpolation
        overlay.fillColor=ColorStateList.valueOf(color)
        overlay.setBounds(0,0,root.width,root.height)
        overlay.draw(canvas)
    }
}
