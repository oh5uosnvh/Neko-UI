package io.nekohasekai.sagernet.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.R

/** Search owns one full-width input row; classification has its own labelled action row. */
class GroupSearchBar(private val toolbar: Toolbar, private val context: Context = toolbar.context) {
    private val inkColor = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        .let { it.getColor(0, -1).also { _ -> it.recycle() } }
    private var parent: ViewGroup? = null
    private var toolbarVisibility = View.VISIBLE
    private var saving = false

    val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val inputRow = LinearLayout(context).apply {
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
        // AppCompat re-enables this after every edit. Keep just our explicit close action.
        findViewById<View>(androidx.appcompat.R.id.search_close_btn)?.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, 0)
        }
    }
    val close = ImageView(context).apply {
        setImageResource(R.drawable.ic_topbar_close)
        setColorFilter(inkColor)
        contentDescription = context.getString(android.R.string.cancel)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        isFocusable = true
    }
    val actionRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(8), 0)
    }
    val status = TextView(context).apply {
        setTextColor(inkColor)
        textSize = 13f
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val classify = action(R.string.group_classify)
    val confirm = action(R.string.group_classify_confirm).apply { visibility = View.GONE }

    init {
        inputRow.addView(search, LinearLayout.LayoutParams(0, dp(56), 1f))
        inputRow.addView(close, LinearLayout.LayoutParams(dp(48), dp(56)))
        actionRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(classify, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        actionRow.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        row.addView(inputRow)
        row.addView(actionRow)
    }

    fun attach(onQuery: (String) -> Unit, onClassify: () -> Unit, onClose: () -> Unit, onConfirm: () -> Unit) {
        check(parent == null)
        val container = toolbar.parent as? ViewGroup ?: toolbar
        parent = container
        toolbarVisibility = toolbar.visibility
        container.addView(row, if (container === toolbar) Toolbar.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ) else ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (container !== toolbar) toolbar.visibility = View.GONE
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String) = true
            override fun onQueryTextChange(newText: String): Boolean {
                if (!saving) onQuery(newText)
                return true
            }
        })
        classify.setOnClickListener { if (classify.isEnabled) onClassify() }
        close.setOnClickListener { if (!saving) onClose() }
        confirm.setOnClickListener { if (confirm.isEnabled) onConfirm() }
        search.requestFocus()
        search.post {
            if (parent != null) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun render(query: String, matches: Int, customSelection: Boolean, selectedCount: Int, animate: Boolean = true,
               saving: Boolean = false) {
        this.saving = saving
        classify.setText(if (query.isBlank()) R.string.group_classification_all else R.string.group_classify)
        classify.visibility = if (customSelection) View.GONE else View.VISIBLE
        classify.isEnabled = !saving && matches > 0
        classify.alpha = if (classify.isEnabled) 1f else .38f
        confirm.visibility = if (customSelection) View.VISIBLE else View.GONE
        confirm.isEnabled = !saving && customSelection && selectedCount > 0
        confirm.alpha = if (confirm.isEnabled) 1f else .38f
        close.isEnabled = !saving
        search.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.isEnabled = !saving
        status.text = when {
            saving -> context.getString(R.string.group_classification_saving)
            customSelection -> context.getString(R.string.group_classification_selected, selectedCount)
            matches == 0 -> context.getString(R.string.group_classification_empty)
            query.isBlank() -> context.getString(R.string.group_classification_total, matches)
            else -> context.getString(R.string.group_classification_matches, matches)
        }
    }

    fun detach() {
        search.setOnQueryTextListener(null)
        search.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        (row.parent as? ViewGroup)?.removeView(row)
        parent = null
        toolbar.visibility = toolbarVisibility
    }

    private fun action(label: Int) = TextView(context).apply {
        setText(label)
        textSize = 14f
        setTextColor(inkColor)
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = attrs.getDrawable(0)
        attrs.recycle()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
}
