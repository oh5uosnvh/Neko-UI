package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup

/** Observe cancellation of the entire gesture, not the synthetic child CANCEL on interception. */
internal class GroupPickerRecyclerView(context: Context) : RecyclerView(context) {
    var cancelDrag: (() -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelDrag?.invoke()
        return super.dispatchTouchEvent(event)
    }
}

@SuppressLint("ClickableViewAccessibility")
internal class GroupPickerAdapter(
    private val context: Context,
    groups: List<ProxyGroup>,
    private val selectedId: () -> Long,
    private val card: (Boolean) -> Drawable,
    private val textColor: Int,
    private val secondaryColor: Int,
    private val onClick: (Long) -> Unit,
) : RecyclerView.Adapter<GroupPickerAdapter.Holder>() {
    private val rows = ArrayList(groups)
    private val counts = HashMap<Long, Long>()
    var startDrag: ((Holder) -> Unit)? = null
    var dragging = false
        private set
    val ids: List<Long> get() = rows.map { it.id }

    init {
        setHasStableIds(true)
    }

    class Holder(val row: LinearLayout, val nameCard: LinearLayout,
                 val label: TextView, val handle: TextView) : RecyclerView.ViewHolder(row) {
        var groupId = RecyclerView.NO_ID
    }

    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textColor)
            setPadding(0, dp(10), 0, dp(10))
        }
        val nameCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(2), 0)
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val handle = TextView(context).apply {
            text = "☷"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(secondaryColor)
            contentDescription = context.getString(R.string.group_reorder_handle)
            isClickable = true
            isFocusable = true
            isLongClickable = false
        }
        // The handle belongs INSIDE the rounded rectangle, flush with its right edge.
        nameCard.addView(handle, LinearLayout.LayoutParams(dp(48), dp(48)))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(2))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(nameCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            isFocusable = true
            isLongClickable = false
        }
        val holder = Holder(row, nameCard, label, handle)
        row.setOnClickListener {
            // A holder can move without rebinding. Never capture an adapter position here.
            if (!dragging && rows.any { it.id == holder.groupId }) onClick(holder.groupId)
        }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                        startDrag?.invoke(holder)
                    }
                }
            }
            // Consume the whole handle gesture: no row click, long-edit or long-press timer.
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val group = rows[position]
        holder.groupId = group.id
        val selected = group.id == selectedId()
        holder.row.isSelected = selected
        holder.nameCard.isSelected = selected
        holder.nameCard.background = card(selected)
        holder.label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        holder.label.paint.isFakeBoldText = selected
        val name = group.displayName()
        val count = counts[group.id]
        holder.label.text = if (count == null) name else {
            val full = "$name·$count"
            SpannableString(full).apply {
                setSpan(ForegroundColorSpan(secondaryColor), name.length, full.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    fun move(from: Int, to: Int): Boolean {
        if (from == to || from !in rows.indices || to !in rows.indices) return false
        rows.add(to, rows.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    fun replace(groups: List<ProxyGroup>) {
        check(!dragging)
        rows.clear()
        rows.addAll(groups)
        notifyDataSetChanged()
    }

    fun restore(ids: List<Long>) {
        val byId = rows.associateBy { it.id }
        replace(GroupOrderStore.mergeOrder(this.ids, ids).map { byId.getValue(it) })
    }

    fun updateCounts(fresh: Map<Long, Long>) {
        counts.putAll(fresh)
        if (!dragging) notifyItemRangeChanged(0, itemCount)
    }

    fun updateDraggingState(value: Boolean) {
        dragging = value
        // Rebinding during ItemTouchHelper.onMove can jerk the active row back to its origin.
        if (!value) notifyItemRangeChanged(0, itemCount)
    }
}

/** One immutable ID snapshot per gesture, committed exactly once on clearView (or dismiss). */
internal class GroupPickerDragCallback(
    private val adapter: GroupPickerAdapter,
    private val commit: (List<Long>) -> Unit,
    private val cancelled: () -> Unit,
    private val settled: () -> Unit,
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
    private var before: List<Long>? = null
    private var activeHolder: RecyclerView.ViewHolder? = null
    private var cancelledGesture = false
    private var closed = false
    val isDragging: Boolean get() = before != null

    override fun isLongPressDragEnabled() = false
    override fun isItemViewSwipeEnabled() = false

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (!closed && actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            // A new down can arrive during the previous recovery animation. Settle it first;
            // a late clearView for its holder must not commit this new gesture.
            if (before != null) finish()
            before = adapter.ids
            cancelledGesture = false
            activeHolder = viewHolder
            adapter.updateDraggingState(true)
        }
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder): Boolean =
        !closed && before != null && adapter.move(
            viewHolder.bindingAdapterPosition, target.bindingAdapterPosition,
        )

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (activeHolder === viewHolder) finish()
    }

    fun cancelGesture() {
        if (before != null) cancelledGesture = true
    }

    fun close() {
        closed = true
        finish()
    }

    private fun finish() {
        val original = before ?: return
        before = null
        activeHolder = null
        adapter.updateDraggingState(false)
        val finalIds = adapter.ids
        if (cancelledGesture) {
            adapter.restore(original)
            cancelled()
        } else if (finalIds != original) {
            commit(finalIds)
        }
        settled()
    }
}
