package io.nekohasekai.sagernet.widget

import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

/** Same entries/layout/background as the outbound editor, anchored to the card's outbound label. */
object RouteOutboundMenu {
    fun show(anchor: View, outbound: Long, onChoice: (Int) -> Unit): ListPopupWindow {
        val context = anchor.context
        val labels = context.resources.getStringArray(R.array.outbound_entry)
        val current = when(outbound) { 0L -> 0; -1L -> 1; -2L -> 2; else -> 3 }
        val adapter = object : ArrayAdapter<String>(context, R.layout.simple_menu_dropdown_item, labels) {
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val view = super.getView(position, recycled, parent) as TextView
                view.setBackgroundColor(if (position == current) context.getColorAttr(R.attr.colorMaterial100) else android.graphics.Color.TRANSPARENT)
                view.isSelected = position == current
                return view
            }
        }
        val popup = ListPopupWindow(context).apply {
            anchorView = anchor
            setAdapter(adapter)
            isModal = true
            setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_spinner_dropdown))
            val measure = TextView(context).apply { setTextAppearance(context, android.R.style.TextAppearance_Material_Subhead) }
            val padding = (48 * context.resources.displayMetrics.density).toInt()
            val width = labels.maxOf { measure.paint.measureText(it).toInt() } + padding
            setContentWidth(width.coerceAtLeast((144 * context.resources.displayMetrics.density).toInt()))
        }
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            onChoice(position)
        }
        popup.show()
        popup.setSelection(current)
        return popup
    }
}
