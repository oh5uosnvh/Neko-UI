package io.nekohasekai.sagernet.widget

import android.view.MotionEvent
import android.view.View
import io.nekohasekai.sagernet.R

/** One content-wide ripple and one latency action. Only the info icon is separate. */
internal object TrayTapTargets {
    fun bind(root: View, onTest: () -> Unit, onInfo: () -> Unit) {
        val content = root.findViewById<View>(R.id.tray_content)
        val test = View.OnClickListener { onTest() }
        // StatsBar also initializes the official tx/rx/status bindings here.
        root.setOnClickListener(test)
        content.setOnClickListener(test)
        listOf(R.id.bar_speed_zone, R.id.bar_delay_zone, R.id.bar_network_card).forEach {
            root.findViewById<View>(it).apply {
                setOnClickListener(null)
                isClickable = false
                isFocusable = false
                background = null
            }
        }
        // BottomAppBar padding outside content still runs the same action.
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { content.isPressed = true; content.drawableHotspotChanged(event.x, event.y) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> content.isPressed = false
            }
            false
        }
        root.findViewById<View>(R.id.bar_ip_info).setOnClickListener { onInfo() }
    }
}
