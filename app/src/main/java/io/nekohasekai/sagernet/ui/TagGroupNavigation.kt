package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.Parcelable
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Activity-scoped, non-retaining cache. Removed pages can recreate views at the same offset. */
object TagGroupNavigation {
    const val FRAGMENT_TAG = "tag_group_list"
    internal const val STATE_SCROLL = "tag_group_scroll"
    private const val STATE_NAVIGATION = "tag_group_navigation"
    private class Entry {
        var fragment = WeakReference<TagGroupFragment>(null)
        var scroll: Parcelable? = null
    }
    // Values hold no Activity, View, Context or strong Fragment reference.
    private val entries = WeakHashMap<MainActivity, Entry>()

    @JvmOverloads
    fun open(activity: MainActivity, forward: Boolean = true) {
        if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved) return
        val entry = entries.getOrPut(activity) { Entry() }
        val manager = activity.supportFragmentManager
        // MainActivity can use FRAGMENT_TAG when adding this page. Also support its current
        // untagged displayFragment implementation and Android's restored instances.
        val restored = manager.findFragmentByTag(FRAGMENT_TAG) as? TagGroupFragment
            ?: manager.fragments.filterIsInstance<TagGroupFragment>().firstOrNull()
        val fragment = restored ?: entry.fragment.get() ?: TagGroupFragment().apply {
            arguments = Bundle().apply { putParcelable(STATE_SCROLL, entry.scroll) }
        }
        entry.fragment = WeakReference(fragment)
        activity.displayFragment(fragment, forward)
    }

    internal fun remember(activity: MainActivity, fragment: TagGroupFragment) {
        entries.getOrPut(activity) { Entry() }.fragment = WeakReference(fragment)
    }

    internal fun rememberScroll(activity: MainActivity, fragment: TagGroupFragment, state: Parcelable?) {
        val entry = entries.getOrPut(activity) { Entry() }
        entry.fragment = WeakReference(fragment)
        entry.scroll = state
        // Arguments survive FragmentManager state restoration without a static Context cache.
        val args = fragment.arguments ?: Bundle().also { fragment.arguments = it }
        args.putParcelable(STATE_SCROLL, state)
    }

    /** Optional MainActivity save/restore hooks, needed if it removes non-root restored pages. */
    fun saveState(activity: MainActivity, outState: Bundle) {
        outState.putParcelable(STATE_NAVIGATION, entries[activity]?.scroll)
    }

    fun restoreState(activity: MainActivity, savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        entries.getOrPut(activity) { Entry() }.scroll = savedInstanceState.getParcelable(STATE_NAVIGATION)
        val fragment = activity.supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? TagGroupFragment
            ?: activity.supportFragmentManager.fragments.filterIsInstance<TagGroupFragment>().firstOrNull()
        if (fragment != null) remember(activity, fragment)
    }
}
