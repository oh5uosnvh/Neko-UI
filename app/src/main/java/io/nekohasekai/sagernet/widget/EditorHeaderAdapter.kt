package io.nekohasekai.sagernet.widget

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Retain the editor view (including its FragmentContainerView), never a ViewHolder.
 *
 * Leave it in the inflated hierarchy until RecyclerView's first natural bind so fragment
 * restoration can find it even before the activity finishes configuring its adapters.
 * A refresh/change animation can request a second holder while the old one still exists;
 * separate shells make that safe without duplicating the fragment or its preference state.
 */
internal class EditorHeaderAdapter(private val content: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(android.widget.FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val shell = holder.itemView as ViewGroup
        if (content.parent === shell) return
        (content.parent as? ViewGroup)?.removeView(content)
        shell.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
