package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import io.nekohasekai.sagernet.widget.ChoiceMenuPopup
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RemoteRouteUpdater
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutEmptyRouteBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.RouteOutboundMenu
import io.nekohasekai.sagernet.widget.UndoSnackbarManager

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>

    private var outboundPopup: ChoiceMenuPopup? = null
    private var pendingProfileRuleId = 0L
    private val savingOutbounds = hashSetOf<Long>()

    private val chooseRuleProfile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ruleId = pendingProfileRuleId
        pendingProfileRuleId = 0L
        if (result.resultCode == Activity.RESULT_OK && ruleId > 0) {
            val profileId = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            if (profileId > 0) saveQuickOutbound(ruleId, profileId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingProfileRuleId = savedInstanceState?.getLong("pendingProfileRuleId", 0L) ?: 0L
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("pendingProfileRuleId", pendingProfileRuleId)
    }

    private fun showQuickOutbound(anchor: View, ruleId: Long, outbound: Long) {
        if (ruleId in savingOutbounds || pendingProfileRuleId != 0L) return
        outboundPopup?.dismiss()
        outboundPopup = RouteOutboundMenu.show(anchor, outbound) { position ->
            when(position) {
                0 -> saveQuickOutbound(ruleId, 0)
                1 -> saveQuickOutbound(ruleId, -1)
                2 -> saveQuickOutbound(ruleId, -2)
                3 -> {
                    pendingProfileRuleId = ruleId
                    chooseRuleProfile.launch(Intent(requireContext(), ProfileSelectActivity::class.java).apply {
                        if (outbound > 0) ProfileManager.getProfile(outbound)?.let {
                            putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                        }
                    })
                }
            }
        }
    }

    private fun saveQuickOutbound(ruleId: Long, outbound: Long) {
        if (!savingOutbounds.add(ruleId)) return
        lifecycleScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { RouteOutboundStore.set(SagerDatabase.instance, ruleId, outbound) }
                if (saved == null) {
                    if (isAdded) snackbar(getString(R.string.route_quick_save_failed)).show()
                    return@launch
                }
                // UI labels are published from the latest persisted row so parallel switch updates win correctly.
                val latest = withContext(Dispatchers.IO) { SagerDatabase.rulesDao.getById(ruleId) } ?: return@launch
                ProfileManager.ruleIterator { onUpdated(latest) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Logs.w(error)
                if (isAdded) snackbar(getString(R.string.route_quick_save_failed)).show()
            } finally { savingOutbounds.remove(ruleId) }
        }
    }

    override fun onDestroyView() {
        outboundPopup?.dismiss()
        outboundPopup = null
        if (::ruleAdapter.isInitialized) ProfileManager.removeListener(ruleAdapter)
        if (::undoManager.isInitialized) undoManager.flush()
        if (::ruleListView.isInitialized) ruleListView.adapter = null
        super.onDestroyView()
    }

    /** 问题1-B：切回路由页时列表回顶部 */
    override fun resetScrollState() {
        if (::ruleListView.isInitialized) ruleListView.scrollToPosition(0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_route)
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.setOnMenuItemClickListener(this)

        ruleListView = view.findViewById(R.id.route_list)
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                ruleAdapter.remove(index)
                undoManager.remove(index to (viewHolder as RuleAdapter.RuleHolder).rule)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target is RuleAdapter.DocumentHolder) {
                    false
                } else {
                    ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    true
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }

            // REQ-04：与配置页/分组页统一——拖到列表边缘匀速自动滚动（不随停留
            // 渐进加速），速度由偏好 dragScrollSpeed 驱动，替换默认的加速滚动。
            override fun interpolateOutOfBoundsScroll(
                recyclerView: RecyclerView,
                viewSize: Int,
                viewSizeOutOfBounds: Int,
                totalSize: Int,
                msSinceStartScroll: Long,
            ): Int {
                return GroupDragScroll.pixels(recyclerView, viewSizeOutOfBounds)
            }
        }).attachToRecyclerView(ruleListView)
    }

    override fun onDestroy() {
        if (::ruleAdapter.isInitialized) {
            ProfileManager.removeListener(ruleAdapter)
        }
        super.onDestroy()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_route -> {
                startActivity(Intent(context, RouteSettingsActivity::class.java))
            }
            R.id.action_reset_route -> {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.route_reset_title)
                    .setMessage(R.string.route_reset_message)
                    .setPositiveButton(R.string.route_reset) { _, _ ->
                        runOnDefaultDispatcher {
                            // Restore only the catalog entries; custom routes and manual rules survive.
                            ProfileManager.ensureDefaultRoutes(reset = true)
                            ruleAdapter.reload()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.action_update_builtin_routes -> {
                lifecycleScope.launch {
                    val failures = withContext(Dispatchers.IO) { RemoteRouteUpdater.updateAll() }
                    ruleAdapter.reload()
                    if (failures.isNotEmpty() && isAdded) {
                        snackbar(getString(R.string.route_update_failed,
                            failures.joinToString(", ") { it.first })).show()
                    }
                }
            }
            R.id.action_manage_assets -> {
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            }
        }
        return true
    }

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        suspend fun reload() {
            val rules = ProfileManager.getRules()
            ruleListView.post {
                ruleList.clear()
                ruleList.addAll(rules)
                ruleAdapter.notifyDataSetChanged()
            }
        }

        init {
            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                DocumentHolder(LayoutEmptyRouteBinding.inflate(layoutInflater, parent, false))
            } else {
                RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return 0
            return 1
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is DocumentHolder) {
                holder.bind()
            } else if (holder is RuleHolder) {
                holder.bind(ruleList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return ruleList.size + 1
        }

        override fun getItemId(position: Int): Long {
            if (position == 0) return 0L
            return ruleList[position - 1].id
        }

        private val updated = HashSet<RuleEntity>()
        fun move(from: Int, to: Int) {
            val first = ruleList[from - 1]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from - 1 until to - 1) else Pair(-1, to downTo from - 1)
            for (i in range) {
                val next = ruleList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                ruleList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            ruleList[to - 1] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            val changes = updated.map { it.id to it.userOrder }
            updated.clear()
            if (changes.isEmpty()) return
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    SagerDatabase.instance.runInTransaction {
                        changes.forEach { (id, order) -> SagerDatabase.rulesDao.updateOrder(id, order) }
                    }
                }
                needReload()
            }
        }

        fun remove(index: Int) {
            ruleList.removeAt(index - 1)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            for ((index, item) in actions) {
                ruleList.add(index - 1, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            runOnDefaultDispatcher {
                ProfileManager.deleteRules(rules)
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            ruleListView.post {
                ruleList.add(rule)
                ruleAdapter.notifyItemInserted(ruleList.size)
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            ruleListView.post {
                val index = ruleList.indexOfFirst { it.id == rule.id }
                if (index < 0) return@post
                // Quick outbound changes must not undo an in-progress reorder or switch interaction.
                val visible = ruleList[index]
                ruleList[index] = rule.copy(userOrder = visible.userOrder)
                ruleAdapter.notifyItemChanged(index + 1)
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            val index = ruleList.indexOfFirst { it.id == ruleId }
            if (index == -1) {
                onMainDispatcher {
                    needReload()
                }
            } else ruleListView.post {
                ruleList.removeAt(index)
                ruleAdapter.notifyItemRemoved(index + 1)
                needReload()
            }
        }

        override suspend fun onCleared() {
            ruleListView.post {
                ruleList.clear()
                ruleAdapter.notifyDataSetChanged()
                needReload()
            }
        }

        inner class DocumentHolder(binding: LayoutEmptyRouteBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind() {
                itemView.setOnClickListener {
                    it.context.launchCustomTab("https://matsuridayo.github.io/nb4a-route/")
                }
            }
        }

        private fun postFreshRule(ruleId: Long) {
            runOnDefaultDispatcher {
                val fresh = SagerDatabase.rulesDao.getById(ruleId) ?: return@runOnDefaultDispatcher
                ProfileManager.ruleIterator { onUpdated(fresh) }
            }
        }

        inner class RuleHolder(binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity
            val profileName = binding.profileName
            val profileType = binding.profileType
            val routeOutbound = binding.routeOutbound
            val editButton = binding.edit
            val shareLayout = binding.share
            val enableSwitch = binding.enable

            fun bind(ruleEntity: RuleEntity) {
                rule = ruleEntity
                profileName.text = rule.displayName()
                profileType.text = rule.mkSummary()
                routeOutbound.text = rule.displayOutbound()

                // 根据路由类型设置文字颜色
                val colorRes = when (rule.outbound) {
                    -2L -> R.color.color_route_block   // 屏蔽：红色
                    -1L -> R.color.color_route_direct  // 直连：绿色
                    0L -> R.color.color_route_proxy    // 代理：蓝色
                    else -> R.color.color_route_config // 配置：紫色
                }
                routeOutbound.setTextColor(ContextCompat.getColor(itemView.context, colorRes))
                routeOutbound.isClickable = true
                routeOutbound.isFocusable = true
                routeOutbound.minHeight = (40 * resources.displayMetrics.density).toInt()
                routeOutbound.gravity = android.view.Gravity.CENTER_VERTICAL
                // Match the edit pencil's theme ripple on every recycled bind.
                routeOutbound.setBackgroundResource(routeOutbound.context.theme.resolveResourceId(
                    androidx.appcompat.R.attr.selectableItemBackgroundBorderless,
                ))
                routeOutbound.setOnClickListener { anchor ->
                    showQuickOutbound(anchor, rule.id, rule.outbound)
                }
                // A failed/empty fixed subscription keeps its card and offers a direct retry.
                if (rule.builtinKey.startsWith("remote_") && rule.subscriptionState == "waiting") {
                    routeOutbound.text = getString(R.string.route_retry_update)
                    routeOutbound.setOnClickListener {
                        lifecycleScope.launch {
                            try {
                                RemoteRouteUpdater.update(rule.builtinKey)
                                ruleAdapter.reload()
                            } catch (_: Exception) {
                                if (isAdded) snackbar(getString(R.string.route_update_failed, rule.displayName())).show()
                            }
                        }
                    }
                }

                itemView.setOnClickListener(null)
                itemView.isClickable = false
                itemView.isFocusable = false
                enableSwitch.bind(rule.id) {
                    enableSwitch.setOnCheckedChangeListener(null)
                    enableSwitch.isChecked = rule.enabled
                }
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    val ruleId = rule.id
                    rule.enabled = isChecked
                    runOnDefaultDispatcher {
                        SagerDatabase.rulesDao.updateEnabled(ruleId, isChecked)
                        postFreshRule(ruleId)
                        onMainDispatcher {
                            needReload()
                        }
                    }
                }
                editButton.setOnClickListener {
                    startActivity(Intent(it.context, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
                    })
                }
            }
        }

    }

}
