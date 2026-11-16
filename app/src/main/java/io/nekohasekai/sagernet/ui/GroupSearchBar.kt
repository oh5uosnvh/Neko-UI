package io.nekohasekai.sagernet.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import io.nekohasekai.sagernet.R

/** Group search styling: classify sits immediately left of X; the tick slot stays reserved. */
class GroupSearchBar(private val toolbar: Toolbar, private val context: Context = toolbar.context) {
    private val inkColor = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        .let { it.getColor(0, -1).also { _ -> it.recycle() } }
    private var parent: ViewGroup? = null
    private var animator: ValueAnimator? = null
    private var classifyShown = false
    private var classifyWidth = 0
    private var toolbarVisibility = View.VISIBLE

    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
    }
    val search = SearchView(context).apply {
        setIconifiedByDefault(false)
        isIconified = false
        queryHint = context.getString(R.string.search)
        maxWidth = Int.MAX_VALUE
        findViewById<View>(androidx.appcompat.R.id.search_plate)?.setBackgroundColor(0)
        findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(inkColor)
            setHintTextColor(inkColor)
        }
        // AppCompat re-enables its clear button on every edit. Zero dimensions prevent a second X.
        findViewById<View>(androidx.appcompat.R.id.search_close_btn)?.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, 0)
        }
    }
    val classify = TextView(context).apply {
        setText(R.string.group_classify)
        textSize = 13f
        setTextColor(inkColor)
        gravity = Gravity.CENTER
        isSingleLine = true
        setPadding(dp(8), 0, dp(8), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0x22FFFFFF)
            setStroke(dp(1), 0x44FFFFFF)
        }
        isClickable = true
        isFocusable = true
    }
    // Width grows from zero; clipping and positive translation reveal the pill leftwards from X.
    val classifySlot = FrameLayout(context).apply { clipChildren = true }
    val close = icon(R.drawable.ic_topbar_close, android.R.string.cancel)
    val confirm = icon(R.drawable.ic_action_done_toolbar, R.string.group_classify_confirm).apply {
        visibility = View.INVISIBLE // not GONE: the far-right slot exists even for an empty query
        isEnabled = false
    }

    init {
        row.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        classify.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        classifyWidth = classify.measuredWidth
        classifySlot.addView(classify, FrameLayout.LayoutParams(classifyWidth, dp(28), Gravity.CENTER_VERTICAL))
        classifySlot.visibility = View.GONE
        row.addView(classifySlot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(close, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(confirm, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun attach(onQuery: (String) -> Unit, onClassify: () -> Unit, onClose: () -> Unit, onConfirm: () -> Unit) {
        check(parent == null)
        val container = toolbar.parent as? ViewGroup ?: toolbar
        parent = container
        toolbarVisibility = toolbar.visibility
        val height = context.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize)).let {
            it.getDimensionPixelSize(0, dp(56)).also { _ -> it.recycle() }
        }
        container.addView(row, if (container === toolbar) Toolbar.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, height
        ) else ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        if (container !== toolbar) toolbar.visibility = View.GONE
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String) = true
            override fun onQueryTextChange(newText: String): Boolean { onQuery(newText); return true }
        })
        classify.setOnClickListener { if (classifyShown) onClassify() }
        close.setOnClickListener { onClose() }
        confirm.setOnClickListener { if (confirm.isEnabled) onConfirm() }
        search.alpha = 0f
        search.translationX = -dp(24).toFloat()
        search.animate().alpha(1f).translationX(0f).setDuration(240L).start()
        search.requestFocus()
        search.post {
            if (parent != null) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun render(query: String, matches: Int, customSelection: Boolean, selectedCount: Int, animate: Boolean = true) {
        // Empty search exposes the conservative one-click action immediately; a
        // non-empty query needs at least two matching cards before classification.
        val canClassify = !customSelection && (query.isBlank() || matches >= 2)
        classify.setText(if (query.isBlank()) R.string.group_classify_one_click else R.string.group_classify)
        classify.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        classifyWidth = classify.measuredWidth
        classify.layoutParams = classify.layoutParams.apply { width = classifyWidth }
        setClassifyVisible(canClassify, animate)
        confirm.visibility = if (customSelection) View.VISIBLE else View.INVISIBLE
        confirm.isEnabled = customSelection && selectedCount > 0
        confirm.alpha = if (confirm.isEnabled || !customSelection) 1f else .38f
    }

    private fun setClassifyVisible(show: Boolean, animate: Boolean) {
        if (classifyShown == show) return
        classifyShown = show
        animator?.cancel()
        animator = null
        val startWidth = classifySlot.layoutParams.width
        val target = if (show) classifyWidth + dp(8) else 0
        classifySlot.isVisible = true
        classify.isEnabled = show
        fun applyWidth(width: Int) {
            classifySlot.layoutParams = classifySlot.layoutParams.apply { this.width = width }
            val fraction = if (target == 0 && width == 0) 0f else width.toFloat() / (classifyWidth + dp(8))
            classify.translationX = classifyWidth * (1f - fraction)
            classify.alpha = fraction
            classifySlot.visibility = if (width > 0 || show) View.VISIBLE else View.GONE
        }
        if (!animate) { applyWidth(target); return }
        animator = ValueAnimator.ofInt(startWidth, target).apply {
            duration = if (show) 220L else 180L
            addUpdateListener { applyWidth(it.animatedValue as Int) }
            start()
        }
    }

    fun detach() {
        animator?.cancel()
        animator = null
        search.animate().cancel()
        search.setOnQueryTextListener(null)
        search.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        (row.parent as? ViewGroup)?.removeView(row)
        parent = null
        toolbar.visibility = toolbarVisibility
    }

    private fun icon(res: Int, description: Int) = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(inkColor)
        contentDescription = context.getString(description)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        isFocusable = true
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = attrs.getDrawable(0)
        attrs.recycle()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}
