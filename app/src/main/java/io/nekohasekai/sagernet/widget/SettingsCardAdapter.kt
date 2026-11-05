package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.clearSettingsRowPress
import java.util.IdentityHashMap

/** Settings-only: keep AndroidX binding/data/dependencies, replace only section presentation. */
fun PreferenceFragmentCompat.createSettingsCardAdapter(screen: PreferenceScreen): SettingsCardAdapter {
    setDivider(null)
    setDividerHeight(0)
    return SettingsCardAdapter(screen)
}

/** Immutable, rebuilt on hierarchy/visibility changes, never from the drawing loop. */
internal class SettingsCardIndex(source: List<Preference>) {
    val preferences = ArrayList<Preference>()
    val sourcePositions: IntArray
    val sourceToPosition = IntArray(source.size) { RecyclerView.NO_POSITION }
    val sections = ArrayList<IntRange>()
    val sectionAt: IntArray

    init {
        val positions = ArrayList<Int>()
        val populated = IdentityHashMap<PreferenceCategory, Boolean>()
        fun hasContent(group: PreferenceCategory): Boolean = populated[group] ?: run {
            var found = false
            for (i in 0 until group.preferenceCount) {
                val child = group.getPreference(i)
                if (!child.isVisible) continue
                // Only categories are headings; a nested PreferenceScreen/custom group is
                // still a real clickable preference even when it has no children here.
                if (child !is PreferenceCategory || hasContent(child)) found = true
            }
            populated[group] = found
            found
        }
        fun closeEmptyHeadings(first: Int) {
            while (preferences.size > first) {
                preferences.removeAt(preferences.lastIndex)
                sourceToPosition[positions.removeAt(positions.lastIndex)] = RecyclerView.NO_POSITION
            }
        }
        var first = 0
        var hasRows = false
        var previousHeading: PreferenceCategory? = null
        source.forEachIndexed { sourcePosition, preference ->
            if (preference is PreferenceCategory) {
                // Do not mutate isVisible: a later visible child must revive its category.
                if (!hasContent(preference)) return@forEachIndexed
                if (hasRows) {
                    sections.add(first until preferences.size)
                    first = preferences.size
                    hasRows = false
                } else if (previousHeading != null) {
                    var parent = preference.parent
                    while (parent != null && parent !== previousHeading) parent = parent.parent
                    // Only keep stacked ancestor headings. An unrelated heading with no
                    // displayed content (e.g. a collapsed group) must not label its sibling.
                    if (parent == null) closeEmptyHeadings(first)
                }
                previousHeading = preference
                // Consecutive parent/nested headings stay with their first content row,
                // rather than producing an empty parent-heading card.
                if (preference.title.isNullOrEmpty() && preference.summary.isNullOrEmpty() &&
                    preference.icon == null) return@forEachIndexed
            } else {
                hasRows = true
            }
            sourceToPosition[sourcePosition] = preferences.size
            preferences.add(preference)
            positions.add(sourcePosition)
        }
        if (!hasRows) closeEmptyHeadings(first)
        if (preferences.size > first) sections.add(first until preferences.size)
        sourcePositions = positions.toIntArray()
        sectionAt = IntArray(preferences.size)
        sections.forEachIndexed { section, range ->
            for (position in range) sectionAt[position] = section
        }
    }
}

/**
 * A presentation adapter, not a new preference hierarchy. Original Preference objects and the
 * stock adapter own IDs, listeners, widget inflation, resetState(), payloads and persistence.
 * Filtering only empty/untitled category headings avoids zero-height recycled ghost rows.
 */
