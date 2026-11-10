package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.TagGroup
import io.nekohasekai.sagernet.widget.SubmenuBackIcon

/** Navigation only: none of these transitions select, reorder or mutate a root group. */
internal class GroupPickerNavigation {
    sealed class Page {
        data object Configuration : Page()
        data object Tags : Page()
        data class Members(val tagId: Long) : Page()
    }
    var page: Page = Page.Configuration
        private set
    fun configuration() { page = Page.Configuration }
    fun tags() { page = Page.Tags }
    fun members(tagId: Long) { page = Page.Members(tagId) }
    fun back(): Boolean = when (page) {
        Page.Configuration -> false
        Page.Tags -> { configuration(); true }
        is Page.Members -> { tags(); true }
    }
    fun reconcile(fresh: List<TagGroup>): Boolean {
        val current = page as? Page.Members ?: return false
        if (fresh.any { it.id == current.tagId }) return false
        tags()
        return true
    }
}

/** Filter references in root order; membership iteration order must never become display order. */
internal fun tagPickerMembers(roots: List<ProxyGroup>, members: Set<Long>): List<ProxyGroup> =
    roots.filter { it.id in members }

internal fun tagPickerTags(tags: List<TagGroup>): List<TagGroup> = tags.sortedBy { it.userOrder }

/** Dispatch before PopupWindow's decor consumes BACK; release only returns one page. */
internal class GroupPickerSurface(context: Context) : FrameLayout(context) {
    var onBack: (() -> Unit)? = null
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && onBack != null) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/** Equal-width root entries, then a dedicated back row (never a group action). */
internal class GroupPickerHeader(
    context: Context,
    private val color: Int,
    private val card: (Boolean) -> Drawable,
    configuration: () -> Unit,
    tags: () -> Unit,
    back: () -> Unit,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val entries = LinearLayout(context).apply {
        orientation = HORIZONTAL
        fun entry(label: Int, selected: Boolean, action: () -> Unit) = TextView(context).apply {
            text = context.getString(label)
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(color)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            isSelected = selected
            background = card(selected)
            isFocusable = true
            setOnClickListener { action() }
        }
        addView(entry(R.string.tag_picker_configuration, true, configuration), LayoutParams(0, -1, 1f))
        addView(entry(R.string.tag_picker_tags, false, tags), LayoutParams(0, -1, 1f))
    }
    private val title = TextView(context).apply {
        textSize = 15f
        setTextColor(color)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.ic_tag_group)
        setColorFilter(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val backRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        background = card(false)
        isFocusable = true
        addView(SubmenuBackIcon(context, color), LayoutParams(dp(20), dp(24)))
        addView(icon, LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
        addView(title, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        setOnClickListener { back() }
    }
    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), 0)
        minimumHeight = dp(52)
        addView(entries, LayoutParams(-1, dp(48)))
        addView(backRow, LayoutParams(-1, dp(48)))
        bind(GroupPickerNavigation.Page.Configuration, null)
    }
    fun bind(page: GroupPickerNavigation.Page, tagName: String?) {
        val root = page == GroupPickerNavigation.Page.Configuration
        entries.visibility = if (root) View.VISIBLE else View.GONE
        backRow.visibility = if (root) View.GONE else View.VISIBLE
        icon.visibility = if (page is GroupPickerNavigation.Page.Members) View.VISIBLE else View.GONE
        title.text = tagName ?: context.getString(R.string.tag_picker_title)
        backRow.contentDescription = context.getString(R.string.tag_picker_back_to, title.text)
    }
}

/** Second level is name only: no counts, edit actions or root reorder handles. */
internal class TagPickerAdapter(
    private val context: Context,
    private val color: Int,
    private val card: () -> Drawable,
    private val onClick: (Long) -> Unit,
) : RecyclerView.Adapter<TagPickerAdapter.Holder>() {
    private var rows: List<TagGroup> = emptyList()
    val ids: List<Long> get() = rows.map { it.id }
    class Holder(val label: TextView) : RecyclerView.ViewHolder(label) { var tagId = RecyclerView.NO_ID }
    init { setHasStableIds(true) }
    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].id
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val holder = Holder(TextView(context).apply {
            textSize = 15f
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(48)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = card()
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                leftMargin = dp(12); rightMargin = dp(12)
                topMargin = dp(2); bottomMargin = dp(2)
            }
        })
        holder.label.setOnClickListener {
            if (rows.any { it.id == holder.tagId }) onClick(holder.tagId)
        }
        return holder
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.tagId = rows[position].id
        holder.label.text = rows[position].name
    }
    fun replace(tags: List<TagGroup>, recycler: RecyclerView) {
        val fresh = tagPickerTags(tags)
        if (rows == fresh) return
        val viewport = pickerViewport(recycler, ids)
        rows = fresh
        notifyDataSetChanged()
        viewport?.restore(recycler, ids)
    }
}

/** Stable-ID + exact decorated offset survives rename/member changes and removed anchors. */
internal data class PickerViewport(val id: Long, val position: Int, val offset: Int) {
    fun restore(recycler: RecyclerView, ids: List<Long>) {
        if (ids.isEmpty()) return
        val moved = ids.indexOf(id)
        val target = if (moved >= 0) moved else position.coerceIn(0, ids.lastIndex)
        (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, offset)
    }
}
internal fun pickerViewport(recycler: RecyclerView, ids: List<Long>): PickerViewport? {
    val layout = recycler.layoutManager as? LinearLayoutManager ?: return null
    val first = layout.findFirstVisibleItemPosition()
    val id = ids.getOrNull(first) ?: return null
    val view = layout.findViewByPosition(first) ?: return null
    return PickerViewport(id, first, layout.getDecoratedTop(view) - recycler.paddingTop)
}
