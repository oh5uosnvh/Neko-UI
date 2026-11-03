package io.nekohasekai.sagernet.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.nekohasekai.sagernet.R
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One scrollbar in the viewport, never in a translated pager page or its blur recording. */
internal class FixedGroupScrollbar(host: GroupListEdgeLayout) {
    private val host = WeakReference(host)
    private val density = host.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val color = host.context.obtainStyledAttributes(intArrayOf(R.attr.colorPrimary)).let {
        try { it.getColor(0, Color.GRAY) } finally { it.recycle() }
    }
    private val lists = WeakHashMap<ExactScrollRecyclerView, RecyclerView.OnScrollListener>()
    private var selected = WeakReference<ExactScrollRecyclerView>(null)
    private var target = WeakReference<ExactScrollRecyclerView>(null)
    private val bounds = RectF()

    /** Local geometry also works before window attach; includes page matrices AND pager scroll. */
    private fun boundsInHost(list: View, root: ViewGroup): RectF {
        bounds.set(0f, 0f, list.width.toFloat(), list.height.toFloat())
        var child = list
        while (child !== root) {
            val parent = child.parent as? ViewGroup ?: break
            child.matrix.mapRect(bounds)
            bounds.offset((child.left - parent.scrollX).toFloat(), (child.top - parent.scrollY).toFloat())
            child = parent
        }
        return bounds
    }
    private var pointer = -1
    private var grab = 0f
    private var fingerTop = 0f
    private var pixelsPerThumbPixel = 0.0
    private var remainder = 0.0
    private var draggingThumb: RectF? = null
    val activeList get() = selected.get()
    val isDragging get() = pointer != -1
    val registeredListCount get() = lists.size

    data class Geometry(val track: RectF, val thumb: RectF, val scrollable: Long)

