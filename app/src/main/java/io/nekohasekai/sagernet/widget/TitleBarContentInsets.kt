package io.nekohasekai.sagernet.widget

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import java.util.IdentityHashMap

/** Visits structural containers only, never RecyclerView children or adapter data. */
internal class TitleBarContentInsets {
    private class Entry(val view: View) {
        var base = view.paddingTop
        var applied = 0
        val clip = (view as? ViewGroup)?.clipToPadding
    }
    private val entries = IdentityHashMap<View, Entry>()

    /** A vertical stack receives its inset ONCE, in its leading branch. Overlays each get it. */
    fun sync(content: View?, inset: Int): Boolean {
        val targets = ArrayList<View>()
        if (content != null && content.visibility != View.GONE) collect(content, targets)
        var changed = false
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!targets.contains(entry.key)) {
                changed = apply(entry.value, 0) || changed
                entry.value.clip?.let { (entry.key as ViewGroup).clipToPadding = it }
                iterator.remove()
            }
        }
        for (view in targets) {
            val entry = entries[view] ?: Entry(view).also { entries[view] = it }
            if (view is ViewGroup && view.clipToPadding) view.clipToPadding = false
            changed = apply(entry, inset.coerceAtLeast(0)) || changed
        }
        return changed
    }

    private fun apply(entry: Entry, inset: Int): Boolean {
        val view = entry.view
        // External absolute top changes become the new base. Updating only bottom,
        // left, right, system/IME/control insets never resets or accumulates our top.
        if (view.paddingTop != entry.base + entry.applied) entry.base = view.paddingTop
        entry.applied = inset
        val top = entry.base + inset
        if (view.paddingTop == top) return false
        if (view.isPaddingRelative) {
            view.setPaddingRelative(view.paddingStart, top, view.paddingEnd, view.paddingBottom)
        } else view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
        return true
    }

    fun release() {
        for ((view, entry) in entries) {
            apply(entry, 0)
            entry.clip?.let { (view as ViewGroup).clipToPadding = it }
        }
        entries.clear()
    }

    private fun collect(view: View, result: MutableList<View>) {
        if (view is GroupListEdgeLayout) {
            // It owns the pager's top padding so tabs and the title gap are applied
            // exactly once by GroupListEdgeLayout.syncTop().
            return
        }
        if (isScrollSurface(view)) { result += view; return }
        if (view !is ViewGroup) { result += view; return }
        if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) {
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.visibility != View.GONE) {
                    collect(child, result)
                    return
                }
            }
            // Empty dynamic preference/fragment host: reserve the initial gap until
            // its list arrives; transfer the gap rather than applying it twice.
            result += view
            return
        }
        var found = false
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child.visibility == View.GONE) continue
            val before = result.size
            collectScrollBranches(child, result)
            found = found || before != result.size
        }
        if (!found) result += view
    }

    private fun collectScrollBranches(view: View, result: MutableList<View>) {
        if (isScrollSurface(view) || view is LinearLayout) { collect(view, result); return }
        if (view !is ViewGroup) return // Editor scrollbar/empty-label overlays are not inset targets.
        if (view.childCount == 0) { result += view; return }
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child.visibility != View.GONE) collectScrollBranches(child, result)
        }
    }

    private fun isScrollSurface(view: View): Boolean = view is RecyclerView ||
        view is ScrollView || view is NestedScrollView || view is AbsListView || view is EditText
}
