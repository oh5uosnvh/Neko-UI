package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Direct children: appbar (containing toolbar), title_bar_content (MATCH_PARENT),
 * optional title_bar_footer (WRAP_CONTENT). Content starts at the title's actual
 * ink bottom, while its first scrolling row remains below the whole toolbar.
 * No appbar scrolling behavior, opaque toolbar surface, divider, or elevation.
 */
class TitleBarLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private var header: View? = null
    private var content: View? = null
    private var footer: View? = null
    private var toolbar: Toolbar? = null
    private val rect = RectF()
    private val insets = TitleBarContentInsets()
    private val edge = TitleBarEdgeRenderer()
    private var listening: ViewTreeObserver? = null
    private var syncing = false
    private var contentBottom = 0f
    private var headerBottom = 0
    private var rejectedGesture = false
    private var contentGesture = false

    /** Root coordinates, including status-bar/root padding; never toolbar.bottom. */
    var titleGlyphBottom: Float = 0f
        private set

    /** Optional animation hook. Nonzero translationX on this root/ancestors is automatic. */
    var pageTransitionRunning: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val globalLayout = ViewTreeObserver.OnGlobalLayoutListener {
        // Global-layout callbacks only: no persistent pre-draw listener, timer, or
        // invalidation loop. Dynamic Fragment/Preference lists request layout themselves.
        if (refreshGeometryAndInsets()) requestLayout()
    }

    init {
        clipChildren = true
        isChildrenDrawingOrderEnabled = true
        applySurfaceColor()
    }

    private fun applySurfaceColor() {
        val values = context.obtainStyledAttributes(intArrayOf(R.attr.colorLayerLow))
        try { setBackgroundColor(values.getColor(0, Color.TRANSPARENT)) } finally { values.recycle() }
        elevation = 0f
        stateListAnimator = null
    }

    private fun bindChildren() {
        var nextHeader: View? = null
        var nextContent: View? = null
        var nextFooter: View? = null
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            when (child.id) {
                R.id.appbar -> nextHeader = child
                R.id.title_bar_content -> nextContent = child
                R.id.title_bar_footer -> nextFooter = child
            }
        }
        if (content !== nextContent) { insets.release(); edge.release() }
        header = nextHeader
        content = nextContent
        footer = nextFooter
        toolbar = nextHeader?.findViewById(R.id.toolbar)
        makeHeaderClear(nextHeader)
        makeHeaderClear(toolbar)
    }

    private fun makeHeaderClear(view: View?) {
        if (view == null) return
        if (view.background != null) view.background = null
        if (view.elevation != 0f) view.elevation = 0f
        if (view.translationZ != 0f) view.translationZ = 0f
        if (view.stateListAnimator != null) view.stateListAnimator = null
        if (view is AppBarLayout) {
            view.isLiftOnScroll = false
            view.setLiftable(false)
            view.setLifted(false)
            view.statusBarForeground = null
        }
    }

    override fun onFinishInflate() { super.onFinishInflate(); bindChildren() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        bindChildren()
        // Measure the header/footer using their real intrinsic size (insets and large font).
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val headerHeight = header?.takeIf { it.visibility != GONE }?.let {
            val lp = it.layoutParams as LayoutParams
            it.measuredHeight + lp.topMargin + lp.bottomMargin
        } ?: 0
        val footerHeight = footer?.takeIf { it.visibility != GONE }?.let {
            val lp = it.layoutParams as LayoutParams
            it.measuredHeight + lp.topMargin + lp.bottomMargin
        } ?: 0
        val provisionalTop = if (headerBottom > 0) ceil(titleGlyphBottom).toInt()
            else paddingTop + headerHeight
        content?.takeIf { it.visibility != GONE }?.let {
            measureContent(it, measuredWidth, (measuredHeight - paddingBottom -
                footerHeight - provisionalTop).coerceAtLeast(0))
        }
    }

    private fun measureContent(view: View, width: Int, availableHeight: Int) {
        val lp = view.layoutParams as LayoutParams
        val w = getChildMeasureSpec(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width)
        val h = getChildMeasureSpec(MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.EXACTLY),
            lp.topMargin + lp.bottomMargin, lp.height)
        view.measure(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Do not call FrameLayout.onLayout: it would first move the content to y=0,
        // then back to the ink edge on every layout (two descendant layouts/scroll jumps).
        bindChildren()
        header?.takeIf { it.visibility != GONE }?.let { place(it, paddingTop) }
        val availableBottom = height - paddingBottom
        val footerTop = footer?.takeIf { it.visibility != GONE }?.let {
            val lp = it.layoutParams as LayoutParams
            val y = (availableBottom - lp.bottomMargin - it.measuredHeight).coerceAtLeast(paddingTop)
            place(it, y - lp.topMargin)
            (it.top - lp.topMargin).coerceAtLeast(paddingTop)
        } ?: availableBottom
        contentBottom = footerTop.coerceAtLeast(paddingTop).toFloat()
        updateTitleBoundary()
        val padded = insets.sync(content, (headerBottom - ceil(titleGlyphBottom).toInt()).coerceAtLeast(0))
        content?.takeIf { it.visibility != GONE }?.let {
            val y = ceil(titleGlyphBottom).toInt().coerceAtMost(footerTop)
            val lp = it.layoutParams as LayoutParams
            val h = (footerTop - y).coerceAtLeast(0)
            if (padded || it.measuredHeight != (h - lp.topMargin - lp.bottomMargin).coerceAtLeast(0)) {
                measureContent(it, width, h)
            }
            place(it, y)
        }
    }

    private fun place(view: View, top: Int) {
        val lp = view.layoutParams as LayoutParams
        val gravity = Gravity.getAbsoluteGravity(lp.gravity.takeIf { it != -1 } ?: Gravity.START,
            layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK
        val x = when (gravity) {
            Gravity.RIGHT -> width - paddingRight - lp.rightMargin - view.measuredWidth
            Gravity.CENTER_HORIZONTAL -> paddingLeft + lp.leftMargin +
                (width - paddingLeft - paddingRight - lp.leftMargin - lp.rightMargin - view.measuredWidth) / 2
            else -> paddingLeft + lp.leftMargin
        }
        val y = top + lp.topMargin
        view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
    }

    private fun updateTitleBoundary(): Boolean {
        val bar = toolbar
        val appbar = header
        val headerVisible = appbar != null && appbar.visibility != GONE
        headerBottom = if (headerVisible &&
            TitleGlyphMetrics.bounds(appbar!!, this, rect)) ceil(rect.bottom).toInt() else paddingTop
        val toolbarVisible = headerVisible && bar != null && bar.visibility == VISIBLE
        val toolbarBottom = if (toolbarVisible &&
            TitleGlyphMetrics.bounds(bar!!, this, rect)) rect.bottom else headerBottom.toFloat()
        val measured = bar?.takeIf { toolbarVisible }?.let(TitleGlyphMetrics::titleView)
            ?.let { TitleGlyphMetrics.bottom(it, this) }
        // A toolbar without title ink safely keeps content below the header until a title arrives.
        val next = (measured ?: toolbarBottom).coerceIn(paddingTop.toFloat(),
            maxOf(paddingTop.toFloat(), toolbarBottom))
        if (titleGlyphBottom == next) return false
        titleGlyphBottom = next
        invalidate()
        return true
    }

    private fun refreshGeometryAndInsets(): Boolean {
        if (syncing || width == 0) return false
        syncing = true
        return try {
            bindChildren()
            val changed = updateTitleBoundary()
            val padded = insets.sync(content, (headerBottom - ceil(titleGlyphBottom).toInt()).coerceAtLeast(0))
            changed || padded
        } finally { syncing = false }
    }

    /** Optional after dynamic insertion; ordinary addView/Fragment layout is handled automatically. */
    fun refreshContentInsets() {
        if (refreshGeometryAndInsets()) requestLayout()
        invalidate()
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        val headerIndex = header?.let(::indexOfChild) ?: -1
        if (headerIndex < 0) return drawingPosition
        return when {
            drawingPosition == childCount - 1 -> headerIndex
            drawingPosition >= headerIndex -> drawingPosition + 1
            else -> drawingPosition
        }
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (child !== content) return super.drawChild(canvas, child, drawingTime)
        return edge.draw(canvas, width, titleGlyphBottom, contentBottom, headerBottom.toFloat(),
            resources.displayMetrics.density, useLocalBlur(canvas.isHardwareAccelerated)) {
            super.drawChild(it, child, drawingTime)
        }
    }

    internal fun useLocalBlur(hardware: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !hardware || pageTransitionRunning) return false
        var view: View? = this
        while (view != null) {
            if (abs(view.translationX) > .01f || view.alpha < .999f ||
                view.animation?.hasEnded() == false) return false
            view = view.parent as? View
        }
        return content?.let { abs(it.translationX) <= .01f && it.alpha >= .999f } != false
    }

    override fun setTranslationX(translationX: Float) {
        val changedPhase = (abs(this.translationX) > .01f) != (abs(translationX) > .01f)
        super.setTranslationX(translationX)
        // Property animators normally reuse the view's display list. Re-record ONLY
        // when entering/leaving horizontal motion so that list is the cheap fade one.
        if (changedPhase) invalidate()
    }

    private fun headerActionAt(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != VISIBLE || x < 0f || y < 0f || x >= view.width || y >= view.height) return false
        if (view !== header && view !== toolbar && view.isClickable && view.isEnabled) return true
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            if (headerActionAt(child, x + view.scrollX - child.x, y + view.scrollY - child.y)) return true
        }
        return false
    }

    private fun dispatchToContent(event: MotionEvent): Boolean {
        val target = content ?: return false
        val local = MotionEvent.obtain(event)
        local.offsetLocation(scrollX - target.x, scrollY - target.y)
        return try { target.dispatchTouchEvent(local) } finally { local.recycle() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { contentGesture = false; rejectedGesture = false }
        val end = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        if (contentGesture) {
            val handled = dispatchToContent(event)
            if (end) contentGesture = false
            return handled
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val target = content
            val header = header
            if (target?.visibility == VISIBLE && header != null &&
                event.y >= titleGlyphBottom && event.y < minOf(header.bottom.toFloat(), contentBottom) &&
                event.x >= target.x && event.x < target.x + target.width &&
                !headerActionAt(header, event.x - header.x, event.y - header.y)) {
                // Toolbar.onTouchEvent consumes blank space. Route only its transparent
                // lower strip to content; navigation/action touch targets retain priority.
                contentGesture = dispatchToContent(event)
                if (contentGesture) return true
            }
            rejectedGesture = false
            val insideHeader = header?.let {
                it.visibility == VISIBLE && event.x >= it.left && event.x < it.right &&
                    event.y >= it.top && event.y < it.bottom
            } == true
            val insideFooter = footer?.let {
                it.visibility == VISIBLE && event.x >= it.left && event.x < it.right &&
                    event.y >= it.top && event.y < it.bottom
            } == true
            // Content's actual bounds begin at ceil(inkBottom), so hidden content cannot
            // receive a DOWN above the boundary. Header actions keep their full touch area.
            rejectedGesture = !insideHeader && !insideFooter &&
                (event.y < titleGlyphBottom || event.y >= contentBottom)
        }
        if (rejectedGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                rejectedGesture = false
            }
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bindChildren()
        listening?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(globalLayout)
        listening = viewTreeObserver.also { it.addOnGlobalLayoutListener(globalLayout) }
        refreshContentInsets()
    }

    override fun onDetachedFromWindow() {
        listening?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(globalLayout)
        listening = null
        edge.release()
        insets.release()
        contentGesture = false
        rejectedGesture = false
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        edge.release()
        applySurfaceColor()
        refreshContentInsets()
        requestLayout()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        requestLayout()
    }
}