    fun bind(list: ExactScrollRecyclerView) {
        list.useFixedScrollbar(true)
        if (lists.containsKey(list)) return
        val owner = WeakReference(this)
        val listener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                owner.get()?.host?.get()?.postInvalidateOnAnimation()
            }
        }
        lists[list] = listener
        list.addOnScrollListener(listener)
    }

    private fun belongsTo(list: View, root: ViewGroup): Boolean {
        var view: View? = list
        while (view != null && view !== root) {
            if (view.visibility != View.VISIBLE || view.alpha <= 0f) return false
            view = view.parent as? View
        }
        return view === root
    }

    private fun pagePosition(list: View, pager: ViewPager2): Int {
        val recycler = pager.getChildAt(0) as? RecyclerView ?: return RecyclerView.NO_POSITION
        var page = list
        while (page.parent is View && page.parent !== recycler) page = page.parent as View
        return if (page.parent === recycler) recycler.getChildAdapterPosition(page) else RecyclerView.NO_POSITION
    }

    private fun select(root: GroupListEdgeLayout): ExactScrollRecyclerView? {
        target.get()?.takeIf { isDragging && belongsTo(it, root) }?.let { return it }
        val pager = root.findViewById<ViewPager2>(R.id.group_pager)
        var winner: ExactScrollRecyclerView? = null
        var best = 0f
        for (list in lists.keys.toList()) {
            if (list.width <= 0 || list.height <= 0 || !belongsTo(list, root)) continue
            val position = boundsInHost(list, root)
            val x = position.left
            val y = position.top
            val area = (min(root.width.toFloat(), x + list.width) - max(0f, x)).coerceAtLeast(0f) *
                (min(root.height.toFloat(), y + list.height) - max(0f, y)).coerceAtLeast(0f)
            if (area <= 0f) continue
            if (pager != null && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE &&
                pagePosition(list, pager) == pager.currentItem) {
                winner = list
                break
            }
            if (area > best || (area == best && list === selected.get())) {
                best = area
                winner = list
            }
        }
        selected = WeakReference(winner)
        return winner
    }

    fun geometry(): Geometry? {
        val root = host.get() ?: return null
        val list = select(root) ?: return null
        val extent = list.computeVerticalScrollExtent().toLong().coerceAtLeast(0)
        val scrollable = (list.computeVerticalScrollRange().toLong() - extent).coerceAtLeast(0)
        if (extent == 0L || scrollable == 0L) return null
        val y = boundsInHost(list, root).top
        val tabs = root.findViewById<View>(R.id.group_tab)
        val top = max(max(56f * density, y + list.paddingTop),
            if (tabs?.visibility == View.VISIBLE) tabs.bottom.toFloat() else 0f)
        val bottom = min(root.height.toFloat(), y + list.height - list.paddingBottom)
        val thumbHeight = list.getScrollBarThumbHeight().toFloat().coerceAtMost(bottom - top)
        if (bottom <= top || thumbHeight <= 0f || bottom - top <= thumbHeight) return null
        // Physical right in both LTR/RTL. Deliberately no list/window x in these bounds.
        val right = (root.width - root.paddingRight).toFloat()
        val width = max(3f * density, list.getScrollBarWidth().toFloat())
        val track = RectF(right - width, top, right, bottom)
        val offset = list.computeVerticalScrollOffset().toLong().coerceIn(0, scrollable)
        val thumbY = top + (bottom - top - thumbHeight) * (offset.toDouble() / scrollable).toFloat()
        val thumb = RectF(track.left, thumbY, right, thumbY + thumbHeight)
        draggingThumb?.takeIf { isDragging && target.get() === list }?.let {
            val pinned = it.top.coerceIn(top, bottom - thumbHeight)
            thumb.offsetTo(track.left, pinned)
        }
        return Geometry(track, thumb, scrollable)
    }

    fun draw(canvas: Canvas) {
        val geometry = geometry() ?: return
        val save = canvas.save()
        canvas.clipRect(geometry.track)
        paint.color = color
        paint.alpha = 48
        canvas.drawRoundRect(geometry.track, 2f * density, 2f * density, paint)
        paint.alpha = 255
        canvas.drawRoundRect(geometry.thumb, 2f * density, 2f * density, paint)
        canvas.restoreToCount(save)
    }

    fun touch(event: MotionEvent): Boolean {
        val root = host.get() ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            val geometry = geometry() ?: return false
            val thumb = geometry.thumb
            val inset = 24f * density
            if (event.x < thumb.left - inset || event.x > thumb.right ||
                event.y < geometry.track.top || event.y > geometry.track.bottom ||
                event.y < thumb.top - inset || event.y > thumb.bottom + inset) return false
            val list = activeList ?: return false
            list.stopScroll()
            target = WeakReference(list)
            pointer = event.getPointerId(0)
            grab = (event.y - thumb.top).coerceIn(0f, thumb.height())
            fingerTop = thumb.top
            pixelsPerThumbPixel = geometry.scrollable.toDouble() /
                (geometry.track.height() - thumb.height())
            draggingThumb = RectF(thumb)
            root.parent?.requestDisallowInterceptTouchEvent(true)
            root.postInvalidateOnAnimation()
            return true
        }
        if (!isDragging) return false
        val list = target.get()
        if (event.actionMasked == MotionEvent.ACTION_CANCEL ||
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
            list == null || !belongsTo(list, root)) {
            cancel()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val index = event.findPointerIndex(pointer)
            val geometry = geometry()
            if (index < 0 || geometry == null) { cancel(); return true }
            val top = (event.getY(index) - grab).coerceIn(geometry.track.top,
                geometry.track.bottom - geometry.thumb.height())
            val pixels = (top - fingerTop) * pixelsPerThumbPixel + remainder
            val delta = pixels.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).roundToInt()
            remainder = pixels - delta
            fingerTop = top
            draggingThumb = RectF(geometry.thumb).apply { offsetTo(left, top) }
            // Freeze the conversion during this gesture: changing visible row heights must
            // not add a second delta or jump the grabbed thumb away from the finger.
            list.scrollFixedPixels(delta, when (top) {
                geometry.track.top -> -1
                geometry.track.bottom - geometry.thumb.height() -> 1
                else -> 0
            })
            root.postInvalidateOnAnimation()
        }
        return true
    }

    fun cancel() {
        pointer = -1
        target.clear()
        draggingThumb = null
        remainder = 0.0
        host.get()?.let {
            it.parent?.requestDisallowInterceptTouchEvent(false)
            it.postInvalidateOnAnimation()
        }
    }

    fun detach() {
        cancel()
        selected.clear()
        for ((list, listener) in lists.entries.toList()) {
            list.removeOnScrollListener(listener)
            list.useFixedScrollbar(false)
        }
        lists.clear()
    }
}
