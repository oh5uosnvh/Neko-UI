package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import io.nekohasekai.sagernet.R
import kotlin.math.max
import kotlin.math.min

/**
 * The ONE list-menu engine behind every Neko popup entrance (spec §2/§4):
 *
 * - Single PopupWindow for the whole tree. A `▶` row never opens a second
 *   window; the submenu slides in inside THIS window (right -> left), with the
 *   surface height animating to the incoming page's measured height.
 * - Open: FlClash spring growing out of the real anchor (PopupEntrance).
 * - Close: shrink + fade, then the window is removed and the selection is
 *   delivered — a one-frame dismiss() is never used.
 * - Submenu pages carry a bold `‹ title` back row; returning re-reads the
 *   live MenuItem check states and never replays the entrance animation.
 * - Menu XML, callbacks, checkable semantics and enabling stay untouched;
 *   only presentation and motion are owned here.
 */
object FlMenu {
    /** Show one window for the whole menu tree and return it for lifecycle owners/tests. */
    fun show(anchor: View, menu: Menu, onItem: (MenuItem) -> Boolean): PopupWindow =
        showReserved(anchor, menu, onItem, null, null)

    fun showReserved(anchor: View, menu: Menu, onItem: (MenuItem) -> Boolean,
        onBeforeItem: ((MenuItem) -> Boolean)?,
        onCancelled: (() -> Unit)?): PopupWindow {
        return FlMenuPopup(anchor, menu, onItem, onBeforeItem, onCancelled).also { it.show() }
    }
}

