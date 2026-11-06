package io.nekohasekai.sagernet.ui

import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ControlBar

/** The configuration control is a foreground overlay, never a bottom content/tray region. */
internal class ConfigurationControlOverlay(
    private val holder: View,
    private val bar: ControlBar,
) {
    private fun dp(value: Int) = (value * bar.resources.displayMetrics.density).toInt()

    companion object {
        fun shouldShow(isConfiguration: Boolean, isProfileSelect: Boolean, added: Boolean,
                       hidden: Boolean, rootVisible: Boolean, offscreen: Boolean,
                       attached: Boolean): Boolean =
            isConfiguration && !isProfileSelect && added && !hidden && rootVisible && !offscreen && attached
    }

    /** Called only with the main configuration's root, never the profile-select host. */
    fun show(configurationRoot: View?, visible: Boolean) {
        bar.visibility = if (visible && configurationRoot != null) View.VISIBLE else View.GONE
        configurationRoot?.let { padNodeLists(it) }
    }

    fun applyInsets(left: Int, right: Int, bottom: Int) {
        val barParams = bar.layoutParams as CoordinatorLayout.LayoutParams
        val barLeft = dp(14) + left
        val barRight = dp(14) + right
        val barBottom = dp(14) + bottom
        if (barParams.leftMargin != barLeft || barParams.rightMargin != barRight ||
            barParams.bottomMargin != barBottom) {
            barParams.leftMargin = barLeft
            barParams.rightMargin = barRight
            barParams.bottomMargin = barBottom
            bar.layoutParams = barParams
        }
        val content = holder.layoutParams as CoordinatorLayout.LayoutParams
        // Only the actual system/IME safe area is excluded. The 84dp strip floats OVER the list.
        if (content.bottomMargin != bottom) {
            content.bottomMargin = bottom
            holder.layoutParams = content
        }
    }

    private fun padNodeLists(view: View) {
        // RecyclerView descendants contain hundreds of card children; stop at each
        // registered node viewport rather than walking every card on lifecycle callbacks.
        if (view is RecyclerView && view.id == R.id.configuration_list) {
            val padding = dp(84 + 14 + 14)
            if (view.paddingBottom != padding) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, padding)
            view.clipToPadding = false
            return // Stop at actual node lists, not ViewPager2's internal RecyclerView.
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) padNodeLists(view.getChildAt(index))
        }
    }
}
