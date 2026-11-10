package io.nekohasekai.sagernet.widget

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/**
 * Lightweight modal focus layer for anchored selection popups. PopupWindow cannot
 * use Window.FLAG_DIM_BEHIND, so the scrim lives in the anchor window below the
 * popup. It never receives input or accessibility focus; PopupWindow keeps all
 * BACK/outside-touch semantics.
 */
internal object PopupScrimController {
    private data class Entry(val view: View, var owners: Int)
    private val entries = WeakHashMap<ViewGroup, Entry>()

    @Synchronized
    fun attach(anchor: View): Any? {
        val root = anchor.rootView as? ViewGroup ?: return null
        val existing = entries[root]
        if (existing != null) {
            existing.owners++
            return root
        }
        val scrim = View(anchor.context).apply {
            background = ColorDrawable(Color.argb(46, 0, 0, 0))
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = null
        }
        root.addView(scrim, 0, ViewGroup.LayoutParams(-1, -1))
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // Blur only the underlying activity window. PopupWindow is a separate
            // window and remains sharp above it; older Android falls back to dim.
            root.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                6f, 6f, android.graphics.Shader.TileMode.CLAMP,
            ))
        }
        entries[root] = Entry(scrim, 1)
        return root
    }

    @Synchronized
    fun detach(token: Any?) {
        val root = token as? ViewGroup ?: return
        val entry = entries[root] ?: return
        entry.owners--
        if (entry.owners > 0) return
        (entry.view.parent as? ViewGroup)?.removeView(entry.view)
        if (android.os.Build.VERSION.SDK_INT >= 31) root.setRenderEffect(null)
        entries.remove(root)
    }
}
