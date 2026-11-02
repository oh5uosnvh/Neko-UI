package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import android.text.format.Formatter
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
import androidx.appcompat.widget.PopupMenu
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
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.util.*

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    private lateinit var itemTouchHelper: ItemTouchHelper
    private var filterPopup: PopupWindow? = null
    private var filterSubscription: Boolean? = null
    private var sortMode = SORT_DEFAULT

    companion object {
        const val EXTRA_TARGET_GROUP_ID = "target_group_id"
        private const val TOPBAR_SEGMENT_LIGHTEN = 0.30f

        private const val SORT_DEFAULT = 0
        private const val SORT_ASC = 1
        private const val SORT_DESC = 2
    }

    /** 问题1-B：切回分组页时列表回顶部 */
    override fun resetScrollState() {
        if (::groupListView.isInitialized) groupListView.scrollToPosition(0)
    }

    /** MainActivity 复用本实例时的定位请求（替代一次性 arguments 方案） */
    private var pendingScrollGroupId = -1L

    /** 跳到指定分组（由 MainActivity#openGroupAt 调用） */
    fun scrollToGroup(groupId: Long) {
        pendingScrollGroupId = groupId
        if (::groupListView.isInitialized) {
            groupListView.post { consumePendingScroll() }
        }
    }

    private fun consumePendingScroll() {
        if (!::groupListView.isInitialized || !::groupAdapter.isInitialized) return
        val targetId = pendingScrollGroupId
        if (targetId <= 0L) return
        val position = groupAdapter.groupList.indexOfFirst { it.id == targetId }
        if (position >= 0) {
            groupListView.scrollToPosition(position)
            pendingScrollGroupId = -1L
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

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

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
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
                val maxScroll = recyclerView.resources.getDimensionPixelSize(
                    R.dimen.item_touch_helper_max_drag_scroll_per_frame
                )
                val speed = (maxScroll * 0.5f).toInt().coerceAtLeast(1)
                return if (viewSizeOutOfBounds > 0) speed else -speed
            }
        })
        itemTouchHelper.attachToRecyclerView(groupListView)

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }

            R.id.action_update_all -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.update_all_subscription)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.SUBSCRIPTION }
                            .forEach {
                                GroupUpdater.startUpdate(it, true)
                            }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
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
            radius = dp2px(12).toFloat()
            cardElevation = dp2px(8).toFloat()
            strokeWidth = 0
            val ta = ctx.obtainStyledAttributes(intArrayOf(R.attr.colorSurface))
            val surface = ta.getColor(0, Color.DKGRAY)
            ta.recycle()
            setCardBackgroundColor(surface)
            addView(content)
        }

        filterPopup = PopupWindow(
            card,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp2px(8).toFloat()
            isOutsideTouchable = true
            showAsDropDown(anchor, 0, dp2px(4))
        }
    }

    private fun dismissFilterPicker() {
        filterPopup?.dismiss()
        filterPopup = null
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

        suspend fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            if (groups.isEmpty()) {
                SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                groups.addAll(SagerDatabase.groupDao.allGroups())
            }
            val ungrouped = groups.firstOrNull { it.ungrouped }
            if (groups.size > 1 && ungrouped != null && PendingProfileDeletes.count(ungrouped.id) == 0L) {
                groups.removeAll { it.ungrouped }
            }
            fullList.clear()
            fullList.addAll(groups)
            rebuildVisible()
            groupListView.post {
                notifyDataSetChanged()
                val targetId = arguments?.getLong(EXTRA_TARGET_GROUP_ID, -1L) ?: -1L
                if (targetId > 0L) {
                    val targetPos = groupList.indexOfFirst { it.id == targetId }
                    if (targetPos >= 0) {
                        groupListView.scrollToPosition(targetPos)
                    }
                    arguments?.remove(EXTRA_TARGET_GROUP_ID)
                }
                consumePendingScroll()
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
        }

        fun applyFilter() {
            rebuildVisible()
            notifyDataSetChanged()
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
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
        fun commitMove() = runOnDefaultDispatcher {
            // 后台线程只碰快照；updated 的增删全部在主线程锁内完成
            val moved = synchronized(updated) {
                val snapshot = ArrayList(updated)
                updated.clear()
                snapshot
            }
            for (group in moved) {
                SagerDatabase.groupDao.updateGroup(group)
            }
            for (group in moved) {
                GroupManager.iterator { groupUpdated(group) }
            }
        }

        fun remove(index: Int) {
            val item = groupList.removeAt(index)
            fullList.removeAll { it.id == item.id }
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
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
            // 撤销恢复时本地已在（undo 已加回），跳过，避免重复添加
            if (groupList.any { it.id == group.id }) return
            groupList.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
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
        dismissFilterPicker()
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
        val editButton = binding.edit
        val optionsButton = binding.options
        val updateButton = binding.groupUpdate
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

            itemView.setOnClickListener { }

            editButton.isGone = proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                val menuGroup = proxyGroup

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (menuGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                popup.setOnMenuItemClickListener { item -> onMenuItemClick(item, menuGroup) }
                popup.show()
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
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isGone = proxyGroup.ungrouped
            }

            val subscription = proxyGroup.subscription
            if (subscription != null && subscription.bytesUsed > 0L) { // SIP008 & Open Online Config
                groupTraffic.isVisible = true
                groupTraffic.text = if (subscription.bytesRemaining > 0L) {
                    app.getString(
                        R.string.subscription_traffic, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        ), Formatter.formatFileSize(
                            app, subscription.bytesRemaining
                        )
                    )
                } else {
                    app.getString(
                        R.string.subscription_used, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        )
                    )
                }
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
                                used.toBytesString(),
                                remain.toBytesString()
                            )
                        } else {
                            getString(R.string.subscription_used, used.toBytesString())
                        }
                    }
                    get("expire=([0-9]+)")?.apply {
                        text += "\n"
                        text += getString(
                            R.string.subscription_expire,
                            Util.timeStamp2Text(this.toLong() * 1000)
                        )
                    }
                } catch (_: NumberFormatException) {
                    // ignore
                }

                if (text.isNotEmpty()) {
                    groupTraffic.isVisible = true
                    groupTraffic.text = text
                    groupStatus.setPadding(0)
                }
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            groupUser.text = subscription?.username ?: ""

            runOnDefaultDispatcher {
                val size = PendingProfileDeletes.count(group.id)
                onMainDispatcher {
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
                                val date = Date(group.subscription!!.lastUpdated * 1000L)
                                getString(
                                    R.string.group_status_proxies_subscription,
                                    size,
                                    "${date.month + 1} - ${date.date}"
                                )
                            }

                        }
                    }
                }

            }

        }
    }

}