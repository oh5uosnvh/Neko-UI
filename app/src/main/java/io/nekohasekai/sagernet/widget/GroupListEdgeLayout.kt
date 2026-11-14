package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.nekohasekai.sagernet.R
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Shares the Groups page's renderer, but spans the complete transparent header:
 * cards fade from group_tab.bottom up to the actual Neko title ink. Only the pager
 * enters the bounded edge recording; pills, title, and pinned scrollbar do not.
 */
class GroupListEdgeLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val defaultBandHeight = 56f * resources.displayMetrics.density
    private val edge = TitleBarEdgeRenderer()
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

    private fun titleLayout(): TitleBarLayout? {
        var ancestor = parent as? View
        while (ancestor != null) {
            if (ancestor is TitleBarLayout) return ancestor
            ancestor = ancestor.parent as? View
        }
        return null
    }

    private fun syncTop(list: RecyclerView) {
        val original = baseTop[list] ?: return
        // Use the integer content origin, not a truncated float band: the first
        // row must be at header.bottom + its original 4dp even for fractional ink.
        val titleInset = titleLayout()?.titleContentTopInset() ?: 0
        val top = original + titleInset
        if (list.paddingTop != top) {
            if (list.isPaddingRelative) {
                list.setPaddingRelative(list.paddingStart, top, list.paddingEnd, list.paddingBottom)
            } else list.setPadding(list.paddingLeft, top, list.paddingRight, list.paddingBottom)
        }
    }

    internal fun syncHeaderInsets(): Boolean {
        var changed = false
        baseTop.keys.toList().forEach { list ->
            val before = list.paddingTop
            syncTop(list)
            changed = changed || before != list.paddingTop
        }
        return changed
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        syncHeaderInsets()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child.id != R.id.group_pager || width == 0 || height == 0) {
            return super.drawChild(canvas, child, drawingTime)
        }
        val parentTitle = titleLayout()
        // Same masks, blur radius and bounded sampling as the Groups page. Its
        // default 48dp cap is only widened here to the measured toolbar+tabs gap.
        // Start in root ink coordinates (fractional pixels included), then map to
        // this container; the actual child viewport begins at ceil(inkBottom).
        val top = parentTitle?.let {
            it.titleGlyphBottom - kotlin.math.ceil(it.titleGlyphBottom)
        } ?: 0f
        val band = parentTitle?.titleEdgeBandHeight() ?: defaultBandHeight
        val pagerState = (child as? ViewPager2)?.scrollState ?: ViewPager2.SCROLL_STATE_IDLE
        val blur = child.alpha >= .999f &&
            useTopBlur(canvas.isHardwareAccelerated, pagerState) &&
            parentTitle?.useLocalBlur(canvas.isHardwareAccelerated) != false
        return edge.draw(canvas, width, top, height.toFloat(), top + band,
            resources.displayMetrics.density, blur, edgeBandLimit = band) {
            super.drawChild(it, child, drawingTime)
        }
    }

    override fun onDetachedFromWindow() {
        edge.release()
        fixedScrollbar.detach()
        super.onDetachedFromWindow()
    }

    internal fun useTopBlur(hardware: Boolean, pagerState: Int): Boolean =
        Build.VERSION.SDK_INT >= 31 && hardware && pagerState == ViewPager2.SCROLL_STATE_IDLE
}
