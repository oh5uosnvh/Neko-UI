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
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup

/** Shared stable-ID move contract; root and quick-tag gestures have identical recovery rules. */
internal interface GroupPickerReorderAdapter {
    val ids: List<Long>
    fun move(from: Int, to: Int): Boolean
    fun restore(ids: List<Long>)
    fun prepareDrag(holder: RecyclerView.ViewHolder)
    fun updateDraggingState(value: Boolean)
}

/** Same move/recovery timing as the group page, without change cross-fades/recycled holders. */
internal fun groupPickerItemAnimator() = DefaultItemAnimator().apply {
    supportsChangeAnimations = false
    moveDuration = 250L
}

/** Observe cancellation of the entire gesture, not the synthetic child CANCEL on interception. */
internal class GroupPickerRecyclerView(context: Context) : RecyclerView(context) {
    var cancelDrag: (() -> Unit)? = null
    /** Runs before ItemTouchHelper sees DOWN (and its automatic recovery-row grab). */
    var dragTouch: ((MotionEvent) -> Unit)? = null

    // notifyItemMoved is pending until the next layout, BEFORE ItemAnimator.isRunning is true.
    val isSettling: Boolean
        get() = isComputingLayout || hasPendingAdapterUpdates() || itemAnimator?.isRunning == true

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        dragTouch?.invoke(event)
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
) : RecyclerView.Adapter<GroupPickerAdapter.Holder>(), GroupPickerReorderAdapter {
    private val rows = ArrayList(groups)
    private val counts = HashMap<Long, Long>()
    private var recycler: GroupPickerRecyclerView? = null
    private var countsDirty = false
    private val flushCounts = object : Runnable {
        override fun run() {
            val list = recycler ?: return
            if (!countsDirty || dragging) return
            if (list.isSettling) list.postOnAnimation(this)
            else {
                countsDirty = false
                // A text-only payload can still make LinearLayoutManager clamp a partly
                // visible first row to the padding edge. Retain its exact visual offset.
                val layout = list.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val first = layout?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                val anchorId = rows.getOrNull(first)?.id
                val anchorView = if (first >= 0) layout?.findViewByPosition(first) else null
                val offset = anchorView?.let { layout!!.getDecoratedTop(it) - list.paddingTop }
                notifyItemRangeChanged(0, itemCount, COUNTS)
                if (anchorId != null && offset != null) {
                    val position = rows.indexOfFirst { it.id == anchorId }
                    if (position >= 0) layout?.scrollToPositionWithOffset(position, offset)
                }
            }
        }
    }
    var startDrag: ((Holder) -> Unit)? = null
    /** Members reorder their slots in the complete root order, never a separate order. */
    var reorderable = true
    private var suppressClick = false
    var dragging = false
        private set
    override val ids: List<Long> get() = rows.map { it.id }

    init {
        setHasStableIds(true)
    }

    private companion object { val COUNTS = Any() }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView as? GroupPickerRecyclerView
        if (countsDirty) recycler?.postOnAnimation(flushCounts)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeCallbacks(flushCounts)
        recycler = null
        super.onDetachedFromRecyclerView(recyclerView)
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
            text = "≡"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
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
            if (!dragging && !suppressClick && rows.any { it.id == holder.groupId }) onClick(holder.groupId)
        }
        row.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !dragging) suppressClick = false
            false
        }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    suppressClick = true
                    if (reorderable && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
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
        holder.handle.visibility = if (reorderable) View.VISIBLE else View.GONE
        val selected = group.id == selectedId()
        holder.row.isSelected = selected
        holder.nameCard.isSelected = selected
        holder.nameCard.background = card(selected)
        holder.label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        bindLabel(holder, group)
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it === COUNTS }) bindLabel(holder, rows[position])
        else super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindLabel(holder: Holder, group: ProxyGroup) {
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

    override fun move(from: Int, to: Int): Boolean {
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

    override fun restore(ids: List<Long>) {
        // Cancellation rolls back through moves, not a full invalidation during recovery.
        GroupOrderStore.mergeOrder(this.ids, ids).forEachIndexed { to, id ->
            val from = rows.indexOfFirst { it.id == id }
            if (from != to) move(from, to)
        }
    }

    fun updateCounts(fresh: Map<Long, Long>) {
        counts.putAll(fresh)
        countsDirty = true
        if (!dragging) recycler?.let {
            it.removeCallbacks(flushCounts)
            it.postOnAnimation(flushCounts)
        }
    }

    override fun prepareDrag(holder: RecyclerView.ViewHolder) {
        // ItemTouchHelper owns the selected row's translation; do not let a neighbour's
        // previous move animator keep writing to it after a quick second handle-down.
        recycler?.itemAnimator?.endAnimation(holder)
    }

    override fun updateDraggingState(value: Boolean) {
        dragging = value
        // No end-of-drag range rebind: it would cancel a still-running neighbour move.
        recycler?.removeCallbacks(flushCounts)
        if (!value && countsDirty) recycler?.postOnAnimation(flushCounts)
    }
}

/** One immutable ID snapshot per gesture, committed exactly once on clearView (or dismiss). */
internal class GroupPickerDragCallback(
    private val adapter: GroupPickerReorderAdapter,
    private val commit: (List<Long>) -> Unit,
    private val cancelled: () -> Unit,
    private val settled: () -> Unit,
) : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
    private var before: List<Long>? = null
    private var activeHolder: RecyclerView.ViewHolder? = null
    private var selectedHolder: RecyclerView.ViewHolder? = null
    private var cancelledGesture = false
    private var closed = false
    private var armedHolder: RecyclerView.ViewHolder? = null
    private var handleGestureOpen = false
    private var recoveryMayGrab = false
    val isDragging: Boolean get() = before != null
    val isSelected: Boolean get() = selectedHolder != null

    /**
     * ItemTouchHelper's DOWN/recover path calls select() WITHOUT checking drag flags.
     * Merely disabling long-press or returning 0 from getDragDirs cannot stop a body
     * touch from grabbing the recovering row. Retire recovery before dispatching a
     * fresh DOWN, so the body reaches RecyclerView normally and the handle receives
     * its own DOWN. Never end neighbour ItemAnimator animations for ordinary scrolling.
     */
    fun attach(recycler: GroupPickerRecyclerView, touch: ItemTouchHelper) {
        recycler.dragTouch = { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    armedHolder = null
                    handleGestureOpen = true
                    if (!closed && recoveryMayGrab && !isSelected) {
                        recoveryMayGrab = false
                        touch.attachToRecyclerView(null)
                        // There need not be a recover animation (e.g. a detached holder).
                        finish()
                        touch.attachToRecyclerView(recycler)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleGestureOpen = false
                    armedHolder = null
                }
            }
        }
        touch.attachToRecyclerView(recycler)
    }

    /** Called only by the inline handle's ACTION_DOWN listener. */
    fun armHandle(holder: RecyclerView.ViewHolder): Boolean {
        if (closed || isSelected || !handleGestureOpen ||
            holder.bindingAdapterPosition == RecyclerView.NO_POSITION) return false
        armedHolder = holder
        return true
    }

    override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
        if (!closed && handleGestureOpen && armedHolder === viewHolder) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0

    override fun isLongPressDragEnabled() = false
    override fun isItemViewSwipeEnabled() = false

    override fun interpolateOutOfBoundsScroll(recyclerView: RecyclerView, viewSize: Int,
        viewSizeOutOfBounds: Int, totalSize: Int, msSinceStartScroll: Long): Int =
        GroupDragScroll.pixels(recyclerView, viewSizeOutOfBounds)

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (!closed && handleGestureOpen && armedHolder === viewHolder &&
            actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            // A new down can arrive during the previous recovery animation. Settle it first;
            // a late clearView for its holder must not commit this new gesture.
            if (before != null) finish(notifySettled = false)
            before = adapter.ids
            cancelledGesture = false
            activeHolder = viewHolder
            selectedHolder = viewHolder
            recoveryMayGrab = true
            adapter.prepareDrag(viewHolder)
            adapter.updateDraggingState(true)
        } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
            selectedHolder = null
        }
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder): Boolean {
        if (closed || before == null || cancelledGesture || !handleGestureOpen ||
            armedHolder !== viewHolder || activeHolder !== viewHolder) return false
        // Settle any previous step before starting the next move; this prevents upward
        // multi-row drags from stacking unfinished neighbour translations, while keeping
        // the ItemAnimator alive for the current step.
        recyclerView.itemAnimator?.endAnimations()
        return adapter.move(
            viewHolder.bindingAdapterPosition, target.bindingAdapterPosition,
        )
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (activeHolder === viewHolder) finish()
    }

    fun cancelGesture() {
        handleGestureOpen = false
        armedHolder = null
        if (before != null) cancelledGesture = true
    }

    fun close() {
        closed = true
        handleGestureOpen = false
        armedHolder = null
        finish()
    }

    private fun finish(notifySettled: Boolean = true) {
        val original = before ?: return
        before = null
        activeHolder = null
        selectedHolder = null
        adapter.updateDraggingState(false)
        val finalIds = adapter.ids
        if (cancelledGesture) {
            adapter.restore(original)
            cancelled()
        } else if (finalIds != original) {
            commit(finalIds)
        }
        if (notifySettled) settled()
    }
}