internal class FlMenuPopup(
    private val anchor: View,
    private val menu: Menu,
    private val onItem: (MenuItem) -> Boolean,
    private val onBeforeItem: ((MenuItem) -> Boolean)?,
    private val onCancelled: (() -> Unit)?,
) : PopupWindow(anchor.context) {
    private val density = anchor.context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()

    private val night = anchor.context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val surfaceColor = if (night) Color.rgb(37, 38, 40) else Color.WHITE
    private val titleColor = themeColor(android.R.attr.textColorPrimary)
    private val hintColor = themeColor(android.R.attr.textColorSecondary)
    private val accentColor = themeColor(androidx.appcompat.R.attr.colorPrimary)
    private val pressedColor = ColorUtils.blendARGB(surfaceColor, accentColor, .16f)
    private val selectedColor = ColorUtils.blendARGB(surfaceColor, accentColor, .10f)
    
    private val surface = FrameLayout(anchor.context).apply {
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = dp(16).toFloat()
            // One complete perimeter belongs to the surface, never to each page.
            setStroke(resources.getDimensionPixelSize(R.dimen.card_stroke_width),
                resources.getColor(R.color.card_stroke, context.theme))
        }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutDirection = anchor.layoutDirection
    }
    private val pager = FrameLayout(anchor.context)
    private var current: Page? = null
    private val pageStack = ArrayDeque<Page>()
    private var transitioning = false
    private var exiting = false
    private var pendingItem: MenuItem? = null
    private var reservationActive = false
    private fun cancelReservation() {
        if (!reservationActive) return
        reservationActive = false
        onCancelled?.invoke()
    }
    private var exitCompleted = false
    private var lastX = 0
    private var lastY = 0
    private var aboveAnchor = false
    private var pageAnimator: ValueAnimator? = null
    private var removed = false
    private val anchorAttach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = cancelImmediately()
    }

    private class Page(
        val view: ScrollView,
        val contentHeight: Int,
        val title: CharSequence?,
        val menu: Menu,
    )

    init {
        contentView = surface
        isFocusable = true
        isOutsideTouchable = true
        inputMethodMode = INPUT_METHOD_NOT_NEEDED
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = dp(8).toFloat()
        animationStyle = 0
        enterTransition = null
        exitTransition = null
        setOnDismissListener {
            // Only our animated exit owns a pending selection. A window can also
            // be dismissed by PopupWindow itself (outside touch, anchor detach,
            // lifecycle); those paths must cancel rather than invoke business
            // callbacks on a stale card/fragment.
            val item = if (exitCompleted) pendingItem else null
            removed = true
            pendingItem = null
            exitCompleted = false
            stopPageAnimation()
            PopupEntrance.cancel(surface)
            anchor.removeOnAttachStateChangeListener(anchorAttach)
            if (item != null) {
                reservationActive = false
                dispatchItem(item)
            } else cancelReservation()
        }
    }

    private fun themeColor(attr: Int, fallback: Int = titleColor): Int {
        val value = TypedValue()
        return if (anchor.context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) anchor.context.getColor(value.resourceId) else value.data
        } else fallback
    }

    fun show() {
        check(anchor.isAttachedToWindow && anchor.windowToken != null) {
            "FlMenu anchor must be attached to a window before showing"
        }
        val page = buildPage(menu, null)
        current = page
        surface.addView(pager, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pager.addView(page.view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        var width = measureWidth()
        val screen = Rect().also(anchor::getWindowVisibleDisplayFrame)
        if (screen.width() <= 0 || screen.height() <= 0) screen.set(0, 0,
            anchor.resources.displayMetrics.widthPixels, anchor.resources.displayMetrics.heightPixels)
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        width = width.coerceAtMost(screen.width() - dp(16))

        // Measure against the real anchor room. At this point the popup has no
        // y yet, so using lastY here makes the first page height depend on 0.
        page.view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val contentHeight = max(page.view.measuredHeight, dp(48))
        val below = screen.bottom - location[1] - anchor.height
        val above = location[1] - screen.top
        val room = max(below, above) - dp(16)
        // contentHeight is the page's inner height. Include both surface
        // paddings in the PopupWindow height; omitting the bottom padding clips
        // the last row against the rounded border (especially on submenu pages).
        val surfacePadding = surface.paddingTop + surface.paddingBottom
        val height = (contentHeight + surfacePadding).coerceAtMost(room.coerceAtLeast(dp(96)))
        val rtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = if (rtl) location[0] else location[0] + anchor.width - width - surface.paddingLeft
        x = x.coerceIn(screen.left + dp(8), max(screen.left + dp(8), screen.right - width - dp(8)))
        val y: Int
        if (below >= height + dp(8) || below >= above) {
            y = min(location[1] + anchor.height + dp(2), screen.bottom - height - dp(8))
            aboveAnchor = false
        } else {
            y = max(location[1] - dp(2) - height, screen.top + dp(8))
            aboveAnchor = true
        }
        lastX = x
        lastY = y

        setWindowSize(width, height)
        anchor.addOnAttachStateChangeListener(anchorAttach)
        try {
            showAtLocation(anchor.rootView, Gravity.TOP or Gravity.LEFT, x, y)
        } catch (e: RuntimeException) {
            anchor.removeOnAttachStateChangeListener(anchorAttach)
            throw e
        }
        surface.isFocusableInTouchMode = true
        surface.requestFocus()
        PopupEntrance.animate(surface, aboveAnchor, anchor)
    }

    /** Width across EVERY page's rows so a page switch never resizes horizontally. */
    private fun measureWidth(): Int {
        val probe = LinearLayout(anchor.context)
        var widest = 0
        val pages = ArrayList<Pair<Menu, CharSequence?>>()
        fun collect(level: Menu, title: CharSequence?) {
            pages += level to title
            for (i in 0 until level.size()) level.getItem(i).subMenu?.let {
                collect(it, level.getItem(i).title)
            }
        }
        collect(menu, null)
        for ((m, title) in pages) {
            val page = buildPage(m, title)
            pager.addView(page.view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            page.view.measure(
                View.MeasureSpec.makeMeasureSpec((anchor.resources.displayMetrics.widthPixels - dp(16)),
                    View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            widest = max(widest, page.view.measuredWidth)
            pager.removeView(page.view)
        }
        probe.background = null
        return widest.coerceAtLeast(dp(144))
    }

    private fun pageHeight(page: Page, width: Int, screen: Rect): Int {
        page.view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val content = max(page.view.measuredHeight, dp(48))
        val bottomRoom = screen.bottom - lastY - dp(8)
        val topRoom = lastY - screen.top - dp(8)
        val available = if (aboveAnchor) topRoom else bottomRoom
        return (content + surface.paddingTop + surface.paddingBottom)
            .coerceAtMost(bottomRoom.coerceAtLeast(dp(96)))
    }

    private fun setWindowSize(width: Int, height: Int) {
        val pad = surface.paddingTop + surface.paddingBottom
        val childParams = pager.layoutParams
        if (childParams != null) childParams.height = height - pad
        else pager.layoutParams = FrameLayout.LayoutParams(width, height - pad)
        pager.requestLayout()
        if (isShowing) update(lastX, lastY, width, height) else {
            this.width = width
            this.height = height
        }
    }

    private fun surfaceWidth(): Int = width.takeIf { it > 0 } ?: surface.width

    /** One menu level as a scrollable page of rows. */
    private fun buildPage(level: Menu, title: CharSequence?): Page {
        val context = anchor.context
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (title != null) rows.addView(backRow(title))
        var any = false
        for (i in 0 until level.size()) {
            val item = level.getItem(i)
            if (!item.isVisible) continue
            any = true
            rows.addView(row(item))
        }
        if (!any) rows.addView(rowPlaceholder())
        val scroll = object : ScrollView(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                    if (current?.title != null && current?.view === this) popPage() else dismissAnimated()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
            addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // ScrollView has no useful layoutParams until attached to a parent.
        // Keep the page height derived from an actual measurement instead of
        // dereferencing its (normally null) pre-attachment params.
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(anchor.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return Page(scroll, scroll.measuredHeight, title, level)
    }

    private fun rowPlaceholder(): View = TextView(anchor.context).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextColor(hintColor)
        textSize = 14f
        text = ""
    }

    private fun backRow(title: CharSequence): View {
        val context = anchor.context
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPaddingRelative(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = title
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), round(pressedColor))
                addState(intArrayOf(), round(Color.TRANSPARENT))
            }
            addView(TextView(context).apply {
                text = "‹"
                setTextColor(titleColor)
                textSize = 18f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20), dp(24)))
            addView(TextView(context).apply {
                text = title
                setTextColor(titleColor)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            setOnClickListener { if (!transitioning && !exiting) popPage() }
        }
    }

    private fun row(item: MenuItem): View {
        val context = anchor.context
        val hasChildren = item.hasSubMenu()
        return LinearLayout(context).apply {
            id = item.itemId
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            setPaddingRelative(dp(12), dp(10), dp(12), dp(10))
            isEnabled = item.isEnabled
            isSelected = item.isChecked
            contentDescription = item.title
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            alpha = if (item.isEnabled) 1f else .45f
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), round(pressedColor))
                addState(intArrayOf(android.R.attr.state_focused), round(pressedColor))
                addState(intArrayOf(), round(Color.TRANSPARENT))
            }
            if (item.isChecked) background = round(selectedColor)

            item.icon?.constantState?.newDrawable()?.mutate()?.let {
                DrawableCompat.setTint(it, if (item.isChecked) accentColor else titleColor)
                addView(ImageView(context).apply {
                    setImageDrawable(it)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            }

            val color = when {
                !item.isEnabled -> hintColor
                item.isChecked -> accentColor
                else -> titleColor
            }
            addView(TextView(context).apply {
                text = item.title
                setTextColor(color)
                textSize = 15f
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (hasChildren) addView(TextView(context).apply {
                text = "▶"
                setTextColor(hintColor)
                textSize = 11f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })

            setOnClickListener {
                if (transitioning || exiting || !item.isEnabled || !item.isVisible) return@setOnClickListener
                when {
                    hasChildren -> pushPage(item)
                    else -> {
                        // Reserve test requests NOW, before the visible 200ms exit,
                        // so a second popup cannot launch another test in that gap.
                        if (onBeforeItem?.invoke(item) == false) return@setOnClickListener
                        reservationActive = onBeforeItem != null
                        pendingItem = item
                        dismissAnimated()
                    }
                }
            }
        }
    }

    private fun round(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    // ------------------------------------------------------------- page paging

    private fun pushPage(item: MenuItem) {
        val sub = item.subMenu ?: return
        pageStack.addLast(current ?: return)
        switchPages(buildPage(sub, item.title), forward = true)
    }

    private fun popPage() {
        // Only submenu pages carry a title; the root page never slides back.
        val current = current ?: return
        if (current.title == null) return
        // Return to the actual parent, not always the root. Rebuilding the
        // parent page also re-reads live checked/enabled/visible states.
        val parent = pageStack.removeLastOrNull() ?: return
        switchPages(buildPage(parent.menu, parent.title), forward = false)
    }

    private fun switchPages(next: Page, forward: Boolean) {
        val from = current ?: return
        if (transitioning) return
        transitioning = true
        current = next
        val width = surfaceWidth().coerceAtLeast(dp(144))
        val rtl = anchor.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val direction = if (rtl) -1f else 1f
        val screen = Rect().also(anchor::getWindowVisibleDisplayFrame)
        if (screen.width() <= 0 || screen.height() <= 0) screen.set(0, 0,
            anchor.resources.displayMetrics.widthPixels, anchor.resources.displayMetrics.heightPixels)
        val targetHeight = pageHeight(next, width - surface.paddingLeft - surface.paddingRight, screen)
        val startHeight = height
        next.view.setBackgroundColor(surfaceColor)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT)
        if (forward) {
            next.view.translationX = direction * width
            pager.addView(next.view, params)
        } else {
            // Parent stays stationary under the departing child, exactly as in
            // the manual. Only the child page ever moves horizontally.
            pager.addView(next.view, 0, params)
        }
        pageAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PAGE_DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val f = it.animatedValue as Float
                if (forward) next.view.translationX = direction * width * (1f - f)
                else from.view.translationX = direction * width * f
                val h = (startHeight + (targetHeight - startHeight) * f).toInt()
                if (h != height) setWindowSize(width, h)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pageAnimator = null
                    pager.removeView(from.view)
                    next.view.translationX = 0f
                    setWindowSize(width, targetHeight)
                    transitioning = false
                }
            })
            start()
        }
    }

    private fun stopPageAnimation() {
        pageAnimator?.removeAllListeners()
        pageAnimator?.removeAllUpdateListeners()
        pageAnimator?.cancel()
        pageAnimator = null
        transitioning = false
    }

    // Selection closes after the visible exit; platform Back/outside cancellation
    // clears a staged choice instead of executing a stale action.
    fun dismissAnimated() = beginClose(true)

    override fun dismiss() {
        if (exiting) {
            pendingItem = null
            cancelReservation()
            return
        }
        beginClose(false)
    }

    private fun beginClose(deliverSelection: Boolean) {
        if (removed || exiting) return
        if (!deliverSelection) {
            pendingItem = null
            cancelReservation()
        }
        exiting = true
        stopPageAnimation()
        if (!isShowing) {
            cancelImmediately()
            return
        }
        PopupEntrance.exit(surface, aboveAnchor, anchor) {
            exitCompleted = deliverSelection
            super.dismiss()
        }
    }

    fun cancelImmediately() {
        pendingItem = null
        cancelReservation()
        exitCompleted = false
        exiting = true
        stopPageAnimation()
        PopupEntrance.cancel(surface)
        anchor.removeOnAttachStateChangeListener(anchorAttach)
        super.dismiss()
        removed = true
    }

    private fun dispatchItem(item: MenuItem) {
        // MenuBuilder's normal dispatch preserves item listeners, menu-level
        // callbacks, intents and checked/exclusive-group semantics. Only when
        // that dispatch is unhandled do we invoke this popup's business hook.
        val handled = current?.menu?.performIdentifierAction(item.itemId, 0) == true
        if (!handled) onItem(item)
    }

    companion object {
        const val PAGE_DURATION_MS = 300L
    }
}
