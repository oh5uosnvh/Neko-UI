package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.animation.Animator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MessageStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding
    private lateinit var navigation: CompactNavigationMenu
    private val pageSlide = PageSlideTransition {
        clearNavigationContent()
        syncMainControls()
        (currentMainFragment as? GroupFragment)?.onGroupPageReady()
        currentMainFragment?.view?.postInvalidateOnAnimation()
    }
    private var currentNavId = R.id.nav_configuration
    private var pendingGroupNavigation: Job? = null
    private var currentMainFragment: ToolbarFragment? = null
    private val controlLatencyGate = ControlLatencyGate()
    private var controlLatencyJob: Job? = null
    private lateinit var controlOverlay: ConfigurationControlOverlay
    private var startupProbe: ConnectionProbeStatus? = null
    private var controlErrorDetail: String? = null
    private var lastErrorDialog = ""
    private var latestToggleIntent = false
    private val toggleStartRequest = ToggleStartRequest()
    private var vpnPermissionRequestInFlight = false

    /** Process background, not fragment navigation, owns latency-test minimization. */
    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            (currentMainFragment as? ConfigurationFragment)?.minimizeActiveLatencyTest()
            fragmentCache.values.filterIsInstance<ConfigurationFragment>()
                .filter { it !== currentMainFragment }
                .forEach { it.minimizeActiveLatencyTest() }
        }

        override fun onStart(owner: LifecycleOwner) {
            (currentMainFragment as? ConfigurationFragment)?.resumeActiveLatencyTest()
            fragmentCache.values.filterIsInstance<ConfigurationFragment>()
                .filter { it !== currentMainFragment }
                .forEach { it.resumeActiveLatencyTest() }
        }
    }

    // Only pages explicitly visited by the user are retained. Cold navigation waits for the
    // requested view's real first draw instead of inflating unrelated pages during startup.
    private val fragmentCache = HashMap<Int, ToolbarFragment>()
    private var navigationGeneration = 0L
    private var navigationCommitPending = false
    private var pendingPageNavigation: Runnable? = null
    private var coldNavigationFragment: ToolbarFragment? = null
    private var navigationContent: NavigationPageContent? = null

    private fun clearNavigationContent() {
        navigationContent?.dispose()
        navigationContent = null
        coldNavigationFragment = null
    }

    companion object {
        /** 参与实例缓存（重建时按 tag 找回）的页面 */
        private val CACHED_NAV_IDS = intArrayOf(
            R.id.nav_configuration, R.id.nav_group, R.id.nav_route,
            R.id.nav_settings, R.id.nav_tools, R.id.nav_logcat, R.id.nav_about,
        )
        private const val NAV_TAG_PREFIX = "main_nav:"
        private const val STATE_NAV_ID = "main_nav_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
        MessageStore.setCurrentActivity(this)
        // The UI starts unbound: do not restore a stale Connected tray before the service replies.
        DataStore.serviceState = BaseService.State.Idle

        binding = LayoutMainBinding.inflate(layoutInflater)
        controlOverlay = ConfigurationControlOverlay(binding.fragmentHolder, binding.controlBar)
        navigation = CompactNavigationMenu(this, binding.root) { onNavigationItemSelected(it) }
        // 页面缓存复用后层次里会同时存在多个页面的 AppBarLayout，
        // 每个页面视图创建时即时补一次状态栏内边距，避免顶栏顶进状态栏。
        supportFragmentManager.registerFragmentLifecycleCallbacks(navInsetCallbacks, true)
        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            restoreFragments(savedInstanceState)
        }
        onBackPressedDispatcher.addCallback {
            if (navigation.isShowing) {
                navigation.dismiss()
            } else if (currentMainFragment is ConfigurationFragment) {
                cancelPendingPageNavigation()
                pageSlide.cancel()
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration, forward = false)
            }
        }

        // Handle the latest UI intent immediately. Do not gate this callback on
        // Connecting/Stoppng: a fast OFF/ON/OFF sequence must reach the service
        // in the same order as the user's taps.
        binding.controlBar.onToggleIntent = { desiredConnected ->
            latestToggleIntent = desiredConnected
            if (desiredConnected) toggleStartRequest.request(
                io.nekohasekai.sagernet.bg.ServiceStartPolicy.fence.snapshot()
            ) else toggleStartRequest.cancel()
            dispatchLatestToggleIntent()
        }
        binding.controlBar.onToggle = {
            // Kept for embedders/tests that use the legacy callback directly.
            if (DataStore.serviceState.canStop) {
                latestToggleIntent = false
                toggleStartRequest.cancel()
                SagerNet.stopService()
            } else {
                latestToggleIntent = true
                toggleStartRequest.request(io.nekohasekai.sagernet.bg.ServiceStartPolicy.fence.snapshot())
                dispatchLatestToggleIntent()
            }
        }
        binding.controlBar.onTest = { testControlLatency() }
        binding.controlBar.onError = { showControlErrorSnackbar() }

        setContentView(binding.root)
        installControlBarInsets()
        if (currentMainFragment == null) {
            currentMainFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        }
        syncMainControls()
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        // MOD-24: 不再提示预览版信息（本构建为 MOD 版本，非官方预览版）

        if (false && isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        MessageStore.setCurrentActivity(this)

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val restoredFragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        if (restoredFragment != null) {
            currentMainFragment = restoredFragment
            syncMainControls()
            renderConnectionProbe()
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
            navigation.refresh()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        // 魔改协议（x365 等）冷启动首测可能失败：延迟 700ms 重试一次
        return try {
            connection.service!!.urlTest()
        } catch (first: Exception) {
            Logs.w("urlTest cold-start retry: ${first.message}")
            Thread.sleep(700)
            if (!DataStore.serviceState.connected || connection.service == null) {
                throw first
            }
            connection.service!!.urlTest()
        }
    }

    suspend fun importSubscription(uri: Uri, targetTagId: Long? = null) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            if (targetTagId == null) displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        try { finishImportSubscription(group, targetTagId) }
                        catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            onMainDispatcher { snackbar(R.string.tag_member_failed).show() }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup, tagId: Long? = null) {
        val created = GroupManager.createGroup(subscription, tagId)
        DataStore.selectedGroup = created.id
        DataStore.editingGroup = created.id
        GroupUpdater.startUpdate(created, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ ->
                launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/")
            }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (!item.isVisible || !item.isEnabled) return false
        if (item.itemId == currentNavId && currentMainFragment === fragmentCache[item.itemId]) {
            cancelPendingPageNavigation()
            navigation.dismiss()
            return true
        }
        return displayFragmentWithId(item.itemId)
    }

    /** Reuse cached pages; delay only the requested commit, never unrelated UI construction. */
    fun displayFragment(fragment: ToolbarFragment, forward: Boolean = true) {
        if (supportFragmentManager.isStateSaved || isFinishing || isDestroyed) return
        navigation.cancel()
        cancelPendingPageNavigation()
        if (currentMainFragment === fragment) return
        pendingGroupNavigation?.cancel()
        pendingGroupNavigation = null
        (currentMainFragment as? GroupFragment)?.cancelGroupJump()
        pageSlide.cancel()
        val generation = navigationGeneration
        if (currentMainFragment == null || !binding.root.isAttachedToWindow) {
            commitPageNavigation(fragment, forward, generation)
            return
        }
        // Popup dismissal runs in an animation callback. Yield a drawn frame before cold
        // inflation, and coalesce rapid selections without flushing FragmentManager globally.
        val commit = object : Runnable {
            override fun run() {
                if (generation != navigationGeneration) return
                if (!canNavigate()) { cancelPendingPageNavigation(); return }
                if (navigationCommitPending) {
                    binding.root.postOnAnimation(this)
                    return
                }
                pendingPageNavigation = null
                commitPageNavigation(fragment, forward, generation)
            }
        }
        pendingPageNavigation = commit
        binding.root.postOnAnimation {
            if (generation == navigationGeneration) binding.root.post(commit)
        }
    }

    private fun canNavigate() = !isFinishing && !isDestroyed &&
        !supportFragmentManager.isStateSaved &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun cancelPendingPageNavigation() {
        navigationGeneration++
        clearNavigationContent()
        pendingPageNavigation?.let { if (::binding.isInitialized) binding.root.removeCallbacks(it) }
        pendingPageNavigation = null
    }

    /** No commitNow/executePendingTransactions: nested pages own their initialization. */
    @SuppressLint("CommitTransaction")
    private fun commitPageNavigation(fragment: ToolbarFragment, forward: Boolean, generation: Long) {
        if (generation != navigationGeneration || supportFragmentManager.isStateSaved ||
            isFinishing || isDestroyed) return
        val old = currentMainFragment
        if (old === fragment) return
        // Capture before a traffic removal destroys Fragment.view; the animator still owns it.
        val outgoingView = old?.view
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        if (old != null && old.isAdded && binding.root.isAttachedToWindow && canNavigate()) {
            pageSlide.prepare(binding.fragmentHolder,
                isValid = { generation == navigationGeneration && canNavigate() },
                isReady = { !navigationCommitPending && currentMainFragment === fragment &&
                    (navigationContent?.isReady() != false) &&
                    NavigationFrameGate.isPageReady(fragment.view) },
                accepts = { it === outgoingView || it === fragment.view })
            if (fragment.view == null) coldNavigationFragment = fragment
            val animation = if (forward) R.animator.page_slide_forward else R.animator.page_slide_backward
            transaction.setCustomAnimations(animation, animation, R.animator.page_slide_backward, R.animator.page_slide_backward)
        }
        if (old != null && old !== fragment && old.isAdded) {
            if (fragmentCache.values.any { it === old }) {
                transaction.setMaxLifecycle(old, Lifecycle.State.STARTED)
                if (!old.isHidden) transaction.hide(old)
            } else {
                transaction.remove(old) // Traffic WebView intentionally remains uncached.
            }
        }
        for (cached in fragmentCache.values) {
            if (cached !== fragment && cached !== old && cached.isAdded && !cached.isHidden) {
                transaction.setMaxLifecycle(cached, Lifecycle.State.STARTED)
                transaction.hide(cached)
            }
        }
        if (fragment.isDetached) {
            transaction.attach(fragment).show(fragment)
        } else if (fragment.isAdded) {
            transaction.show(fragment)
        } else {
            transaction.add(R.id.fragment_holder, fragment, tagOf(fragment))
        }
        transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
        currentMainFragment = fragment
        currentNavId = fragmentCache.entries.firstOrNull { it.value === fragment }?.key
            ?: if (fragment is TagGroupFragment || fragment is GroupFragment) R.id.nav_group else R.id.nav_traffic
        navigation.setSelected(currentNavId)
        navigationCommitPending = true
        transaction.runOnCommit {
            navigationCommitPending = false
            syncMainControls()
        }
        transaction.commit()
        syncMainControls()
    }

    internal fun createPageAnimator(view: View, enter: Boolean, nextAnim: Int): Animator? =
        pageSlide.create(view, enter, nextAnim)?.also { syncMainControls() }

    /** 页面视图创建时补齐顶栏的状态栏内边距（多页面共存场景） */
    private val navInsetCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager,
            f: Fragment,
            v: View,
            savedInstanceState: Bundle?,
        ) {
            applyStatusBarInset(v)
            if (f === coldNavigationFragment) {
                navigationContent?.dispose()
                navigationContent = NavigationPageContent(v, currentNavId)
            }
            if (f is ConfigurationFragment && !f.select) {
                v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = syncMainControls()
                    override fun onViewDetachedFromWindow(view: View) = syncMainControls()
                })
            }
            if (f is ConfigurationFragment && !f.select && f === currentMainFragment) {
                syncMainControls()
            } else if (f.parentFragment === currentMainFragment && currentMainFragment is ConfigurationFragment) {
                // A newly created/cached pager page also needs scroll-range padding.
                syncMainControls()
            }
        }

        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f === currentMainFragment || f.parentFragment === currentMainFragment) syncMainControls()
        }

        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === currentMainFragment) syncMainControls()
        }
    }

    private fun tagOf(fragment: ToolbarFragment): String? = when {
        fragment is TagGroupFragment -> TagGroupNavigation.FRAGMENT_TAG
        fragment is GroupFragment && (fragment.arguments?.getLong("tag_id", 0L) ?: 0L) > 0L ->
            "tag_members_${fragment.arguments?.getLong("tag_id")}_${fragment.arguments?.getBoolean("tag_edit") == true}"
        else -> fragmentCache.entries.firstOrNull { it.value === fragment }?.let { NAV_TAG_PREFIX + it.key }
    }

    private fun createFragment(@IdRes id: Int): ToolbarFragment? = when (id) {
        R.id.nav_configuration -> ConfigurationFragment()
        R.id.nav_group -> GroupFragment()
        R.id.nav_route -> RouteFragment()
        R.id.nav_settings -> SettingsFragment()
        R.id.nav_traffic -> WebviewFragment()
        R.id.nav_tools -> ToolsFragment()
        R.id.nav_logcat -> LogcatFragment()
        R.id.nav_about -> AboutFragment()
        else -> null
    }

    private fun obtainFragment(@IdRes id: Int): ToolbarFragment =
        fragmentCache.getOrPut(id) { createFragment(id) ?: error("unknown nav id: $id") }

    /** Open configuration page and make the requested group the visible pager page. */
    fun openConfigurationGroup(groupId: Long) {
        if (groupId <= 0L || !displayFragmentWithId(R.id.nav_configuration)) return
        DataStore.stageSelectedGroup(groupId)
        (fragmentCache[R.id.nav_configuration] as? ConfigurationFragment)
            ?.selectGroupWhenReady(groupId)
    }

    /** Load off-main before exposing a cold group page: its very first frame contains card zero. */
    fun openGroupAt(groupId: Long) {
        if (groupId <= 0L || supportFragmentManager.isStateSaved) return
        pendingGroupNavigation?.cancel()
        val fragment = obtainFragment(R.id.nav_group) as GroupFragment
        fragment.cancelGroupJump()
        pendingGroupNavigation = lifecycleScope.launch {
            fragment.prepareGroupJump(groupId)
            pendingGroupNavigation = null
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || supportFragmentManager.isStateSaved) {
                fragment.cancelGroupJump()
                return@launch
            }
            displayFragment(fragment)
            fragment.beginPreparedGroupJump()
        }
    }

    /** 进程/配置变更重建后，从 tag 恢复缓存的页面实例 */
    private fun restoreFragments(savedInstanceState: Bundle) {
        for (id in CACHED_NAV_IDS) {
            (supportFragmentManager.findFragmentByTag(NAV_TAG_PREFIX + id) as? ToolbarFragment)
                ?.let { fragmentCache[id] = it }
        }
        val currentTag = savedInstanceState.getString("main_tag_page")
        val tagPage = currentTag?.let { supportFragmentManager.findFragmentByTag(it) as? ToolbarFragment }
        TagGroupNavigation.restoreState(this, savedInstanceState)
        // Retain a restored tag page; its roots remain separate from the standard nav cache.
        val kept = fragmentCache.values.toSet() + listOfNotNull(tagPage)
        for (f in supportFragmentManager.fragments.toList()) {
            if (f is ToolbarFragment && kept.none { it === f }) {
                supportFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .remove(f)
                    .commitAllowingStateLoss()
            }
        }
        val lastId = savedInstanceState.getInt(STATE_NAV_ID, R.id.nav_configuration)
        val restored = tagPage ?: fragmentCache[lastId]
        if (restored != null) {
            currentMainFragment = restored
            currentNavId = lastId
            navigation.setSelected(currentNavId)
        } else if (lastId == R.id.nav_traffic) {
            // 之前停在仪表盘（不进缓存）：重新打开一次
            displayFragmentWithId(R.id.nav_traffic)
        } else {
            displayFragmentWithId(R.id.nav_configuration)
        }
    }

    private fun syncMainControls() {
        if (!::binding.isInitialized || !::controlOverlay.isInitialized) return
        val configuration = currentMainFragment as? ConfigurationFragment
        val root = configuration?.view
        val visible = ConfigurationControlOverlay.shouldShow(
            configuration != null, configuration?.select == true, configuration?.isAdded == true,
            configuration?.isHidden == true, root?.isShown == true,
            pageSlide.isRunning || root?.translationX != 0f || root?.translationY != 0f,
            root?.isAttachedToWindow == true,
        )
        // Navigation is not a service acknowledgement: it must not release a pending VPN request.
        val wasVisible = binding.controlBar.visibility == View.VISIBLE
        controlOverlay.show(root, visible)
        if (visible && !wasVisible) renderConnectionProbe()
    }

    private fun installControlBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.coordinator) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            controlOverlay.applyInsets(safe.left, safe.right, maxOf(safe.bottom, keyboard))
            syncMainControls()
            insets
        }
        ViewCompat.requestApplyInsets(binding.coordinator)
    }

    private fun invalidateControlLatency() {
        controlLatencyGate.invalidate()
        controlLatencyJob?.cancel()
        controlLatencyJob = null
        if (::binding.isInitialized) binding.controlBar.clearMeasurements()
    }

    private fun testControlLatency() {
        if (binding.controlBar.visibility != View.VISIBLE ||
            !DataStore.serviceState.connected || connection.service == null) {
            binding.controlBar.showLatency(-1)
            return
        }
        val request = controlLatencyGate.begin(DataStore.selectedProxy, DataStore.currentProfile) ?: return
        binding.controlBar.showTesting()
        controlErrorDetail = null
        controlLatencyJob = lifecycleScope.launch {
            try {
                val elapsed = withContext(Dispatchers.IO) { urlTest() }
                if (controlLatencyGate.finish(request, DataStore.serviceState.connected,
                        DataStore.selectedProxy, DataStore.currentProfile)) {
                    startupProbe = startupProbe?.copy(elapsed = elapsed, phase = "connected", error = "")
                    binding.controlBar.showLatency(elapsed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (controlLatencyGate.finish(request, DataStore.serviceState.connected,
                        DataStore.selectedProxy, DataStore.currentProfile)) {
                    showControlFailure(error.readableMessage, true)
                }
            }
        }
    }

    override fun connectionProbe(snapshot: String) {
        val probe = ConnectionProbeStatus.parse(snapshot) ?: return
        if (probe.profileId != DataStore.selectedProxy) return
        val previous = startupProbe
        if (previous != null && previous.profileId == probe.profileId && previous.generation > probe.generation) return
        startupProbe = probe
        renderConnectionProbe()
    }

    private fun renderConnectionProbe() {
        if (!::binding.isInitialized || binding.controlBar.visibility != View.VISIBLE) return
        val probe = startupProbe ?: return
        if (probe.profileId != DataStore.selectedProxy || controlLatencyGate.isRunning) return
        when (probe.phase) {
            "connecting" -> if (DataStore.serviceState == BaseService.State.Connecting) binding.controlBar.showConnectionTesting()
                else if (DataStore.serviceState.connected) binding.controlBar.showTesting()
            "connected" -> if (DataStore.serviceState.connected && probe.elapsed >= 0) {
                controlErrorDetail = null
                binding.controlBar.showLatency(probe.elapsed)
            }
            "failed" -> showControlFailure(probe.error, true)
        }
    }

    private fun showControlFailure(detail: String, automaticSnackbar: Boolean) {
        val error = detail.ifBlank { getString(R.string.control_bar_unavailable) }
        controlErrorDetail = error
        val summary = getString(when (LatencyFailureLabel.kind(error)) {
            LatencyFailureLabel.Kind.TIMEOUT -> R.string.connection_test_timeout_error
            LatencyFailureLabel.Kind.RESET -> R.string.control_bar_connection_reset
            LatencyFailureLabel.Kind.UNAVAILABLE -> R.string.control_bar_unavailable
        })
        binding.controlBar.showFailure(summary)
        if (automaticSnackbar && error != lastErrorDialog && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lastErrorDialog = error
            showControlErrorSnackbar()
        }
    }

    private fun showControlErrorSnackbar() {
        val detail = controlErrorDetail ?: return
        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        snackbar(getString(R.string.connection_test_error, detail)).show()
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    fun displayFragmentWithId(@IdRes id: Int, forward: Boolean = true): Boolean {
        if (supportFragmentManager.isStateSaved || isFinishing || isDestroyed) return false
        navigation.cancel()
        pendingGroupNavigation?.cancel()
        pendingGroupNavigation = null
        if (id == currentNavId && currentMainFragment === fragmentCache[id] && currentMainFragment != null) {
            cancelPendingPageNavigation()
            return true
        }
        if (id == R.id.nav_faq) {
            cancelPendingPageNavigation()
            pageSlide.cancel()
            launchCustomTab("https://matsuridayo.github.io/")
            return false
        }
        val fragment = if (id == R.id.nav_traffic) {
            WebviewFragment()
        } else {
            fragmentCache[id] ?: (createFragment(id)?.also { fragmentCache[id] = it }
                ?: return false)
        }
        displayFragment(fragment, forward)
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
    ) {
        DataStore.serviceState = state
        val noProfile = state == BaseService.State.Stopped && msg == getString(R.string.profile_empty)
        if (noProfile) {
            // This start was rejected, not a fresh ON tap queued during cleanup.
            latestToggleIntent = false
            toggleStartRequest.cancel()
            vpnPermissionRequestInFlight = false
        }
        val currentEpoch = io.nekohasekai.sagernet.bg.ServiceStartPolicy.fence.snapshot()
        if (state == BaseService.State.Connecting || state == BaseService.State.Connected) {
            toggleStartRequest.acknowledgeStarted()
        }
        if (state == BaseService.State.Stopping || state == BaseService.State.Stopped) {
            // Notification Stop changes the epoch. A previous successful ON tap
            // is not permission to reopen the VPN after an external stop.
            latestToggleIntent = toggleStartRequest.pending(currentEpoch)
        }
        if (state == BaseService.State.Stopped && latestToggleIntent) {
            window.decorView.post { dispatchLatestToggleIntent() }
        }
        if (state == BaseService.State.Connecting) {
            startupProbe = null
            controlErrorDetail = null
            lastErrorDialog = ""
        }
        if (!state.connected) invalidateControlLatency()
        binding.controlBar.changeState(state) // Only an actual service acknowledgement unlocks toggle.
        if (noProfile) binding.controlBar.reset() // Clear optimistic ON and settle the stopped power button.
        refreshConfigurationProfileState()
        syncMainControls()
        renderConnectionProbe()
        if (!msg.isNullOrBlank()) showControlFailure(msg, true)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.controlBar.isShown) {
                anchorView = binding.controlBar
                setBackgroundTint(resources.getColor(R.color.control_bar_surface, theme))
                setTextColor(resources.getColor(R.color.control_bar_text, theme))
                setActionTextColor(resources.getColor(R.color.control_bar_text, theme))
            }
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) {
        val state = try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
        changeState(state)
        runCatching { service.connectionProbe }.getOrNull()?.let { connectionProbe(it) }
    }

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        changeState(BaseService.State.Idle)
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private fun dispatchLatestToggleIntent() {
        if (!::binding.isInitialized) return
        val state = DataStore.serviceState
        if (latestToggleIntent) {
            if (!toggleStartRequest.pending(io.nekohasekai.sagernet.bg.ServiceStartPolicy.fence.snapshot())) {
                latestToggleIntent = false
                return
            }
            // Wait for a previous stop to reach Stopped. Starting a new VPN
            // while the old runner is still stopping loses the user's ON tap.
            if (state == BaseService.State.Connecting ||
                state == BaseService.State.Stopping ||
                state == BaseService.State.Connected ||
                vpnPermissionRequestInFlight) return
            vpnPermissionRequestInFlight = true
            runCatching { connect.launch(null) }.onFailure {
                vpnPermissionRequestInFlight = false
                Logs.w(it)
                binding.controlBar.changeState(DataStore.serviceState)
            }
        } else if (state.canStop || state == BaseService.State.Connecting ||
            state == BaseService.State.Stopping) {
            SagerNet.stopService()
        }
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) { denied ->
        vpnPermissionRequestInFlight = false
        if (denied) {
            latestToggleIntent = false
            toggleStartRequest.cancel()
            binding.controlBar.changeState(DataStore.serviceState)
            syncMainControls() // Authoritative permission cancellation re-arms the capsule.
            snackbar(R.string.vpn_permission_denied).show()
        } else if (!latestToggleIntent) {
            // The permission sheet may finish after the user has already tapped
            // OFF. Do not resurrect a VPN for a stale START result.
            SagerNet.stopService()
        }
        // Successful requests stay visually immediate; service broadcasts remain authoritative.
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        if (binding.controlBar.visibility == View.VISIBLE) {
            binding.controlBar.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
        }
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROFILE_ID, Key.PROFILE_CURRENT -> {
                startupProbe = null
                controlErrorDetail = null
                lastErrorDialog = ""
                invalidateControlLatency()
            }
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    private fun cancelNavigationWork() {
        cancelPendingPageNavigation()
        if (::navigation.isInitialized) navigation.cancel()
        pendingGroupNavigation?.cancel()
        pendingGroupNavigation = null
        fragmentCache.values.filterIsInstance<GroupFragment>().forEach { it.cancelGroupJump() }
        pageSlide.cancel()
    }

    override fun onStop() {
        cancelNavigationWork()
        invalidateControlLatency()
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        cancelNavigationWork()
        invalidateControlLatency()
        if (::binding.isInitialized) {
            binding.controlBar.onToggle = null
            binding.controlBar.onToggleIntent = null
            binding.controlBar.onTest = null
            binding.controlBar.onError = null
        }
        super.onDestroy()
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(navInsetCallbacks)
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        cancelNavigationWork()
        outState.putInt(STATE_NAV_ID, currentNavId)
        TagGroupNavigation.saveState(this, outState)
        currentMainFragment?.let { fragment ->
            if (fragment is TagGroupFragment || (fragment is GroupFragment &&
                    (fragment.arguments?.getLong("tag_id", 0L) ?: 0L) > 0L)) {
                outState.putString("main_tag_page", tagOf(fragment))
            }
        }
        super.onSaveInstanceState(outState)
    }

    /** Kept for the configuration callback; it no longer opens or contains a drawer. */
    fun openDrawer() = openNavigationMenu()

    fun openNavigationMenu(anchor: View? = null) {
        if (supportFragmentManager.isStateSaved || isFinishing || isDestroyed) return
        cancelPendingPageNavigation()
        pageSlide.cancel()
        pendingGroupNavigation?.cancel()
        pendingGroupNavigation = null
        (currentMainFragment as? GroupFragment)?.cancelGroupJump()
        val toolbar = currentMainFragment?.view?.findViewById<View>(R.id.toolbar)
        navigation.show(anchor ?: toolbar?.takeIf { it.isShown } ?: currentMainFragment?.view?.findViewById(R.id.appbar) ?: binding.root)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && navigation.isShowing) {
            navigation.dismiss()
            return true
        }
        if (super.onKeyDown(keyCode, event)) return true
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            openNavigationMenu()
            return true
        }
        if (navigation.isShowing) return false
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
