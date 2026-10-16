package io.nekohasekai.sagernet.widget

import android.view.View
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService

/** Presentation only: invoked after the official state update, before layout. */
internal object TrayStatusText {
    fun apply(root: View, state: BaseService.State) {
        root.findViewById<TextView>(R.id.status).setText(
            if (state == BaseService.State.Connected) R.string.neko_handshake_testing
            else R.string.neko_handshake_idle
        )
    }
}
