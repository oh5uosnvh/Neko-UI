package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import android.view.MenuItem
import android.view.View
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.TextView
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.AnimatedPopupWindow
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import java.lang.NumberFormatException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    private lateinit var itemTouchHelper: ItemTouchHelper
    private var filterPopup: AnimatedPopupWindow? = null
    private var filterSubscription: Boolean? = null
    private var sortMode = SORT_DEFAULT

    // Search is a full-width row like the configuration page, never a collapsing menu action.
    private var groupSearchQuery = ""
    private var groupSearchBar: GroupSearchBar? = null
    private var groupSearchMode = false
    private val searchBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitGroupSearchMode()
    }

    private fun updateGroupSearchActions() {
        if (!::groupAdapter.isInitialized) return
        groupSearchBar?.render(groupSearchQuery, groupAdapter.searchMatches().size,
            groupAdapter.customSelection, selectedForClassification.size)
    }
    private val selectedForClassification = LinkedHashSet<Long>()

    companion object {
        const val EXTRA_TARGET_GROUP_ID = "target_group_id"
        private const val TOPBAR_SEGMENT_LIGHTEN = 0.30f

        private const val SORT_DEFAULT = 0
        private const val SORT_ASC = 1
        private const val SORT_DESC = 2
    }

    // Normal menu navigation retains the cached layout manager position. Only jump resets top.
    override fun resetScrollState() = Unit

    private var preparedGroups: List<ProxyGroup>? = null
    private var preparedGroupId = -1L
    private var jumpGeneration = 0
    private var jump: GroupJumpController? = null
    private var highlightCard: com.google.android.material.card.MaterialCardView? = null
    private var highlightColor: android.content.res.ColorStateList? = null
    private var highlightReset: Runnable? = null

    suspend fun prepareGroupJump(groupId: Long) {
        cancelGroupJump()
        val token = jumpGeneration
        val groups = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGroups() }
        if (token != jumpGeneration) return
        preparedGroups = groups
        preparedGroupId = groupId
        if (view != null && ::groupAdapter.isInitialized) {
            // A prior type filter must not make a requested (otherwise valid) group unreachable.
            val target = groups.firstOrNull { it.id == groupId }
            if (target != null && filterSubscription != null &&
                (target.type == GroupType.SUBSCRIPTION) != filterSubscription) filterSubscription = null
            groupAdapter.submitGroups(groups)
            preparedGroups = null
            jump?.prepare(groupId)
        }
    }

    fun beginPreparedGroupJump() { jump?.start() }

    internal fun onGroupPageReady() { jump?.onReady() }

    override fun onResume() {
        super.onResume()
        onGroupPageReady()
    }

    fun cancelGroupJump() {
        jumpGeneration++
        preparedGroups = null
        preparedGroupId = -1L
        jump?.cancel()
        clearJumpHighlight()
    }

    /** Compatibility entry; the activity owns the lifecycle-cancelled asynchronous preparation. */
    fun scrollToGroup(groupId: Long) { (getActivity() as? MainActivity)?.openGroupAt(groupId) }

    private fun clearJumpHighlight() {
        highlightReset?.let { if (::groupListView.isInitialized) groupListView.removeCallbacks(it) }
        highlightReset = null
        val card = highlightCard
        val color = highlightColor
        if (card != null && color != null) card.setCardBackgroundColor(color)
        highlightCard = null
        highlightColor = null
    }

    private fun highlightGroup(id: Long) {
        val holder = groupListView.findViewHolderForItemId(id) as? GroupHolder ?: return
        if (holder.itemId != id || holder.proxyGroup.id != id) return
        val card = holder.itemView as? com.google.android.material.card.MaterialCardView ?: return
        clearJumpHighlight()
        highlightCard = card
        highlightColor = card.cardBackgroundColor
        val foreground = toolbarForeground()
        card.setCardBackgroundColor(ColorUtils.blendARGB(card.cardBackgroundColor.defaultColor, foreground, .12f))
        // Visual feedback only: no performClick, pressed state, edit, subscription or drag event.
        highlightReset = Runnable { clearJumpHighlight() }.also { groupListView.postDelayed(it, 220L) }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        searchBackCallback.isEnabled = groupSearchMode && !hidden
        if (hidden) { cancelGroupJump(); dismissFilterPicker() }
        else if (preparedGroupId > 0L) beginPreparedGroupJump()
    }

    private suspend fun loadGroups(): List<ProxyGroup> {
        val groups = SagerDatabase.groupDao.allGroups().toMutableList()
        if (groups.isEmpty()) {
            SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
            groups.addAll(SagerDatabase.groupDao.allGroups())
        }
        val ungrouped = groups.firstOrNull { it.ungrouped }
        if (groups.size > 1 && ungrouped != null && PendingProfileDeletes.count(ungrouped.id) == 0L) {
            groups.removeAll { it.ungrouped }
        }
        return groups
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)

        groupListView = view.findViewById(R.id.group_list)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        viewLifecycleOwner.lifecycleScope.launch {
            PendingProfileDeletes.changes.collect {
                if (::groupAdapter.isInitialized) groupAdapter.notifyDataSetChanged()
            }
        }
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter
        jump = GroupJumpController(groupListView, layoutManager,
            { groupAdapter.groupList.map { it.id } },
            { !isHidden && view.translationX == 0f && isResumed },
            ::highlightGroup, ::clearJumpHighlight)
        preparedGroups?.let { groupAdapter.submitGroups(it) }
        preparedGroups = null
        if (preparedGroupId > 0L) {
            jump?.prepare(preparedGroupId)
            jump?.start()
        }

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (groupSearchMode || groupAdapter.customSelection || proxyGroup.ungrouped ||
                    proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (groupSearchMode || groupAdapter.customSelection || proxyGroup.ungrouped ||
                    proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                val group = (viewHolder as GroupHolder).proxyGroup
                groupAdapter.remove(index)
                undoManager.remove(index to group)
                // 立即同步到配置页：滑动即广播移除（撤销时在 undo 中广播恢复，
                // 正式 commit 的广播对此幂等），不再等撤销条 2 秒超时
                runOnDefaultDispatcher {
                    GroupManager.iterator { groupRemoved(group.id) }
                }
            }

            // 官方机制：长按卡片触发拖动排序（☷ 手柄已按需求移除）
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                // 拖动开始：屏蔽自身 commitMove 广播的回声。回声的 notifyItemChanged
                // 会重绑正在拖拽的卡片、打乱 ItemTouchHelper 的位置记账 → 未过中线
                // 就提前换位。配置页分组与 ☴ 列表等其他监听者不受影响，实时同步保留。
                groupAdapter.suppressSelfEcho = true
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
                groupAdapter.suppressSelfEcho = false
            }

            // 长按拖动到列表上下边缘：固定为最大速度的 50%（规格8，非渐进加速）
            override fun interpolateOutOfBoundsScroll(
                recyclerView: RecyclerView,
                viewSize: Int,
                viewSizeOutOfBounds: Int,
                totalSize: Int,
                msSinceStartScroll: Long,
            ): Int {
                return GroupDragScroll.pixels(recyclerView, viewSizeOutOfBounds)
            }
        })
        itemTouchHelper.attachToRecyclerView(groupListView)

    }

    private fun enterGroupSearchMode() {
        if (groupSearchMode) return
        groupSearchMode = true
        searchBackCallback.isEnabled = !isHidden
        groupSearchBar = GroupSearchBar(toolbar, requireContext()).also { bar ->
            bar.attach(onQuery = { query ->
                // Editing invalidates the previous manual selection, including any pending tick.
                if (groupSearchQuery != query.trim()) {
                    selectedForClassification.clear()
                    groupAdapter.customSelection = false
                }
                groupSearchQuery = query.trim()
                groupAdapter.applyFilter()
            }, onClassify = ::openClassificationChoice, onClose = ::exitGroupSearchMode,
                onConfirm = { saveClassification(selectedForClassification.toList()) })
        }
        groupAdapter.applyFilter()
    }

    private fun exitGroupSearchMode() {
        if (!groupSearchMode) return
        groupSearchMode = false
        searchBackCallback.isEnabled = false
        groupSearchBar?.detach()
        groupSearchBar = null
        groupSearchQuery = ""
        selectedForClassification.clear()
        groupAdapter.customSelection = false
        groupAdapter.applyFilter()
    }

    private fun openClassificationChoice() {
        val matched = groupAdapter.searchMatches()
        if (matched.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.group_classify)
            .setItems(arrayOf(
                getString(R.string.group_classify_confirm),
                getString(R.string.group_classify_cancel),
                getString(R.string.group_classify_custom),
            )) { _, which ->
                when (which) {
                    0 -> saveClassification(matched.map { it.id })
                    1 -> Unit
                    2 -> {
                        selectedForClassification.clear()
                        groupAdapter.customSelection = true
                        updateGroupSearchActions()
                        groupAdapter.notifyDataSetChanged()
                    }
                }
            }.show()
    }

    private fun saveClassification(ids: List<Long>) {
        if (ids.isEmpty()) return
        val selected = ids.toSet()
        val reordered = groupAdapter.classifyOrder(selected)
        GroupOrderStore.submit(reordered.map { it.id }) { result -> result.error?.let { Logs.w(it) } }
        exitGroupSearchMode()
        (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_group)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_group_search -> enterGroupSearchMode()
            R.id.action_new_group -> {
                if (!groupSearchMode) {
                    startActivity(Intent(context, GroupSettingsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, 0L)
                    })
                }
            }
            R.id.action_update_all -> {
                if (groupSearchMode) {
                    exitGroupSearchMode()
                } else {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.update_all_subscription)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            SagerDatabase.groupDao.allGroups()
                                .filter { it.type == GroupType.SUBSCRIPTION }
                                .forEach { GroupUpdater.startUpdate(it, true) }
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
            }
        }
        return true
    }

    private val groupProfileExport = GroupProfileExport(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupProfileExport.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        groupProfileExport.saveState(outState)
    }


    private fun toolbarForeground(): Int {
        val tv = android.util.TypedValue()
        val ctx = toolbar.context
        return if (ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
        } else {
            themeTextColor()
        }
    }

    private fun applySegmentCellStyle(tv: TextView, foreground: Int) {
        tv.apply {
            gravity = Gravity.CENTER
            minWidth = resources.getDimensionPixelSize(R.dimen.topbar_segment_cell_min_width)
            val hPad = resources.getDimensionPixelSize(R.dimen.topbar_segment_cell_padding_h)
            setPadding(hPad, 0, hPad, 0)
            setTextColor(foreground)
            textSize = 13f
            includeFontPadding = false
            isSingleLine = true
            isClickable = true
            isFocusable = true
            background = rippleDrawable()
        }
    }

    private fun rippleDrawable(): android.graphics.drawable.Drawable? {
        val tv = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true,
            )
        ) {
            ContextCompat.getDrawable(requireContext(), tv.resourceId)
        } else null
    }

    private fun themeTextColor(): Int {
        val ta = requireContext().obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary)
        )
        val color = ta.getColor(0, Color.WHITE)
        ta.recycle()
        return color
    }

    private fun pickerContent(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp2px(4), 0, dp2px(4))
    }

    private fun addPickerRow(content: LinearLayout, row: View) {
        content.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun showPickerWindow(content: LinearLayout, anchor: View) {
        dismissFilterPicker()
        val ctx = requireContext()

        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = resources.getDimension(R.dimen.popup_corner_radius)
            cardElevation = dp2px(8).toFloat()
            strokeWidth = 0
            val ta = ctx.obtainStyledAttributes(intArrayOf(R.attr.colorSurface))
            val surface = ta.getColor(0, Color.DKGRAY)
            ta.recycle()
            setCardBackgroundColor(surface)
            addView(content)
        }

        filterPopup = AnimatedPopupWindow(
            card,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
            anchor,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp2px(8).toFloat()
            isOutsideTouchable = true
            setOnDismissListener {
                if (filterPopup === this) filterPopup = null
                if (filterCard === card) filterCard = null
            }
            showAsDropDown(anchor, 0, dp2px(4))
            io.nekohasekai.sagernet.widget.PopupEntrance.animate(card, isAboveAnchor, anchor)
        }
        filterCard = card
    }

    private var filterCard: View? = null

    private fun dismissFilterPicker(immediate: Boolean = false) {
        val popup = filterPopup ?: return
        if (immediate) popup.cancelImmediately() else popup.dismiss()
        filterPopup = null
        filterCard = null
    }

    private fun filterRow(label: String, selected: Boolean): TextView = TextView(requireContext()).apply {
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(
            if (selected) {
                val ta = context.obtainStyledAttributes(intArrayOf(R.attr.colorPrimary))
                val c = ta.getColor(0, themeTextColor())
                ta.recycle()
                c
            } else {
                themeTextColor()
            }
        )
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp2px(16), dp2px(10), dp2px(20), dp2px(10))
        isClickable = true
        isFocusable = true
        background = rippleDrawable()
    }

    private fun setFilter(subscriptionOnly: Boolean?) {
        filterSubscription = subscriptionOnly
        dismissFilterPicker()
        groupAdapter.applyFilter()
    }

    private fun setSort(mode: Int) {
        sortMode = mode
        dismissFilterPicker()
        groupAdapter.applyFilter()
    }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        private val fullList = ArrayList<ProxyGroup>()
        val groupList = ArrayList<ProxyGroup>()

        private var dataGeneration = 0
        var customSelection = false

        fun searchMatches(): List<ProxyGroup> {
            val q = groupSearchQuery.trim()
            if (q.isEmpty()) return emptyList()
            return fullList
                .filter { filterSubscription == null ||
                    (it.type == GroupType.SUBSCRIPTION) == filterSubscription }
                .filter { it.displayName().contains(q, ignoreCase = true) }
        }

        fun applyCustomSelection(group: ProxyGroup): Boolean {
            if (!customSelection) return false
            if (!group.displayName().contains(groupSearchQuery.trim(), ignoreCase = true)) return true
            if (selectedForClassification.contains(group.id)) selectedForClassification.remove(group.id)
            else selectedForClassification.add(group.id)
            updateGroupSearchActions()
            notifyDataSetChanged()
            return true
        }

        fun classifyOrder(ids: Set<Long>): List<ProxyGroup> =
            GroupClassification.stackSelected(fullList.toList()) { it.id in ids }

        fun submitGroups(groups: List<ProxyGroup>) {
            dataGeneration++
            fullList.clear()
            fullList.addAll(groups)
            rebuildVisible()
            notifyDataSetChanged()
        }

        suspend fun reload() {
            val token = onMainDispatcher { dataGeneration }
            val groups = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadGroups() }
            onMainDispatcher {
                if (view == null || groupAdapter !== this@GroupAdapter || token != dataGeneration) return@onMainDispatcher
                submitGroups(groups)
            }
        }

        private fun getGroupExpireTime(group: ProxyGroup): Long? {
            if (group.type != GroupType.SUBSCRIPTION) return null
            val sub = group.subscription ?: return null
            if (sub.expiryDate != null && sub.expiryDate > 0) {
                return sub.expiryDate.toLong()
            }
            val info = sub.subscriptionUserinfo
            if (!info.isNullOrBlank()) {
                val match = "expire=([0-9]+)".toRegex().find(info)
                if (match != null && match.groupValues.size > 1) {
                    val exp = match.groupValues[1].toLongOrNull()
                    if (exp != null && exp > 0L) {
                        return exp
                    }
                }
            }
            return null
        }

        private fun sortGroups(list: List<ProxyGroup>, ascending: Boolean): List<ProxyGroup> {
            val subsWithExpire = ArrayList<Pair<ProxyGroup, Long>>()
            val subsNoExpire = ArrayList<ProxyGroup>()
            val locals = ArrayList<ProxyGroup>()

            for (group in list) {
                if (group.type == GroupType.SUBSCRIPTION) {
                    val exp = getGroupExpireTime(group)
                    if (exp != null) {
                        subsWithExpire.add(group to exp)
                    } else {
                        subsNoExpire.add(group)
                    }
                } else {
                    locals.add(group)
                }
            }

            // 订阅有到期时间：按到期时间排序
            val sortedSubsWithExpire = if (ascending) {
                subsWithExpire.sortedBy { it.second }.map { it.first }
            } else {
                subsWithExpire.sortedByDescending { it.second }.map { it.first }
            }

            // 本地配置：按创建时间 (id 升序为最早，降序为最新)
            val sortedLocals = if (ascending) {
                locals.sortedBy { it.id }
            } else {
                locals.sortedByDescending { it.id }
            }

            // 没有到期时间或者是长期有效的订阅：全排在底下
            return sortedSubsWithExpire + sortedLocals + subsNoExpire
        }

        private fun rebuildVisible() {
            var list = fullList.toList()
            val query = groupSearchQuery.trim()
            if (query.isNotEmpty()) {
                list = list.filter { it.displayName().contains(query, ignoreCase = true) }
            }
            val filter = filterSubscription
            if (filter != null) {
                list = list.filter { (it.type == GroupType.SUBSCRIPTION) == filter }
            }
            list = when (sortMode) {
                SORT_ASC -> sortGroups(list, ascending = true)
                SORT_DESC -> sortGroups(list, ascending = false)
                else -> list
            }
            groupList.clear()
            groupList.addAll(list)
            selectedForClassification.retainAll(list.map { it.id }.toSet())
            updateGroupSearchActions()
        }

        fun applyFilter() {
            rebuildVisible()
            notifyDataSetChanged()
        }

        init {
            setHasStableIds(true)
            if (preparedGroups == null) viewLifecycleOwner.lifecycleScope.launch { reload() }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            if (holder.itemView === highlightCard) clearJumpHighlight()
            holder.bind(groupList[position])
        }

        override fun onViewRecycled(holder: GroupHolder) {
            if (holder.itemView === highlightCard) clearJumpHighlight()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        // 拖动进行中：屏蔽自身 commitMove 广播的回声（见 ItemTouchHelper#onMove）
        var suppressSelfEcho = false

        fun move(from: Int, to: Int) {
            // 防护：拖动动画期间 bindingAdapterPosition 可能为 NO_POSITION
            if (from == to || from < 0 || to < 0 ||
                from >= groupList.size || to >= groupList.size
            ) return
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                synchronized(updated) { updated.add(next) }
            }
            first.userOrder = previousOrder
            groupList[to] = first
            synchronized(updated) { updated.add(first) }
            notifyItemMoved(from, to)
        }

        // 官方语义：clearView（松手落位）时统一落库；在此官方点之后追加一轮广播，
        // 让配置页标签栏与 ☴ 快速列表即时跟随新顺序。拖动中途零落库、零广播。
        fun commitMove() {
            val changed = synchronized(updated) {
                val any = updated.isNotEmpty()
                updated.clear()
                any
            }
            if (!changed) return
            // Same FIFO writer as the popup: never persist stale whole group objects.
            GroupOrderStore.submit(groupList.map { it.id }) { result ->
                result.error?.let { Logs.w(it) }
            }
        }

        fun remove(index: Int) {
            val item = groupList.removeAt(index)
            fullList.removeAll { it.id == item.id }
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((_, item) in actions) {
                if (fullList.none { it.id == item.id }) fullList.add(item)
            }
            rebuildVisible()
            notifyDataSetChanged()
            // 撤销 = 取消删除：把配置页刚移除的标签广播恢复
            runOnDefaultDispatcher {
                for ((_, item) in actions) {
                    GroupManager.iterator { groupAdd(item) }
                }
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            if (fullList.any { it.id == group.id }) return
            fullList.add(group)
            fullList.sortWith(compareBy({ it.userOrder }, { it.id }))
            delay(300L)
            onMainDispatcher {
                undoManager.flush()
                rebuildVisible()
                notifyDataSetChanged()
                if (group.type == GroupType.SUBSCRIPTION) GroupUpdater.startUpdate(group, true)
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            onMainDispatcher {
                undoManager.flush()
                fullList.removeAll { it.id == groupId }
                rebuildVisible()
                notifyDataSetChanged()
                if (SagerDatabase.groupDao.allGroups().size <= 2) runOnDefaultDispatcher { reload() }
            }
        }

        override suspend fun groupsReordered(groups: List<ProxyGroup>, changed: List<ProxyGroup>) {
            onMainDispatcher {
                if (suppressSelfEcho) return@onMainDispatcher
                val orders = groups.associate { it.id to it.userOrder }
                val before = groupList.map { it.id }
                // An order echo must not flush Undo or reread/rebind every node/group.
                fullList.forEach { group -> orders[group.id]?.let { group.userOrder = it } }
                fullList.sortWith(compareBy({ it.userOrder }, { it.id }))
                rebuildVisible()
                if (before != groupList.map { it.id }) notifyDataSetChanged()
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val known = onMainDispatcher {
                val allIndex = fullList.indexOfFirst { it.id == group.id }
                if (allIndex < 0) return@onMainDispatcher false
                // Ignore our own active drag's echoes, not a newly persisted external order.
                if (suppressSelfEcho || fullList[allIndex] === group) return@onMainDispatcher true
                val before = groupList.map { it.id }
                fullList[allIndex] = group
                fullList.sortBy { it.userOrder }
                undoManager.flush()
                rebuildVisible()
                val after = groupList.map { it.id }
                if (before.size != after.size || before.toSet() != after.toSet()) {
                    notifyDataSetChanged()
                } else {
                    val current = before.toMutableList()
                    after.forEachIndexed { target, id ->
                        val source = current.indexOf(id)
                        if (source != target) {
                            current.add(target, current.removeAt(source))
                            notifyItemMoved(source, target)
                        }
                    }
                    val index = after.indexOf(group.id)
                    if (index >= 0) notifyItemChanged(index)
                }
                true
            }
            if (!known) reload()
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }

    }

    override fun onDestroyView() {
        groupSearchBar?.detach()
        groupSearchBar = null
        groupSearchMode = false
        groupSearchQuery = ""
        selectedForClassification.clear()
        searchBackCallback.isEnabled = false
        searchBackCallback.remove()
        cancelGroupJump()
        jump?.close()
        jump = null
        preparedGroups = null
        dismissFilterPicker()
        if (::groupAdapter.isInitialized) GroupManager.removeListener(groupAdapter)
        if (::undoManager.isInitialized) undoManager.flush()
        if (::itemTouchHelper.isInitialized) itemTouchHelper.attachToRecyclerView(null)
        if (::groupListView.isInitialized) groupListView.adapter = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        lateinit var proxyGroup: ProxyGroup
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupUser = binding.groupUser
        val groupJumpButton = binding.groupJump
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
        val selectedIndicator = binding.groupSelectedIndicator
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress

        private fun onMenuItemClick(item: MenuItem, menuGroup: ProxyGroup): Boolean {

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            when (item.itemId) {
                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        menuGroup.toUniversalLink(), menuGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    export(menuGroup.toUniversalLink())
                }

                R.id.action_export_clipboard -> {
                    groupProfileExport.toClipboard(menuGroup.id)
                }

                R.id.action_export_file -> {
                    groupProfileExport.toFile(menuGroup.id, menuGroup.displayName())
                }

                R.id.action_clear -> {
                    GroupClearAction.confirm(activity, menuGroup)
                }
            }

            return true
        }


        fun bind(group: ProxyGroup) {
            proxyGroup = group

            selectedIndicator.isVisible = groupAdapter.customSelection &&
                selectedForClassification.contains(proxyGroup.id)
            if (groupAdapter.customSelection) {
                groupJumpButton.isGone = true
            }
            itemView.setOnClickListener {
                if (groupAdapter.applyCustomSelection(proxyGroup)) return@setOnClickListener
            }
            groupJumpButton.isGone = groupAdapter.customSelection || proxyGroup.ungrouped
            groupJumpButton.setOnClickListener {
                DataStore.stageSelectedGroup(proxyGroup.id)
                (activity as? MainActivity)?.openConfigurationGroup(proxyGroup.id)
            }
            optionsButton.contentDescription = getString(R.string.delete)

            editButton.isGone = proxyGroup.ungrouped
            optionsButton.isGone = groupAdapter.customSelection
            editButton.isGone = groupAdapter.customSelection || proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()
            groupUser.isGone = true
            groupUser.text = ""

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            val menuGroup = proxyGroup
            optionsButton.setOnClickListener {
                if (menuGroup.ungrouped) return@setOnClickListener
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm)
                    .setMessage(R.string.group_delete_confirm_prompt)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher { GroupManager.deleteGroup(menuGroup.id) }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }

            if (proxyGroup.id in GroupUpdater.updating) {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[proxyGroup.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
                groupJumpButton.isGone = true
                optionsButton.isGone = true
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = groupAdapter.customSelection ||
                    proxyGroup.type != GroupType.SUBSCRIPTION
                groupJumpButton.isGone = groupAdapter.customSelection || proxyGroup.ungrouped
                optionsButton.isGone = groupAdapter.customSelection
                editButton.isGone = groupAdapter.customSelection || proxyGroup.ungrouped
            }

            val subscription = proxyGroup.subscription
            groupTraffic.isVisible = false
            groupTraffic.text = ""
            fun compactBytes(value: Long): String {
                if (value < 0L) return "0kb"
                val units = arrayOf("kb", "MB", "GB", "TB", "PB")
                var amount = value.toDouble() / 1024.0
                var unit = 0
                while (amount >= 1024.0 && unit < units.lastIndex) {
                    amount /= 1024.0
                    unit++
                }
                return if (amount >= 100.0 || amount == amount.toLong().toDouble())
                    "${amount.toLong()}${units[unit]}"
                else "${String.format(Locale.US, "%.2f", amount).trimEnd('0').trimEnd('.')}${units[unit]}"
            }

            fun expiryText(seconds: Long?): String? {
                if (seconds == null || seconds <= 0L) return null
                return Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
                    .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
            var usageText: String? = null
            var expireLabel: String? = null
            if (subscription != null &&
                (subscription.bytesUsed > 0L || subscription.bytesRemaining > 0L)
            ) { // SIP008 & Open Online Config
                usageText = if (subscription.bytesRemaining > 0L) {
                    val total = subscription.bytesUsed + subscription.bytesRemaining
                    app.getString(R.string.subscription_traffic, compactBytes(subscription.bytesUsed), compactBytes(total))
                } else app.getString(R.string.subscription_used, compactBytes(subscription.bytesUsed))
                groupStatus.setPadding(0)
            } else if (subscription != null && !subscription.subscriptionUserinfo.isNullOrBlank()) { // Raw
                var text = ""

                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscription.subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }

                try {
                    var used: Long = 0
                    get("upload=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    get("download=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    val total = get("total=([0-9]+)")?.toLong() ?: 0
                    val remain = total - used
                    if (used > 0 || total > 0) {
                        text += if (remain > 0) {
                            getString(
                                R.string.subscription_traffic,
                                compactBytes(used),
                                compactBytes(total)
                            )
                        } else {
                            getString(R.string.subscription_used, compactBytes(used))
                        }
                    }
                    val expire = get("expire=([0-9]+)")?.toLongOrNull()
                    expireLabel = expiryText(expire)
                } catch (_: NumberFormatException) {
                    // ignore
                }

                if (text.isNotEmpty()) usageText = text
            }
            if (expireLabel == null) expireLabel = expiryText(subscription?.expiryDate?.toLong())
            if (usageText != null || expireLabel != null) {
                groupTraffic.isVisible = true
                groupTraffic.text = listOfNotNull(
                    usageText,
                    expireLabel,
                ).joinToString(" · ")
                groupStatus.setPadding(0)
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            groupUser.text = subscription?.username ?: ""

            runOnDefaultDispatcher {
                val size = PendingProfileDeletes.count(group.id)
                onMainDispatcher {
                    if (proxyGroup.id != group.id) return@onMainDispatcher
                    @Suppress("DEPRECATION") when (group.type) {
                        GroupType.BASIC -> {
                            if (size == 0L) {
                                groupStatus.setText(R.string.group_status_empty)
                            } else {
                                groupStatus.text = getString(R.string.group_status_proxies, size)
                            }
                        }

                        GroupType.SUBSCRIPTION -> {
                                        groupStatus.text = if (size == 0L) {
                                getString(R.string.group_status_empty_subscription)
                            } else {
                                val updated = group.subscription?.lastUpdated?.toLong()
                                    ?.takeIf { it > 0L }
                                    ?.let {
                                        Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
                                            .toLocalDate().format(DateTimeFormatter.ofPattern("MM-dd"))
                                    }
                                    ?: "-"
                                getString(R.string.group_status_proxies_subscription, size, updated)
                            }

                        }
                    }
                }

            }

        }
    }

}