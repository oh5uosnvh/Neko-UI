package io.nekohasekai.sagernet.ui

import android.view.ViewTreeObserver
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.widget.GroupPillTabLayout
import io.nekohasekai.sagernet.widget.GroupTabPill

/** Own selection/scroll while Mediator replaces every tab for an insert/remove notification. */
internal class GroupTabMutation(
    private val tabs: TabLayout,
    private val pager: ViewPager2,
    private val ids: () -> List<Long>,
    private val isAlive: () -> Boolean,
    private val blockSelection: (Boolean) -> Unit,
    private val selectId: (Long, Int) -> Unit,
    private val onFinished: () -> Unit,
) {
    data class Snapshot(val selectedId: Long?, val scrollX: Int)

    private var generation = 0L
    private var pending: ViewTreeObserver.OnPreDrawListener? = null
    private var observer: ViewTreeObserver? = null
    private var previousManualCentering = false
    private var active = false
    val isPending get() = active

    fun capture(selectedId: Long? = ids().getOrNull(pager.currentItem)) =
        Snapshot(selectedId, tabs.scrollX)

    /** Main-thread only. No posted adapter write: a fast undo supersedes a pending removal. */
    fun change(snapshot: Snapshot, mutation: () -> Unit) {
        cancel()
        val ticket = ++generation
        active = true
        try {
            blockSelection(true)
            (tabs as? GroupPillTabLayout)?.let {
                previousManualCentering = it.manualCentering
                it.lockViewport(snapshot.scrollX, pager.currentItem)
                it.manualCentering = true
            }
            mutation()
            align(snapshot)
            // post() is not a layout barrier. Mediator has just rebuilt TabViews whose
            // left/right/strip width are still zero or belong to the previous layout.
            // Reapply after real layout, then once more after pager's layout callbacks.
            var passes = 0
            val listener = ViewTreeObserver.OnPreDrawListener {
                // A listener from a previous dispatch must not cancel a newer mutation.
                if (ticket == generation) {
                    try {
                        if (!isAlive()) {
                            cancel()
                        } else {
                            align(snapshot)
                            if (!tabs.isLayoutRequested && !pager.isLayoutRequested && ++passes >= 2) {
                                cancel()
                                onFinished()
                            } else {
                                tabs.postOnAnimation { if (ticket == generation) tabs.invalidate() }
                            }
                        }
                    } catch (error: Throwable) {
                        cancel()
                        throw error
                    }
                }
                true
            }
            pending = listener
            observer = tabs.viewTreeObserver.also { it.addOnPreDrawListener(listener) }
            tabs.invalidate()
        } catch (error: Throwable) {
            cancel()
            throw error
        }
    }

    private fun align(snapshot: Snapshot) {
        val currentIds = ids()
        val id = snapshot.selectedId?.takeIf { it in currentIds } ?: currentIds.firstOrNull()
        val position = currentIds.indexOf(id)
        if (position >= 0) {
            selectId(id!!, position)
            pager.setCurrentItem(position, false)
            // updateIndicator=false avoids TabLayout's selected-tab centring animator.
            tabs.selectTab(tabs.getTabAt(position), false)
            for (index in 0 until tabs.tabCount) {
                val tab = tabs.getTabAt(index) ?: continue
                tab.view.isSelected = index == position
                (tab.customView as? GroupTabPill)?.settleSelected(index == position)
            }
        }
        // scrollTo cancels no layout work, so this also runs at pre-draw. Never animate
        // from Mediator's temporary tab-zero offset; that visibly flashes the first tabs.
        if (tabs is GroupPillTabLayout) tabs.restoreViewport() else tabs.scrollTo(snapshot.scrollX, 0)
    }

    fun cancel() {
        ++generation
        pending?.let { listener -> observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        observer = null
        val wasActive = active
        pending = null
        active = false
        if (wasActive) {
            (tabs as? GroupPillTabLayout)?.let {
                it.unlockViewport()
                it.manualCentering = previousManualCentering
            }
            blockSelection(false)
        }
    }

    companion object {
        /** Original neighbours survive other deletes/inserts better than a stale index. */
        fun insertionIndex(before: List<Long>, restoredId: Long, current: List<Long>): Int {
            val at = before.indexOf(restoredId)
            if (at < 0) return current.size
            before.drop(at + 1).firstOrNull { it in current }?.let { return current.indexOf(it) }
            before.take(at).lastOrNull { it in current }?.let { return current.indexOf(it) + 1 }
            return at.coerceIn(0, current.size)
        }
    }
}
