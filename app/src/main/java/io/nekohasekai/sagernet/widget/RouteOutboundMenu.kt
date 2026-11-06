package io.nekohasekai.sagernet.widget

import android.view.View
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R

/** Same entries/layout/background as the outbound editor, anchored to the card's outbound label. */
internal object RouteOutboundMenu {
    fun show(anchor: View, outbound: Long, onChoice: (Int) -> Unit): ChoiceMenuPopup {
        val context = anchor.context
        val labels = context.resources.getStringArray(R.array.outbound_entry)
        val current = when(outbound) { 0L -> 0; -1L -> 1; -2L -> 2; else -> 3 }
        val adapter = ThemedChoiceAdapter(context) { current }.apply { addAll(*labels) }
        // The unified animated list popup (FlClash spring in / shrink+fade out),
        // identical to the settings dropdowns; "代理" label rows share one entrance.
        return ChoiceMenuPopup(anchor, adapter, current) { position ->
            if (position != null) onChoice(position)
        }.apply { show() }
    }
}
