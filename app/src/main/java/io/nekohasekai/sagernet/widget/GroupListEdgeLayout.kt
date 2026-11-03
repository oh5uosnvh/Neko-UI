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

    /** Keep the existing 4dp gap and all CURRENT bottom/control-overlay insets. */
    fun bindList(list: RecyclerView) {
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
        more = super.drawChild(canvas, child, drawingTime) || more
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated) {
            val blur = renderer ?: LocalBlur(6f * resources.displayMetrics.density)
                .also { renderer = it }
            // Include a small sampling gutter below the band to avoid a seam.
            blur.record(width, (band + blur.gutter).toInt().coerceAtMost(height)) {
                super.drawChild(it, child, drawingTime)
            }
            val blurLayer = canvas.saveLayer(0f, 0f, width.toFloat(), band, null)
            blur.draw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), band, blurMask)
            canvas.restoreToCount(blurLayer)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), band, fade)
        canvas.restoreToCount(edgeLayer)
        canvas.restoreToCount(save)
        return more
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31) renderer?.release()
        renderer = null
        super.onDetachedFromWindow()
    }

    @TargetApi(31)
    private class LocalBlur(radius: Float) {
        val gutter = radius * 2f
        private val node = RenderNode("group-list-top-band").apply {
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        fun record(width: Int, height: Int, draw: (Canvas) -> Unit) {
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording(width, height)
            try {
                recording.clipRect(0, 0, width, height)
                draw(recording)
            } finally { node.endRecording() }
        }
        fun draw(canvas: Canvas) = canvas.drawRenderNode(node)
        fun release() = node.discardDisplayList()
    }
}
