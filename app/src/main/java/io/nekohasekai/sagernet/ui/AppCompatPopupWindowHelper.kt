package io.nekohasekai.sagernet.ui

import android.view.Menu
import android.view.MenuItem
import android.view.View
import io.nekohasekai.sagernet.widget.FlMenu

/** Attach an inflated menu to a self-drawn toolbar anchor. */
object AppCompatPopupWindowHelper {
    fun show(
        menu: Menu?,
        anchor: View,
        onClick: (MenuItem) -> Boolean,
        fallback: () -> Unit = {},
    ) {
        if (menu == null) {
            fallback()
            return
        }
        // Rendering/attachment failures are programming errors. Do not silently
        // replace a broken single-window menu with a second PopupMenu.
        FlMenu.show(anchor, menu, onClick)
    }
}
