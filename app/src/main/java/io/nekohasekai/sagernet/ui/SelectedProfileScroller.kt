package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.floor

/** A bounded 500ms journey from the LIVE viewport, never a reset to row zero. */
internal class SelectedProfileScroller(private val list: RecyclerView) : AutoCloseable {
    private var animator: ValueAnimator? = null
    private var pending: ViewTreeObserver.OnPreDrawListener? = null
    private var observer: ViewTreeObserver? = null
    private var targetId = 0L
    private var ids: (() -> List<Long>)? = null
    private val touch = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) cancel()
            return false
        }
    }
    init { list.addOnItemTouchListener(touch) }

    fun scrollTo(id: Long, currentIds: () -> List<Long>) {
        cancel()
        targetId = id
        ids = currentIds
        pending = ViewTreeObserver.OnPreDrawListener {
            val manager = list.layoutManager as? LinearLayoutManager
            if (manager != null && list.isShown && list.childCount > 0 && !list.isComputingLayout) {
                removePending()
                animate(manager)
            }
            true
        }.also { listener -> observer = list.viewTreeObserver.also { it.addOnPreDrawListener(listener) } }
        list.invalidate()
    }

    private fun animate(manager: LinearLayoutManager) {
        val snapshot = ids?.invoke().orEmpty()
        val target = snapshot.indexOf(targetId)
        val first = manager.findFirstVisibleItemPosition()
        if (target < 0 || first < 0) { cancel(); return }
        val span = (manager as? GridLayoutManager)?.spanCount ?: 1
        val sample = manager.findViewByPosition(first) ?: return
        val rowHeight = manager.getDecoratedMeasuredHeight(sample).coerceAtLeast(1)
        val top = manager.getDecoratedTop(sample) - list.paddingTop
        val start = (first / span).toFloat() - top.toFloat() / rowHeight
        val end = (target / span).toFloat()
        list.stopScroll()
        if (kotlin.math.abs(start - end) < .001f) return
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val liveIds = ids?.invoke()
                if ((liveIds !== snapshot && liveIds != snapshot) || liveIds?.getOrNull(target) != targetId || !list.isShown) {
                    this@SelectedProfileScroller.cancel(); return@addUpdateListener
                }
                val coordinate = animation.animatedValue as Float
                val row = floor(coordinate).toInt().coerceAtLeast(0)
                val index = (row * span).coerceAtMost(snapshot.lastIndex)
                manager.scrollToPositionWithOffset(index, -((coordinate - row) * rowHeight).toInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { animator = null }
            })
            start()
        }
    }
    private fun removePending() {
        pending?.let { listener -> observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        pending = null; observer = null
    }
    fun cancel() {
        removePending()
        animator?.removeAllListeners(); animator?.removeAllUpdateListeners(); animator?.cancel(); animator = null
        ids = null; targetId = 0L
    }
    override fun close() { cancel(); list.removeOnItemTouchListener(touch) }
    companion object { const val DURATION_MS = 500L }
}
