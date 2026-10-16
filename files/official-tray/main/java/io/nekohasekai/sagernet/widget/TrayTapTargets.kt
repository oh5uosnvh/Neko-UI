package io.nekohasekai.sagernet.widget

import android.view.View
import io.nekohasekai.sagernet.R

/** The whole tray runs the latency test. Only the info icon opens IP details. */
internal object TrayTapTargets {
    fun bind(root: View, onTest: () -> Unit, onInfo: () -> Unit) {
        val test = View.OnClickListener { onTest() }
        root.setOnClickListener(test)
        listOf(R.id.bar_speed_zone, R.id.bar_delay_zone, R.id.bar_network_card).forEach {
            root.findViewById<View>(it).setOnClickListener(test)
        }
        root.findViewById<View>(R.id.bar_ip_info).setOnClickListener { onInfo() }
    }
}
