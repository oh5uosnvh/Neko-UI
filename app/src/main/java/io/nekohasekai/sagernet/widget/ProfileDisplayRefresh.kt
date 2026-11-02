package io.nekohasekai.sagernet.widget

import androidx.recyclerview.widget.RecyclerView

/** Display-only preferences are not part of the node DiffUtil identity/content snapshot. */
object ProfileDisplayRefresh {
    fun apply(list: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        val refresh = Runnable {
            if (list.adapter !== adapter) return@Runnable
            if (adapter.itemCount > 0) adapter.notifyItemRangeChanged(0, adapter.itemCount)
            list.requestLayout()
            list.invalidate()
        }
        if (list.isComputingLayout) list.post(refresh) else refresh.run()
    }
}
