package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.annotation.TargetApi
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** y=0 is precisely the opaque toolbar's bottom, NOT the bottom of the tabs.
 * Only the pager's bounded top 56dp is processed; pills/toolbar never enter the
 * recording. API31+ hardware canvas uses a real local RenderNode blur, all other
 * canvases use the same edge fade. No full-screen bitmap/list RenderEffect.
 */
class GroupListEdgeLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val bandHeight = 56f * resources.displayMetrics.density
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        shader = LinearGradient(0f, 0f, 0f, bandHeight,
            intArrayOf(Color.TRANSPARENT, 0xB3FFFFFF.toInt(), Color.WHITE),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    private val blurMask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        shader = LinearGradient(0f, 0f, 0f, bandHeight,
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.2f, 1f), Shader.TileMode.CLAMP)
    }
    private var renderer: LocalBlur? = null
    private val baseTop = WeakHashMap<RecyclerView, Int>()
    internal val fixedScrollbar = FixedGroupScrollbar(this)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Postdraw: NOT recorded in card RenderNode/fade, NOT translated with ViewPager.
        fixedScrollbar.draw(canvas)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (fixedScrollbar.touch(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        baseTop.keys.toList().filterIsInstance<ExactScrollRecyclerView>().forEach(fixedScrollbar::bind)
    }

    /** Keep the existing 4dp gap and all CURRENT bottom/control-overlay insets. */
    fun bindList(list: RecyclerView) {
        if (list is ExactScrollRecyclerView) fixedScrollbar.bind(list)
        if (!baseTop.containsKey(list)) {
            baseTop[list] = list.paddingTop
            val host = WeakReference(this)
            list.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                host.get()?.syncTop(view as RecyclerView)
            }
        }
        list.clipToPadding = false
        syncTop(list)
    }

    private fun syncTop(list: RecyclerView) {
        val original = baseTop[list] ?: return
        val tabs = findViewById<View>(R.id.group_tab)
        val inset = if (tabs?.visibility == View.VISIBLE) tabs.height else 0
        val top = original + inset
        if (list.paddingTop != top) {
            list.setPadding(list.paddingLeft, top, list.paddingRight, list.paddingBottom)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        baseTop.keys.toList().forEach(::syncTop)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child.id != R.id.group_pager || width == 0 || height == 0) {
            return super.drawChild(canvas, child, drawingTime)
        }
        val band = bandHeight.coerceAtMost(height.toFloat())
        // The unaffected rest is drawn normally (including fast-scroll/overscroll).
        var save = canvas.save()
        canvas.clipRect(0f, band, width.toFloat(), height.toFloat())
        var more = super.drawChild(canvas, child, drawingTime)
        canvas.restoreToCount(save)

        save = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), band)
        val edgeLayer = canvas.saveLayer(0f, 0f, width.toFloat(), band, null)
        val pagerState = (child as? androidx.viewpager2.widget.ViewPager2)?.scrollState
            ?: androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE
        if (child.alpha >= .999f && useTopBlur(canvas.isHardwareAccelerated, pagerState)) {
            val blur = renderer ?: LocalBlur(6f * resources.displayMetrics.density)
                .also { renderer = it }
            // Record ONE sharp sample; reuse it for both sharp and blurred top layers.
            // The pager itself is traversed at most twice, never three times per frame.
            blur.record(width, (band + blur.gutter).toInt().coerceAtMost(height), drawingTime) {
                more = super.drawChild(it, child, drawingTime) || more
            }
            blur.drawSharp(canvas)
            val blurLayer = canvas.saveLayer(0f, 0f, width.toFloat(), band, null)
            blur.draw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), band, blurMask)
            canvas.restoreToCount(blurLayer)
        } else {
            // Manual swipes AND Tab-triggered settling take the same cheap fade path.
            // No RenderNode recording while the horizontal pager is moving.
            more = super.drawChild(canvas, child, drawingTime) || more
        }
        canvas.drawRect(0f, 0f, width.toFloat(), band, fade)
        canvas.restoreToCount(edgeLayer)
        canvas.restoreToCount(save)
        return more
    }

    override fun onDetachedFromWindow() {
        renderer?.takeIf { Build.VERSION.SDK_INT >= 31 }?.release()
        renderer = null
        fixedScrollbar.detach()
        super.onDetachedFromWindow()
    }

    internal fun useTopBlur(hardware: Boolean, pagerState: Int): Boolean =
        Build.VERSION.SDK_INT >= 31 && hardware &&
            pagerState == androidx.viewpager2.widget.ViewPager2.SCROLL_STATE_IDLE

    @TargetApi(31)
    private class LocalBlur(radius: Float) {
        val gutter = radius * 2f
        private val sample = RenderNode("group-list-top-sample")
        private val node = RenderNode("group-list-top-band").apply {
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        private var lastFrame = Long.MIN_VALUE
        private var lastWidth = 0
        private var lastHeight = 0
        fun record(width: Int, height: Int, frame: Long, draw: (Canvas) -> Unit) {
            // ViewGroup may request the same child draw more than once for one frame.
            if (frame == lastFrame && width == lastWidth && height == lastHeight) return
            sample.setPosition(0, 0, width, height)
            val recording = sample.beginRecording(width, height)
            try {
                recording.clipRect(0, 0, width, height)
                draw(recording)
            } finally { sample.endRecording() }
            if (width != lastWidth || height != lastHeight) {
                node.setPosition(0, 0, width, height)
                val blurred = node.beginRecording(width, height)
                try { blurred.drawRenderNode(sample) } finally { node.endRecording() }
            }
            lastFrame = frame
            lastWidth = width
            lastHeight = height
        }
        fun drawSharp(canvas: Canvas) = canvas.drawRenderNode(sample)
        fun draw(canvas: Canvas) = canvas.drawRenderNode(node)
        fun release() {
            sample.discardDisplayList()
            node.discardDisplayList()
        }
    }
}
