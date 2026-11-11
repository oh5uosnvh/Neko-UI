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
import kotlinx.coroutines.ensureActive
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
import io.nekohasekai.sagernet.database.TagGroupStore
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
import io.nekohasekai.sagernet.widget.AnimatedPopupWindow
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.QuickLongPress
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
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

/** Full binds and live updates must both overwrite recycled traffic UI state. */
internal fun bindProfileTraffic(
    trafficText: TextView,
    statisticsEnabled: Boolean,
    twoColumn: Boolean,
    tx: Long,
    rx: Long,
) {
    val showTraffic = shouldShowProfileTraffic(statisticsEnabled, twoColumn, tx, rx)
    trafficText.text = if (showTraffic) trafficText.context.getString(
        R.string.traffic,
        Formatter.formatFileSize(trafficText.context, rx),
        Formatter.formatFileSize(trafficText.context, tx),
    ) else ""
    trafficText.isVisible = showTraffic
    trafficText.translationY = if (showTraffic) {
        2f * trafficText.resources.displayMetrics.density
    } else 0f
}

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
    /** Captured tag for this configuration view; null means all groups. */
    @Volatile
    private var scopedTagId: Long? = null
    private var scopeGeneration = AtomicLong()
    private var pendingFileTagId: Long? = null
    private val groupProfileExport = GroupProfileExport(this)
    private var groupTabsMediator: TabLayoutMediator? = null
    // TabLayout pools/recreates TabViews. Weak entries must not retain removed tabs.
    private val tabLongPressBindings =
        java.util.WeakHashMap<View, java.lang.ref.WeakReference<QuickLongPress>>()

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

    private val titleJumpSequence = SelectedJumpSequence()

    /** The ⊙ jump already completed the first leg of the title's two-step cycle. */
    internal fun armTitleJumpAfterSelected(profileId: Long, groupId: Long) {
        titleJumpSequence.returnToFirst(profileId, groupId, groupId)
    }

    /** Title click alternates selected card and this group's top, both in 500ms. */
    private fun scrollToSelectedProfile() {
        val fragment = getCurrentGroupFragment() ?: return
        val nodes = fragment.adapter ?: return
        val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
        val visibleGroupId = adapter.groupList.getOrNull(groupPager.currentItem)?.id ?: 0L
        val selectedHere = selectedProxy > 0L && selectedProxy in nodes.configurationIdList
        val goTop = if (selectedHere) {
            titleJumpSequence.returnToFirst(selectedProxy, visibleGroupId, visibleGroupId)
        } else {
            titleJumpSequence.reset()
            false
        }
        // 如果 ⊙ 的定位动画刚把卡片送进视口，标题点击只负责把这次
        // “已定位”状态交给二次标题循环，不要先 cancel 动画再重算一遍。
        // cancel 在这里会让 RecyclerView 从当前帧重新落位，表现为上下轻抖。
        // 已经在当前视口的选定卡片不重复启动滚动。尤其是 ⊙ 刚完成定位后，
        // 再点 Neko 不能从同一位置重新跑一遍动画造成上下微抖。
        if (!goTop && selectedProxy > 0L) {
            val lm = fragment.layoutManager as? LinearLayoutManager
            val index = nodes.configurationIdList.indexOf(selectedProxy)
            if (lm != null && index >= 0 && index in
                lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()) return
        }
        cancelSelectedCardJump()
        if (selectedProxy > 0L && !selectedHere) {
            runOnDefaultDispatcher {
                val groupId = SagerDatabase.proxyDao.getById(selectedProxy)?.groupId
                onMainDispatcher {
                    if (isAdded && viewLifecycleOwnerLiveData.value != null && groupId != null &&
                        (selectedItem?.id ?: DataStore.selectedProxy) == selectedProxy) {
                        titleJumpSequence.returnToFirst(selectedProxy, groupId, visibleGroupId)
                        requestSelectedCardJump(groupId, selectedProxy)
                    }
                }
            }
            return
        }
        val target = if (goTop) nodes.configurationIdList.firstOrNull()
            else selectedProxy.takeIf { it in nodes.configurationIdList } ?: nodes.configurationIdList.firstOrNull()
        if (target == null) return
        selectedCardScroller = SelectedProfileScroller(fragment.configurationListView).also {
            it.scrollTo(target) { nodes.configurationIdList }
        }
    }


    /** 🔍 那个可折叠菜单项，收起搜索框时要用它 collapseActionView() */

    // 030: 实时读取——旧的 by lazy 首访后缓存，设置里开关后必须重启才生效
    private var addressDisplaySnapshot = DataStore.alwaysShowAddress
    private var cardStyleSnapshot = DataStore.profileCardStyle
    val alwaysShowAddress get() = addressDisplaySnapshot
    private var lastAppliedAlwaysShowAddress = addressDisplaySnapshot
    private var lastAppliedTrafficStatistics = DataStore.profileTrafficStatistics
    @Volatile
    private var openingGroupSettings = false

    private fun updateDisplaySnapshots() {
        addressDisplaySnapshot = DataStore.alwaysShowAddress
        cardStyleSnapshot = DataStore.profileCardStyle
    }

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

    private fun currentScopeTag(): Long? = scopedTagId?.takeIf { it > 0L }

    /** The tag locked by this configuration view, if any. The picker reads this on open. */
    internal fun currentLockedTagId(): Long? = currentScopeTag()

    /** Toggle the picker lock without dismissing its popup or changing the selected group. */
    internal fun toggleTagLockFromPicker(tagId: Long): Boolean {
        if (tagId <= 0L || !isAdded) return false
        if (currentScopeTag() == tagId) {
            scopedTagId = null
            prepareConfigurationMenu(toolbar.menu)
            if (::adapter.isInitialized) adapter.reload(now = true)
        } else {
            val live = try { TagGroupStore.get(tagId) != null } catch (_: Exception) { false }
            if (!live) return false
            scopedTagId = tagId
            prepareConfigurationMenu(toolbar.menu)
            if (::adapter.isInitialized) adapter.reload(now = true)
        }
        return true
    }

    /** Entering is only accepted for a live tag; deleted tags always return to all groups. */
    internal fun enterTagScope(tagId: Long): Boolean {
        if (tagId <= 0L || !isAdded) return false
        val generation = scopeGeneration.incrementAndGet()
        runOnDefaultDispatcher {
            val live = try { TagGroupStore.get(tagId) != null } catch (_: Exception) { false }
            onMainDispatcher {
                if (generation != scopeGeneration.get() || !isAdded || view == null) return@onMainDispatcher
                if (!live) { exitTagScope(); return@onMainDispatcher }
                scopedTagId = tagId
                topBar.dismissGroupPicker(immediate = true)
                prepareConfigurationMenu(toolbar.menu)
                if (::adapter.isInitialized) adapter.reload(now = true)
            }
        }
        return true
    }

    internal fun exitTagScope() {
        scopeGeneration.incrementAndGet()
        if (scopedTagId == null) return
        scopedTagId = null
        topBar.dismissGroupPicker(immediate = true)
        if (view != null) prepareConfigurationMenu(toolbar.menu)
        if (::adapter.isInitialized) adapter.reload(now = true)
    }

    internal fun toggleTagScope(tagId: Long): Boolean {
        if (scopedTagId == tagId) { exitTagScope(); return true }
        return enterTagScope(tagId)
    }

    private fun scopedTagMembers(tagId: Long): Set<Long> = try {
        TagGroupStore.members(tagId)
    } catch (_: Exception) { emptySet() }

    /** Validate the initiating scope, never silently downgrade a deleted tag to all groups. IO only. */
    private fun requireImportTag(tagId: Long?) {
        if (tagId != null) requireNotNull(TagGroupStore.get(tagId)) { "Import tag no longer exists" }
    }

    private suspend fun resolveImportTarget(tagId: Long?): Long {
        if (tagId == null) return DataStore.selectedGroupForImport()
        requireImportTag(tagId)
        val candidate = GroupPickerTagScope.importTarget(
            SagerDatabase.groupDao.allGroups(), TagGroupStore.members(tagId), DataStore.selectedGroup)
        if (candidate != null) return candidate.id
        // Root insertion and membership use the existing atomic GroupManager API.
        return GroupManager.createGroup(ProxyGroup(name = "My group", type = GroupType.BASIC), tagId).id
    }

    /** Revalidate inside the same outer write transaction as the node append. */
    private fun requireImportTarget(tagId: Long?, groupId: Long) {
        requireImportTag(tagId)
        if (tagId != null) {
            check(groupId in TagGroupStore.members(tagId)) { "Import group left the captured tag" }
            check(SagerDatabase.groupDao.getById(groupId)?.type == GroupType.BASIC &&
                !io.nekohasekai.sagernet.database.PendingGroupDeletes.contains(groupId)) { "Import group is not writable" }
        }
    }

    private suspend fun importSubscriptionCaptured(link: String, tagId: Long?) {
        try {
            requireImportTag(tagId)
            (activity as? MainActivity)?.importSubscription(link.toUri(), tagId)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            onMainDispatcher { if (isAdded) snackbar(R.string.tag_member_failed).show() }
        }
    }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            val currentIdx = if (::groupPager.isInitialized) groupPager.currentItem else -1
            if (currentIdx < 0 || !::adapter.isInitialized) return null
            adapter.getGroupFragment(currentIdx)?.takeIf { it.isAdded && it.view != null }
                ?: adapter.groupList.getOrNull(currentIdx)?.id?.let {
                    childFragmentManager.findFragmentByTag("f" + it) as? GroupFragment
                }
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

    private data class SelectedCardJump(val groupId: Long, val profileId: Long)
    private var selectedCardJump: SelectedCardJump? = null
    private var selectedCardScroller: SelectedProfileScroller? = null

    internal fun cancelSelectedCardJump() {
        selectedCardJump = null
        selectedCardScroller?.close()
        selectedCardScroller = null
    }

    internal fun requestSelectedCardJump(groupId: Long, profileId: Long) {
        cancelSelectedCardJump()
        val position = adapter.groupList.indexOfFirst { it.id == groupId }
        if (position < 0) return
        selectedCardJump = SelectedCardJump(groupId, profileId)
        requestGroupPage(position)
        trySelectedCardJump()
    }

    private fun trySelectedCardJump() {
        val request = selectedCardJump ?: return
        if (pageTransition?.clicking == true || groupPager.scrollState != ViewPager2.SCROLL_STATE_IDLE) return
        if (adapter.groupList.getOrNull(groupPager.currentItem)?.id != request.groupId) return
        val index = groupPager.currentItem
        val tab = tabLayout.getTabAt(index)?.view
        val strip = tabLayout.getChildAt(0)
        if (tab != null && strip != null && tab.width > 0) {
            val targetX = ((tab.left + tab.right - tabLayout.width) / 2)
                .coerceIn(0, (strip.width - tabLayout.width).coerceAtLeast(0))
            tabLayout.smoothScrollTo(targetX, 0)
        }
        val child = getCurrentGroupFragment() ?: return
        val nodes = child.adapter ?: return
        if (child.view == null || !nodes.hasCurrentSearchResult() || request.profileId !in nodes.configurationIdList) return
        selectedCardJump = null
        selectedCardScroller = SelectedProfileScroller(child.configurationListView).also {
            it.scrollTo(request.profileId) { nodes.configurationIdList }
        }
    }

    private val pageRequests = GroupPageRequests()
    private val groupSelectionWriter = GroupSelectionWriter { id ->
        if (DataStore.configurationStore.getLong(Key.PROFILE_GROUP, -1L) != id) DataStore.selectedGroup = id
    }
    private var selectedGroupUi = 0L
    private var pendingGroupSelection = 0L
    private var pageTransition: GroupPageTransition? = null
    private var pageRequestPosted = false
    private var pageTabListener: TabLayout.OnTabSelectedListener? = null

    /** Called by the group-management page; survives a cold pager reload. */
    internal fun selectGroupWhenReady(groupId: Long) {
        pendingGroupSelection = groupId
        view?.post { applyPendingGroupSelection() }
    }

    private fun applyPendingGroupSelection() {
        val id = pendingGroupSelection
        if (id <= 0L || !::adapter.isInitialized || !::groupPager.isInitialized) return
        val position = adapter.groupList.indexOfFirst { it.id == id }
        if (position < 0) return
        pendingGroupSelection = 0L
        DataStore.stageSelectedGroup(id)
        requestGroupPage(position)
    }

    /** Only user clicks animate; restoration/order alignment keeps setCurrentItem(..., false). */
    fun requestGroupPage(position: Int) {
        // Group navigation from search results (including the ⊙ jump action)
        // leaves the search scope; do not restore a stale search toolbar when
        // returning to the configuration page.
        topBar.collapseSearch()
        val groupId = adapter.groupList.getOrNull(position)?.id ?: return
        pageTransition?.let { it.request(groupId); return }
        pageRequests.request(groupId)
        schedulePageRequest()
    }

    /** Cancel an outgoing click before adapter positions change; its centre ObjectAnimator
     * otherwise outlives the short viewport restore and can select a stale page index. */
    private fun resetGroupPageTransition() {
        pageTransition?.let { groupPager.unregisterOnPageChangeCallback(it); it.close() }
        pageTransition = GroupPageTransition(groupPager, tabLayout,
            ids = { adapter.groupList.map { group -> group.id } },
            onSettled = { position -> updateSelectedCallback.onPageSelected(position) },
            isReady = {
                val child = getCurrentGroupFragment()
                child?.view != null && child.adapter?.hasCurrentSearchResult() == true
            },
        ).also { groupPager.registerOnPageChangeCallback(it) }
    }

    private fun schedulePageRequest() {
        if (pageRequestPosted || viewLifecycleOwnerLiveData.value == null) return
        pageRequestPosted = true
        groupPager.postOnAnimation {
            pageRequestPosted = false
            if (viewLifecycleOwnerLiveData.value == null) return@postOnAnimation
            pageRequests.applyWhenIdle(groupPager, adapter.groupList.map { it.id }, isRestoringGroup)
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_IDLE) {
                schedulePageRequest()
                trySelectedCardJump()
            }
        }

        override fun onPageSelected(position: Int) {
            if (pageTransition?.clicking == true) return
            trySelectedCardJump()
            if (!isRestoringGroup) adapter.selectedGroupIndex = position
            if (!select && !isRestoringGroup && position in adapter.groupList.indices) {
                val newId = adapter.groupList[position].id
                if (selectedGroupUi != newId) {
                    selectedGroupUi = newId
                    DataStore.stageSelectedGroup(newId)
                    groupSelectionWriter.submit(newId)
                }
                if (searchQueryText.isNotBlank() || searchResultsAdapter != null) {
                    onSearchQueryChanged(searchQueryText)
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

    /** The visible page owns search results; other pages must never retain a global snapshot. */
    private var searchQueryText = ""
    private var searchResultsAdapter: GroupFragment.ConfigurationAdapter? = null

    private fun onSearchQueryChanged(query: String) {
        searchQueryText = query
        val current = getCurrentGroupFragment()?.adapter
        if (searchResultsAdapter !== current) {
            searchResultsAdapter?.filter("", global = false)
            searchResultsAdapter = current
        }
        // Old rows are still on screen while the new query loads. They are not deletable results.
        searchScope.updateDeleteVisibility(false)
        current?.filter(query, global = searchScope.current == SearchScopeController.Scope.GLOBAL)
    }

    private fun onSearchResultsChanged(source: GroupFragment.ConfigurationAdapter) {
        if (getCurrentGroupFragment()?.adapter !== source) return
        trySelectedCardJump()
        searchScope.updateDeleteVisibility(
            searchQueryText.isNotBlank() && source.hasCurrentSearchResult() &&
                source.configurationIdList.isNotEmpty(),
        )
    }

    /** 批量删除当前搜索匹配出的所有节点 */
    private fun deleteCurrentSearchResults() {
        val query = topBar.searchField?.query?.toString().orEmpty()
        if (query.isBlank()) return

        val a = getCurrentGroupFragment()?.adapter ?: return
        if (!a.hasCurrentSearchResult()) return
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
                if (!page.isUndoReady() || page.adapter !== a || !a.hasCurrentSearchResult() ||
                    topBar.searchField?.query?.toString() != query) return@setPositiveButton
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
        scopedTagId = savedInstanceState?.getLong("scopedTagId", 0L)?.takeIf { it > 0L }
        pendingFileTagId = savedInstanceState?.getLong("pendingFileTagId", 0L)?.takeIf { it > 0L }
        openingFilePicker = savedInstanceState?.getBoolean("openingFilePicker", false) ?: false
        groupProfileExport.restoreState(savedInstanceState)
        setHasOptionsMenu(true)
        startProfileStateActor()
        refreshProfileState()

        // FragmentManager restores this page and its child pager state itself.
        // A self detach/attach during onCreate duplicates inflation and listener setup.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("scopedTagId", scopedTagId ?: 0L)
        outState.putLong("pendingFileTagId", pendingFileTagId ?: 0L)
        outState.putBoolean("openingFilePicker", openingFilePicker)
        super.onSaveInstanceState(outState)
        groupProfileExport.saveState(outState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!select) {
            viewLifecycleOwner.lifecycleScope.launch {
                TagGroupStore.changes.collectLatest {
                    val id = currentScopeTag() ?: return@collectLatest
                    if (!isAdded || TagGroupStore.get(id) == null) exitTagScope()
                    else if (::adapter.isInitialized) adapter.reload(now = true)
                }
            }
        }

        if (!select) {
            // 菜单仍然 inflate 到 toolbar 上 —— checkOrderMenu / global_mode
            // 勾选都要 findItem()。但整条 toolbar 会被 TopBarController 设为
            // GONE：它一个 item 都不能显示，显示了就会和自绘那一排重叠，
            // 而且只要存在非 always 项，AppCompat 还会自动补一个 ⋮ 溢出按钮。
            // 📄+ / ⋮ 的弹出改由我们指定锚点（AppCompatPopupWindowHelper）。
            toolbar.inflateMenu(R.menu.add_profile_menu)
            prepareConfigurationMenu(toolbar.menu)
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
                    // Self-drawn overflow does not trigger onPrepareOptionsMenu.
                    prepareConfigurationMenu(toolbar.menu)
                    AppCompatPopupWindowHelper.show(
                        toolbar.menu.findItem(R.id.action_misc)?.subMenu, anchor,
                        onClick = { onMenuItemClick(it) },
                        onBeforeItem = { reserveLatencyTestRequest(it) },
                        onCancelled = { cancelLatencyTestReservation() },
                    ) { popupToolbarOverflow() }
                }
            })
        } else {
            topBar.attachSelection(toolbar, titleRes)
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
        groupPager.offscreenPageLimit = 1
        view.post { applyPendingGroupSelection() }

        groupTabsMediator = TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            val groupId = adapter.groupList.getOrNull(position)?.id
            io.nekohasekai.sagernet.widget.GroupTabPill.bindTab(
                tab, adapter.groupList.getOrNull(position)?.displayName(),
            )
            tabLongPressBindings.remove(tab.view)?.get()?.unbind()
            val binding = QuickLongPress(tab.view) { v ->
                // Resolve the stable ID at trigger time, not a captured position
                // or the selected tab; another group may have moved/been deleted.
                val group = adapter.groupList.firstOrNull { it.id == groupId }
                if (isAdded && viewLifecycleOwnerLiveData.value != null &&
                    tab.position >= 0 && group != null
                ) {
                    showTabGroupMenu(v, group)
                    true
                } else false
            }
            tab.view.setOnClickListener {
                if (!isRestoringGroup && isAdded) requestGroupPage(tab.position)
            }
            tabLongPressBindings[tab.view] = java.lang.ref.WeakReference(binding)
        }.also { it.attach() }
        // Keep Mediator's page/adapter observers; replace only its immediate click writer.
        tabLayout.clearOnTabSelectedListeners()
        pageTabListener = object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!isRestoringGroup && tab.position != groupPager.currentItem) requestGroupPage(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (!isRestoringGroup && tab.position != groupPager.currentItem) requestGroupPage(tab.position)
            }
        }.also { tabLayout.addOnTabSelectedListener(it) }
        resetGroupPageTransition()

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

    internal fun prepareConfigurationMenu(menu: Menu) {
        // Scope is now controlled by the picker header's Lock/Unlock button;
        // keeping a second "show all" command here caused two competing states.
        LatencyTestMenuPolicy.apply(menu, DataStore.runningTest || pendingLatencyRequest != null)
        menu.findItem(R.id.action_global_mode)?.isChecked = DataStore.globalMode
        menu.findItem(R.id.action_always_show_address)?.isChecked = DataStore.alwaysShowAddress
        val allGroups = SagerDatabase.groupDao.allGroups()
        menu.findItem(R.id.action_global_no_reload)?.isChecked =
            allGroups.isNotEmpty() && allGroups.all { it.isSelector }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        prepareConfigurationMenu(menu)
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

    override fun onDestroyView() {
        titleJumpSequence.reset()
        cancelSelectedCardJump()
        pageTransition?.let { groupPager.unregisterOnPageChangeCallback(it); it.close() }
        pageTransition = null
        pageRequests.clear()
        pageRequestPosted = false
        pageTabListener?.let { tabLayout.removeOnTabSelectedListener(it) }
        pageTabListener = null
        if (::groupPager.isInitialized) groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
        if (::adapter.isInitialized) adapter.tabMutation.cancel()
        groupTabsMediator?.detach()
        groupTabsMediator = null
        tabLongPressBindings.values.mapNotNull { it.get() }.forEach { it.unbind() }
        tabLongPressBindings.clear()
        searchResultsAdapter?.cancelSearch()
        searchResultsAdapter = null
        searchQueryText = ""
        val ownedLatencyTask = activeLatencyCancel != null || pendingLatencyRequest != null
        activeLatencyCancel?.invoke()
        latencyGeneration.incrementAndGet()
        activeLatencyGeneration = 0L
        activeLatencyCancel = null
        activeLatencyMinimize = null
        activeLatencyIds.clear()
        cardLatencySession.clear()
        if (ownedLatencyTask) DataStore.runningTest = false
        pendingLatencyRequest = null
        latencyOverlay?.release()
        latencyOverlay = null
        topBar.detach()
        searchScope.detach()
        DataStore.profileCacheStore.unregisterChangeListener(this)
        DataStore.configurationStore.unregisterChangeListener(displayPrefListener)
        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }
        super.onDestroyView()
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
        groupSelectionWriter.close()

        super.onDestroy()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 被抽屉切走（hide）时收起搜索，避免返回键拦截器在其它页面残留
        if (hidden) {
            // Navigation between Configuration/Groups/Settings is still inside
            // the foreground activity. Keep the test running and leave its
            // strip/notification state untouched; true process backgrounding
            // is handled by MainActivity's ProcessLifecycle observer.
            topBar.collapseSearch()
        } else if (::groupPager.isInitialized && ::tabLayout.isInitialized && ::adapter.isInitialized) {
            activeLatencyResume?.invoke()
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

            if (lastAppliedAlwaysShowAddress != DataStore.alwaysShowAddress) refreshAddressDisplayPreferences()
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

    @Volatile
    private var openingFilePicker = false

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            val capturedTagId = pendingFileTagId
            val importOwner = viewLifecycleOwnerLiveData.value
            val importAdapter = if (::adapter.isInitialized) adapter else null
            pendingFileTagId = null
            openingFilePicker = false
            if (file != null) runOnDefaultDispatcher {
                try {
                    requireImportTag(capturedTagId)
                    val fileName =
                        requireContext().contentResolver.query(file, null, null, null, null)
                            ?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                                    .let(cursor::getString)
                            }
                    val proxies = mutableListOf<AbstractBean>()
                    var streamedCount: Int? = null
                    var streamedTarget = 0L
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
                        requireContext().contentResolver.openInputStream(file)!!.use { input ->
                            val reader = input.bufferedReader()
                            reader.mark(64 * 1024)
                            val probe = CharArray(16 * 1024)
                            val length = reader.read(probe)
                            val prefix = if (length > 0) String(probe, 0, length) else ""
                            reader.reset()
                            if (StreamingProfileImport.isNodeListPrefix(prefix)) {
                                streamedTarget = resolveImportTarget(capturedTagId)
                                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                                streamedCount = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    SagerDatabase.instance.runInTransaction(java.util.concurrent.Callable {
                                        requireImportTarget(capturedTagId, streamedTarget)
                                        StreamingProfileImport.append(SagerDatabase.instance, streamedTarget, reader,
                                            "file", fileName.orEmpty()) { job?.ensureActive() }.count
                                    })
                                }
                            } else {
                                RawUpdater.parseRaw(reader.readText(), fileName.orEmpty())
                                    ?.let(proxies::addAll)
                            }
                        }
                    }
                    if (streamedCount != null) {
                        val count = streamedCount!!
                        if (count > 0) {
                            GroupManager.postUpdate(streamedTarget)
                            GroupManager.postReload(streamedTarget)
                            onMainDispatcher {
                                if (isAdded && importOwner != null && viewLifecycleOwnerLiveData.value === importOwner &&
                                    importAdapter != null && isCurrentGroupPagerAdapter(importAdapter) &&
                                    currentScopeTag() == capturedTagId) {
                                    DataStore.editingGroup = streamedTarget
                                    importAdapter.selectImportedGroup(streamedTarget)
                                    snackbar(resources.getQuantityString(R.plurals.added, count, count)).show()
                                }
                            }
                        } else onMainDispatcher {
                            if (isAdded) snackbar(getString(R.string.no_proxies_found_in_file)).show()
                        }
                    } else if (proxies.isEmpty()) onMainDispatcher {
                        if (isAdded) snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    } else import(proxies, "file", fileName.orEmpty(), capturedTagId)
                } catch (e: SubscriptionFoundException) {
                    importSubscriptionCaptured(e.link, capturedTagId)
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

    suspend fun import(proxies: List<AbstractBean>, source: String = "", fileName: String = "",
                       capturedTagId: Long? = currentScopeTag()) {
        val importOwner = onMainDispatcher { viewLifecycleOwnerLiveData.value }
        val importAdapter = onMainDispatcher { if (::adapter.isInitialized) adapter else null }
        val targetId = withContext(Dispatchers.IO) {
            val target = resolveImportTarget(capturedTagId)
            SagerDatabase.instance.runInTransaction {
                requireImportTarget(capturedTagId, target)
                ProfileBatchStore.append(SagerDatabase.instance, target, proxies, source, fileName)
            }
            target
        }
        GroupManager.postUpdate(targetId)
        GroupManager.postReload(targetId)
        onMainDispatcher {
            if (!isAdded || importOwner == null || viewLifecycleOwnerLiveData.value !== importOwner ||
                importAdapter == null || !isCurrentGroupPagerAdapter(importAdapter) ||
                currentScopeTag() != capturedTagId) return@onMainDispatcher
            DataStore.editingGroup = targetId
            importAdapter.selectImportedGroup(targetId)
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
                val capturedTagId = currentScopeTag()
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else runOnDefaultDispatcher {
                    try {
                        requireImportTag(capturedTagId)
                        val proxies = RawUpdater.parseRaw(text)
                        if (proxies.isNullOrEmpty()) {
                            onMainDispatcher {
                                snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                            }
                        } else {
                            import(proxies, capturedTagId = capturedTagId)
                        }
                    } catch (e: SubscriptionFoundException) {
                        if (e.link.startsWith("sn://")) {
                            importSubscriptionCaptured(e.link, capturedTagId)
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
                            val tagStillExists = capturedTagId == null || TagGroupStore.get(capturedTagId) != null
                            onMainDispatcher {
                                if (!tagStillExists) {
                                    if (isAdded) snackbar(R.string.tag_member_failed).show()
                                    return@onMainDispatcher
                                }
                                if (!isAdded) return@onMainDispatcher
                                startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                                    putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                    putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, subscriptionLink)
                                    capturedTagId?.let { putExtra(GroupSettingsActivity.EXTRA_TAG_ID, it) }
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
                if (openingFilePicker) return true
                pendingFileTagId = currentScopeTag()
                openingFilePicker = true
                startFilesForResult(importFile, "*/*")
            }

            R.id.action_new_group -> {
                if (openingGroupSettings) return true
                openingGroupSettings = true
                startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, 0L)
                    currentScopeTag()?.let { putExtra(GroupSettingsActivity.EXTRA_TAG_ID, it) }
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
                if (DataStore.runningTest || pendingLatencyRequest != null || latencyCleanupInProgress) return true
                val clearGroupId = DataStore.currentGroupId()
                latencyCleanupInProgress = true
                runOnDefaultDispatcher {
                    try {
                        if (DataStore.runningTest) return@runOnDefaultDispatcher
                        val profiles = PendingProfileDeletes.loadGroup(clearGroupId)
                        val toClear = profiles.filter { it.status != 0 }.onEach {
                            it.status = 0; it.ping = 0; it.error = null
                        }
                        if (toClear.isNotEmpty()) ProfileManager.updateProfile(toClear)
                        onMainDispatcher {
                            if (DataStore.currentGroupId() == clearGroupId) {
                                getCurrentGroupFragment()?.adapter?.clearTestResults()
                            }
                        }
                    } finally {
                        latencyCleanupInProgress = false
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                if (DataStore.runningTest || pendingLatencyRequest != null || duplicateCleanupInProgress || latencyCleanupInProgress) return true
                duplicateCleanupInProgress = true
                val groupId = DataStore.currentGroupId()
                runOnDefaultDispatcher {
                    try {
                        val nodes = PendingProfileDeletes.loadGroup(groupId).filter { it.status != 0 && it.status != 1 }
                        onMainDispatcher {
                            if (!isAdded || nodes.isEmpty() || DataStore.runningTest) duplicateCleanupInProgress = false
                            else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage("您确认要删除这些重复配置吗？\n一共 ${nodes.size} 个重复配置。\n\n" + nodes.take(50).joinToString("\n") { node ->
                                    val name = node.displayName().orEmpty().replace("\n", " ")
                                    if (name.length > 42) name.take(41) + "…" else name
                                } + if (nodes.size > 50) "\n……" else "")
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    if (DataStore.runningTest || pendingLatencyRequest != null) {
                                        duplicateCleanupInProgress = false
                                        return@setPositiveButton
                                    }
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

            R.id.action_connection_tcp_ping, R.id.action_connection_url_test -> {
                // Parent rows only navigate; FlMenu renders their submenu.
                return true
            }

            R.id.action_tcp_ping_all, R.id.action_tcp_ping_failed, R.id.action_tcp_ping_untested,
            R.id.action_url_test_all, R.id.action_url_test_failed, R.id.action_url_test_untested -> {
                startReservedLatencyTest(item)
            }

            R.id.action_always_show_address -> {
                // Toggle the stored value, not a possibly stale popup clone's checkbox.
                DataStore.alwaysShowAddress = !DataStore.alwaysShowAddress
                item.isChecked = DataStore.alwaysShowAddress
                refreshAddressDisplayPreferences()
                return true
            }

            R.id.action_global_no_reload -> {
                // Batch the group switches; every enabled group contributes to one
                // compiled runtime selector. Changing membership applies a rebuild once.
                val groups = SagerDatabase.groupDao.allGroups()
                val enable = groups.isNotEmpty() && !groups.all { it.isSelector }
                runOnDefaultDispatcher {
                    try {
                        groups.forEach { group ->
                            if (group.isSelector != enable) {
                                group.isSelector = enable
                                SagerDatabase.groupDao.updateGroup(group)
                                GroupManager.postUpdate(group)
                            }
                        }
                        if (DataStore.serviceState.started) SagerNet.reloadService()
                        onMainDispatcher {
                            if (isAdded) snackbar(if (enable) "已为全部分组启用 selector，正在自动重连" else "已为全部分组关闭 selector，正在自动重连").show()
                        }
                    } catch (error: Exception) {
                        Logs.w(error)
                        onMainDispatcher { if (isAdded) snackbar(error.readableMessage).show() }
                    }
                }
                item.isChecked = enable
                return true
            }

            R.id.action_global_mode -> {
                item.isChecked = !item.isChecked
                DataStore.globalMode = item.isChecked
                // Room writes synchronously; the committed value is visible to :bg.
                // Hot snapshot ACK / explicit reconnect fallback is owned by the service.
                io.nekohasekai.sagernet.database.RuntimeRouteUpdater.request(
                    io.nekohasekai.sagernet.database.RuntimeRouteUpdater.Reason.GLOBAL_MODE,
                )
                return true
            }
        }
        // The popup invokes this same handler after its animated dismissal.
        // Consume every known action here; returning false lets AppCompat
        // dispatch the toolbar item a second time (notably file/new-group).
        return true
    }

    private var latencyOverlay: LatencyTestOverlay? = null
    private var pendingLatencyRequest: LatencyTestRequest? = null
    @Volatile private var latencyCleanupInProgress = false
    private val latencyGeneration = AtomicLong(0L)

    private fun latencyOverlay(): LatencyTestOverlay? {
        val root = (view as? ViewGroup) ?: return null
        return latencyOverlay ?: LatencyTestOverlay(root) { cancelActiveLatencyTest() }.also { latencyOverlay = it }
    }

    private fun reserveLatencyTestRequest(item: MenuItem): Boolean {
        if (LatencyTestMenuPolicy.isCleanup(item.itemId) &&
            (DataStore.runningTest || pendingLatencyRequest != null)) return false
        val request = when (item.itemId) {
            R.id.action_tcp_ping_all -> LatencyTestRequest(DataStore.currentGroupId(), LatencyTestMethod.TCP, LatencyTestScope.ALL)
            R.id.action_tcp_ping_failed -> LatencyTestRequest(DataStore.currentGroupId(), LatencyTestMethod.TCP, LatencyTestScope.FAILED)
            R.id.action_tcp_ping_untested -> LatencyTestRequest(DataStore.currentGroupId(), LatencyTestMethod.TCP, LatencyTestScope.UNTESTED)
            R.id.action_url_test_all -> LatencyTestRequest(DataStore.currentGroupId(), LatencyTestMethod.URL, LatencyTestScope.ALL)
            R.id.action_url_test_failed -> LatencyTestRequest(DataStore.currentGroupId(), LatencyTestMethod.URL, LatencyTestScope.FAILED)
            R.id.action_url_test_untested -> LatencyTestRequest(DataStore.currentGroupId(), LatencyTestMethod.URL, LatencyTestScope.UNTESTED)
            else -> return true
        }
        if (DataStore.runningTest || pendingLatencyRequest != null || duplicateCleanupInProgress || latencyCleanupInProgress) return false
        DataStore.runningTest = true
        pendingLatencyRequest = request
        return true
    }

    private fun cancelLatencyTestReservation() {
        if (pendingLatencyRequest != null) {
            pendingLatencyRequest = null
            DataStore.runningTest = false
        }
    }

    private fun startReservedLatencyTest(item: MenuItem): Boolean {
        val request = pendingLatencyRequest ?: return false
        pendingLatencyRequest = null
        when (request.method) {
            LatencyTestMethod.TCP -> pingTest(false, request, true)
            LatencyTestMethod.URL -> urlTest(request, true)
        }
        return true
    }

    private fun cancelActiveLatencyTest() {
        activeLatencyCancel?.invoke()
    }

    private var activeLatencyCancel: (() -> Unit)? = null
    private var activeLatencyResume: (() -> Unit)? = null
    private var activeLatencyMinimize: (() -> Unit)? = null

    fun minimizeActiveLatencyTest() { activeLatencyMinimize?.invoke() }

    fun resumeActiveLatencyTest() { activeLatencyResume?.invoke() }

    inner class TestDialog(private val request: LatencyTestRequest, var proxyN: Int, private val generation: Long) {
        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit
        val dialogStatus = AtomicInteger(0) // 1 minimized, 2 interrupted
        var notification: ConnectionTestNotification? = null
        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        val targetIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        private val flushScheduled = AtomicBoolean(false)
        private val resultsLock = Any()
        val finishedN = AtomicInteger(0)
        val successN = AtomicInteger(0)
        val failedN = AtomicInteger(0)
        private val terminal = AtomicBoolean(false)
        private val flushLock = Mutex()
        private val renderScheduled = AtomicBoolean(false)
        fun ownsSession() = generation == activeLatencyGeneration && generation == latencyGeneration.get()

        fun scheduleFlush() {
            if (dialogStatus.get() == 2 || !flushScheduled.compareAndSet(false, true)) return
            runOnDefaultDispatcher {
                delay(250)
                flushScheduled.set(false)
                flushPending()
            }
        }

        suspend fun flushPending() {
            flushLock.withLock {
                val snapshot = synchronized(resultsLock) { ArrayList(results).also { results.clear() } }
                if (snapshot.isEmpty() || !ownsSession()) return@withLock
                // Merge only measurement fields onto the latest row. Never overwrite
                // a node name/bean edited while testing, or recreate a deleted row.
                val alive = snapshot.mapNotNull { measured ->
                    if (measured.id !in targetIds) null
                    else SagerDatabase.proxyDao.getById(measured.id)?.apply {
                        status = measured.status
                        ping = measured.ping
                        error = measured.error
                    }
                }
                if (alive.isNotEmpty() && ownsSession()) {
                    try { ProfileManager.updateProfile(alive) } catch (e: Exception) { Logs.w(e) }
                }
            }
        }

        fun update(profile: ProxyEntity) {
            synchronized(resultsLock) {
                if (dialogStatus.get() == 2 || !ownsSession() || terminal.get()) return
                results.add(profile)
                cardLatencySession.complete(profile, generation)
                if (profile.status == 1) successN.incrementAndGet() else failedN.incrementAndGet()
                finishedN.incrementAndGet()
            }
            scheduleFlush()
            // Merge bursts from the worker pool into a small local UI update.
            if (renderScheduled.compareAndSet(false, true)) runOnMainDispatcher {
                delay(32)
                renderScheduled.set(false)
                if (!ownsSession() || terminal.get() || dialogStatus.get() == 2) return@runOnMainDispatcher
                val progress = finishedN.get()
                if (dialogStatus.get() == 0) latencyOverlay()?.running(progress, proxyN, failedN.get())
                notification?.updateNotification(progress, proxyN, progress >= proxyN)
            }
        }

        fun finish(interrupted: Boolean) {
            synchronized(resultsLock) {
                if (!terminal.compareAndSet(false, true)) return
            }
            runOnMainDispatcher {
                if (!ownsSession()) return@runOnMainDispatcher
                latencyOverlay()?.completed(successN.get(), failedN.get(), interrupted)
                notification?.updateNotification(finishedN.get(), proxyN, true)
                refreshVisibleCardLatency()
                delay(150L)
                if (ownsSession() && !DataStore.runningTest) {
                    cardLatencySession.clear()
                    refreshVisibleCardLatency()
                }
            }
        }
    }

    @Volatile private var activeLatencyGeneration = 0L

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean, requested: LatencyTestRequest? = null, reserved: Boolean = false) {
        val request = requested ?: LatencyTestRequest(DataStore.currentGroupId(),
            LatencyTestMethod.TCP, LatencyTestScope.ALL)
        if (!reserved && (DataStore.runningTest || pendingLatencyRequest != null || latencyCleanupInProgress || duplicateCleanupInProgress)) return
        if (!reserved) DataStore.runningTest = true
        val generation = latencyGeneration.incrementAndGet()
        activeLatencyGeneration = generation
        val testJobs = mutableListOf<Job>()
        val groupId = request.groupId
        val test = TestDialog(request, 0, generation)
        // Lazy start guarantees the UI cancel/minimize hooks exist before even
        // an empty/fast target set can finish. Unexpected worker/DB failures are
        // logged and still run the completion cleanup; no uncaught main crash.
        val mainJob = kotlinx.coroutines.GlobalScope.launch(
            Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, error -> Logs.w(error) },
            start = kotlinx.coroutines.CoroutineStart.LAZY,
        ) {
            val all = SagerDatabase.proxyDao.getByGroup(groupId)
            val profilesList = LatencyTestSelector.select(all, request, activeLatencyIds) {
                if (icmpPing) it.requireBean().canICMPing() else it.requireBean().canTCPing()
            }
            if (profilesList.isEmpty()) {
                runOnMainDispatcher { if (test.ownsSession()) latencyOverlay()?.empty(emptyLatencyMessage(request.scope)) }
                return@launch
            }
            test.proxyN = profilesList.size
            test.targetIds.addAll(profilesList.map { it.id })
            val profiles = ConcurrentLinkedQueue(profilesList)
            activeLatencyIds.addAll(profilesList.map { it.id })
            profilesList.forEach { it.status = 0 }
            cardLatencySession.begin(profilesList.map { it.id }, generation)
            onMainDispatcher {
                if (test.ownsSession()) {
                    refreshVisibleCardLatency()
                    latencyOverlay()?.running(0, profilesList.size, 0)
                }
            }
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        if (!isActive) break
                        var address = profile.requireBean().serverAddress
                        if (!address.isIpAddress()) try {
                            SagerNet.underlyingNetwork!!.getAllByName(address).firstOrNull()?.hostAddress?.let { address = it }
                        } catch (_: UnknownHostException) {}
                        if (!isActive) break
                        if (!address.isIpAddress()) {
                            profile.status = 2; profile.error = app.getString(R.string.connection_test_domain_not_found); test.update(profile); continue
                        }
                        try {
                            if (icmpPing) { /* historical ICMP executor intentionally unchanged */ }
                            else {
                                val socket = SagerNet.underlyingNetwork?.socketFactory?.createSocket() ?: Socket()
                                try {
                                    socket.soTimeout = 3000; socket.bind(InetSocketAddress(0))
                                    val start = SystemClock.elapsedRealtime()
                                    socket.connect(InetSocketAddress(address, profile.requireBean().serverPort), 3000)
                                    if (!isActive) break
                                    profile.status = 1; profile.ping = (SystemClock.elapsedRealtime() - start).toInt(); test.update(profile)
                                } finally { socket.closeQuietly() }
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
                                !message.contains("failed:") -> profile.error = getString(R.string.connection_test_timeout_error)
                                message.contains("ECONNREFUSED") -> profile.error = getString(R.string.connection_test_refused)
                                message.contains("ENETUNREACH") -> profile.error = getString(R.string.connection_test_unreachable)
                                else -> { profile.status = 3; profile.error = message }
                            }
                            }
                            test.update(profile)
                        }
                    }
                })
            }
            testJobs.joinAll()
            test.finish(false)
            test.flushPending()
            if (test.ownsSession()) {
                activeLatencyIds.removeAll(profilesList.map { it.id }.toSet())
                DataStore.runningTest = false
                activeLatencyCancel = null
                activeLatencyMinimize = null
                GroupManager.postReload(groupId)
            }
        }
        activeLatencyCancel = {
            if (test.dialogStatus.getAndSet(2) != 2) {
            test.finish(true)
            runOnDefaultDispatcher {
                mainJob.cancelAndJoin()
                testJobs.forEach { it.cancelAndJoin() }
                test.flushPending()
                if (test.ownsSession()) {
                    activeLatencyIds.removeAll(test.targetIds)
                    activeLatencyCancel = null
                    GroupManager.postReload(groupId)
                }
                if (test.ownsSession()) DataStore.runningTest = false
            }
            }
        }
        activeLatencyMinimize = {
            if (test.ownsSession() && test.dialogStatus.get() != 2) {
                test.dialogStatus.set(1)
                context?.let { ctx ->
                    test.notification = ConnectionTestNotification(ctx,
                        "[${request.groupId}] ${getString(R.string.connection_test)}")
                }
            }
        }
        activeLatencyResume = {
            if (test.ownsSession() && test.dialogStatus.compareAndSet(1, 0)) {
                test.notification?.updateNotification(test.finishedN.get(), test.proxyN, true)
                latencyOverlay()?.running(test.finishedN.get(), test.proxyN, test.failedN.get())
            }
        }
        mainJob.invokeOnCompletion { failure ->
            if (failure != null && test.dialogStatus.get() != 2) test.finish(true)
            if (test.ownsSession() && test.dialogStatus.get() != 2 && DataStore.runningTest) {
                DataStore.runningTest = false
                activeLatencyIds.removeAll(test.targetIds)
                activeLatencyCancel = null
                activeLatencyResume = null
                activeLatencyMinimize = null
            }
        }
        mainJob.start()
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest(requested: LatencyTestRequest? = null, reserved: Boolean = false) {
        val request = requested ?: LatencyTestRequest(DataStore.currentGroupId(), LatencyTestMethod.URL, LatencyTestScope.ALL)
        if (!reserved && (DataStore.runningTest || pendingLatencyRequest != null || latencyCleanupInProgress || duplicateCleanupInProgress)) return
        if (!reserved) DataStore.runningTest = true
        val generation = latencyGeneration.incrementAndGet()
        activeLatencyGeneration = generation
        val testJobs = mutableListOf<Job>()
        var testPool: kotlinx.coroutines.ExecutorCoroutineDispatcher? = null
        val groupId = request.groupId
        val test = TestDialog(request, 0, generation)
        // Lazy start guarantees the UI cancel/minimize hooks exist before even
        // an empty/fast target set can finish. Unexpected worker/DB failures are
        // logged and still run the completion cleanup; no uncaught main crash.
        val mainJob = kotlinx.coroutines.GlobalScope.launch(
            Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, error -> Logs.w(error) },
            start = kotlinx.coroutines.CoroutineStart.LAZY,
        ) {
            val all = SagerDatabase.proxyDao.getByGroup(groupId)
            val profilesList = LatencyTestSelector.select(all, request, activeLatencyIds) { true }
            if (profilesList.isEmpty()) {
                runOnMainDispatcher { if (test.ownsSession()) latencyOverlay()?.empty(emptyLatencyMessage(request.scope)) }
                return@launch
            }
            testPool = Executors.newFixedThreadPool(DataStore.connectionTestConcurrent.coerceIn(1, 16)).asCoroutineDispatcher()
            test.proxyN = profilesList.size
            test.targetIds.addAll(profilesList.map { it.id })
            val profiles = ConcurrentLinkedQueue(profilesList)
            activeLatencyIds.addAll(profilesList.map { it.id }); profilesList.forEach { it.status = 0 }
            cardLatencySession.begin(profilesList.map { it.id }, generation)
            onMainDispatcher {
                if (test.ownsSession()) {
                    refreshVisibleCardLatency()
                    latencyOverlay()?.running(0, profilesList.size, 0)
                }
            }
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    val urlTest = UrlTest(testPool!!)
                    while (isActive) {
                        val profile = profiles.poll() ?: break
                        if (!isActive) break
                        try { profile.status = 1; profile.ping = urlTest.doTest(profile) }
                        catch (e: PluginManager.PluginNotFoundException) { profile.status = 2; profile.error = e.readableMessage }
                        catch (e: Exception) { profile.status = 3; profile.error = e.readableMessage }
                        test.update(profile)
                    }
                })
            }
            testJobs.joinAll(); testPool?.close(); test.finish(false); test.flushPending()
            if (test.ownsSession()) {
                activeLatencyIds.removeAll(profilesList.map { it.id }.toSet()); DataStore.runningTest = false; activeLatencyCancel = null; activeLatencyMinimize = null
                GroupManager.postReload(groupId)
            }
        }
        activeLatencyCancel = {
            if (test.dialogStatus.getAndSet(2) != 2) {
            test.finish(true)
            runCatching { testPool?.close() }
            runOnDefaultDispatcher {
                mainJob.cancelAndJoin()
                testJobs.forEach { it.cancelAndJoin() }
                test.flushPending()
                if (test.ownsSession()) {
                    activeLatencyIds.removeAll(test.targetIds); activeLatencyCancel = null; activeLatencyMinimize = null; DataStore.runningTest = false
                    GroupManager.postReload(groupId)
                }
            }
            }
        }
        activeLatencyMinimize = {
            if (test.ownsSession() && test.dialogStatus.get() != 2) {
                test.dialogStatus.set(1)
                context?.let { ctx ->
                    test.notification = ConnectionTestNotification(ctx,
                        "[${request.groupId}] ${getString(R.string.connection_test)}")
                }
            }
        }
        activeLatencyResume = {
            if (test.ownsSession() && test.dialogStatus.compareAndSet(1, 0)) {
                test.notification?.updateNotification(test.finishedN.get(), test.proxyN, true)
                latencyOverlay()?.running(test.finishedN.get(), test.proxyN, test.failedN.get())
            }
        }
        mainJob.invokeOnCompletion { failure ->
            runCatching { testPool?.close() }
            if (failure != null && test.dialogStatus.get() != 2) test.finish(true)
            if (test.ownsSession() && test.dialogStatus.get() != 2 && DataStore.runningTest) {
                DataStore.runningTest = false
                activeLatencyIds.removeAll(test.targetIds)
                activeLatencyCancel = null
                activeLatencyResume = null
                activeLatencyMinimize = null
            }
        }
        mainJob.start()
    }

    private val cardLatencySession = CardLatencySession()
    private fun refreshVisibleCardLatency() {
        if (!isAdded || !::adapter.isInitialized) return
        adapter.groupFragments.values.forEach { child ->
            val nodes = child.adapter ?: return@forEach
            if (child.view == null) return@forEach
            val list = child.configurationListView
            for (index in 0 until list.childCount) {
                val holder = list.getChildViewHolder(list.getChildAt(index)) as? GroupFragment.ConfigurationHolder ?: continue
                val position = holder.bindingAdapterPosition
                if (position >= 0) nodes.notifyItemChanged(position)
            }
        }
    }

    private val activeLatencyIds = ConcurrentHashMap.newKeySet<Long>()
    private fun emptyLatencyMessage(scope: LatencyTestScope): CharSequence = getString(when (scope) {
        LatencyTestScope.ALL -> R.string.latency_test_empty_all
        LatencyTestScope.FAILED -> R.string.latency_test_empty_failed
        LatencyTestScope.UNTESTED -> R.string.latency_test_empty_untested
    })

    private val groupDeleteAccess = Mutex()
    private val deletingGroupIds = mutableSetOf<Long>()

    /** Long-press delete: preserve the current tab, or select its left neighbour. */
    /**
     * 018: 长按分组标签菜单 —— 自绘 PopupWindow（替代系统 PopupMenu）。
     *
     * 规格：
     *   编辑
     *   跳转分组
     *   ┄┄┄┄┄┄
     *   导出到剪切板
     *   导出到文件
     *   ┄┄┄┄┄┄
     *   清空
     *   删除
     *
     * 宽度收缩到内容：面板宽 = 最宽行实测宽。
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

        var popupRef: AnimatedPopupWindow? = null

        fun closeGroupMenu(action: () -> Unit) {
            val popup = popupRef
            if (popup == null || !popup.isShowing) {
                popup?.dismiss()
                action()
                return
            }
            // Selection is delivered only after the animated window has detached.
            popup.dismissAnimated(action)
        }

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
                closeGroupMenu(action)
            }
            return item
        }

        // 操作 / 导出 / 清理之间的虚线（左右各留 16dp，不顶满）
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
                setColor(themeColor(R.attr.colorLayerHigh))
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
            menuItem(getString(R.string.action_export_clipboard)) {
                groupProfileExport.toClipboard(group.id)
            }
        }
        addRow {
            menuItem(getString(R.string.action_export_file)) {
                groupProfileExport.toFile(group.id, group.displayName())
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

        val popup = AnimatedPopupWindow(
            column,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
            anchor,
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        }
        popupRef = popup
        popup.setOnDismissListener {
            if (popupRef === popup) popupRef = null
        }
        popup.showAsDropDown(anchor, 0, dp(4))
        io.nekohasekai.sagernet.widget.PopupEntrance.animate(column, popup.isAboveAnchor, anchor)

        // 面板超出屏幕右缘时整体左移（-1 = 宽高不变）
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val overflow = loc[0] + maxW + dp(8) - resources.displayMetrics.widthPixels
        if (overflow > 0) popup.update(anchor, -overflow, dp(4), -1, -1)
    }

    private data class DeletedGroupSnapshot(
        val ids: List<Long>, val tabs: GroupTabMutation.Snapshot,
    )

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
                        // The visible page is authoritative; persisted selection may still
                        // be queued by GroupSelectionWriter. Long-press need not select 10.
                        DeletedGroupSnapshot(ids, adapter.tabMutation.capture())
                    } ?: return@withLock
                    if (group.id !in snapshot.ids || SagerDatabase.groupDao.getById(group.id) == null) return@withLock
                    val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
                    GroupManager.deleteGroup(group.id)
                    onMainDispatcher {
                        if (!isAdded || viewLifecycleOwnerLiveData.value !== owner) return@onMainDispatcher
                        snackbar(getString(R.string.group_deleted_undo, group.displayName())).apply {
                            setAction(R.string.undo) {
                                runOnDefaultDispatcher {
                                    restoreGroup(group, profiles, snapshot, owner)
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
        group: ProxyGroup, profiles: List<ProxyEntity>, snapshot: DeletedGroupSnapshot,
        owner: androidx.lifecycle.LifecycleOwner,
    ) {
        try {
            groupDeleteAccess.withLock {
                SagerDatabase.instance.runInTransaction {
                    SagerDatabase.groupDao.createGroup(group)
                    if (profiles.isNotEmpty()) SagerDatabase.proxyDao.insert(profiles)
                }
                onMainDispatcher {
                    // A Snackbar from the old view must never mutate a replacement pager.
                    if (!isAdded || viewLifecycleOwnerLiveData.value !== owner) return@onMainDispatcher
                    adapter.restoreGroupAt(group, snapshot.ids, snapshot.tabs)
                }
                GroupManager.postReload(group.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun refreshAddressDisplayPreferences() {
        runOnMainDispatcher {
            if (!isAdded) return@runOnMainDispatcher
            updateDisplaySnapshots()
            lastAppliedAlwaysShowAddress = addressDisplaySnapshot
            lastAppliedTrafficStatistics = DataStore.profileTrafficStatistics
            if (view != null && !select) {
                toolbar.menu.findItem(R.id.action_always_show_address)?.isChecked = lastAppliedAlwaysShowAddress
            }
            val cached = if (::adapter.isInitialized) adapter.groupFragments.values.toList() else emptyList()
            val restored = childFragmentManager.fragments.filterIsInstance<GroupFragment>()
            (cached + restored).distinct().forEach { it.refreshAddressDisplay() }
        }
    }

    override fun onResume() {
        super.onResume()
        openingGroupSettings = false
        updateDisplaySnapshots()
        if (lastAppliedAlwaysShowAddress != addressDisplaySnapshot ||
            lastAppliedTrafficStatistics != DataStore.profileTrafficStatistics
        ) refreshAddressDisplayPreferences()
    }

    private val displayPrefListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            if (key == Key.ALWAYS_SHOW_ADDRESS || key == Key.PROFILE_TRAFFIC_STATISTICS) {
                // Display follows the switch, never stale non-zero counters or the next traffic event.
                refreshAddressDisplayPreferences()
            }
            if (key == Key.PROFILE_CARD_STYLE) runOnMainDispatcher {
                updateDisplaySnapshots()
                refreshAllGroupFragmentsCardStyle()
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

            groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)

            runOnDefaultDispatcher {
                val requestedTag = currentScopeTag()
                if (requestedTag != null && TagGroupStore.get(requestedTag) == null) {
                    onMainDispatcher { if (generation == reloadGeneration.get()) exitTagScope() }
                    return@runOnDefaultDispatcher
                }
                val memberIds = requestedTag?.let { TagGroupStore.members(it) }
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups()
                    .filterNot { io.nekohasekai.sagernet.database.PendingGroupDeletes.contains(it.id) }
                    .filter { requestedTag == null || it.id in memberIds.orEmpty() })
                if (newGroupList.isEmpty() && requestedTag == null) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups().filterNot { io.nekohasekai.sagernet.database.PendingGroupDeletes.contains(it.id) })
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
                                            schedulePageRequest()
                                        }
                                    }
                                } else {
                                    isRestoringGroup = false
                                    schedulePageRequest()
                                }
                                val hideTab = groupList.size < 2
                                tabLayout.isGone = hideTab
                                toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                                groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                                applyPendingGroupSelection()
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
            onMainDispatcher {
                if (viewLifecycleOwnerLiveData.value == null || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@onMainDispatcher
                if (currentScopeTag() != null && group.id !in scopedTagMembers(currentScopeTag()!!)) return@onMainDispatcher
                if (groupList.any { it.id == group.id }) return@onMainDispatcher
                tabMutation.change(tabMutation.capture(group.id), centerSelected = true) {
                    val newIndex = groupList.size
                    groupList.add(group)
                    notifyItemInserted(newIndex)
                    if (groupList.any { !it.ungrouped }) tabLayout.visibility = View.VISIBLE
                }
            }
        }

        internal fun selectImportedGroup(groupId: Long) {
            if (groupList.none { it.id == groupId } || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return
            tabMutation.change(tabMutation.capture(groupId), centerSelected = true) {}
        }

        internal val tabMutation = GroupTabMutation(
            tabLayout, groupPager,
            ids = { groupList.map { it.id } },
            isAlive = { viewLifecycleOwnerLiveData.value != null && isCurrentGroupPagerAdapter(this@GroupPagerAdapter) },
            blockSelection = { blocked ->
                isRestoringGroup = blocked
                if (blocked) {
                    pageRequests.clear()
                    resetGroupPageTransition()
                }
            },
            selectId = { id, position ->
                selectedGroupIndex = position
                if (!select && selectedGroupUi != id) {
                    selectedGroupUi = id
                    DataStore.stageSelectedGroup(id)
                    groupSelectionWriter.submit(id)
                }
            },
            onFinished = { schedulePageRequest() },
        )

        override suspend fun groupRemoved(groupId: Long) {
            onMainDispatcher {
                if (viewLifecycleOwnerLiveData.value == null || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@onMainDispatcher
                val ids = groupList.map { it.id }
                val index = ids.indexOf(groupId)
                if (index < 0) return@onMainDispatcher
                val snapshot = tabMutation.capture()
                val keepId = GroupTabPolicy.afterRemoval(ids, groupId, snapshot.selectedId ?: 0L)
                tabMutation.change(snapshot.copy(selectedId = keepId)) {
                    groupList.removeAt(index)
                    groupFragments.remove(groupId)
                    notifyItemRemoved(index)
                }
            }
        }

        /** Undo keeps the original selection (not necessarily this group) and viewport. */
        internal fun restoreGroupAt(group: ProxyGroup, before: List<Long>, snapshot: GroupTabMutation.Snapshot) {
            if (viewLifecycleOwnerLiveData.value == null || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter) ||
                groupList.any { it.id == group.id }) return
            val at = GroupTabMutation.insertionIndex(before, group.id, groupList.map { it.id })
            tabMutation.change(snapshot) {
                groupList.add(at, group)
                notifyItemInserted(at)
                if (groupList.any { !it.ungrouped }) tabLayout.visibility = View.VISIBLE
            }
        }

        private var reorderGeneration = 0L
        private var pendingOrder: List<ProxyGroup>? = null
        private var orderPosted = false

        override suspend fun groupsReordered(groups: List<ProxyGroup>, changed: List<ProxyGroup>) {
            onMainDispatcher {
                if (viewLifecycleOwnerLiveData.value == null || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@onMainDispatcher
                pendingOrder = groups
                if (orderPosted) return@onMainDispatcher
                orderPosted = true
                tabLayout.postOnAnimation {
                    orderPosted = false
                    val snapshot = pendingOrder ?: return@postOnAnimation
                    pendingOrder = null
                    if (viewLifecycleOwnerLiveData.value == null || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@postOnAnimation
                    val existing = groupList.associateBy { it.id }
                    val ordered = snapshot.mapNotNull { existing[it.id] }
                    if (ordered.size != groupList.size) return@postOnAnimation
                    val orders = snapshot.associate { it.id to it.userOrder }
                    ordered.forEach { it.userOrder = orders.getValue(it.id) }
                    if (ordered.map { it.id } == groupList.map { it.id }) return@postOnAnimation
                    val currentId = groupList.getOrNull(groupPager.currentItem)?.id
                    val generation = ++reorderGeneration
                    isRestoringGroup = true
                    groupList = ArrayList(ordered)
                    // One stable-ID invalidation; never N notifyItemMoved/Mediator rebuilds.
                    notifyDataSetChanged()
                    val selected = groupList.indexOfFirst { it.id == currentId }
                    if (selected >= 0) {
                        selectedGroupIndex = selected
                        groupPager.setCurrentItem(selected, false)
                        tabLayout.setScrollPosition(selected, 0f, true)
                    }
                    tabLayout.post {
                        if (generation == reorderGeneration && isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) {
                            isRestoringGroup = false
                            schedulePageRequest()
                        }
                    }
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            onMainDispatcher {
                if (viewLifecycleOwnerLiveData.value == null || !isCurrentGroupPagerAdapter(this@GroupPagerAdapter)) return@onMainDispatcher
                val index = groupList.indexOfFirst { it.id == group.id }
                if (index < 0) return@onMainDispatcher
                val orderChanged = groupList[index].userOrder != group.userOrder
                groupList[index] = group
                io.nekohasekai.sagernet.widget.GroupTabPill.bindTab(tabLayout.getTabAt(index) ?: return@onMainDispatcher, group.displayName())
                if (orderChanged) groupsReordered(groupList.sortedWith(compareBy({ it.userOrder }, { it.id })), listOf(group))
            }
        }

        /** Group-only fallback after returning from management; no profile/DNS reload. */
        fun syncOrderFromDb() {
            runOnDefaultDispatcher {
                val fresh = SagerDatabase.groupDao.allGroups().filterNot { io.nekohasekai.sagernet.database.PendingGroupDeletes.contains(it.id) }.sortedWith(compareBy({ it.userOrder }, { it.id }))
                groupsReordered(fresh, emptyList())
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
            if (!::proxyGroup.isInitialized) {
                savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.let { proxyGroup = it }
            }
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

            // proxyGroup was restored before onViewCreated; never construct a second adapter/listener here.
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
                    // 与分组页/路由页统一：由偏好 dragScrollSpeed 驱动的恒速
                    // 边缘滚动（原为恒定 0.4×，语义见 GroupDragScroll 注释）。
                    return GroupDragScroll.pixels(recyclerView, viewSizeOutOfBounds)
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

        private var lastAddressPreference = DataStore.alwaysShowAddress
        private var lastTrafficPreference = DataStore.profileTrafficStatistics

        fun refreshAddressDisplay() {
            lastAddressPreference = DataStore.alwaysShowAddress
            lastTrafficPreference = DataStore.profileTrafficStatistics
            if (!::configurationListView.isInitialized || view == null) return
            val currentAdapter = adapter ?: return
            // A reload/DiffUtil of unchanged nodes is insufficient for display-only preferences.
            io.nekohasekai.sagernet.widget.ProfileDisplayRefresh.apply(configurationListView, currentAdapter)
        }

        override fun onResume() {
            super.onResume()
            if (lastAddressPreference != DataStore.alwaysShowAddress ||
                lastTrafficPreference != DataStore.profileTrafficStatistics
            ) refreshAddressDisplay()

            val currentAdapter = adapter
            if (currentAdapter != null && !currentAdapter.hasCurrentSearchResult() &&
                !currentAdapter.isProfileLoadInFlight()) currentAdapter.reloadProfiles()
            checkOrderMenu()
            // Resuming a neighbouring page must not steal focus and clamp its scroll anchor.
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
            // Default cross-fade change animations detach & rebuild the clicked view
            // between selection/status rebinds; rapid taps then land on a recycled
            // holder and the click is silently swallowed ("点了却选不中"). Text-only
            // rebinds (status/traffic/selection) need no animation.
            configurationListView.itemAnimator = null
            (parentFragment as? ConfigurationFragment)?.view
                ?.findViewById<io.nekohasekai.sagernet.widget.GroupListEdgeLayout>(
                    R.id.group_list_foreground,
                )?.bindList(configurationListView)
            setupLayoutManager()
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(6)
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
            }
            adapter?.reloadProfiles()
        }

        override fun onDestroyView() {
            adapter?.let {
                it.cancelSearch()
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }
            if (::itemTouchHelper.isInitialized) itemTouchHelper.attachToRecyclerView(null)
            if (::configurationListView.isInitialized) configurationListView.adapter = null
            adapter = null
            super.onDestroyView()
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

            private val batchLoadGeneration = ProfileSearchGeneration()
            private var searchLoading: Job? = null
            private var appliedSearchRequest: ProfileSearchGeneration.Request? = null
            private var groupSearchAnchor: Triple<Long?, Int, Int>? = null

            fun hasCurrentSearchResult(): Boolean =
                appliedSearchRequest?.let { batchLoadGeneration.accepts(it) } == true

            fun isProfileLoadInFlight(): Boolean = searchLoading?.isActive == true

            fun cancelSearch() {
                batchLoadGeneration.invalidate()
                searchLoading?.cancel()
                appliedSearchRequest = null
            }

            private fun presentBatchSnapshot(
                ids: List<Long>, nodes: Map<Long, ProxyEntity>,
                restoreAnchor: Triple<Long?, Int, Int>? = null,
            ) {
                val manager = configurationListView.layoutManager as? LinearLayoutManager
                val first = restoreAnchor?.second ?: manager?.findFirstVisibleItemPosition() ?: 0
                val anchorId = restoreAnchor?.first ?: configurationIdList.getOrNull(first.coerceAtLeast(0))
                val offset = restoreAnchor?.third ?: manager?.findViewByPosition(first)?.let {
                    manager.getDecoratedTop(it) - configurationListView.paddingTop
                } ?: 0
                val previousIds = configurationIdList.toList()
                val oldNodes = if (configurationIdList.size <= 1200) HashMap(configurationList) else emptyMap()
                val nextIds = ids.distinct().filterNot { PendingProfileDeletes.contains(it) }
                val nextNodes = nodes.filterKeys { !PendingProfileDeletes.contains(it) }
                // Large replacement: one stable-ID refresh avoids Myers diff O(N*D)
                // on 100k -> 20k; small updates retain precise animations.
                val diff = if (previousIds.isNotEmpty() && previousIds.size + nextIds.size <= 1200) DiffUtil.calculateDiff(object : DiffUtil.Callback() {
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
                    val target = if (position >= 0) position else first.coerceIn(0, configurationIdList.lastIndex)
                    if (manager != null) manager.scrollToPositionWithOffset(target, offset)
                    else configurationListView.scrollToPosition(target)
                }
                configurationListView.requestLayout()
                configurationListView.invalidate()
                (parentFragment as? ConfigurationFragment)?.onSearchResultsChanged(this)
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

            // Traffic is inline in the title row and never changes card height.
            private fun hasMiddleRow(p: ProxyEntity): Boolean = false

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

            override fun onViewDetachedFromWindow(holder: ConfigurationHolder) {
                holder.resetLatencyDisplay()
                super.onViewDetachedFromWindow(holder)
            }

            override fun onViewRecycled(holder: ConfigurationHolder) {
                holder.resetLatencyDisplay()
                bindProfileTraffic(holder.trafficText, false, false, 0L, 0L)
                holder.lastSelfHasMiddleRow = null
                holder.lastBoundTx = Long.MIN_VALUE
                holder.lastBoundRx = Long.MIN_VALUE
            }

            override fun onViewAttachedToWindow(holder: ConfigurationHolder) {
                super.onViewAttachedToWindow(holder)
                val profileId = holder.itemId
                val cached = configurationList[profileId] ?: return
                holder.bind(cached)
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
                // Traffic never changes row geometry; apply the same binding for
                // non-zero updates and resets to zero so hidden state is not stale.
                if (previousHasMiddleRow == null) {
                    holder.bind(cached)
                } else {
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
                val query = ProfileSearchQuery(name, proxyGroup.id, global)
                if (groupSearchAnchor == null && name.isNotBlank()) {
                    val manager = configurationListView.layoutManager as? LinearLayoutManager
                    val first = manager?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
                    val offset = manager?.findViewByPosition(first)?.let {
                        manager.getDecoratedTop(it) - configurationListView.paddingTop
                    } ?: 0
                    groupSearchAnchor = Triple(configurationIdList.getOrNull(first), first, offset)
                }
                // Scope is part of the request. Never suppress GROUP -> GLOBAL -> GROUP
                // just because the user kept the same text.
                val request = batchLoadGeneration.begin(query)
                searchLoading?.cancel()
                appliedSearchRequest = null
                (parentFragment as? ConfigurationFragment)?.onSearchResultsChanged(this)
                val order = proxyGroup.order
                searchLoading = runOnDefaultDispatcher {
                    try {
                        val source = if (query.isGlobal) SagerDatabase.proxyDao.getAll()
                            else PendingProfileDeletes.loadGroup(query.groupId)
                        var profiles = query.select(source, { it.groupId }, { node, lower ->
                            node.displayName().lowercase().contains(lower) ||
                                node.displayType().lowercase().contains(lower) ||
                                node.displayAddress().lowercase().contains(lower)
                        }, { PendingProfileDeletes.contains(it.id) })
                        if (!query.isGlobal) profiles = when (order) {
                            GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName() }
                            GroupOrder.BY_DELAY -> profiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                            else -> profiles
                        }
                        val map = profiles.associateBy { it.id }
                        val ids = profiles.map { it.id }
                        onMainDispatcher {
                            if (batchLoadGeneration.accepts(request) && isAdded && proxyGroup.id == query.groupId &&
                                ::configurationListView.isInitialized && configurationListView.adapter === this@ConfigurationAdapter) {
                                appliedSearchRequest = request
                                presentBatchSnapshot(ids, map, if (name.isBlank()) groupSearchAnchor else null)
                                if (name.isBlank()) groupSearchAnchor = null
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            if (batchLoadGeneration.accepts(request) && isAdded) snackbar(e.readableMessage).show()
                        }
                    }
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
                batchLoadGeneration.invalidate()
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
                    batchLoadGeneration.invalidate()
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

            override suspend fun groupsReordered(groups: List<ProxyGroup>, changed: List<ProxyGroup>) {
                // Group order is not node order or DNS. Only update metadata; never load profiles.
                groups.firstOrNull { it.id == proxyGroup.id }?.let { proxyGroup.userOrder = it.userOrder }
            }

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
                // GroupManager/onResume may call from Default; view state and ticket creation
                // are serialized with scope clicks on Main before any database work starts.
                runOnMainDispatcher {
                    if (!isAdded || !::configurationListView.isInitialized ||
                        configurationListView.adapter !== this@ConfigurationAdapter) return@runOnMainDispatcher
                    val host = parentFragment as? ConfigurationFragment
                    val active = host?.takeIf { it.searchQueryText.isNotBlank() &&
                        it.getCurrentGroupFragment()?.adapter === this@ConfigurationAdapter }
                    val query = batchLoadGeneration.currentQuery
                    if (active != null) {
                        filter(active.searchQueryText, active.searchScope.current == SearchScopeController.Scope.GLOBAL)
                    } else {
                        filter(query?.text.orEmpty(), query?.global == true)
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

                // Single-window engine: QR/clipboard/configuration ▶ become pages
                // inside the same animated surface instead of a second cascade popup.
                io.nekohasekai.sagernet.widget.FlMenu.show(anchor, popup.menu) { onMenuItemClick(it) }
            }

            val profileName: io.nekohasekai.sagernet.widget.ProfileNameTextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            // This is an EndpointTextView in layout_profile.xml. Keep the
            // specialized type visible at the binding boundary so endpoint
            // fitting cannot be accidentally replaced by TextView ellipsizing.
            val profileAddress: io.nekohasekai.sagernet.widget.EndpointTextView =
                view.findViewById(R.id.profile_address)
            val profilePending: View = view.findViewById(R.id.profile_pending)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)
            private val cardLatency = CardLatencyBinding(profileStatus, profilePending)
            internal fun resetLatencyDisplay() = cardLatency.reset()
            val addressRow: View = view.findViewById(R.id.address_row)

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
                // 延迟/状态区域（xxms、超时、不可用）不再消费点击：与卡片其余区域
                // 一样选中该节点——双列除右上角 ⋮ 外全区域、单列含延迟位置。
                // （原先 status==3 弹错误 snackbar 的行为移除，用户明确要求可点选。）
                profileStatus.isClickable = false
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
                    // Re-tapping the already-selected card must be a pure no-op on
                    // phones: even a same-value write fires the PROFILE_ID store
                    // listener, whose invalidateControlLatency() wipes the control
                    // bar's live speed/latency back to 0.00/-- ms.
                    if (proxyEntity.id == DataStore.selectedProxy && !SagerNet.isTv) {
                        return@runOnDefaultDispatcher
                    }
                    // An enabled-group member uses the global selector ACK path;
                    // incompatible runtime graphs are automatically reconnected.
                    if (SagerDatabase.groupDao.getById(proxyEntity.groupId)?.isSelector == true && DataStore.serviceState.started) {
                        profileAccess.withLock {
                            val previous = DataStore.selectedProxy
                            val receipt = try {
                                val service = (pf.activity as? MainActivity)?.connection?.service
                                    ?: error("后台服务未连接")
                                org.json.JSONObject(service.selectSelectorNode(proxyEntity.id))
                            } catch (e: Exception) {
                                onMainDispatcher {
                                    if (pf.isAdded && pf.view != null) pf.snackbar(e.readableMessage).show()
                                }
                                return@withLock
                            }
                            if (receipt.optBoolean("ok") &&
                                (receipt.optBoolean("ack") || receipt.optBoolean("accepted")) &&
                                receipt.optLong("profileId", -1L) == proxyEntity.id) {
                                onMainDispatcher {
                                    if (pf.isAdded && pf.view != null) {
                                        pf.refreshProfileState()
                                        if (receipt.optBoolean("reloading")) pf.snackbar(
                                            receipt.optString("message", "已自动重连（非免重载切换）")
                                        ).show()
                                    }
                                }
                                ProfileManager.postUpdate(previous, noTraffic = true)
                                ProfileManager.postUpdate(proxyEntity.id, noTraffic = true)
                            } else onMainDispatcher {
                                if (pf.isAdded && pf.view != null) pf.snackbar(
                                    receipt.optString("message", "免重载切换失败；请重新连接以载入最新配置")
                                ).show()
                            }
                        }
                        return@runOnDefaultDispatcher
                    }
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
                        if (DataStore.serviceState.started) {
                            // Rapid taps land while the tunnel is still Connecting
                            // (canStop == false); the old tryLock-or-skip path silently
                            // dropped the rebuild and left the VPN on the old node. Wait
                            // for a stable state, then reload only if this click is still
                            // the newest selection.
                            repeat(80) {
                                val state = DataStore.serviceState
                                when {
                                    !state.started -> return@runOnDefaultDispatcher
                                    state.canStop -> {
                                        reloadAccess.withLock {
                                            if (DataStore.serviceState.canStop &&
                                                DataStore.selectedProxy == proxyEntity.id
                                            ) {
                                                SagerNet.reloadService()
                                            }
                                        }
                                        return@runOnDefaultDispatcher
                                    }
                                    else -> delay(100)
                                }
                            }
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
                // 「分享 ▶」合并进同一窗口：其子项成为二级页，不再弹第二个菜单。
                val share = popup.menu.findItem(R.id.action_share)
                if (share != null) {
                    // The base item is a leaf. Adding a second sub-menu item with
                    // the same id leaves both rows in MenuBuilder (and the first
                    // one wins hit testing), which made the share entry appear
                    // twice and made its callback look like it was dropped.
                    val groupId = share.groupId
                    val itemId = share.itemId
                    val order = share.order
                    val title = share.title
                    popup.menu.removeItem(itemId)
                    val sub = popup.menu.addSubMenu(groupId, itemId, order, title)
                    popup.menuInflater.inflate(R.menu.profile_share_menu, sub)
                    when {
                        !proxyEntity.haveStandardLink() -> {
                            sub.findItem(R.id.action_group_qr)?.subMenu?.removeItem(R.id.action_standard_qr)
                            sub.findItem(R.id.action_group_clipboard)?.subMenu?.removeItem(
                                R.id.action_standard_clipboard
                            )
                        }
                        !proxyEntity.haveLink() -> {
                            sub.removeItem(R.id.action_group_qr)
                            sub.removeItem(R.id.action_group_clipboard)
                        }
                    }
                    if (proxyEntity.nekoBean != null) {
                        sub.removeItem(R.id.action_group_configuration)
                    }
                }
                io.nekohasekai.sagernet.widget.FlMenu.show(anchor, popup.menu) { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit -> {
                            anchor.context.startActivity(
                                proxyEntity.settingIntent(
                                    anchor.context, proxyGroup.type == GroupType.SUBSCRIPTION
                                )
                            )
                            true
                        }
                        R.id.action_share -> true // Handled through its submenu page.
                        R.id.action_delete -> {
                            removeProfile(proxyEntity)
                            true
                        }
                        else -> onMenuItemClick(menuItem)
                    }
                }
            }

            private fun applySelected(selected: Boolean) {
                val ctx = card.context
                val surface = ctx.getColorAttr(R.attr.colorSurface)
                if ((parentFragment as? ConfigurationFragment)?.cardStyleSnapshot == 1) {
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
                val twoColumn = layoutManager is FixedGridLayoutManager
                // 双列卡片框统一大小（图6/7 参照）：名称区固定预留两行，1 行名
                // （如「台湾 01」）与 2 行名（如「日本 Melbiko mas 08」）的卡片同高，
                // 协议/延迟行始终落在卡片最后一行，不再出现悬在中间的情况。
                // Keep a stable card row in both layouts; single-line names occupy the
                // second line as reserved space and long names ellipsize there.
                profileName.minLines = 2
                val fullProtocol = proxyEntity.displayType()
                profileType.text = io.nekohasekai.sagernet.widget.TwoColumnProtocolLabel.display(fullProtocol, twoColumn)
                profileType.contentDescription = fullProtocol
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

                val rx = proxyEntity.rx
                val tx = proxyEntity.tx
                val secondary = requireContext().getColorAttr(android.R.attr.textColorSecondary)

                // Traffic is inline, not structural; zero-byte profiles must remain hidden.
                // Single-column statistics occupy title line two, never an additional row.
                // The title always has two physical lines. Traffic is right-aligned
                // inside line two and must not reduce the name to one line.
                profileName.minLines = 2
                profileName.maxLines = 2
                profileName.ellipsize = android.text.TextUtils.TruncateAt.END
                profileName.twoColumnDisplay = twoColumn
                profileName.wrapLongNames = !twoColumn
                val nameContainer = view.findViewById<View>(R.id.title_text_container)
                // Keep the full two-line title height even when traffic is visible;
                // traffic overlays line two on the right instead of clipping the name.
                nameContainer.layoutParams = nameContainer.layoutParams.apply {
                    height = resources.getDimensionPixelSize(R.dimen.profile_card_title_row_height)
                }
                nameContainer.setPaddingRelative(
                    (12 * resources.displayMetrics.density).toInt(),
                    0,
                    resources.getDimensionPixelSize(if (twoColumn) R.dimen.profile_card_action_width else R.dimen.profile_card_actions_width),
                    0
                )
                profileName.gravity = android.view.Gravity.TOP
                val nameMetrics = profileName.paint.fontMetricsInt
                // Align first-line font box with the 48dp action icons, not the two-line block.
                val firstLineCenter = (nameMetrics.bottom - nameMetrics.top) / 2
                profileName.setPadding(0, ((24 * resources.displayMetrics.density).toInt() - firstLineCenter).coerceAtLeast(0), 0, 0)
                trafficText.setTextColor(secondary)
                bindProfileTraffic(trafficText, DataStore.profileTrafficStatistics, twoColumn, tx, rx)

                val showAddress = pf.alwaysShowAddress
                val address = if (showAddress) bean.displayAddress() else ""
                profileAddress.text = address
                addressRow.isVisible = showAddress
                card.layoutParams = card.layoutParams.apply {
                    height = resources.getDimensionPixelSize(
                        if (showAddress) R.dimen.profile_card_height
                        else R.dimen.profile_card_height_without_address
                    )
                }
                lastSelfHasMiddleRow = false

                // Preserve pre16's text/colour/error semantics. Only LIVE targeted
                // measurements own pending; ordinary rebinds are immediately opaque.
                val entry = pf.cardLatencySession.get(proxyEntity.id)
                    ?.takeIf { DataStore.runningTest || it.result != null }
                val measured = entry?.result ?: proxyEntity
                profileStatus.text = ""
                profileStatus.setTextColor(secondary)
                when (measured.status) {
                    1 -> {
                        profileStatus.text = getString(R.string.available, measured.ping)
                        profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                    }
                    2 -> {
                        profileStatus.text = measured.error ?: getString(R.string.unavailable)
                        profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    }
                    3 -> {
                        val err = measured.error ?: "<?>"
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    }
                }
                cardLatency.bind(entry, entry?.result != null)

                val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
                val isDoubleColumn = layoutManager is FixedGridLayoutManager
                // Same row contract in one/two-column layouts: protocol is the
                // flexible side, status is wrap-content and never ellipsized.
                (profileType.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.width = 0
                    it.weight = 1f
                    profileType.layoutParams = it
                }
                (profileStatus.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    it.weight = 0f
                    profileStatus.layoutParams = it
                }
                if (isDoubleColumn) {
                    // Two-column cards expose exactly one overflow action.
                    doubleColumnMenuButton.isGone = select
                    editButton.isGone = true
                    shareLayout.isGone = true
                    removeButton.isGone = true
                } else {
                    // Single-column cards retain the separate edit/share/delete actions.
                    doubleColumnMenuButton.isGone = true
                    shareLayout.isGone = selectOrChain
                    editButton.isGone = select
                    removeButton.isGone = select
                }

                proxyEntity.nekoBean?.apply {
                    if (!isDoubleColumn) {
                        shareLayout.isGone = true
                    }
                }

                val selected = pf.isSelectedProfile(proxyEntity.id)
                val started =
                    selected && pf.serviceStartedSnapshot && pf.isCurrentProfile(proxyEntity.id)
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

                bindProfileTraffic(trafficText, DataStore.profileTrafficStatistics,
                    layoutManager is FixedGridLayoutManager, proxyEntity.tx, proxyEntity.rx)
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