@SuppressLint("RestrictedApi")
class SettingsCardAdapter(screen: PreferenceScreen) : RecyclerView.Adapter<PreferenceViewHolder>(),
    PreferenceGroup.PreferencePositionCallback {
    private val source = PreferenceGroupAdapter(screen)
    internal var index = buildIndex()
        private set
    private var decoration: SettingsCardDecoration? = null
    private var observing = false

    private fun buildIndex() = SettingsCardIndex(List(source.itemCount) {
        source.getItem(it)!!.also { preference ->
            if (preference is PreferenceCategory) {
                preference.layoutResource = R.layout.preference_settings_card_heading
            }
        }
    })

    private val changes = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = refreshStructure()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refreshStructure()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refreshStructure()
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = refreshStructure()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) =
            onItemRangeChanged(positionStart, itemCount, null)

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            if ((positionStart until positionStart + itemCount).any { source.getItem(it) is PreferenceCategory }) {
                val next = buildIndex()
                val structureChanged = !next.sourcePositions.contentEquals(index.sourcePositions) ||
                    next.sections != index.sections
                index = next
                if (structureChanged) {
                    notifyDataSetChanged()
                    return
                }
            }
            // Keep AndroidX's non-null Preference payload: the same holder/widget is rebound
            // in place, so ReferenceSwitch's running animation is not replaced by a crossfade.
            for (sourcePosition in positionStart until positionStart + itemCount) {
                val position = index.sourceToPosition.getOrNull(sourcePosition) ?: continue
                if (position != RecyclerView.NO_POSITION) notifyItemChanged(position, payload)
            }
        }
    }

    init {
        setHasStableIds(source.hasStableIds())
        observe()
    }

    private fun observe() {
        if (!observing) {
            source.registerAdapterDataObserver(changes)
            observing = true
        }
    }

    private fun refreshStructure() {
        index = buildIndex()
        // Stock PreferenceGroupAdapter also uses this for hierarchy changes by default.
        // It invalidates cached decoration insets, including a recycled new first/last row.
        notifyDataSetChanged()
    }

    fun getItem(position: Int): Preference? = index.preferences.getOrNull(position)
    override fun getItemCount(): Int = index.preferences.size
    override fun getItemId(position: Int): Long = source.getItemId(index.sourcePositions[position])

    override fun getItemViewType(position: Int): Int =
        source.getItemViewType(index.sourcePositions[position])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreferenceViewHolder =
        source.onCreateViewHolder(parent, viewType)

    override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        source.onBindViewHolder(holder, index.sourcePositions[position])
        holder.setDividerAllowedAbove(false)
        holder.setDividerAllowedBelow(false)
        ViewCompat.setAccessibilityHeading(holder.itemView, getItem(position) is PreferenceCategory)
        if (getItem(position) is PreferenceCategory) {
            // The stock category reserves an invisible icon even when none is supplied.
            // Keep a real category icon, but never keep its empty 56dp title column.
            holder.findViewById(android.R.id.icon)?.visibility =
                if (getItem(position)?.icon == null) android.view.View.GONE else android.view.View.VISIBLE
        }
        clearSettingsRowPress(holder.itemView)
    }

    override fun getPreferenceAdapterPosition(key: String): Int =
        mappedPosition(source.getPreferenceAdapterPosition(key))

    override fun getPreferenceAdapterPosition(preference: Preference): Int =
        mappedPosition(source.getPreferenceAdapterPosition(preference))

    private fun mappedPosition(sourcePosition: Int): Int {
        val position = index.sourceToPosition.getOrNull(sourcePosition) ?: return RecyclerView.NO_POSITION
        if (position != RecyclerView.NO_POSITION) return position
        val category = source.getItem(sourcePosition) as? PreferenceCategory ?: return position
        // scrollToPreference(untitledCategory) still lands on that category's first child.
        for (i in sourcePosition + 1 until source.itemCount) {
            var parent = source.getItem(i)?.parent
            while (parent != null && parent !== category) parent = parent.parent
            if (parent === category && index.sourceToPosition[i] != RecyclerView.NO_POSITION) {
                return index.sourceToPosition[i]
            }
        }
        return RecyclerView.NO_POSITION
    }

    private var previousAccessibility: androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate? = null
    private var cardAccessibility: androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        observe()
        index = buildIndex()
        decoration = SettingsCardDecoration(recyclerView.context, this).also {
            recyclerView.addItemDecoration(it)
        }
        val colors = recyclerView.context.obtainStyledAttributes(intArrayOf(R.attr.colorLayerLow))
        recyclerView.setBackgroundColor(colors.getColor(0, 0))
        colors.recycle()
        previousAccessibility = recyclerView.compatAccessibilityDelegate
        cardAccessibility = SettingsCardAccessibility(recyclerView, this).also {
            recyclerView.setAccessibilityDelegateCompat(it)
        }
        source.onAttachedToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        decoration?.let { recyclerView.removeItemDecoration(it) }
        decoration = null
        if (recyclerView.compatAccessibilityDelegate === cardAccessibility) {
            recyclerView.setAccessibilityDelegateCompat(previousAccessibility)
        }
        previousAccessibility = null
        cardAccessibility = null
        source.unregisterAdapterDataObserver(changes)
        observing = false
        source.onDetachedFromRecyclerView(recyclerView)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewRecycled(holder: PreferenceViewHolder) = source.onViewRecycled(holder)
    override fun onFailedToRecycleView(holder: PreferenceViewHolder): Boolean = source.onFailedToRecycleView(holder)
    override fun onViewAttachedToWindow(holder: PreferenceViewHolder) = source.onViewAttachedToWindow(holder)
    override fun onViewDetachedFromWindow(holder: PreferenceViewHolder) = source.onViewDetachedFromWindow(holder)
}

/** Stock preference accessibility only recognizes PreferenceGroupAdapter, so forward it here. */
@Suppress("DEPRECATION")
private class SettingsCardAccessibility(
    private val list: RecyclerView,
    private val adapter: SettingsCardAdapter,
) : androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate(list) {
    private val defaultItemDelegate = super.getItemDelegate()
    private val preferenceItemDelegate = object : androidx.core.view.AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(
            host: android.view.View,
            info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat,
        ) {
            defaultItemDelegate.onInitializeAccessibilityNodeInfo(host, info)
            adapter.getItem(list.getChildAdapterPosition(host))?.onInitializeAccessibilityNodeInfo(info)
        }

        override fun performAccessibilityAction(host: android.view.View, action: Int, args: android.os.Bundle?): Boolean =
            defaultItemDelegate.performAccessibilityAction(host, action, args)
    }

    override fun getItemDelegate(): androidx.core.view.AccessibilityDelegateCompat = preferenceItemDelegate
}
