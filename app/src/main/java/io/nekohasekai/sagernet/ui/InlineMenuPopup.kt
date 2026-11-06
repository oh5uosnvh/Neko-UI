package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.view.Menu
import android.view.MenuItem
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.PopupEntrance
import androidx.appcompat.view.menu.MenuItemImpl
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A single-window menu with in-place submenu navigation.
 * Submenus slide over the same surface instead of opening a second popup window.
 */
internal class InlineMenuPopup private constructor(
    private val anchor: View,
    private val source: Menu,
    private val onClick: (MenuItem) -> Boolean,
) {
    private val context = anchor.context
    private val density = context.resources.displayMetrics.density
    private val foreground: Int = run {
        // Theme.SagerNet can be dark without UI_MODE_NIGHT being set (the app
        // owns its palette). Derive contrast from the actual popup surface,
        // otherwise every label becomes #202020 on the dark #252628 panel.
        val values = context.obtainStyledAttributes(intArrayOf(R.attr.colorSurface))
        val surface = try { values.getColor(0, Color.DKGRAY) } finally { values.recycle() }
        val luminance = (0.299 * Color.red(surface) +
            0.587 * Color.green(surface) + 0.114 * Color.blue(surface)) / 255.0
        if (luminance > .5) Color.rgb(32, 32, 32) else Color.WHITE
    }
    private val width: Int
    private val surface: FrameLayout
    private val popup: PopupWindow
    private val pages = ArrayList<Menu>()
    private val titles = ArrayList<CharSequence?>()
    private var current = 0
    private var transition: ValueAnimator? = null
    private var currentHeight = 1

    init {
        width = measureWidth(source)
        pages += source
        titles += null
        val page = buildPage(source, null)
        currentHeight = page.measuredHeight
            .coerceAtLeast(dp(1))
        surface = FrameLayout(context).apply {
            background = context.getDrawable(R.drawable.bg_popup_menu)
            clipChildren = true
            clipToPadding = true
            addView(page, FrameLayout.LayoutParams(width, currentHeight))
        }
        popup = PopupWindow(surface, width, currentHeight, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            animationStyle = 0
            enterTransition = null
            exitTransition = null
        }
        popup.setOnDismissListener { transition?.cancel(); transition = null }
    }

    fun show() {
        if (!anchor.isAttachedToWindow || anchor.windowToken == null) return
        popup.showAsDropDown(anchor, 0, dp(4))
        PopupEntrance.animate(surface, popup.isAboveAnchor, anchor = anchor)
    }

    private fun open(item: MenuItem) {
        val submenu = item.subMenu ?: return
        if (transition?.isRunning == true) return
        pages += submenu
        titles += item.title
        slideTo(current + 1, forward = true)
    }

    private fun back() {
        if (current == 0 || transition?.isRunning == true) return
        slideTo(current - 1, forward = false)
    }

    private fun slideTo(target: Int, forward: Boolean) {
        val targetPage = buildPage(pages[target], titles[target])
        val oldPage = surface.getChildAt(0)
        val targetHeight = targetPage.measuredHeight
        val startHeight = currentHeight
        targetPage.translationX = if (forward) width.toFloat() else -width.toFloat()
        surface.addView(targetPage, FrameLayout.LayoutParams(width, targetHeight))
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener { valueAnimator ->
                val f = valueAnimator.animatedValue as Float
                oldPage.translationX = (if (forward) -width else width) * f
                targetPage.translationX = (if (forward) width else -width) * (1f - f)
                val h = (startHeight + (targetHeight - startHeight) * f).roundToInt()
                surface.layoutParams = surface.layoutParams.apply { height = h }
                surface.requestLayout()
                currentHeight = h
                popup.update(anchor, 0, if (popup.isAboveAnchor) -dp(4) else dp(4), width, h)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (transition !== animation) return
                    surface.removeView(oldPage)
                    targetPage.translationX = 0f
                    current = target
                    currentHeight = targetHeight
                    surface.layoutParams = surface.layoutParams.apply { height = targetHeight }
                    transition = null
                }
            })
        }
        transition = animator
        animator.start()
    }

    private fun buildPage(menu: Menu, title: CharSequence?): LinearLayout {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        }
        if (title != null) {
            page.addView(titleRow(title), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ))
        }
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            // AppCompat may report a submenu item as hidden until its presenter
            // attaches. Its title is still the authoritative row content, so do
            // not let that transient flag produce an empty popup page.
            if (item.isVisible || item.title != null) page.addView(itemRow(item), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ))
        }
        page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return page
    }

    private fun titleRow(title: CharSequence): View = TextView(context).apply {
        text = "\u2039  $title"
        setTextSize(16f)
        setTextColor(this@InlineMenuPopup.foreground)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = true
        setOnClickListener { back() }
    }

    private fun itemRow(item: MenuItem): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(14), 0)
            isEnabled = item.isEnabled
        }
        val label = TextView(context).apply {
            text = item.title
            setTextSize(16f)
            setTextColor(if (item.isEnabled) this@InlineMenuPopup.foreground
            else this@InlineMenuPopup.foreground.withAlpha(110))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        row.addView(label)
        val marker = TextView(context).apply {
            text = when {
                item.hasSubMenu() -> "\u25b6"
                item.isCheckable && item.isChecked -> "\u2713"
                else -> ""
            }
            setTextSize(if (item.hasSubMenu()) 15f else 20f)
            setTextColor(if (item.isEnabled) this@InlineMenuPopup.foreground
            else this@InlineMenuPopup.foreground.withAlpha(110))
            gravity = Gravity.CENTER
            minWidth = dp(28)
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        row.addView(marker)
        if (item.isEnabled) {
            row.setOnClickListener {
                if (item.hasSubMenu()) {
                    open(item)
                } else {
                    if (item.isCheckable) item.isChecked = true
                    val handled = onClick(item) || (item as? MenuItemImpl)?.invoke() == true
                    if (handled) popup.dismiss()
                }
            }
        }
        return row
    }

    private fun measureWidth(menu: Menu): Int {
        val maxWidth = context.resources.displayMetrics.widthPixels - dp(32)
        val paint = TextView(context).paint.apply { textSize = sp(16f) }
        var widest = dp(180)
        fun scan(currentMenu: Menu) {
            for (index in 0 until currentMenu.size()) {
                val item = currentMenu.getItem(index)
                if (!item.isVisible && item.title == null) continue
                widest = max(widest, (paint.measureText(item.title?.toString() ?: "") + dp(70)).roundToInt())
                if (item.hasSubMenu()) scan(item.subMenu!!)
            }
        }
        scan(menu)
        return widest.coerceAtMost(maxWidth)
    }

    private fun color(attr: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attr))
        val resolved = try {
            values.getColorStateList(0)?.getColorForState(
                intArrayOf(android.R.attr.state_enabled), Color.WHITE
            ) ?: values.getColor(0, Color.WHITE)
        } finally {
            values.recycle()
        }
        // Some themes expose textColorPrimary as a state-list resource; getColor()
        // can return a transparent default for that form. Menu labels must remain
        // visible on the popup surface in both day and night themes.
        return if (Color.alpha(resolved) < 128) Color.WHITE else resolved
    }

    private fun dp(value: Int) = (value * density).roundToInt()
    private fun sp(value: Float) = value * context.resources.displayMetrics.scaledDensity

    private fun Int.withAlpha(alpha: Int) = Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

    companion object {
        fun show(menu: Menu, anchor: View, onClick: (MenuItem) -> Boolean) {
            InlineMenuPopup(anchor, menu, onClick).show()
        }
    }
}
