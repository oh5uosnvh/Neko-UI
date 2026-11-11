package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
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
import io.nekohasekai.sagernet.widget.QuickLongPress
import io.nekohasekai.sagernet.widget.SubmenuBackIcon

/** Navigation only: none of these transitions select, reorder or mutate a root group. */
/** Bridge used by the picker (owned by TopBarController) without coupling that controller to scopes. */
internal object GroupPickerTagScopeBridge {
    @Volatile var onLongPress: ((Long, View) -> Boolean)? = null
}

/** Pure scope rules kept small so empty/deleted tags and stale results are testable. */
internal object GroupPickerTagScope {
    fun groups(roots: List<ProxyGroup>, memberIds: Set<Long>): List<ProxyGroup> =
        tagPickerMembers(roots, memberIds)
    fun valid(tagId: Long?, tags: List<TagGroup>): Boolean =
        tagId != null && tagId > 0L && tags.any { it.id == tagId }

    fun importTarget(roots: List<ProxyGroup>, members: Set<Long>, selectedId: Long): ProxyGroup? {
        val manual = groups(roots, members).filter { it.type == io.nekohasekai.sagernet.GroupType.BASIC &&
            !io.nekohasekai.sagernet.database.PendingGroupDeletes.contains(it.id) }
        return manual.firstOrNull { it.id == selectedId } ?: manual.firstOrNull()
    }
}

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
    private var boundTagId: Long? = null
    private lateinit var configurationEntry: TextView
    private lateinit var tagsEntry: TextView
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
        configurationEntry = entry(R.string.tag_picker_configuration, true, configuration)
        tagsEntry = entry(R.string.tag_picker_tags, false, tags)
        addView(configurationEntry, LayoutParams(0, -1, 1f))
        addView(tagsEntry, LayoutParams(0, -1, 1f))
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
        QuickLongPress(backRow) { view ->
            val id = boundTagId
            if (id == null) false else GroupPickerTagScopeBridge.onLongPress?.invoke(id, view) ?: false
        }
        addView(entries, LayoutParams(-1, dp(48)))
        addView(backRow, LayoutParams(-1, dp(48)))
        // Initial state is already configured by the entry defaults; bind() is
        // called after construction when the actual page is known.
    }
    fun bind(page: GroupPickerNavigation.Page, tagName: String?) {
        boundTagId = (page as? GroupPickerNavigation.Page.Members)?.tagId
        val members = page is GroupPickerNavigation.Page.Members
        // Keep the two-scope switch visible on the configuration and tag pages.
        // The member page gets the dedicated back/title row to avoid duplicate headers.
        entries.visibility = if (members) View.GONE else View.VISIBLE
        backRow.visibility = if (members) View.VISIBLE else View.GONE
        configurationEntry.isSelected = page == GroupPickerNavigation.Page.Configuration
        tagsEntry.isSelected = page is GroupPickerNavigation.Page.Tags
        configurationEntry.background = card(configurationEntry.isSelected)
        tagsEntry.background = card(tagsEntry.isSelected)
        icon.visibility = if (members) View.VISIBLE else View.GONE
        title.text = tagName ?: context.getString(R.string.tag_picker_title)
        backRow.contentDescription = context.getString(R.string.tag_picker_back_to, title.text)
    }
}

/** Second level: member GROUP counts, with the same inline handle/recovery as root rows. */
@SuppressLint("ClickableViewAccessibility")
internal class TagPickerAdapter(
    private val context: Context,
    private val color: Int,
    private val card: () -> Drawable,
    private val onClick: (Long) -> Unit,
    private val secondaryColor: Int = color,
) : RecyclerView.Adapter<TagPickerAdapter.Holder>(), GroupPickerReorderAdapter {
    private val rows = ArrayList<TagGroup>()
    private var counts: Map<Long, Long> = emptyMap()
    private var recycler: RecyclerView? = null
    private var suppressClick = false
    var dragging = false
        private set
    var startDrag: ((Holder) -> Unit)? = null
    override val ids: List<Long> get() = rows.map { it.id }
    class Holder(val row: LinearLayout, val nameCard: LinearLayout,
                 val label: TextView, val handle: TextView) : RecyclerView.ViewHolder(row) {
        var tagId = RecyclerView.NO_ID
    }
    init { setHasStableIds(true) }
    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].id
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
    }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val label = TextView(context).apply {
            textSize = 15f
            setTextColor(color)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, dp(10))
            isLongClickable = false
        }
        val handle = TextView(context).apply {
            text = "≡"
            textSize = 22f
            setTextColor(secondaryColor)
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.group_reorder_handle)
            isClickable = true
            isFocusable = true
            isLongClickable = false
        }
        val nameCard = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(2), 0)
            background = card()
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
            isFocusable = true
            isLongClickable = false
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            addView(nameCard, LinearLayout.LayoutParams(-1, -2))
        }
        val holder = Holder(row, nameCard, label, handle)
        val click = View.OnClickListener {
            if (!dragging && !suppressClick && rows.any { it.id == holder.tagId }) onClick(holder.tagId)
        }
        row.setOnClickListener(click)
        label.setOnClickListener(click)
        val touch = View.OnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !dragging) suppressClick = false
            false
        }
        row.setOnTouchListener(touch)
        label.setOnTouchListener(touch)
        handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                suppressClick = true
                if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) startDrag?.invoke(holder)
            }
            true // includes UP after clearView; never opens the tag at the end of a drag
        }
        return holder
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tag = rows[position]
        holder.tagId = tag.id
        val full = "${tag.name}·${counts[tag.id] ?: 0L}"
        holder.label.text = SpannableString(full).apply {
            setSpan(ForegroundColorSpan(secondaryColor), tag.name.length, full.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    fun replace(tags: List<TagGroup>, recycler: RecyclerView,
                memberCounts: Map<Long, Long> = counts) {
        check(!dragging)
        val fresh = tagPickerTags(tags)
        if (rows == fresh && counts == memberCounts) return
        val viewport = pickerViewport(recycler, ids)
        rows.clear()
        rows.addAll(fresh)
        counts = memberCounts
        notifyDataSetChanged()
        viewport?.restore(recycler, ids)
    }
    override fun move(from: Int, to: Int): Boolean {
        if (from == to || from !in rows.indices || to !in rows.indices) return false
        rows.add(to, rows.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }
    override fun restore(ids: List<Long>) {
        GroupOrderStore.mergeOrder(this.ids, ids).forEachIndexed { to, id ->
            val from = rows.indexOfFirst { it.id == id }
            if (from != to) move(from, to)
        }
    }
    override fun prepareDrag(holder: RecyclerView.ViewHolder) {
        recycler?.itemAnimator?.endAnimation(holder)
    }
    override fun updateDraggingState(value: Boolean) { dragging = value }
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
