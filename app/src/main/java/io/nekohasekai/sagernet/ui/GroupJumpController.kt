package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.floor

/** A single stable-ID request; no retry loop, database work or synthetic click events. */
internal class GroupJumpController(
    private val list: RecyclerView,
    private val manager: LinearLayoutManager,
    private val ids: () -> List<Long>,
    private val isReady: () -> Boolean,
    private val highlight: (Long) -> Unit,
    private val clearHighlight: () -> Unit,
) : AutoCloseable {
    private var target = -1L
    private var generation = 0
    private var animator: ValueAnimator? = null
    private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var firstFrame = false
    private var active = false
    private val touch = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) cancel()
            return false // Do not consume real clicks, drags, swipes or ripple feedback.
        }
    }
    private val observer = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = dataChanged()
        override fun onItemRangeRemoved(start: Int, count: Int) = dataChanged()
        override fun onItemRangeInserted(start: Int, count: Int) = dataChanged()
        override fun onItemRangeMoved(from: Int, to: Int, count: Int) = dataChanged()
        override fun onItemRangeChanged(start: Int, count: Int) = dataChanged()
        override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) = dataChanged()
    }
    private var snapshot = emptyList<Long>()
    private val observedAdapter = list.adapter
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = onReady()
        override fun onViewDetachedFromWindow(view: View) = cancel()
    }

    init {
        list.addOnItemTouchListener(touch)
        list.addOnAttachStateChangeListener(attachment)
        observedAdapter?.registerAdapterDataObserver(observer)
    }

    /** Called before show(), so even a cached page previously at the bottom enters at card zero. */
    fun prepare(id: Long) {
        cancel()
        if (id <= 0L || id !in ids()) return
        target = id
        snapshot = ids().toList()
        list.stopScroll()
        manager.scrollToPositionWithOffset(0, 0)
        firstFrame = true
    }

    fun start() {
        if (target <= 0L || active) return
        active = true
        preDraw = ViewTreeObserver.OnPreDrawListener {
            if (target <= 0L) return@OnPreDrawListener true
            if (firstFrame) {
                firstFrame = false // Permit one real top-card frame; do not scroll in this draw.
                list.postInvalidateOnAnimation()
            } else if (isReady() && list.isShown && !list.isComputingLayout && list.childCount > 0) {
                removePreDraw()
                animate()
            }
            true
        }.also { list.viewTreeObserver.addOnPreDrawListener(it) }
        list.invalidate()
    }

    /** The host calls this after RESUMED/slide completion; no self-posting readiness loop. */
    fun onReady() {
        if (active && preDraw != null) list.postInvalidateOnAnimation()
    }

    private fun animate() {
        val position = ids().indexOf(target)
        if (position < 0) { cancel(); return }
        val token = generation
        if (position == 0) { finish(token); return }
        // Interpolate adapter-space instead of RecyclerView's unbounded offscreen seek loop.
        // This gives distant and variable-height cards the same 500ms journey without binding
        // the intervening dataset up front. Only visible rows are measured/bound each frame.
        animator = ValueAnimator.ofFloat(0f, position.toFloat()).apply {
            duration = SCROLL_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (token != generation || ids() != snapshot || !list.isShown || !isReady()) {
                    this@GroupJumpController.cancel()
                    return@addUpdateListener
                }
                val coordinate = it.animatedValue as Float
                val row = floor(coordinate).toInt().coerceAtMost(position)
                val sample = manager.findViewByPosition(row) ?: list.getChildAt(0)
                val height = sample?.let { manager.getDecoratedMeasuredHeight(it) }?.coerceAtLeast(1) ?: 1
                manager.scrollToPositionWithOffset(row, -((coordinate - row) * height).toInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token == generation) { animator = null; finish(token) }
                }
            })
            start()
        }
    }

    private fun finish(token: Int) {
        val id = target
        val position = ids().indexOf(id)
        if (token != generation || position < 0) { cancel(); return }
        manager.scrollToPositionWithOffset(position, 0)
        // A pre-draw guarantees the final target holder is laid out and stable-ID checked.
        preDraw = ViewTreeObserver.OnPreDrawListener {
            removePreDraw()
            if (token == generation && isReady() && list.isShown && ids() == snapshot &&
                ids().getOrNull(position) == id &&
                list.findViewHolderForItemId(id)?.itemId == id) {
                target = -1L
                active = false
                highlight(id)
            } else cancel()
            true
        }.also { list.viewTreeObserver.addOnPreDrawListener(it) }
        list.invalidate()
    }

    private fun dataChanged() {
        if (target > 0 && ids() != snapshot) cancel() // Deletion/reorder cannot highlight a recycled card.
    }
    private fun removePreDraw() {
        preDraw?.let { if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnPreDrawListener(it) }
        preDraw = null
    }
    fun cancel() {
        generation++
        target = -1L
        active = false
        removePreDraw()
        animator?.removeAllUpdateListeners()
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        clearHighlight()
    }
    override fun close() {
        cancel()
        list.removeOnItemTouchListener(touch)
        list.removeOnAttachStateChangeListener(attachment)
        observedAdapter?.unregisterAdapterDataObserver(observer)
    }
    companion object { const val SCROLL_DURATION_MS = 500L }
}
