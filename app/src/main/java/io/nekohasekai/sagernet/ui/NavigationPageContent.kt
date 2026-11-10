package io.nekohasekai.sagernet.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R

/** Observe existing asynchronous loaders; never duplicate their IO or initialize another page. */
internal class NavigationPageContent(private val root: View, private val navId: Int) {
    private var loaded = false
    private var adapter: RecyclerView.Adapter<*>? = null
    private var text: TextView? = null
    private val changes = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() { loaded = true }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { loaded = true }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { loaded = true }
    }
    private val textChanges = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { loaded = true }
    }

    init {
        when (navId) {
            R.id.nav_group, R.id.nav_route -> {
                val id = if (navId == R.id.nav_group) R.id.group_list else R.id.route_list
                adapter = root.findViewById<RecyclerView>(id)?.adapter
                    ?: if (navId == R.id.nav_group) root.findViewById<RecyclerView>(R.id.tag_list)?.adapter else null
                // Route has a permanent documentation row even before its async DB result.
                loaded = (adapter?.itemCount ?: 0) > if (navId == R.id.nav_group) 0 else 1
                adapter?.registerAdapterDataObserver(changes)
            }
            R.id.nav_logcat -> {
                text = root.findViewById(R.id.textview)
                loaded = !text?.text.isNullOrEmpty()
                // Empty log is valid too: observe the first result, not its string length.
                text?.addTextChangedListener(textChanges)
            }
            else -> loaded = true
        }
    }

    fun isReady(): Boolean {
        if (!loaded) {
            // An explicitly drawn empty result is ready too; don't let tag pages hit the timeout valve.
            loaded = root.findViewById<View>(R.id.tag_empty)?.visibility == View.VISIBLE
        }
        if (!loaded) return false
        if (navId == R.id.nav_about) {
            val cards = root.findViewById<RecyclerView>(
                com.danielstone.materialaboutlibrary.R.id.mal_recyclerview)
            val license = root.findViewById<TextView>(R.id.license)
            return (cards?.adapter?.itemCount ?: 0) > 0 && !license?.text.isNullOrEmpty()
        }
        return true
    }

    fun dispose() {
        adapter?.unregisterAdapterDataObserver(changes)
        adapter = null
        text?.removeTextChangedListener(textChanges)
        text = null
    }
}
