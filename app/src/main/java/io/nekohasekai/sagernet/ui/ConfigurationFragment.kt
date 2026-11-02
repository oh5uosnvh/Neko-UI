package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import com.google.android.material.snackbar.Snackbar
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.size
import kotlinx.coroutines.delay
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.FixedGridLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.JuicitySettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksRSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SnellSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanGoSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import moe.matsuri.nb4a.utils.Util
import libcore.Libcore
import okhttp3.internal.closeQuietly
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.collections.set
import androidx.appcompat.app.AlertDialog
import io.nekohasekai.sagernet.database.SubscriptionBean
import kotlin.math.abs

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    private var isRestoringGroup = false

    /** 顶栏 ⊙ / 分组 控件，见 TopBarController */
    private val topBar = TopBarController(this)

    /** 搜索框内的 [分组/全局] 范围切换 */
    private val searchScope = SearchScopeController()
    /**
     * 搜索展开时接管返回键。
     *
     * 原版是 AppCompat 的可折叠 action view，SearchView 内部会处理 BACK
     * （SearchAutoComplete.onKeyPreIme），所以按返回能收起搜索框。
     * 现在顶栏整排自绘、SearchView 不再挂在菜单项上，那条内部处理不会
     * 生效，必须自己接：只在「搜索展开」时启用，这样其它情况下返回键
     * 行为完全不受影响。
     */
    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            topBar.collapseSearch()
        }
    }

    /** TopBarController 在搜索展开/收起时通知这里 */
    fun onSearchStateChanged(expanded: Boolean) {
        searchBackCallback.isEnabled = expanded
    }

    /** 兜底：交给顶层 Activity 弹溢出菜单 */
    private fun popupToolbarOverflow() {
        var ctx: android.content.Context? = requireContext()
        while (ctx is android.content.ContextWrapper) {
            if (ctx is MainActivity) {
                ctx.openOptionsMenu()
                return
            }
            ctx = ctx.baseContext
        }
        Logs.w("no MainActivity host found for overflow menu")
    }

    /** 滚到当前选中节点；不在可见范围内则滚到它，否则回顶部 */
    private fun scrollToSelectedProfile() {
        val fragment = getCurrentGroupFragment() ?: return
        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
        val selectedProfileIndex =
            fragment.adapter?.configurationIdList?.indexOf(selectedProxy) ?: -1

        if (selectedProfileIndex != -1) {
            val layoutManager = fragment.layoutManager
            if (layoutManager is LinearLayoutManager) {
                val first = layoutManager.findFirstVisibleItemPosition()
                val last = layoutManager.findLastVisibleItemPosition()
                if (selectedProfileIndex !in first..last) {
                    fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                    return
                }
            } else {
                fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                return
            }
        }
        fragment.configurationListView.scrollTo(0)
    }


    /** 🔍 那个可折叠菜单项，收起搜索框时要用它 collapseActionView() */

    // 030: 实时读取——旧的 by lazy 首访后缓存，设置里开关后必须重启才生效
    val alwaysShowAddress get() = DataStore.alwaysShowAddress
    private var lastAppliedAlwaysShowAddress = DataStore.alwaysShowAddress

    @Volatile
    private var selectedProxySnapshot = selectedItem?.id ?: 0L

    @Volatile
    private var currentProfileSnapshot = 0L

    @Volatile
    private var serviceStartedSnapshot = DataStore.serviceState.started

    private data class ProfileStateSnapshot(
        val selectedProxy: Long,
        val currentProfile: Long,
        val serviceStarted: Boolean,
    )

    private val profileStateRequests = Channel<Long>(Channel.CONFLATED)
    private val profileStateGeneration = AtomicLong()
    private val profileStateInitialized = CompletableDeferred<Unit>()

    fun refreshProfileState() {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val generation = profileStateGeneration.incrementAndGet()
            profileStateRequests.trySend(generation)
        }
    }

    private fun updateSelectedProxySnapshot(profileId: Long) {
        val generation = profileStateGeneration.incrementAndGet()
        updateProfileStateSnapshots(
            profileId,
            currentProfileSnapshot,
            DataStore.serviceState.started,
        )
        profileStateRequests.trySend(generation)
    }

    private fun startProfileStateActor() {
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            for (generation in profileStateRequests) {
                val snapshot = try {
                    withContext(Dispatchers.IO) {
                        ProfileStateSnapshot(
                            selectedProxy = selectedItem?.id ?: DataStore.selectedProxy,
                            currentProfile = DataStore.currentProfile,
                            serviceStarted = DataStore.serviceState.started,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    if (generation == profileStateGeneration.get()) {
                        profileStateInitialized.complete(Unit)
                    }
                    continue
                }
                if (generation != profileStateGeneration.get()) continue
                updateProfileStateSnapshots(
                    snapshot.selectedProxy,
                    snapshot.currentProfile,
                    snapshot.serviceStarted,
                )
                profileStateInitialized.complete(Unit)
            }
        }
    }

    private fun updateProfileStateSnapshots(
        selectedProxy: Long,
        currentProfile: Long,
        serviceStarted: Boolean,
    ) {
        val changedIds = mutableSetOf<Long>()
        if (selectedProxySnapshot != selectedProxy) {
            changedIds.add(selectedProxySnapshot)
            changedIds.add(selectedProxy)
        }
        if (currentProfileSnapshot != currentProfile) {
            changedIds.add(currentProfileSnapshot)
            changedIds.add(currentProfile)
        }
        if (serviceStartedSnapshot != serviceStarted) {
            changedIds.add(selectedProxySnapshot)
            changedIds.add(currentProfileSnapshot)
            changedIds.add(selectedProxy)
            changedIds.add(currentProfile)
        }
        changedIds.removeAll { it <= 0L }

        selectedProxySnapshot = selectedProxy
        currentProfileSnapshot = currentProfile
        serviceStartedSnapshot = serviceStarted

        if (changedIds.isEmpty() || !::adapter.isInitialized) return
        adapter.groupFragments.values.forEach { fragment ->
            fragment.adapter?.refreshProfileState(changedIds)
        }
    }

    private fun isSelectedProfile(profileId: Long) = selectedProxySnapshot == profileId

    private fun isCurrentProfile(profileId: Long) = currentProfileSnapshot == profileId

    private fun isCurrentGroupPagerAdapter(candidate: GroupPagerAdapter): Boolean {
        return ::adapter.isInitialized && adapter === candidate
    }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            val byTag = childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as? GroupFragment
            if (byTag != null) return byTag
            val currentIdx = if (::groupPager.isInitialized) groupPager.currentItem else -1
            if (currentIdx >= 0 && ::adapter.isInitialized) adapter.getGroupFragment(currentIdx) else null
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    fun switchAllGroupFragmentsLayout() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.switchLayoutMode()
            }
        }
    }

    fun refreshAllGroupFragmentsCardStyle() {
        adapter.groupFragments.values.forEach { fragment ->
            if (fragment.isAdded && fragment.view != null) {
                fragment.adapter?.notifyDataSetChanged()
            }
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (!isRestoringGroup && adapter.groupList.size > position) {
                val newId = adapter.groupList[position].id
                if (DataStore.selectedGroup != newId) {
                    DataStore.selectedGroup = newId
                }
            }
        }
    }


    override fun onQueryTextChange(query: String): Boolean {
        onSearchQueryChanged(query)
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    /**
     * 按当前搜索范围过滤。
     *
     * - 分组：只过滤当前分组页
     * - 全局：全库搜索匹配的节点，并直接在当前可见列表展示出来（不跳转任何分组Tab，所有命中节点集中展示！）
     *
     * 同时统计当前匹配到的节点总数，联动 [删除] 按钮从 [分组/全局] 背后滑出或缩回。
     */
    private var duplicateCleanupInProgress = false

    private fun onSearchQueryChanged(query: String) {
        val totalHits = if (searchScope.current == SearchScopeController.Scope.GROUP) {
            val a = getCurrentGroupFragment()?.adapter
            a?.filter(query, global = false)
            if (query.isBlank()) 0 else (a?.configurationIdList?.size ?: 0)
        } else {
            // 全局搜索：让当前正在看的分组列表直接展示全库匹配的所有节点！
            val a = getCurrentGroupFragment()?.adapter
            a?.filter(query, global = true)
            if (query.isBlank()) 0 else (a?.configurationIdList?.size ?: 0)
        }

        // 有搜索词且命中了节点才滑出删除按钮，否则缩回去
        searchScope.updateDeleteVisibility(query.isNotBlank() && totalHits > 0)
    }

    /** 批量删除当前搜索匹配出的所有节点 */
    private fun deleteCurrentSearchResults() {
        val query = topBar.searchField?.query?.toString().orEmpty()
        if (query.isBlank()) return

        val a = getCurrentGroupFragment()?.adapter ?: return
        val toDelete = a.configurationIdList.mapNotNull { a.configurationList[it] }

        if (toDelete.isEmpty()) return

        val msg = getString(R.string.delete_confirm_prompt) + "\n" +
                toDelete.take(10).joinToString("\n") { it.displayName().orEmpty() } +
                if (toDelete.size > 10) "\n..." else ""

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.confirm)
            .setMessage(msg)
            .setPositiveButton(R.string.yes) { _, _ ->
                val page = getCurrentGroupFragment() ?: return@setPositiveButton
                if (!page.isUndoReady()) return@setPositiveButton
                val actions = a.stageBatchRemoval(toDelete)
                page.undoManager.remove(actions)
                onSearchQueryChanged(query)
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        startProfileStateActor()
        refreshProfileState()

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!select) {
            // 菜单仍然 inflate 到 toolbar 上 —— checkOrderMenu / global_mode
            // 勾选都要 findItem()。但整条 toolbar 会被 TopBarController 设为
            // GONE：它一个 item 都不能显示，显示了就会和自绘那一排重叠，
            // 而且只要存在非 always 项，AppCompat 还会自动补一个 ⋮ 溢出按钮。
            // 📄+ / ⋮ 的弹出改由我们指定锚点（AppCompatPopupWindowHelper）。
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
            toolbar.setOnMenuItemClickListener(this)

            topBar.attach(toolbar, object : TopBarController.Callbacks {
                override fun onOpenDrawer() {
                    (requireActivity() as MainActivity).openDrawer()
                }

                override fun onTitleClicked() = scrollToSelectedProfile()

                override fun onAddClicked(anchor: View) {
                    AppCompatPopupWindowHelper.show(
                        toolbar.menu.findItem(R.id.action_add)?.subMenu, anchor,
                        onClick = { onMenuItemClick(it) },
                    ) { popupToolbarOverflow() }
                }

                override fun onMoreClicked(anchor: View) {
                    AppCompatPopupWindowHelper.show(
                        toolbar.menu.findItem(R.id.action_misc)?.subMenu, anchor,
                        onClick = { onMenuItemClick(it) },
                    ) { popupToolbarOverflow() }
                }
            })
        } else {
            toolbar.setTitle(titleRes)
            toolbar.menu.add(0, R.id.neko_picker_groups, 0, R.string.topbar_group_desc).apply {
                setIcon(R.drawable.ic_topbar_group)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.neko_picker_groups) {
                    topBar.showGroupPicker(toolbar)
                    true
                } else false
            }
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }


        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)
        // 031: “始终显示地址”等显示开关即时生效——监听配置存储，变化即刷新所有
        // 已创建分组页的卡片（不等返回页面，更不等重启）
        DataStore.configurationStore.registerChangeListener(displayPrefListener)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { v ->
                if (position in adapter.groupList.indices) {
                    val group = adapter.groupList[position]
                    // 018: 长按分组标签 → 编辑 / 跳转分组 / ┄┄ / 清空 / 删除（自绘菜单）
                    showTabGroupMenu(v, group)
                }
                true
            }
        }.attach()

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragment.adapter!!.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    if (layoutManager is LinearLayoutManager) {
                        val first = layoutManager.findFirstVisibleItemPosition()
                        val last = layoutManager.findLastVisibleItemPosition()

                        if (selectedProfileIndex !in first..last) {
                            fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                            return@setOnClickListener
                        }
                    } else {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        // 注册返回键拦截（默认 disabled，仅搜索展开时生效）
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner, searchBackCallback,
        )

        DataStore.profileCacheStore.registerChangeListener(this)


    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        super.onPrepareOptionsMenu(menu)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        DataStore.configurationStore.unregisterChangeListener(displayPrefListener)

        if (::groupPager.isInitialized) {
            try {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            } catch (_: Exception) {}
        }

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        topBar.detach()
        searchScope.detach()

        super.onDestroy()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 被抽屉切走（hide）时收起搜索，避免返回键拦截器在其它页面残留
        if (hidden) {
            topBar.collapseSearch()
        } else if (::groupPager.isInitialized && ::tabLayout.isInitialized && ::adapter.isInitialized) {
            // 显示修复：从分组管理页返回时，把顶部标签栏同步到当前选中的分组。
            // 新建分组等操作发生在页面隐藏期间，标签栏在 GONE 状态下算不出滚动位置，
            // 回来后停在旧位置（要触摸才纠正）；这里照 reload() 的既有同步方式补一次。
            val index = groupPager.currentItem
            if (index in adapter.groupList.indices) {
                tabLayout.post {
                    val tab = tabLayout.getTabAt(index)
                    if (tab != null && !tab.isSelected) {
                        tab.select()
                    }
                    tabLayout.setScrollPosition(index, 0f, true)
                    tabLayout.post {
                        if (index in 0 until tabLayout.tabCount) {
                            tabLayout.setScrollPosition(index, 0f, true)
                        }
                    }
                }
            }

            // 顺序兜底：分组页拖动排序的广播若因竞态未生效，按数据库顺序校正
            adapter.syncOrderFromDb()
        }
    }

    /** 问题1-B：切回配置页时，当前分组的节点列表回顶部 */
    override fun resetScrollState() {
        getCurrentGroupFragment()?.resetScrollState()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) runOnDefaultDispatcher {
                try {
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName != null && fileName.endsWith(".zip")) {
                        // try parse wireguard zip
                        val zip =
                            ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val fileText = zip.bufferedReader().readText()
                            RawUpdater.parseRaw(fileText, entry.name)
                                ?.let { pl -> proxies.addAll(pl) }
                            zip.closeEntry()
                        }
                        zip.closeQuietly()
                    } else {
                        val fileText =
                            requireContext().contentResolver.openInputStream(file)!!.use {
                                it.bufferedReader().readText()
                            }
                        RawUpdater.parseRaw(fileText, fileName ?: "")
                            ?.let { pl -> proxies.addAll(pl) }
                    }
                    if (proxies.isEmpty()) onMainDispatcher {
                        if (isAdded) snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else import(proxies, "file", fileName.orEmpty())
                } catch (e: SubscriptionFoundException) {
                    (activity as? MainActivity)?.importSubscription(e.link.toUri())
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (isAdded) {
                            (activity as? MainActivity)?.snackbar(e.readableMessage)?.show()
                        }
                    }
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>, source: String = "", fileName: String = "") {
        val targetId = DataStore.selectedGroupForImport()
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            ProfileBatchStore.append(SagerDatabase.instance, targetId, proxies, source, fileName)
        }
        GroupManager.postUpdate(targetId)
        GroupManager.postReload(targetId)
        onMainDispatcher {
            DataStore.editingGroup = targetId
            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()
        }

    }

    private fun fetchAirportName(link: String): String? {
        if (!link.startsWith("http")) return null
        val client = Libcore.newHttpClient().apply {
            trySocks5(
                DataStore.mixedPort,
                DataStore.mixedInboundUser,
                DataStore.mixedInboundPass
            )
            tryH3Direct()
            when (DataStore.appTLSVersion) {
                "1.3" -> restrictedTLS()
            }
        }
        try {
            val response = client.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) {
                    allowInsecure()
                }
                setURL(link)
                setUserAgent(USER_AGENT)
            }.execute()

            var remoteName = RawUpdater.parseBodyProfileTitle(Util.getStringBox(response.contentString))
            if (remoteName.isBlank()) {
                remoteName = parseContentDisposition(Util.getStringBox(response.getHeader("content-disposition")))
            }
            if (remoteName.isBlank()) {
                remoteName = decodeProfileTitle(Util.getStringBox(response.getHeader("profile-title")))
            }
            return remoteName.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Logs.w(e)
            return null
        } finally {
            client.close()
        }
    }

    private fun parseContentDisposition(header: String): String {
        if (header.isBlank()) return ""
        val decoded = Util.decodeFilename(header).trim()
        if (decoded.isNotBlank()) return decoded
        return Regex("filename=\"?([^\";]+)\"?").find(header)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun decodeProfileTitle(header: String): String {
        if (header.isBlank()) return ""
        val title = header.trim()
        return try {
            URLDecoder.decode(title, "UTF-8")
        } catch (e: Exception) {
            title
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) {
                            onMainDispatcher {
                                snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                            }
                        } else {
                            import(proxies)
                        }
                    } catch (e: SubscriptionFoundException) {
                        if (e.link.startsWith("sn://")) {
                            onMainDispatcher {
                                (requireActivity() as MainActivity).importSubscription(e.link.toUri())
                            }
                        } else {
                            val subscriptionUri = Uri.parse(e.link)
                            val subscriptionLink = subscriptionUri.getQueryParameter("url") ?: e.link
                            val airportName = subscriptionUri.getQueryParameter("name")?.takeIf { it.isNotBlank() }
                                ?: subscriptionUri.fragment?.takeIf { it.isNotBlank() }

                            val group = ProxyGroup(type = GroupType.SUBSCRIPTION)
                            val subscription = SubscriptionBean()
                            group.subscription = subscription
                            subscription.link = subscriptionLink
                            subscription.autoUpdate = false
                            group.name = airportName ?: ""
                            onMainDispatcher {
                                startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                                    putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                    putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, subscriptionLink)
                                    if (airportName != null) {
                                        putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, airportName)
                                    }
                                })
                            }
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_group -> {
                startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, 0L)
                })
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_ssr -> {
                startActivity(Intent(requireActivity(), ShadowsocksRSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_trojan_go -> {
                startActivity(Intent(requireActivity(), TrojanGoSettingsActivity::class.java))
            }

            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }

            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }

            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }

            R.id.action_new_snell -> {
                startActivity(Intent(requireActivity(), SnellSettingsActivity::class.java))
            }

            R.id.action_new_xhttp -> {
                startActivity(Intent(requireActivity(), moe.matsuri.nb4a.proxy.xhttp.XhttpSettingsActivity::class.java))
            }

            R.id.action_new_oppa -> {
                startActivity(Intent(requireActivity(), io.nekohasekai.sagernet.ui.profile.OppaSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                val trafficService = (activity as? MainActivity)?.connection?.service
                runOnDefaultDispatcher {
                    val profiles = PendingProfileDeletes.loadGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.tx != 0L || profile.rx != 0L) {
                            profile.tx = 0
                            profile.rx = 0
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                    try {
                        trafficService?.resetTraffic(profiles.map { it.id }.toLongArray())
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                    onMainDispatcher {
                        getCurrentGroupFragment()?.adapter?.clearTrafficStatistics()
                    }
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = PendingProfileDeletes.loadGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                    onMainDispatcher {
                        getCurrentGroupFragment()?.adapter?.clearTestResults()
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                if (duplicateCleanupInProgress) return true
                duplicateCleanupInProgress = true
                val groupId = DataStore.currentGroupId()
                runOnDefaultDispatcher {
                    try {
                        val nodes = PendingProfileDeletes.loadGroup(groupId).filter { it.status != 0 && it.status != 1 }
                        onMainDispatcher {
                            if (!isAdded || nodes.isEmpty()) duplicateCleanupInProgress = false
                            else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage("您确认要删除这些重复配置吗？\n一共 ${nodes.size} 个重复配置。\n\n" + nodes.take(50).joinToString("\n") { node ->
                                    val name = node.displayName().orEmpty().replace("\n", " ")
                                    if (name.length > 42) name.take(41) + "…" else name
                                } + if (nodes.size > 50) "\n……" else "")
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    val page = adapter.groupFragments[groupId]
                                    val list = page?.adapter
                                    if (list != null && page?.isUndoReady() == true) page.undoManager.remove(list.stageBatchRemoval(nodes))
                                    duplicateCleanupInProgress = false
                                }
                                .setNegativeButton(R.string.no) { _, _ -> duplicateCleanupInProgress = false }
                                .setOnCancelListener { duplicateCleanupInProgress = false }.show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher { duplicateCleanupInProgress = false }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                if (duplicateCleanupInProgress) return true
                duplicateCleanupInProgress = true
                val targetGroupId = DataStore.currentGroupId()
                runOnDefaultDispatcher {
                    try {
                        val snapshot = PendingProfileDeletes.loadGroup(targetGroupId)
                        val unique = HashSet<Protocols.Deduplication>()
                        val duplicates = snapshot.filter {
                            !unique.add(Protocols.Deduplication(it.requireBean(), it.displayType()))
                        }
                        onMainDispatcher {
                            if (!isAdded || duplicates.isEmpty()) {
                                duplicateCleanupInProgress = false
                            } else {
                                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                    .setMessage("您确认要删除这些重复配置吗？\n一共 ${duplicates.size} 个重复配置。\n\n" + duplicates.take(50).joinToString("\n") { node ->
                                val name = node.displayName().orEmpty().replace("\n", " ")
                                if (name.length > 42) name.take(41) + "…" else name
                            } + if (duplicates.size > 50) "\n……" else "")
                                    .setPositiveButton(R.string.yes) { _, _ ->
                                        val page = adapter.groupFragments[targetGroupId]
                                        val list = page?.adapter
                                        if (list != null && page?.isUndoReady() == true) {
                                            val actions = list.stageBatchRemoval(duplicates)
                                            page.undoManager.remove(actions)
                                        }
                                        duplicateCleanupInProgress = false
                                    }
                                    .setNegativeButton(R.string.no) { _, _ -> duplicateCleanupInProgress = false }
                                    .setOnCancelListener { duplicateCleanupInProgress = false }
                                    .show()
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        onMainDispatcher { duplicateCleanupInProgress = false }
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            duplicateCleanupInProgress = false
                            if (isAdded) snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }

            R.id.action_global_mode -> {
                item.isChecked = !item.isChecked
                DataStore.globalMode = item.isChecked
                if (DataStore.serviceState.canStop) {
                    runOnDefaultDispatcher {
                        try {
                            // 等待一段时间确保配置已保存
                            delay(500)
                            snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                                runOnDefaultDispatcher {
                                    try {
                                        // 再次等待确保配置已保存
                                        delay(100)
                                        SagerNet.reloadService()
                                    } catch (e: Exception) {
                                        Logs.w(e)
                                        onMainDispatcher {
                                            snackbar(getString(R.string.service_failed)).show()
                                        }
                                    }
                                }
                            }.show()
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(getString(R.string.service_failed)).show()
                            }
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        private val flushScheduled = AtomicBoolean(false)
        private val resultsLock = Any()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        // 012: 单个节点测完即防抖批量回写（250ms 合并窗口）。
        // 行延迟经 ProfileManager.onUpdated 定点刷新，实时变化；
        // 不再等全部测完/取消后串行回写（旧实现 180 节点要等 2~3 秒才变化）。
        fun scheduleFlush() {
            if (dialogStatus.get() == 2) return
            if (!flushScheduled.compareAndSet(false, true)) return
            runOnDefaultDispatcher {
                delay(250)
                flushScheduled.set(false)
                flushPending()
            }
        }

        // 取出当前累积结果一次性批量落库；cancel 收尾也复用同一入口。
        suspend fun flushPending() {
            val snapshot = synchronized(resultsLock) {
                val copy = ArrayList(results)
                results.clear()
                copy
            }
            if (snapshot.isEmpty()) return
            try {
                ProfileManager.updateProfile(snapshot)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() != 2) {
                synchronized(resultsLock) { results.add(profile) }
                scheduleFlush()
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.material_green_500)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.material_red_500)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean) {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
                if (icmpPing) {
                    if (it.requireBean().canICMPing()) {
                        return@filter true
                    }
                } else {
                    if (it.requireBean().canTCPing()) {
                        return@filter true
                    }
                }
                return@filter false
            }
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break

                        profile.status = 0
                        var address = profile.requireBean().serverAddress
                        if (!address.isIpAddress()) {
                            try {
                                SagerNet.underlyingNetwork!!.getAllByName(address).apply {
                                    if (isNotEmpty()) {
                                        address = this[0].hostAddress
                                    }
                                }
                            } catch (ignored: UnknownHostException) {
                            }
                        }
                        if (!isActive) break
                        if (!address.isIpAddress()) {
                            profile.status = 2
                            profile.error = app.getString(R.string.connection_test_domain_not_found)
                            test.update(profile)
                            continue
                        }
                        try {
                            if (icmpPing) {
                                // removed
                            } else {
                                val socket =
                                    SagerNet.underlyingNetwork?.socketFactory?.createSocket()
                                        ?: Socket()
                                try {
                                    socket.soTimeout = 3000
                                    socket.bind(InetSocketAddress(0))
                                    val start = SystemClock.elapsedRealtime()
                                    socket.connect(
                                        InetSocketAddress(
                                            address, profile.requireBean().serverPort
                                        ), 3000
                                    )
                                    if (!isActive) break
                                    profile.status = 1
                                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                    test.update(profile)
                                } finally {
                                    socket.closeQuietly()
                                }
                            }
                        } catch (e: Exception) {
                            if (!isActive) break
                            val message = e.readableMessage

                            if (icmpPing) {
                                profile.status = 2
                                profile.error = getString(R.string.connection_test_unreachable)
                            } else {
                                profile.status = 2
                                when {
                                    !message.contains("failed:") -> profile.error =
                                        getString(R.string.connection_test_timeout_error)

                                    else -> when {
                                        message.contains("ECONNREFUSED") -> {
                                            profile.error =
                                                getString(R.string.connection_test_refused)
                                        }

                                        message.contains("ENETUNREACH") -> {
                                            profile.error =
                                                getString(R.string.connection_test_unreachable)
                                        }

                                        else -> {
                                            profile.status = 3
                                            profile.error = message
                                        }
                                    }
                                }
                            }
                            test.update(profile)
                        }
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            // 012: 先放行下一次测试（空档期≈0）；取消与回写全部后台收尾。
            // 进行中的 urlTest 实例最多再跑自身超时（默认 3s）后自行关闭，不阻塞新测试。
            DataStore.runningTest = false
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                // update() 已在测试过程中实时批量落库；这里只补冲最后一批收尾结果。
                test.flushPending()
                GroupManager.postReload(DataStore.currentGroupId())
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()
        // 013: 单实例测试的 init/launch/Libcore.urlTest 全程占用一个线程；旧实现把
        // 这条阻塞链跑在 Dispatchers.Default 上，有效并发被 CPU 核数封顶（并发设置
        // 开再高也快不起来），且取消后的僵尸实例霸占全部 Default 线程直到超时
        // （取消后重测 2~3 秒空档的根因）。改为专用阻塞池：测试实例为无入站轻量
        // 配置，16 路（≈2×CPU 核数）内存安全；真实 HTTP 延迟测量路径零改动。
        // 两条收尾路径（自然测完 / 用户取消）都会经 test.cancel 关闭池子。
        val testPool = Executors.newFixedThreadPool(
            DataStore.connectionTestConcurrent.coerceIn(1, 16)
        ).asCoroutineDispatcher()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id)
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    val urlTest = UrlTest(testPool) // note: this is NOT in bg process
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        profile.status = 0

                        try {
                            val result = urlTest.doTest(profile)
                            profile.status = 1
                            profile.ping = result
                        } catch (e: PluginManager.PluginNotFoundException) {
                            profile.status = 2
                            profile.error = e.readableMessage
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }

                        test.update(profile)
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            // 012: 先放行下一次测试（空档期≈0）；取消与回写全部后台收尾。
            // 013: 僵尸实例已由 TestInstance.invokeOnCancellation 即时关闭，
            // 这里同步关闭专用测试池；Default 线程不再被占坑，postReload 的
            // 后台 DiffUtil 不再被饿死。
            DataStore.runningTest = false
            runCatching { testPool.close() }
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                // update() 已在测试过程中实时批量落库；这里只补冲最后一批收尾结果。
                test.flushPending()
                GroupManager.postReload(DataStore.currentGroupId())
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    private val groupDeleteAccess = Mutex()
    private val deletingGroupIds = mutableSetOf<Long>()

    /** Long-press delete: preserve the current tab, or select its left neighbour. */
    /**
     * 018: 长按分组标签菜单 —— 自绘 PopupWindow（替代系统 PopupMenu）。
     *
     * 规格：
     *   编辑
     *   跳转分组
     *   ┄┄┄┄┄┄（只在清空之上画一条虚线分隔）
     *   清空
     *   删除
     *
     * 宽度收缩到内容：最宽项只有四个汉字，面板宽 = 最宽行实测宽。
     * 不用 MATCH_PARENT 行宽 —— WRAP_CONTENT 父布局里它会被
     * getChildMeasureSpec 放大成整屏可用宽。
     * 行右溢出屏幕时整体左移，保证不裁边。
     * 清空操作由共享撤销流程处理，保留分组并允许还原节点。
     */
    private fun showTabGroupMenu(anchor: View, group: ProxyGroup) {
        val context = requireContext()

        fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

        fun themeColor(attr: Int): Int {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            val c = ta.getColor(0, 0xFF888888.toInt())
            ta.recycle()
            return c
        }

        var popupRef: android.widget.PopupWindow? = null

        fun menuItem(label: String, action: () -> Unit): TextView {
            val item = TextView(context)
            item.text = label
            item.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            item.setTextColor(themeColor(android.R.attr.textColorPrimary))
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true
            )
            item.background = androidx.core.content.ContextCompat.getDrawable(
                context, ripple.resourceId
            )
            item.setPadding(dp(16), dp(11), dp(16), dp(11))
            item.isClickable = true
            item.setOnClickListener {
                popupRef?.dismiss()
                action()
            }
            return item
        }

        // 「跳转分组」与「清空」之间的那条虚线（左右各留 16dp，不顶满）
        fun dashedDivider(): View {
            val view = object : View(context) {
                override fun onDraw(canvas: android.graphics.Canvas) {
                    super.onDraw(canvas)
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = 0x33888888
                        strokeWidth = dp(1).toFloat()
                        pathEffect = android.graphics.DashPathEffect(
                            floatArrayOf(dp(4).toFloat(), dp(3).toFloat()), 0f
                        )
                    }
                    val y = height / 2f
                    canvas.drawLine(
                        dp(16).toFloat(), y, (width - dp(16)).toFloat(), y, paint
                    )
                }
            }
            view.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(9)
            )
            return view
        }

        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = resources.getDimension(R.dimen.popup_corner_radius)
                setColor(themeColor(android.R.attr.colorBackground))
                setStroke(
                    resources.getDimensionPixelSize(R.dimen.card_stroke_width),
                    androidx.core.content.ContextCompat.getColor(context, R.color.card_stroke),
                )
            }
        }

        val rows = arrayListOf<View>()
        fun addRow(build: () -> View) {
            val v = build()
            rows.add(v)
            column.addView(v)
        }
        addRow {
            menuItem(getString(R.string.edit)) {
                context.startActivity(
                    Intent(context, GroupSettingsActivity::class.java)
                        .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                )
            }
        }
        addRow {
            menuItem(getString(R.string.jump_to_group)) {
                (activity as? MainActivity)?.openGroupAt(group.id)
            }
        }
        addRow { dashedDivider() }
        addRow {
            menuItem(getString(R.string.clear_profiles)) {
                GroupClearAction.confirm(requireActivity() as ThemedActivity, group)
            }
        }
        addRow {
            menuItem(getString(R.string.delete)) {
                deleteGroupWithUndo(group)
            }
        }

        // 统一行宽 = 最宽行实测宽：面板宽度随内容收紧，右侧不再留白
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var maxW = 0
        for (row in rows) {
            row.measure(spec, spec)
            maxW = maxOf(maxW, row.measuredWidth)
        }
        for (row in rows) {
            val lp = row.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.width = maxW
            row.layoutParams = lp
        }

        val popup = android.widget.PopupWindow(
            column,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }
        popupRef = popup
        popup.showAsDropDown(anchor, 0, dp(4))

        // 面板超出屏幕右缘时整体左移（-1 = 宽高不变）
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val overflow = loc[0] + maxW + dp(8) - resources.displayMetrics.widthPixels
        if (overflow > 0) popup.update(anchor, -overflow, dp(4), -1, -1)
    }

    fun deleteGroupWithUndo(group: ProxyGroup) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        if (!isAdded || !::adapter.isInitialized || !deletingGroupIds.add(group.id)) return
        owner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                groupDeleteAccess.withLock {
                    // All adapter reads/writes run on Main. Resolve the ID again
                    // after acquiring the lock, never retain an obsolete index.
                    val snapshot = onMainDispatcher {
                        if (!isAdded || viewLifecycleOwnerLiveData.value !== owner) return@onMainDispatcher null
                        val ids = adapter.groupList.map { it.id }
                        Triple(ids, DataStore.selectedGroup, ids.indexOf(group.id))
                    } ?: return@withLock
                    if (snapshot.third < 0 || SagerDatabase.groupDao.getById(group.id) == null) return@withLock
                    val wasCurrent = snapshot.second == group.id
                    val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                    val nextId = GroupTabPolicy.afterRemoval(snapshot.first, group.id, snapshot.second)
                    // Protect selection against TabLayoutMediator's temporary
                    // first-tab selection during the structural update.
                    onMainDispatcher {
                        if (nextId != null) DataStore.selectedGroup = nextId
                    }
                    GroupManager.deleteGroup(group.id)
                    onMainDispatcher {
                        if (!isAdded || viewLifecycleOwnerLiveData.value !== owner) return@onMainDispatcher
                        snackbar(getString(R.string.group_deleted_undo, group.displayName())).apply {
                            setAction(R.string.undo) {
                                runOnDefaultDispatcher {
                                    restoreGroup(group, profiles, wasCurrent, snapshot.third)
                                }
                            }
                            duration = Snackbar.LENGTH_LONG
                        }.show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    if (isAdded && viewLifecycleOwnerLiveData.value === owner) snackbar(e.readableMessage).show()
                }
            } finally {
                onMainDispatcher { deletingGroupIds.remove(group.id) }
            }
        }
    }

    /** 018/022: 还原刚删除的分组（按原 id 回插 + **原位插回标签条**，节点随组恢复）。
     *  020 修复「点了还原但毫无变化」：pager 只认 groupAdd/groupRemoved
     *  （groupUpdated(groupId) 是空实现）。
     *  022 修复「还原被丢到末尾」：groupAdd 永远加在尾部——改为 restoreGroupAt
     *  按删除前记录的位置原位插回（对齐节点卡片撤销的原位语义）。 */
    private suspend fun restoreGroup(
        group: ProxyGroup, profiles: List<ProxyEntity>, wasCurrent: Boolean, position: Int,
    ) {
        runCatching {
            SagerDatabase.instance.runInTransaction {
                SagerDatabase.groupDao.createGroup(group)
                if (profiles.isNotEmpty()) SagerDatabase.proxyDao.insert(profiles)
            }
            if (wasCurrent) {
                DataStore.selectedGroup = group.id
            }
            onMainDispatcher {
                adapter.restoreGroupAt(group, position)
            }
            GroupManager.postReload(group.id)
        }.onFailure {
            Logs.w(it)
        }
    }

    override fun onResume() {
        super.onResume()
        // 030/031: 兜底——从设置返回时若开关变化，立即刷新当前页卡片
        if (lastAppliedAlwaysShowAddress != DataStore.alwaysShowAddress) {
            lastAppliedAlwaysShowAddress = DataStore.alwaysShowAddress
            runOnMainDispatcher {
                if (!isAdded) return@runOnMainDispatcher
                adapter.groupFragments.values.forEach { page ->
                    runCatching { page.adapter?.reloadProfiles() }
                }
            }
        }
    }

    // 031: 显示开关变化 → 刷新所有已创建分组页的卡片（地址行显隐 + 行高）
    private val displayPrefListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            if (key != Key.ALWAYS_SHOW_ADDRESS) return
            lastAppliedAlwaysShowAddress = DataStore.alwaysShowAddress
            runOnMainDispatcher {
                if (!isAdded || !::adapter.isInitialized) return@runOnMainDispatcher
                adapter.groupFragments.values.forEach { page ->
                    runCatching { page.adapter?.reloadProfiles() }
                }
            }
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()
        private val reloadGeneration = AtomicLong()

        fun reload(now: Boolean = false) {
            val generation = reloadGeneration.incrementAndGet()

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                if (generation != reloadGeneration.get()) return@runOnDefaultDispatcher

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var newSelectedGroupIndex: Int? = null
                if (selectedGroup > 0L) {
                    newSelectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    val reloadAdapter = this@GroupPagerAdapter
                    runFunc {
                        val viewOwner = viewLifecycleOwnerLiveData.value
                        if (generation == reloadGeneration.get() && viewOwner != null &&
                            isCurrentGroupPagerAdapter(reloadAdapter)
                        ) {
                            viewOwner.lifecycleScope.launch(Dispatchers.Main.immediate) {
                                profileStateInitialized.await()
                                if (generation != reloadGeneration.get() ||
                                    viewLifecycleOwnerLiveData.value !== viewOwner ||
                                    !isCurrentGroupPagerAdapter(reloadAdapter)
                                ) {
                                    return@launch
                                }
                                refreshProfileState()
                                newSelectedGroupIndex?.let { selectedGroupIndex = it }
                                groupList = newGroupList
                                isRestoringGroup = true
                                notifyDataSetChanged()
                                if (newSelectedGroupIndex != null && selectedGroupIndex in groupList.indices) {
                                    groupPager.setCurrentItem(selectedGroupIndex, false)
                                    tabLayout.post {
                                        if (selectedGroupIndex in 0 until tabLayout.tabCount) {
                                            val tab = tabLayout.getTabAt(selectedGroupIndex)
                                            if (tab != null && !tab.isSelected) {
                                                tab.select()
                                            }
                                            tabLayout.setScrollPosition(selectedGroupIndex, 0f, true)
                                        }
                                        tabLayout.post {
                                            if (selectedGroupIndex in 0 until tabLayout.tabCount) {
                                                tabLayout.setScrollPosition(selectedGroupIndex, 0f, true)
                                            }
                                            isRestoringGroup = false
                                        }
                                    }
                                } else {
                                    isRestoringGroup = false
                                }
                                val hideTab = groupList.size < 2
                                tabLayout.isGone = hideTab
                                toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                                if (!select) {
                                    groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                                }
                            }
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        /** 按页序号取已创建的 GroupFragment，未创建则 null */
        fun getGroupFragment(position: Int): GroupFragment? {
            if (position !in groupList.indices) return null
            return groupFragments[groupList[position].id]
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                val newIndex = groupList.size - 1
                DataStore.selectedGroup = group.id
                selectedGroupIndex = newIndex
                notifyItemInserted(newIndex)
                groupPager.setCurrentItem(newIndex, false)
                tabLayout.getTabAt(newIndex)?.select()
            }
        }

        private var removalGeneration = 0L

        override suspend fun groupRemoved(groupId: Long) {
            onMainDispatcher {
                if (viewLifecycleOwnerLiveData.value == null || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@onMainDispatcher
                val ids = groupList.map { it.id }
                val index = ids.indexOf(groupId)
                if (index < 0) return@onMainDispatcher
                val keepId = GroupTabPolicy.afterRemoval(ids, groupId, DataStore.selectedGroup)
                val scrollX = tabLayout.scrollX
                val generation = ++removalGeneration
                isRestoringGroup = true
                if (keepId != null) DataStore.selectedGroup = keepId
                groupList.removeAt(index)
                groupFragments.remove(groupId)
                notifyItemRemoved(index)
                val nextIndex = groupList.indexOfFirst { it.id == keepId }
                if (nextIndex >= 0) {
                    selectedGroupIndex = nextIndex
                    groupPager.setCurrentItem(nextIndex, false)
                }
                // Mediator and ViewPager both post work during item removal.
                // Finish selection after they settle, guarded against newer deletes.
                tabLayout.post {
                    if (generation != removalGeneration || viewLifecycleOwnerLiveData.value == null ||
                        !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@post
                    val currentIndex = groupList.indexOfFirst { it.id == DataStore.selectedGroup }
                    if (currentIndex >= 0) {
                        groupPager.setCurrentItem(currentIndex, false)
                        tabLayout.setScrollPosition(currentIndex, 0f, true)
                        tabLayout.scrollTo(scrollX, 0)
                    }
                    tabLayout.post {
                        if (generation == removalGeneration && isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) {
                            isRestoringGroup = false
                        }
                    }
                }
            }
        }

        /** 022: 分组删除还原 —— 按删除前的位置原位插回（groupAdd 只会加到尾部）。 */
        fun restoreGroupAt(group: ProxyGroup, index: Int) {
            if (groupList.any { it.id == group.id }) return
            tabLayout.post {
                val at = index.coerceIn(0, groupList.size)
                groupList.add(at, group)
                notifyItemInserted(at)
                if (groupList.any { !it.ungrouped }) tabLayout.visibility = View.VISIBLE
                tabLayout.getTabAt(at)?.select()
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            // 关键：把新对象写回 groupList。
            //
            // 改分组名后返回，☴ 快速列表读的是 groupList（TopBarController#groups()
            // 直接拿 adapter.groupList），而这里原先只更新了 tab 文案、没动 groupList，
            // 于是拿到的还是改名前那个旧对象 —— 表现就是「名字像没改一样」，
            // 直到列表被整体重建（切页/进设置再回来）才刷新。
            // 就地替换保持原有顺序，同时把 ☴ 列表和 tab 一起更新掉。
            val orderChanged = groupList[index].userOrder != group.userOrder
            groupList[index] = group

            if (orderChanged) {
                // 分组页拖动排序（003：松手落库后广播一次）：就地重排
                // （标签栏 + ☴ 快速列表），保持当前分组选中。
                // 只发 notifyItemMoved 平移既有页面——pager 已用分组 id 作稳定 ID，
                // 页面原位跟随不重建；禁止 notifyDataSetChanged（FragmentStateAdapter
                // 会销毁重建全部分组页，松手后必卡一下）。
                val currentId = DataStore.selectedGroup
                tabLayout.post {
                    val target = groupList.sortedBy { it.userOrder }
                    if (target.map { it.id } != groupList.map { it.id }) {
                        for (i in target.indices) {
                            val cur = groupList.indexOfFirst { it.id == target[i].id }
                            if (cur != i) {
                                groupList.add(i, groupList.removeAt(cur))
                                notifyItemMoved(cur, i)
                            }
                        }
                    }
                    val newIndex = groupList.indexOfFirst { it.id == currentId }
                    if (newIndex >= 0) {
                        groupPager.setCurrentItem(newIndex, false)
                    }
                }
            } else {
                tabLayout.post {
                    tabLayout.getTabAt(index)?.text = group.displayName()
                }
            }
        }

        /**
         * 从数据库校正分组顺序（兜底保险丝）：分组页拖动排序的广播若因竞态
         * 没有落到标签栏，页面每次显示时按数据库 userOrder 就地对齐一次。
         * 只在「集合一致、仅顺序不同」时动作，增删场景仍走 reload()。
         */
        fun syncOrderFromDb() {
            runOnDefaultDispatcher {
                val fresh = SagerDatabase.groupDao.allGroups()
                val orderedIds = fresh.sortedBy { it.userOrder }.map { it.id }
                val orderById = fresh.associate { it.id to it.userOrder }
                tabLayout.post {
                    if (!::groupPager.isInitialized || !::tabLayout.isInitialized) return@post
                    val currentId = groupList.getOrNull(groupPager.currentItem)?.id
                    val existing = groupList.associateBy { it.id }
                    val reordered = orderedIds.mapNotNull { existing[it] }
                    if (reordered.size != groupList.size) return@post
                    if (reordered.map { it.id } == groupList.map { it.id }) return@post
                    for (g in reordered) {
                        orderById[g.id]?.let { g.userOrder = it }
                    }
                    groupList.clear()
                    groupList.addAll(reordered)
                    notifyDataSetChanged()
                    val newIndex = groupList.indexOfFirst { it.id == currentId }
                    if (newIndex >= 0) {
                        groupPager.setCurrentItem(newIndex, false)
                        tabLayout.post {
                            val tab = tabLayout.getTabAt(newIndex)
                            if (tab != null && !tab.isSelected) {
                                tab.select()
                            }
                            tabLayout.setScrollPosition(newIndex, 0f, true)
                        }
                    }
                }
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(data: List<TrafficData>) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null

        // 014: 撤销通道是否就绪（select 模式下不初始化 undoManager）。
        fun isUndoReady(): Boolean = ::undoManager.isInitialized

        /** 问题1-B：切回配置页时节点列表回顶部 */
        fun resetScrollState() {
            if (::configurationListView.isInitialized) configurationListView.scrollToPosition(0)
        }

        /**
         * 修复：首次滑入分组节点卡片不渲染。数据级判空 + 防重入装载：
         * 只要适配器还没有数据且当前没有装载在途，就查库装载一次。
         * 可安全地从 onResume / onPageSelected 重复调用。
         */
        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: RecyclerView.LayoutManager
        private lateinit var itemTouchHelper: ItemTouchHelper
        private val alwaysShowAddress: Boolean
            get() = (parentFragment as? ConfigurationFragment)?.alwaysShowAddress == true

        private fun setupItemTouchHelper() {
            if (select) return
            
            if (::itemTouchHelper.isInitialized) {
                itemTouchHelper.attachToRecyclerView(null)
            }
            
            itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, 0) {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    val dragFlags = if (DataStore.groupLayoutMode == 1) {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                    } else {
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN
                    }
                    return makeMovementFlags(dragFlags, 0) // No swipe flags
                }

                override fun getSwipeDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return 0
                }

                override fun getDragDirs(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ): Int {
                    return if (isEnabled) {
                        if (DataStore.groupLayoutMode == 1) {
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                        } else {
                            ItemTouchHelper.UP or ItemTouchHelper.DOWN
                        }
                    } else 0
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                ): Boolean {
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition
                    
                    if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                        return false
                    }
                    
                    adapter?.move(fromPosition, toPosition)
                    return true
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    adapter?.commitMove()
                }

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
                    val speed = (maxScroll * 0.4f).toInt().coerceAtLeast(1)
                    return if (viewSizeOutOfBounds > 0) speed else -speed
                }

            })
            itemTouchHelper.attachToRecyclerView(configurationListView)
        }
        lateinit var configurationListView: RecyclerView

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            if (::configurationListView.isInitialized && configurationListView.size == 0) {
                configurationListView.adapter = adapter
                runOnDefaultDispatcher {
                    adapter?.reloadProfiles()
                }
            } else if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            checkOrderMenu()
            configurationListView.requestFocus()
        }

        fun checkOrderMenu() {
            if (select) return

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                if (proxyGroup.order == order) return
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
            
            val layoutSingle = menu.findItem(R.id.action_layout_single)
            val layoutDouble = menu.findItem(R.id.action_layout_double)
            when (DataStore.groupLayoutMode) {
                0 -> layoutSingle.isChecked = true
                1 -> layoutDouble.isChecked = true
            }
            layoutSingle.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != 0) {
                    DataStore.groupLayoutMode = 0
                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                }
                true
            }
            layoutDouble.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.groupLayoutMode != 1) {
                    DataStore.groupLayoutMode = 1
                    (parentFragment as? ConfigurationFragment)?.switchAllGroupFragmentsLayout()
                }
                true
            }

            val cardClassic = menu.findItem(R.id.action_card_style_classic)
            val cardStroke = menu.findItem(R.id.action_card_style_stroke)
            when (DataStore.profileCardStyle) {
                1 -> cardStroke.isChecked = true
                else -> cardClassic.isChecked = true
            }
            cardClassic.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.profileCardStyle != 0) {
                    DataStore.profileCardStyle = 0
                    (parentFragment as? ConfigurationFragment)?.refreshAllGroupFragmentsCardStyle()
                }
                true
            }
            cardStroke.setOnMenuItemClickListener {
                it.isChecked = true
                if (DataStore.profileCardStyle != 1) {
                    DataStore.profileCardStyle = 1
                    (parentFragment as? ConfigurationFragment)?.refreshAllGroupFragmentsCardStyle()
                }
                true
            }
        }

        private fun setupLayoutManager() {
            layoutManager = if (DataStore.groupLayoutMode == 1) {
                FixedGridLayoutManager(configurationListView, 2)
            } else {
                FixedLinearLayoutManager(configurationListView)
            }
        }
        
        fun switchLayoutMode() {
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            
            setupItemTouchHelper()
            
            adapter?.notifyDataSetChanged()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)
            configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        adapter?.flushPendingTrafficUpdates()
                    }
                }
            })

            if (!select) {
                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)
                setupItemTouchHelper()
                setupBottomBarScrollDriver()
            }
        }

        private fun setupBottomBarScrollDriver() {
            val mainActivity = activity as? MainActivity ?: return
            configurationListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) mainActivity.driveBottomBar(dy)
                }
            })

            val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
            var lastRawY = 0f
            configurationListView.setOnTouchListener { recyclerView, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> lastRawY = event.rawY
                    MotionEvent.ACTION_MOVE -> {
                        val cannotScroll = !recyclerView.canScrollVertically(-1) &&
                                !recyclerView.canScrollVertically(1)
                        if (cannotScroll) {
                            val fingerDy = event.rawY - lastRawY
                            if (abs(fingerDy) >= touchSlop) {
                                mainActivity.driveBottomBar(-fingerDy.toInt())
                                lastRawY = event.rawY
                            }
                        }
                    }
                }
                false
            }
        }

        override fun onDestroy() {
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            private val batchLoadGeneration = AtomicLong()

            private fun presentBatchSnapshot(ids: List<Long>, nodes: Map<Long, ProxyEntity>) {
                val first = (configurationListView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
                val anchorId = configurationIdList.getOrNull(first.coerceAtLeast(0))
                val previousIds = configurationIdList.toList()
                val oldNodes = HashMap(configurationList)
                val nextIds = ids.distinct().filterNot { PendingProfileDeletes.contains(it) }
                val nextNodes = nodes.filterKeys { !PendingProfileDeletes.contains(it) }
                // Large replacement: one stable-ID refresh avoids Myers diff O(N*D)
                // on 100k -> 20k; small updates retain precise animations.
                val diff = if (previousIds.size + nextIds.size <= 4000) DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = previousIds.size
                    override fun getNewListSize() = nextIds.size
                    override fun areItemsTheSame(a: Int, b: Int) = previousIds[a] == nextIds[b]
                    override fun areContentsTheSame(a: Int, b: Int) = oldNodes[previousIds[a]] == nextNodes[nextIds[b]]
                }, false) else null
                configurationList.clear()
                configurationList.putAll(nextNodes)
                configurationIdList = nextIds.toMutableList()
                if (diff != null) diff.dispatchUpdatesTo(this) else notifyDataSetChanged()
                val position = anchorId?.let { configurationIdList.indexOf(it) } ?: -1
                if (configurationIdList.isNotEmpty()) {
                    configurationListView.scrollToPosition(if (position >= 0) position else first.coerceIn(0, configurationIdList.lastIndex))
                }
                configurationListView.requestLayout()
                configurationListView.invalidate()
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()
            private val pendingTrafficUpdates = HashSet<Long>()

            private val profileStatePayload = Any()

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            private fun hasMiddleRow(p: ProxyEntity): Boolean {
                val showTraffic = p.rx + p.tx != 0L
                val bean = p.requireBean()
                val address = if (alwaysShowAddress && bean.name.isNotBlank()) {
                    bean.displayAddress()
                } else ""
                return !((!showTraffic || p.status <= 0) && address.isBlank())
            }

            fun neighbourHasMiddleRow(position: Int): Boolean {
                if (position == RecyclerView.NO_POSITION) return false
                val lm = (layoutManager as? FixedGridLayoutManager) ?: return false
                val spanCount = lm.spanCount
                val rowCount = lm.rowIndexOf(position)
                var rowMax = (rowCount + 1) * spanCount - 1
                if (rowMax >= itemCount) rowMax = itemCount - 1
                var rowStart = rowCount * spanCount
                if (rowStart < 0) rowStart = 0
                for (i in rowStart..rowMax) {
                    if (i == position) continue
                    if (try {
                            hasMiddleRow(getItemAt(i))
                        } catch (e: Exception) {
                            false
                        }
                    ) return true
                }
                return false
            }

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun onBindViewHolder(
                holder: ConfigurationHolder,
                position: Int,
                payloads: List<Any>,
            ) {
                if (payloads.isNotEmpty() && payloads.all { it === profileStatePayload }) {
                    try {
                        holder.bindProfileState(getItemAt(position))
                    } catch (ignored: NullPointerException) { // when group deleted
                    }
                } else {
                    onBindViewHolder(holder, position)
                }
            }

            override fun onViewRecycled(holder: ConfigurationHolder) {
                holder.lastSelfHasMiddleRow = null
                holder.lastBoundTx = Long.MIN_VALUE
                holder.lastBoundRx = Long.MIN_VALUE
            }

            override fun onViewAttachedToWindow(holder: ConfigurationHolder) {
                super.onViewAttachedToWindow(holder)
                val profileId = holder.itemId
                val cached = configurationList[profileId] ?: return
                if (holder.lastBoundTx == cached.tx && holder.lastBoundRx == cached.rx) return
                if (configurationListView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    pendingTrafficUpdates.add(profileId)
                    return
                }
                updateVisibleTraffic(profileId, holder)
            }

            private fun updateVisibleTraffic(
                profileId: Long,
                visibleHolder: ConfigurationHolder? = null,
            ) {
                val cached = configurationList[profileId] ?: return
                val holder = if (visibleHolder != null) {
                    visibleHolder
                } else {
                    configurationListView.findViewHolderForItemId(profileId)
                        as? ConfigurationHolder ?: return
                }
                if (holder.lastBoundTx == cached.tx && holder.lastBoundRx == cached.rx) return

                val index = holder.bindingAdapterPosition
                val previousHasMiddleRow = holder.lastSelfHasMiddleRow
                val previouslyShowedTraffic = holder.lastBoundTx != Long.MIN_VALUE &&
                        holder.lastBoundRx != Long.MIN_VALUE &&
                        holder.lastBoundTx + holder.lastBoundRx != 0L
                val showTraffic = cached.tx + cached.rx != 0L

                if (previousHasMiddleRow == null || previouslyShowedTraffic != showTraffic) {
                    holder.bind(cached)
                } else if (showTraffic) {
                    holder.bindTraffic(cached)
                }

                if (index != RecyclerView.NO_POSITION && previousHasMiddleRow != null &&
                    previousHasMiddleRow != holder.lastSelfHasMiddleRow
                ) {
                    refreshSameRowNeighbours(index)
                }
            }

            fun flushPendingTrafficUpdates() {
                if (pendingTrafficUpdates.isEmpty()) return
                for (index in 0 until configurationListView.childCount) {
                    val holder = configurationListView.getChildViewHolder(
                        configurationListView.getChildAt(index)
                    ) as? ConfigurationHolder ?: continue
                    val profileId = holder.itemId
                    if (profileId in pendingTrafficUpdates) {
                        updateVisibleTraffic(profileId, holder)
                    }
                }
                pendingTrafficUpdates.clear()
            }

            fun refreshSameRowNeighbours(position: Int) {
                if (position == RecyclerView.NO_POSITION) return
                val lm = (layoutManager as? FixedGridLayoutManager) ?: return
                val spanCount = lm.spanCount
                val rowCount = lm.rowIndexOf(position)
                var rowMax = (rowCount + 1) * spanCount - 1
                if (rowMax >= itemCount) rowMax = itemCount - 1
                var rowStart = rowCount * spanCount
                if (rowStart < 0) rowStart = 0
                configurationListView.post {
                    for (i in rowStart..rowMax) {
                        if (i == position) continue
                        notifyItemChanged(i)
                    }
                }
            }

            fun refreshFromPosition(startPosition: Int) {
                if (layoutManager !is FixedGridLayoutManager) return
                val start = startPosition.coerceAtLeast(0)
                if (start >= itemCount) return
                val count = itemCount - start
                configurationListView.post {
                    notifyItemRangeChanged(start, count)
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            fun refreshProfileState(profileIds: Set<Long>) {
                profileIds.forEach { profileId ->
                    val index = configurationIdList.indexOf(profileId)
                    if (index >= 0) notifyItemChanged(index, profileStatePayload)
                }
            }

            private val updated = HashSet<ProxyEntity>()

            fun filter(name: String, global: Boolean = false) {
                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }
                val lower = name.lowercase()
                if (global) {
                    // 全局搜索：直接从全库查询匹配节点，装填进当前可见列表展示
                    val allProfiles = SagerDatabase.proxyDao.getAll()
                    val matched = allProfiles.filter {
                        it.displayName().lowercase().contains(lower) ||
                                it.displayType().lowercase().contains(lower) ||
                                it.displayAddress().lowercase().contains(lower)
                    }
                    configurationList.clear()
                    matched.forEach { configurationList[it.id] = it }
                    configurationIdList.clear()
                    configurationIdList.addAll(matched.map { it.id })
                    notifyDataSetChanged()
                } else {
                    configurationIdList.clear()
                    configurationIdList.addAll(configurationList.filter {
                        it.value.displayName().lowercase().contains(lower) ||
                                it.value.displayType().lowercase().contains(lower) ||
                                it.value.displayAddress().lowercase().contains(lower)
                    }.keys)
                    notifyDataSetChanged()
                }
            }

            fun move(from: Int, to: Int) {
                if (from == to) return

                if (layoutManager is FixedGridLayoutManager) {
                    moveDualColumn(from, to)
                } else {
                    moveLinear(from, to)
                }
            }
            
            private fun moveLinear(from: Int, to: Int) {
                val first = getItemAt(from)
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step)
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }
            
            private fun moveDualColumn(from: Int, to: Int) {
                val draggedItemId = configurationIdList[from]

                configurationIdList.removeAt(from)
                configurationIdList.add(to, draggedItemId)
                
                for (i in configurationIdList.indices) {
                    val item = getItem(configurationIdList[i])
                    val newOrder = (i + 1).toLong()
                    if (item.userOrder != newOrder) {
                        item.userOrder = newOrder
                        updated.add(item)
                    }
                }
                
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
                onMainDispatcher {
                    if (layoutManager is FixedGridLayoutManager) {
                        notifyDataSetChanged()
                    }
                }
            }

            fun clearTrafficStatistics() {
                for (profile in configurationList.values) {
                    if (profile.tx != 0L || profile.rx != 0L) {
                        profile.tx = 0
                        profile.rx = 0
                    }
                }
                notifyDataSetChanged()
            }

            fun clearTestResults() {
                for (profile in configurationList.values) {
                    if (profile.status != 0) {
                        profile.status = 0
                        profile.ping = 0
                        profile.error = null
                    }
                }
                notifyDataSetChanged()
            }

            fun remove(pos: Int) {
                configurationIdList.getOrNull(pos)?.let { id ->
                    configurationList[id]?.let { stageBatchRemoval(listOf(it)) }
                }
            }

            fun stageBatchRemoval(nodes: List<ProxyEntity>): List<Pair<Int, ProxyEntity>> {
                batchLoadGeneration.incrementAndGet()
                val ids = nodes.map { it.id }.toHashSet()
                val positions = configurationIdList.withIndex().associate { it.value to it.index }
                val actions = nodes.map { (positions[it.id] ?: -1) to it }
                PendingProfileDeletes.stage(nodes)
                val keep = configurationIdList.filterNot { it in ids }
                val map = configurationList.filterKeys { it !in ids }
                presentBatchSnapshot(keep, map)
                return actions
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                val snapshot = actions.toList()
                configurationListView.post {
                    batchLoadGeneration.incrementAndGet()
                    PendingProfileDeletes.undo(snapshot.map { it.second })
                    val nodes = HashMap(configurationList)
                    snapshot.forEach { (_, node) -> nodes[node.id] = node }
                    val existing = configurationIdList.toHashSet()
                    val restore = snapshot.filter { it.first >= 0 && existing.add(it.second.id) }.sortedBy { it.first }
                    val merged = ArrayList<Long>(configurationIdList.size + restore.size)
                    var next = 0
                    var add = 0
                    while (next < configurationIdList.size || add < restore.size) {
                        if (add < restore.size && (restore[add].first <= merged.size || next == configurationIdList.size)) {
                            merged.add(restore[add++].second.id)
                        } else merged.add(configurationIdList[next++])
                    }
                    presentBatchSnapshot(merged, nodes)
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                // The Snackbar clears its recycle bin immediately: take a detached snapshot first.
                val snapshot = actions.map { it.second }
                runOnDefaultDispatcher {
                    try {
                        PendingProfileDeletes.commit(snapshot)
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            undo(snapshot.map { -1 to it })
                            if (isAdded) snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                    refreshFromPosition(pos - 1)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id) return
                if (noTraffic) {
                    (parentFragment as? ConfigurationFragment)?.refreshProfileState()
                }
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val cachedProfile = configurationList[profile.id]
                    val updatedProfile = if (noTraffic && cachedProfile != null) {
                        profile.copy(
                            tx = cachedProfile.tx,
                            rx = cachedProfile.rx,
                        ).also { it.dirty = profile.dirty }
                    } else {
                        profile
                    }
                    val contentChanged = !noTraffic ||
                            cachedProfile == null ||
                            cachedProfile != updatedProfile ||
                            cachedProfile.dirty != updatedProfile.dirty ||
                            cachedProfile.displayName() != updatedProfile.displayName()
                    configurationList[profile.id] = updatedProfile
                    if (noTraffic && !contentChanged) return@post

                    val newHasMiddleRow = hasMiddleRow(updatedProfile)
                    val holder = layoutManager.findViewByPosition(index)
                        ?.let { configurationListView.getChildViewHolder(it) } as? ConfigurationHolder
                    val previous = holder?.lastSelfHasMiddleRow
                    notifyItemChanged(index)
                    if (previous != null && previous != newHasMiddleRow) {
                        refreshSameRowNeighbours(index)
                    }
                }
            }

            override suspend fun onUpdated(data: List<TrafficData>) {
                try {
                    onMainDispatcher {
                        for (update in data) {
                            val cached = configurationList[update.id] ?: continue
                            if (cached.tx == update.tx && cached.rx == update.rx) continue

                            cached.tx = update.tx
                            cached.rx = update.rx
                            if (configurationListView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                                pendingTrafficUpdates.add(update.id)
                            } else {
                                updateVisibleTraffic(update.id)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profileId)
                if (index < 0) return

                configurationListView.post {
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                    refreshFromPosition(index - 1)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
                reloadProfiles()
            }

            fun reloadProfiles() {
                val generation = batchLoadGeneration.incrementAndGet()
                val groupId = proxyGroup.id
                val order = proxyGroup.order
                runOnDefaultDispatcher {
                    try {
                        var profiles = PendingProfileDeletes.loadGroup(groupId)
                        profiles = when (order) {
                            GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName() }
                            GroupOrder.BY_DELAY -> profiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                            else -> profiles
                        }
                        val map = profiles.associateBy { it.id }
                        val ids = profiles.map { it.id }
                        onMainDispatcher {
                            if (generation == batchLoadGeneration.get() && isAdded && proxyGroup.id == groupId &&
                                ::configurationListView.isInitialized && configurationListView.adapter === this@ConfigurationAdapter) {
                                presentBatchSnapshot(ids, map)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher { if (isAdded) snackbar(e.readableMessage).show() }
                    }
                }
            }

            // 兜底看门狗（20/20）：官方装载偶发偏慢（部分 70-80 节点分组）或竞态
            // 未落地时接管——800ms 复查适配器仍为空，则自带读库走 20/20 分片流式
            // 补齐，预防长时间/一直白屏。正常装载会先落地，看门狗自动作废。
            private var fallbackArmed = false

            fun armFallback() {
                // Single snapshot loader owns initial fill; no competing watchdog writer.
            }

            /**
             * 兜底分片填充（20/20）：由 armFallback 看门狗调用——官方装载偏慢
             * （部分 70-80 节点分组）或竞态未落地时接管——首屏先出 20 张，
             * 其余按 20 个/步官方 post 写法流式补齐。每步校验数据一致性，
             * 检测到并发写入时直接一次性全量刷新收尾。完成后数据常驻适配器。
             */
            private fun applyFirstFill(map: Map<Long, ProxyEntity>, ids: List<Long>, selectedProfileIndex: Int) {
                presentBatchSnapshot(ids, map)
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            var lastSelfHasMiddleRow: Boolean? = null
            var lastBoundTx = Long.MIN_VALUE
            var lastBoundRx = Long.MIN_VALUE
            private fun showShareMenu(anchor: View, proxyEntity: ProxyEntity) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                when {
                    !proxyEntity.haveStandardLink() -> {
                        popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                        popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                            R.id.action_standard_clipboard
                        )
                    }

                    !proxyEntity.haveLink() -> {
                        popup.menu.removeItem(R.id.action_group_qr)
                        popup.menu.removeItem(R.id.action_group_clipboard)
                    }
                }

                if (proxyEntity.nekoBean != null) {
                    popup.menu.removeItem(R.id.action_group_configuration)
                }

                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            private val card = view as MaterialCardView
            private val selectedIndicator: View = view.findViewById(R.id.selected_indicator)
            val editButton: ImageView = view.findViewById(R.id.edit)
            val doubleColumnMenuButton: ImageView = view.findViewById(R.id.double_column_menu)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            init {
                view.setOnClickListener {
                    val proxyEntity = entity
                    if (select) {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    } else {
                        selectProfile(proxyEntity)
                    }
                }
                profileStatus.setOnClickListener {
                    val proxyEntity = entity
                    if (proxyEntity.status == 3) {
                        alert(proxyEntity.error ?: "<?>").tryToShow()
                    }
                }
                profileStatus.isFocusable = false
                editButton.setOnClickListener {
                    val proxyEntity = entity
                    it.context.startActivity(
                        proxyEntity.settingIntent(
                            it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                        )
                    )
                }
                removeButton.setOnClickListener {
                    removeProfile(entity)
                }
                doubleColumnMenuButton.setOnClickListener {
                    showDoubleColumnMenu(it, entity)
                }
                shareLayout.setOnClickListener {
                    val proxyEntity = entity
                    if (!select && proxyEntity.type != ProxyEntity.TYPE_CHAIN) {
                        showShareMenu(it, proxyEntity)
                    }
                }
            }

            private fun selectProfile(proxyEntity: ProxyEntity) {
                val pf = parentFragment as? ConfigurationFragment ?: return
                runOnDefaultDispatcher {
                    var update: Boolean
                    var lastSelected: Long
                    profileAccess.withLock {
                        update = DataStore.selectedProxy != proxyEntity.id
                        lastSelected = DataStore.selectedProxy
                        DataStore.selectedProxy = proxyEntity.id
                        onMainDispatcher {
                            pf.updateSelectedProxySnapshot(proxyEntity.id)
                        }
                    }

                    if (update) {
                        ProfileManager.postUpdate(lastSelected, noTraffic = true)
                        if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                            SagerNet.reloadService()
                            reloadAccess.unlock()
                        }
                    } else if (SagerNet.isTv) {
                        if (DataStore.serviceState.started) {
                            SagerNet.stopService()
                        } else {
                            SagerNet.startService()
                        }
                    }
                }
            }

            private fun removeProfile(proxyEntity: ProxyEntity) {
                if (select) return
                val currentAdapter = adapter ?: return
                val index = currentAdapter.configurationIdList.indexOf(proxyEntity.id)
                if (index < 0) return
                val removeAction = {
                    currentAdapter.remove(index)
                    undoManager.remove(index to proxyEntity)
                }
                if (DataStore.confirmProfileDelete) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.delete_confirm_prompt)
                        .setPositiveButton(R.string.yes) { _, _ -> removeAction() }
                        .setNegativeButton(R.string.no, null)
                        .show()
                } else {
                    removeAction()
                }
            }

            private fun showDoubleColumnMenu(anchor: View, proxyEntity: ProxyEntity) {
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.double_column_item_menu, popup.menu)
                if (select) popup.menu.removeItem(R.id.action_delete)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit -> {
                            anchor.context.startActivity(
                                proxyEntity.settingIntent(
                                    anchor.context, proxyGroup.type == GroupType.SUBSCRIPTION
                                )
                            )
                            true
                        }
                        R.id.action_share -> {
                            showShareMenu(anchor, proxyEntity)
                            true
                        }
                        R.id.action_delete -> {
                            removeProfile(proxyEntity)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }

            private fun applySelected(selected: Boolean) {
                val ctx = card.context
                val surface = ctx.getColorAttr(R.attr.colorSurface)
                if (DataStore.profileCardStyle == 1) {
                    val primary = ctx.getColorAttr(R.attr.colorPrimary)
                    selectedIndicator.isVisible = false
                    card.cardElevation = 0f
                    card.strokeWidth = ctx.resources.getDimensionPixelSize(
                        if (selected) R.dimen.card_stroke_width_selected
                        else R.dimen.card_stroke_width
                    )
                    card.strokeColor =
                        if (selected) primary else ctx.getColour(R.color.card_stroke)
                    card.setCardBackgroundColor(
                        if (selected) {
                            ColorUtils.compositeColors(
                                ColorUtils.setAlphaComponent(primary, 26), surface
                            )
                        } else {
                            surface
                        }
                    )
                } else {
                    val primary = ctx.getColorAttr(R.attr.selectedColorPrimary)
                    selectedIndicator.isVisible = selected
                    card.strokeWidth = 0
                    card.cardElevation =
                        ctx.resources.getDimension(R.dimen.profile_card_elevation_classic)
                    card.setCardBackgroundColor(
                        if (selected) {
                            ColorUtils.compositeColors(
                                ColorUtils.setAlphaComponent(primary, 20), surface
                            )
                        } else {
                            surface
                        }
                    )
                }
            }

            fun bind(proxyEntity: ProxyEntity) {
                val pf = parentFragment as? ConfigurationFragment ?: return

                entity = proxyEntity
                val bean = proxyEntity.requireBean()

                profileName.text = bean.displayName()
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

                val rx = proxyEntity.rx
                val tx = proxyEntity.tx

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                }

                var address = if (pf.alwaysShowAddress && bean.name.isNotBlank()) {
                    bean.displayAddress()
                } else ""
                if (showTraffic && address.length >= 30) {
                    address = address.substring(0, 27) + "..."
                }

                profileAddress.text = address
                val trafficRowEmpty =
                    (!showTraffic || proxyEntity.status <= 0) && address.isBlank()
                (trafficText.parent as View).visibility = when {
                    !trafficRowEmpty -> View.VISIBLE
                    adapter?.neighbourHasMiddleRow(bindingAdapterPosition) == true -> View.INVISIBLE
                    else -> View.GONE
                }
                lastSelfHasMiddleRow = !trafficRowEmpty

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                } else if (proxyEntity.status == 1) {
                    profileStatus.text = getString(R.string.available, proxyEntity.ping)
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                } else {
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    if (proxyEntity.status == 2) {
                        profileStatus.text = proxyEntity.error
                    }
                }

                if (proxyEntity.status == 3) {
                    val err = proxyEntity.error ?: "<?>"
                    val msg = Protocols.genFriendlyMsg(err)
                    profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
                }

                val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
                val isDoubleColumn = layoutManager is FixedGridLayoutManager
                
                if (isDoubleColumn) {
                    editButton.isGone = true
                    shareLayout.isGone = true
                    removeButton.isGone = true
                    doubleColumnMenuButton.isVisible = true
                } else {
                    shareLayout.isGone = selectOrChain
                    editButton.isGone = select
                    removeButton.isGone = select
                    doubleColumnMenuButton.isGone = true
                }

                proxyEntity.nekoBean?.apply {
                    if (!isDoubleColumn) {
                        shareLayout.isGone = true
                    }
                }

                val selected = pf.isSelectedProfile(proxyEntity.id)
                val started =
                    selected && DataStore.serviceState.started && pf.isCurrentProfile(proxyEntity.id)
                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                applySelected(selected)

                if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                    shareLayer.setBackgroundColor(Color.TRANSPARENT)
                    shareButton.setImageResource(R.drawable.ic_social_share)
                    shareButton.setColorFilter(Color.GRAY)
                    shareButton.isVisible = true
                }

                lastBoundTx = tx
                lastBoundRx = rx

            }

            fun bindProfileState(proxyEntity: ProxyEntity) {
                if (!::entity.isInitialized || entity.id != proxyEntity.id) {
                    bind(proxyEntity)
                    return
                }
                val pf = parentFragment as? ConfigurationFragment ?: return
                val selected = pf.isSelectedProfile(proxyEntity.id)
                val started = selected && DataStore.serviceState.started &&
                        pf.isCurrentProfile(proxyEntity.id)
                editButton.isEnabled = !started
                removeButton.isEnabled = !started
                applySelected(selected)
            }

            fun bindTraffic(proxyEntity: ProxyEntity) {
                if (entity.id != proxyEntity.id) {
                    bind(proxyEntity)
                    return
                }

                val traffic = view.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(view.context, proxyEntity.tx),
                    Formatter.formatFileSize(view.context, proxyEntity.rx)
                )
                if (proxyEntity.status <= 0) {
                    if (profileStatus.text?.toString() != traffic) {
                        profileStatus.text = traffic
                    }
                } else if (trafficText.text?.toString() != traffic) {
                    trafficText.text = traffic
                }
                lastBoundTx = proxyEntity.tx
                lastBoundRx = proxyEntity.rx
            }

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName()!!
                    when (item.itemId) {
                        R.id.action_standard_qr -> showCode(entity.toStdLink())
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                        R.id.action_config_export_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                            .bufferedWriter()
                            .use {
                                it.write(DataStore.serverConfig)
                            }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    /**
     * 顶栏搜索框。展开时才由 TopBarController 创建。
     * 之前它绑在 action_search 菜单项上，还踩过「折叠态下 action_search
     * 其实是 ActionMenuItemView」的 ClassCastException；现在整排自绘，
     * 搜索框由 TopBarController 自己管理生命周期。
     */
    fun createSearchView(): SearchView = SearchView(requireContext()).apply {
        maxWidth = Int.MAX_VALUE
        setOnQueryTextListener(this@ConfigurationFragment)
        searchScope.attach(
            searchView = this,
            onScopeChanged = { scope ->
                onSearchQueryChanged(query?.toString().orEmpty())
            },
            onDeleteClicked = {
                deleteCurrentSearchResults()
            }
        )
    }

    /** 收起搜索框时清空过滤状态 */
    fun clearSearchQuery() {
        onSearchQueryChanged("")
    }

    fun showKeyboard(v: View) {
        val imm = requireContext().getSystemService(
            android.content.Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = requireContext().getSystemService(
            android.content.Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager
        val token = view?.windowToken ?: return
        imm.hideSoftInputFromWindow(token, 0)
    }

}
