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

/** Tag editing searches root groups; manual selection commits only on the explicit tick. */
class TagMemberSearchBar(private val toolbar: Toolbar, private val context: Context) {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        setPadding(dp(6), 0, dp(6), 0)
    }
    private val ink = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).let {
        it.getColor(0, resolveColor(android.R.attr.textColorPrimary)).also { _ -> it.recycle() }
    }
    val back = icon(R.drawable.baseline_arrow_back_24)
    val search = SearchView(context).apply {
        setIconifiedByDefault(false)
        isIconified = false
        maxWidth = Int.MAX_VALUE
        queryHint = context.getString(R.string.tag_member_search)
        findViewById<View>(androidx.appcompat.R.id.search_plate)?.setBackgroundColor(0)
        findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(ink); setHintTextColor(ink)
        }
        findViewById<View>(androidx.appcompat.R.id.search_close_btn)?.apply {
            visibility = View.GONE; layoutParams = LinearLayout.LayoutParams(0, 0)
        }
    }
    val title = TextView(context).apply {
        textSize = 18f
        setTextColor(ink)
        setText(R.string.tag_member_select)
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        visibility = View.GONE
    }
    val manual = TextView(context).apply {
        setText(R.string.tag_member_manual); setTextColor(ink); textSize = 13f
        gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); isClickable = true
    }
    val classify = TextView(context).apply {
        setText(R.string.group_classify); setTextColor(ink); textSize = 13f
        gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0); isClickable = true; visibility = View.GONE
    }
    val done = icon(R.drawable.ic_topbar_close)
    private var attached = false
    private var isManual = false
    init {
        row.addView(back, LinearLayout.LayoutParams(dp(44), -1))
        row.addView(search, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        row.addView(classify, LinearLayout.LayoutParams(-2, -1))
        row.addView(manual, LinearLayout.LayoutParams(-2, -1))
        row.addView(done, LinearLayout.LayoutParams(dp(44), -1))
    }
    fun attach(onQuery: (String) -> Unit, onManual: () -> Unit, onClassify: () -> Unit,
               onClose: () -> Unit, onConfirm: () -> Unit) {
        val parent = toolbar.parent as ViewGroup
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        val height = attrs.getDimensionPixelSize(0, dp(56)); attrs.recycle()
        parent.addView(row, ViewGroup.LayoutParams(-1, height)); toolbar.visibility = View.GONE; attached = true
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String) = true
            override fun onQueryTextChange(newText: String): Boolean { onQuery(newText); return true }
        })
        back.setOnClickListener { onClose() }
        manual.setOnClickListener { onManual() }
        classify.setOnClickListener { if (classify.isEnabled) onClassify() }
        done.setOnClickListener { if (isManual) { if (done.isEnabled) onConfirm() } else onClose() }
        search.requestFocus()
        search.post { if (attached) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) }
    }
    fun render(manualMode: Boolean, newMatches: Int, selected: Int) {
        isManual = manualMode
        search.visibility = if (manualMode) View.GONE else View.VISIBLE
        title.visibility = if (manualMode) View.VISIBLE else View.GONE
        manual.visibility = if (manualMode) View.GONE else View.VISIBLE
        classify.visibility = if (!manualMode && newMatches > 0) View.VISIBLE else View.GONE
        classify.isEnabled = newMatches > 0
        done.setImageResource(if (manualMode) R.drawable.ic_action_done else R.drawable.ic_topbar_close)
        done.contentDescription = context.getString(if (manualMode) R.string.group_classify_confirm else android.R.string.cancel)
        done.isEnabled = !manualMode || selected > 0
        done.alpha = if (done.isEnabled) 1f else .38f
        if (manualMode) {
            search.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        }
    }
    fun detach() {
        attached = false
        search.setOnQueryTextListener(null)
        search.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        (row.parent as? ViewGroup)?.removeView(row); toolbar.visibility = View.VISIBLE
    }
    private fun icon(res: Int) = ImageView(context).apply {
        setImageResource(res); setColorFilter(ink); setPadding(dp(10), dp(10), dp(10), dp(10))
        scaleType = ImageView.ScaleType.CENTER_INSIDE; isClickable = true; isFocusable = true
        contentDescription = context.getString(android.R.string.cancel)
    }
    private fun resolveColor(attr: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        } else 0xFF000000.toInt()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + .5f).toInt()
}
