package io.nekohasekai.sagernet.widget

import android.view.View
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R

/** Same entries/layout/background as the outbound editor, anchored to the card's outbound label. */
object RouteOutboundMenu {
    fun show(anchor: View, outbound: Long, onChoice: (Int) -> Unit): ListPopupWindow {
        val context = anchor.context
        val labels = context.resources.getStringArray(R.array.outbound_entry)
        val current = when(outbound) { 0L -> 0; -1L -> 1; -2L -> 2; else -> 3 }
        val adapter = ThemedChoiceAdapter(context) { current }.apply { addAll(*labels) }
        val popup = ListPopupWindow(context).apply {
            anchorView = anchor
            setAdapter(adapter)
            isModal = true
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_spinner_dropdown))
            // Measure the real padded card. Clamp on narrow screens so long labels wrap instead of clipping.
            val maximum = ThemedChoiceStyle.maximumPopupWidth(context)
            val parent = android.widget.FrameLayout(context)
            val width = labels.indices.maxOf { position ->
                adapter.getView(position, null, parent).apply {
                    measure(View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                }.measuredWidth
            }
            setContentWidth(width.coerceAtLeast(ThemedChoiceStyle.dp(context, 144)).coerceAtMost(maximum))
        }
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onChoice(position)
        }
        popup.show()
        popup.listView?.let { ThemedChoiceStyle.clipMenu(it) }
        popup.setSelection(current)
        return popup
    }
}
