package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.KaringPresetImporter
import io.nekohasekai.sagernet.database.hasKaringEnvelope
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutEmptyRouteBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.UndoSnackbarManager

/** Reassign only the visible group's existing order slots; other rows keep their priority. */
internal fun moveVisibleRoute(rules: MutableList<RuleEntity>, from: Int, to: Int) {
    val orders = rules.map { it.userOrder }.sorted()
    val item = rules.removeAt(from)
    rules.add(to, item)
    rules.forEachIndexed { index, rule -> rule.userOrder = orders[index] }
}

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>

    private val selectProfile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingOutboundRule
        pendingOutboundRule = null
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            if (id > 0L && pending != null) saveOutbound(pending, id)
        }
    }
    private var pendingOutboundRule: RuleEntity? = null
    private var showPresets = true

    private fun saveOutbound(rule: RuleEntity, outbound: Long) {
        runOnDefaultDispatcher {
            val current = SagerDatabase.rulesDao.getById(rule.id) ?: return@runOnDefaultDispatcher
            current.outbound = outbound
            ProfileManager.updateRule(current)
        }
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
                if (index !in 1..ruleAdapter.ruleList.size || viewHolder !is RuleAdapter.RuleHolder) return
                val rule = ruleAdapter.ruleList[index - 1]
                ruleAdapter.remove(index)
                undoManager.remove(index to rule)
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
        }).attachToRecyclerView(ruleListView)
    }

    override fun onDestroyView() {
        if (::undoManager.isInitialized) undoManager.flush()
        if (::ruleAdapter.isInitialized) ruleAdapter.commitMove()
        super.onDestroyView()
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
            R.id.action_import_cn_preset -> {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.karing_import_cn)
                    .setMessage(R.string.karing_import_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ -> importChinaPreset() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
            R.id.action_reset_route -> {
                MaterialAlertDialogBuilder(activity).setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            SagerDatabase.rulesDao.reset()
                            DataStore.rulesFirstCreate = false
                            ruleAdapter.reload()
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
            R.id.action_manage_assets -> {
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            }
        }
        return true
    }

    private fun importChinaPreset() {
        val appContext = requireContext().applicationContext
        runOnDefaultDispatcher {
            runCatching {
                val json = appContext.assets.open("karing/cn.json").bufferedReader().use { it.readText() }
                val preset = KaringPresetImporter.parse(json)
                var added = 0
                var skipped = 0
                SagerDatabase.instance.runInTransaction {
                    val dao = SagerDatabase.rulesDao
                    val existing = dao.allRules()
                    val plan = KaringPresetImporter.importInto(existing, preset, "karing-cn-v1")
                    val newRules = plan.rules.drop(existing.size)
                    var order = dao.nextOrder() ?: 1L
                    newRules.forEach { rule ->
                        rule.userOrder = order++
                        dao.createRule(rule)
                    }
                    added = newRules.size
                    skipped = plan.skippedDuplicates
                }
                added to skipped
            }.onSuccess { (added, skipped) ->
                ruleAdapter.reload()
                onMainDispatcher {
                    needReload()
                    activity.snackbar(getString(R.string.karing_import_result, added, skipped)).show()
                }
            }.onFailure { error ->
                onMainDispatcher { activity.alert(error.readableMessage).tryToShow() }
            }
        }
    }

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        val ruleList = ArrayList<RuleEntity>()
        private val allRules = ArrayList<RuleEntity>()
        private val pendingRemoval = HashSet<Long>()

        private fun refreshVisible() {
            ruleList.clear()
            ruleList.addAll(allRules.filter { it.hasKaringEnvelope() == showPresets && it.id !in pendingRemoval }.sortedBy { it.userOrder })
            notifyDataSetChanged()
        }

        fun selectGroup(presets: Boolean) {
            if (showPresets == presets) return
            commitMove()
            showPresets = presets
            refreshVisible()
        }
        suspend fun reload() {
            val rules = ProfileManager.getRules()
            ruleListView.post {
                allRules.clear()
                allRules.addAll(rules)
                refreshVisible()
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

        private var moved = false
        fun move(from: Int, to: Int) {
            if (from !in 1..ruleList.size || to !in 1..ruleList.size || from == to) return
            moveVisibleRoute(ruleList, from - 1, to - 1)
            moved = true
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            if (!moved) return
            moved = false
            val orders = ruleList.map { it.id to it.userOrder }
            runOnDefaultDispatcher {
                SagerDatabase.instance.runInTransaction {
                    orders.forEach { (id, order) ->
                        SagerDatabase.rulesDao.getById(id)?.let { current ->
                            current.userOrder = order
                            SagerDatabase.rulesDao.updateRule(current)
                        }
                    }
                }
                onMainDispatcher { needReload() }
            }
        }

        fun remove(index: Int) {
            val rule = ruleList.removeAt(index - 1)
            pendingRemoval.add(rule.id)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            actions.forEach { (_, item) ->
                pendingRemoval.remove(item.id)
                if (allRules.none { it.id == item.id }) allRules.add(item)
            }
            refreshVisible()
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            runOnDefaultDispatcher {
                ProfileManager.deleteRules(rules)
                ruleListView.post {
                    rules.forEach { pendingRemoval.remove(it.id) }
                }
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            ruleListView.post {
                allRules.removeAll { it.id == rule.id }
                allRules.add(rule)
                refreshVisible()
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            ruleListView.post {
                val index = allRules.indexOfFirst { it.id == rule.id }
                if (index >= 0) allRules[index] = rule else allRules.add(rule)
                refreshVisible()
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            ruleListView.post {
                allRules.removeAll { it.id == ruleId }
                refreshVisible()
                needReload()
            }
        }

        override suspend fun onCleared() {
            ruleListView.post {
                allRules.clear()
                refreshVisible()
                needReload()
            }
        }

        inner class DocumentHolder(private val binding: LayoutEmptyRouteBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind() {
                binding.routeWarning.setOnClickListener {
                    MaterialAlertDialogBuilder(it.context).setMessage(R.string.route_warn)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.route_documentation) { _, _ ->
                            it.context.launchCustomTab("https://matsuridayo.github.io/nb4a-route/")
                        }.show()
                }
                binding.routePresets.isChecked = showPresets
                binding.routeCustom.isChecked = !showPresets
                binding.routePresets.setOnClickListener {
                    selectGroup(true)
                    binding.routePresets.isChecked = showPresets
                    binding.routeCustom.isChecked = !showPresets
                }
                binding.routeCustom.setOnClickListener {
                    selectGroup(false)
                    binding.routePresets.isChecked = showPresets
                    binding.routeCustom.isChecked = !showPresets
                }
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
                profileType.text = ""
                routeOutbound.text = rule.displayOutbound()

                // 根据路由类型设置文字颜色
                val colorRes = when (rule.outbound) {
                    -2L -> R.color.color_route_block   // 屏蔽：红色
                    -1L -> R.color.color_route_direct  // 直连：绿色
                    0L -> R.color.color_route_proxy    // 代理：蓝色
                    else -> R.color.color_route_config // 配置：紫色
                }
                routeOutbound.setTextColor(ContextCompat.getColor(itemView.context, colorRes))

                routeOutbound.setOnClickListener { anchor ->
                    val selectedRule = rule
                    PopupMenu(anchor.context, anchor).apply {
                        menu.add(0, 0, 0, R.string.route_proxy)
                        menu.add(0, 1, 1, R.string.route_bypass)
                        menu.add(0, 2, 2, R.string.route_block)
                        menu.add(0, 3, 3, R.string.select_profile)
                        setOnMenuItemClickListener { item ->
                            when (item.itemId) {
                                0 -> saveOutbound(selectedRule, 0L)
                                1 -> saveOutbound(selectedRule, -1L)
                                2 -> saveOutbound(selectedRule, -2L)
                                3 -> {
                                    pendingOutboundRule = selectedRule
                                    selectProfile.launch(Intent(anchor.context, ProfileSelectActivity::class.java).apply {
                                        ProfileManager.getProfile(selectedRule.outbound)?.let { selected ->
                                            putExtra(ProfileSelectActivity.EXTRA_SELECTED, selected)
                                        }
                                    })
                                }
                            }
                            true
                        }
                        show()
                    }
                }
                itemView.setOnClickListener {
                    editButton.performClick()
                }
                itemView.isClickable = true
                itemView.isFocusable = true
                enableSwitch.setOnCheckedChangeListener(null)
                enableSwitch.isChecked = rule.enabled
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    val id = rule.id
                    runOnDefaultDispatcher {
                        SagerDatabase.rulesDao.getById(id)?.let { current ->
                            current.enabled = isChecked
                            ProfileManager.updateRule(current)
                        }
                    }
                }
                editButton.setOnClickListener {
                    val editor = if (rule.hasKaringEnvelope()) KaringRouteSettingsActivity::class.java
                        else RouteSettingsActivity::class.java
                    startActivity(Intent(it.context, editor).apply {
                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, rule.id)
                    })
                }
            }
        }

    }

}
