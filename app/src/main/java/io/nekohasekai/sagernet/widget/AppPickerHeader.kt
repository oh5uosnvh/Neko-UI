package io.nekohasekai.sagernet.widget

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** A single filter card is a real row, not a colored/sticky appbar obstruction.
 * Keep the bound EditText/chips alive when recycled so focus, text and listeners
 * survive scrolling and async app filtering without another inflation.
 */
internal class AppPickerHeader(private val content: View) {
    init {
        (content.parent as? ViewGroup)?.removeView(content)
        content.layoutParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun createHolder(): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(
        android.widget.FrameLayout(content.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    ) {}

    fun bindHolder(holder: RecyclerView.ViewHolder) {
        val shell = holder.itemView as ViewGroup
        if (content.parent !== shell) {
            (content.parent as? ViewGroup)?.removeView(content)
            shell.removeAllViews()
            shell.addView(content)
        }
    }

    /** Keep status feedback below the filters, never over the search/chip controls. */
    fun placeStatus(status: View) {
        (status.parent as? ViewGroup)?.removeView(status)
        status.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        status.minimumHeight = (96 * content.resources.displayMetrics.density).toInt()
        (content as ViewGroup).addView(status)
    }

    companion object {
        const val VIEW_TYPE = -1
        const val ROWS = 1
        /** Header is not an app; clamp for empty/rapidly filtered fast-scroll lists. */
        fun section(position: Int, appCount: Int, label: (Int) -> String): String =
            if (position in ROWS until appCount + ROWS) label(position - ROWS) else ""
    }
}
