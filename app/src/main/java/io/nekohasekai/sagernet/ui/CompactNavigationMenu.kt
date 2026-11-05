package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
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
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import io.nekohasekai.sagernet.R

/** The XML is the only menu model. No NavigationView, DrawerLayout, scrim or edge gesture. */
internal class CompactNavigationMenu(
    private val context: Context,
    private val host: View,
    private val onSelected: (MenuItem) -> Unit,
) {
    val menu: Menu = PopupMenu(context, host).apply { inflate(R.menu.main_drawer_menu) }.menu.apply {
        // XML groups used exclusive drawer checks; this popup owns ONE selection across groups.
        // MenuItemImpl.setChecked(false) still selects itself when exclusive-checkable is set.
        for (i in 0 until size()) setGroupCheckable(getItem(i).groupId, true, false)
    }
    internal var window: PopupWindow? = null
        private set
    private var anchor: View? = null
    private var animation: ValueAnimator? = null
    private var closing = false
    private var afterClose: (() -> Unit)? = null
    val isShowing: Boolean get() = window?.isShowing == true

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun round(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun color(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        } else fallback
    }
    private val night: Boolean get() = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    internal val surfaceColor: Int get() = if (night) Color.rgb(37, 38, 40) else Color.WHITE

    fun setSelected(id: Int) {
        for (i in 0 until menu.size()) menu.getItem(i).isChecked = menu.getItem(i).itemId == id
    }

    fun show(view: View) {
        if (isShowing) { dismiss(); return }
        cancel()
        if (!view.isAttachedToWindow) return
        anchor = view
        val content = buildContent()
        val popup = PopupWindow(content, 1, 1, true).apply {
            // The whole rounded surface, not just its rows, participates in the exit fade.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            animationStyle = 0
            enterTransition = null
            exitTransition = null
        }
        window = popup
        popup.setOnDismissListener {
            cancelAnimation()
            content.alpha = 1f
            content.translationY = 0f
            if (window === popup) { window = null; anchor = null }
            closing = false
            afterClose = null // External dismissal must never execute a queued navigation.
        }
        val frame = bounds(view)
        size(popup, content, frame)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val marginX = minOf(dp(8), maxOf(0, (frame.width() - popup.width) / 2))
        val marginY = minOf(dp(8), maxOf(0, (frame.height() - popup.height) / 2))
        val startX = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            location[0] + view.width - popup.width
        } else location[0]
        val x = startX.coerceIn(frame.left + marginX, maxOf(frame.left + marginX, frame.right - popup.width - marginX))
        val y = (location[1] + view.height).coerceIn(frame.top + marginY, maxOf(frame.top + marginY, frame.bottom - popup.height - marginY))
        popup.showAtLocation(host, Gravity.TOP or Gravity.LEFT, x, y)
        content.requestFocus()
        animate(content, opening = true)
    }

    private fun bounds(view: View) = Rect().also {
        view.getWindowVisibleDisplayFrame(it)
        if (it.width() <= 0 || it.height() <= 0) it.set(0, 0, host.width, host.height)
    }

    private fun size(popup: PopupWindow, content: View, frame: Rect) {
        content.measure(View.MeasureSpec.makeMeasureSpec(maxOf(1, frame.width() - dp(16)), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxOf(1, frame.height() - dp(16)), View.MeasureSpec.AT_MOST))
        popup.width = content.measuredWidth
        popup.height = content.measuredHeight
    }

    private fun buildContent(): ScrollView {
        val foreground = color(android.R.attr.textColorPrimary, if (night) Color.WHITE else Color.BLACK)
        val accent = color(androidx.appcompat.R.attr.colorPrimary, foreground)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            val row = LinearLayout(context).apply {
                id = item.itemId
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPaddingRelative(dp(12), dp(8), dp(16), dp(8))
                contentDescription = item.title
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                isEnabled = item.isEnabled
                isSelected = item.isChecked
                isFocusable = true
                alpha = if (item.isEnabled) 1f else .45f
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), round(ColorUtils.blendARGB(surfaceColor, accent, .18f), 10))
                    addState(intArrayOf(android.R.attr.state_focused), round(ColorUtils.blendARGB(surfaceColor, accent, .14f), 10))
                    addState(intArrayOf(android.R.attr.state_selected), round(ColorUtils.blendARGB(surfaceColor, accent, .10f), 10))
                    addState(intArrayOf(), round(Color.TRANSPARENT, 10))
                }
                setOnClickListener {
                    if (!closing && item.isEnabled && item.isVisible) dismiss { onSelected(item) }
                }
            }
            row.addView(ImageView(context).apply {
                item.icon?.constantState?.newDrawable()?.mutate()?.let {
                    DrawableCompat.setTint(it, foreground)
                    setImageDrawable(it)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            row.addView(TextView(context).apply {
                text = item.title
                setTextColor(foreground)
                textSize = 15f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return object : ScrollView(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val closeKey = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    KeyEvent.KEYCODE_DPAD_LEFT
                } else KeyEvent.KEYCODE_DPAD_RIGHT
                if (event.keyCode == closeKey || event.keyCode == KeyEvent.KEYCODE_BACK ||
                    event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) dismiss()
                    return true // Handle descendants too; focused rows otherwise swallow Back.
                }
                if ((event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) &&
                    findFocus() === this && event.action == KeyEvent.ACTION_DOWN) {
                    val candidates = (0 until rows.childCount).map { rows.getChildAt(it) }.filter { it.isEnabled }
                    val target = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) candidates.lastOrNull() else candidates.firstOrNull()
                    if (target?.requestFocus() == true) return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            background = round(surfaceColor, 16)
            clipToOutline = true
            layoutDirection = anchor?.layoutDirection ?: host.layoutDirection
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            isFocusableInTouchMode = true
            addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Refresh conditional items without recreating the activity or leaving stale traffic rows. */
    fun refresh() {
        val popup = window ?: return
        val view = anchor ?: return
        if (closing) return
        cancelAnimation()
        // PopupWindow cannot replace attached content; dismiss/reopen at the same anchor.
        cancel()
        show(view)
    }

    fun dismiss(action: (() -> Unit)? = null) {
        val popup = window
        if (popup == null || !popup.isShowing) { action?.invoke(); return }
        if (closing) return
        closing = true
        afterClose = action
        animate(popup.contentView, opening = false)
    }

    private fun animate(content: View, opening: Boolean) {
        cancelAnimation()
        val start = if (opening) 0f else content.alpha
        if (opening) { content.alpha = 0f; content.translationY = -dp(6).toFloat() }
        animation = ValueAnimator.ofFloat(start, if (opening) 1f else 0f).apply {
            duration = if (opening) 140L else CLOSE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                content.alpha = progress
                content.translationY = -dp(6) * (1f - progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animation.removeAllListeners()
                    (animation as? ValueAnimator)?.removeAllUpdateListeners()
                    this@CompactNavigationMenu.animation = null
                    if (!opening) {
                        val action = afterClose
                        afterClose = null
                        window?.dismiss()
                        // Commit only after the popup window has gone, including cold inflation.
                        action?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun cancelAnimation() {
        animation?.removeAllUpdateListeners()
        animation?.removeAllListeners()
        animation?.cancel()
        animation = null
    }

    /** Lifecycle cancellation: never navigate after onStop/onSaveInstanceState. */
    fun cancel() {
        afterClose = null
        cancelAnimation()
        window?.dismiss()
        window = null
        anchor = null
        closing = false
    }

    companion object { internal const val CLOSE_DURATION_MS = 120L }
}
