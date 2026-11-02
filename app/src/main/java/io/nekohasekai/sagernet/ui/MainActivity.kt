package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
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
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
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
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView
    private var currentMainFragment: ToolbarFragment? = null

    // ---- 侧边栏切换优化 ----
    // 官方实现每次点击都是 new Fragment + replace()，事务恰好在抽屉关闭动画
    // 进行时执行：销毁旧页整棵视图树 + 新页 inflation/初始化，主线程被冻结，
    // 于是「换页必卡、重点当前页不卡（走 isChecked 提前返回）」。现在：
    // 页面实例缓存复用（hide/show），从未创建过的页面推迟到抽屉关闭后再提交。
    /** nav id -> 已创建的 Fragment 实例（同一页面只 inflate 一次） */
    private val fragmentCache = HashMap<Int, ToolbarFragment>()

    /** 尚未创建过的页面：延迟到抽屉关闭动画结束后再执行的切换 */
    private var pendingCommit: (() -> Unit)? = null

    /** 启动预热下标 */
    private var prewarmIndex = 0

    companion object {
        /** 参与实例缓存（重建时按 tag 找回）的页面 */
        private val CACHED_NAV_IDS = intArrayOf(
            R.id.nav_configuration, R.id.nav_group, R.id.nav_route,
            R.id.nav_settings, R.id.nav_tools, R.id.nav_logcat, R.id.nav_about,
        )
        private const val NAV_TAG_PREFIX = "main_nav:"
        private const val STATE_NAV_ID = "main_nav_id"
        private const val PREWARM_DELAY_MS = 300L
        private const val PREWARM_INTERVAL_MS = 250L

        /** 预热时让页面在屏幕外“可见”若干毫秒，用于生成整页渲染列表 */
        private const val WARM_FRAME_MS = 80L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageStore.setCurrentActivity(this)
        // The UI starts unbound: do not restore a stale Connected tray before the service replies.
        DataStore.serviceState = BaseService.State.Idle

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)

        binding.drawerLayout.addDrawerListener(drawerListener)
        // 页面缓存复用后层次里会同时存在多个页面的 AppBarLayout，
        // 每个页面视图创建时即时补一次状态栏内边距，避免顶栏顶进状态栏。
        supportFragmentManager.registerFragmentLifecycleCallbacks(navInsetCallbacks, false)
        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        } else {
            restoreFragments(savedInstanceState)
        }
        onBackPressedDispatcher.addCallback {
            if (currentMainFragment is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop) SagerNet.stopService() else connect.launch(
                null
            )
        }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)
        if (currentMainFragment == null) {
            currentMainFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        }
        syncMainControls(showWhenConnected = false, animate = false)
        changeState(BaseService.State.Idle, animate = false, animateControls = false)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        if (savedInstanceState == null) {
            // 启动后错峰预热其它侧边栏页面，让第一次点击也是热切换
            binding.root.postDelayed(prewarmRunnable, PREWARM_DELAY_MS)
            // ☰ 首开预热：240ms 后离屏构建抽屉菜单的绘制列表
            binding.root.postDelayed(drawerWarmRunnable, 240L)
        }

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

        // 兜底：抽屉已关闭但延迟切换还没执行（动画被系统回收等意外打断）
        if (!binding.drawerLayout.isOpen) {
            val pending = pendingCommit
            pendingCommit = null
            pending?.invoke()
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val restoredFragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        if (restoredFragment != null) {
            val fragmentChanged = restoredFragment !== currentMainFragment
            currentMainFragment = restoredFragment
            syncMainControls(
                fragment = restoredFragment,
                showWhenConnected = fragmentChanged && DataStore.serviceState == BaseService.State.Connected,
                animate = false,
            )
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

    suspend fun importSubscription(uri: Uri) {
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

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        val created = GroupManager.createGroup(subscription)
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

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) {
            binding.drawerLayout.closeDrawers()
            return true
        }
        return displayFragmentWithId(item.itemId)
    }

    /**
     * 切换主内容页。
     *
     * 官方实现是每次 new XxxFragment() + replace()：事务恰好在抽屉关闭动画
     * 进行时执行——销毁旧页整棵视图树 + 新页 inflation/初始化，一帧内做完
     * 几十~几百 ms 的主线程工作，动画与点击反馈必卡。再点同一项只走
     * onNavigationItemSelected 里的 isChecked 提前返回（仅收起抽屉），所以
     * 表现为「换页必卡、点当前页不卡」。
     *
     * 现在：
     *  1. 页面实例缓存复用，切换 = hide/show + 生命周期降级，不销毁不重建；
     *  2. 从未创建过（或重建后视图待创建）的页面延迟到抽屉关闭动画结束后
     *     再一次性提交，动画期间主线程零阻塞。
     */
    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        // 该页可能正处于预热微露期（屏幕外），先把位移归零
        fragment.view?.translationY = 0f
        if (currentMainFragment === fragment) {
            binding.drawerLayout.closeDrawers()
            return
        }

        val commit = {
            val old = currentMainFragment
            val transaction = supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
            if (old != null && old !== fragment && old.isAdded) {
                if (fragmentCache.values.any { it === old }) {
                    transaction.setMaxLifecycle(old, Lifecycle.State.STARTED)
                    if (!old.isHidden) transaction.hide(old)
                } else {
                    // 不在缓存里的页面（仪表盘）：保持原语义，切走即销毁
                    transaction.remove(old)
                }
            }
            for (cached in fragmentCache.values) {
                if (cached !== fragment && cached.isAdded && !cached.isHidden) {
                    transaction.setMaxLifecycle(cached, Lifecycle.State.STARTED)
                    transaction.hide(cached)
                }
            }
            if (fragment.isAdded) {
                transaction.show(fragment)
            } else {
                transaction.add(R.id.fragment_holder, fragment, tagOf(fragment))
            }
            transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            transaction.commitAllowingStateLoss()

            currentMainFragment = fragment
            syncMainControls(fragment, showWhenConnected = false, animate = false)
        }

        binding.drawerLayout.closeDrawers()
        if (fragment.view != null || !binding.drawerLayout.isOpen) {
            // 热切换（页面已就绪）或抽屉没开：立即提交，与抽屉动画同步切换
            pendingCommit = null
            commit()
        } else {
            // 冷页面：先让抽屉关完，避免 inflation 冻结动画
            pendingCommit = commit
        }
    }

    private val drawerListener = object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerClosed(drawerView: View) {
            val pending = pendingCommit
            pendingCommit = null
            pending?.invoke()
        }
    }

    /** 页面视图创建时补齐顶栏的状态栏内边距（多页面共存场景） */
    private val navInsetCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager,
            f: Fragment,
            v: View,
            savedInstanceState: Bundle?,
        ) {
            applyStatusBarInset(v)
        }
    }

    private fun tagOf(fragment: ToolbarFragment): String? =
        fragmentCache.entries.firstOrNull { it.value === fragment }?.let {
            NAV_TAG_PREFIX + it.key
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

    /** 配置页长按分组标签：跳转分组页并定位到该分组（复用实例，避免重建） */
    fun openGroupAt(groupId: Long) {
        val fragment = obtainFragment(R.id.nav_group) as GroupFragment
        displayFragment(fragment)
        navigation.menu.findItem(R.id.nav_group)?.isChecked = true
        fragment.scrollToGroup(groupId)
    }

    /** 进程/配置变更重建后，从 tag 恢复缓存的页面实例 */
    private fun restoreFragments(savedInstanceState: Bundle) {
        for (id in CACHED_NAV_IDS) {
            (supportFragmentManager.findFragmentByTag(NAV_TAG_PREFIX + id) as? ToolbarFragment)
                ?.let { fragmentCache[id] = it }
        }
        // 清掉缓存外的残留顶层 Fragment（例如仪表盘页）
        val kept = fragmentCache.values.toSet()
        for (f in supportFragmentManager.fragments.toList()) {
            if (f is ToolbarFragment && kept.none { it === f }) {
                supportFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .remove(f)
                    .commitAllowingStateLoss()
            }
        }
        val lastId = savedInstanceState.getInt(STATE_NAV_ID, R.id.nav_configuration)
        val restored = fragmentCache[lastId]
        if (restored != null) {
            currentMainFragment = restored
            navigation.menu.findItem(lastId)?.isChecked = true
        } else if (lastId == R.id.nav_traffic) {
            // 之前停在仪表盘（不进缓存）：重新打开一次
            displayFragmentWithId(R.id.nav_traffic)
        } else {
            displayFragmentWithId(R.id.nav_configuration)
        }
    }

    /**
     * 启动后错峰预热其余侧边栏页面（逐个隐藏挂载），让「第一次点击」
     * 也是热切换。不需要这部分启动开销时，清空 prewarmIds 即可。
     */
    private val prewarmIds = intArrayOf(
        R.id.nav_group,
        R.id.nav_settings,
        R.id.nav_route,
        R.id.nav_tools,
        R.id.nav_logcat,
        R.id.nav_about,
    )

    /**
     * ☰ 侧边栏首开预热：抽屉关闭时内容在屏外、从未绘制，第一次打开要现画
     * 整棵菜单树（文本排版 + 显示列表构建），这正是「打开 App 立刻点 ☰ 必卡」
     * 的主因。这里离屏强制构建一次绘制列表（buildLayer），首开直接复用，
     * 跑完即还原图层类型。
     */
    private val drawerWarmRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            try {
                navigation.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                navigation.buildLayer()
            } catch (_: Throwable) {
            }
            navigation.post {
                try {
                    navigation.setLayerType(View.LAYER_TYPE_NONE, null)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private val prewarmRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (binding.drawerLayout.isOpen) {
                binding.root.postDelayed(this, PREWARM_INTERVAL_MS)
                return
            }
            // 弹窗/菜单/对话框持有窗口焦点时完全让路，绝不和用户正盯着的东西抢帧
            if (!binding.root.hasWindowFocus()) {
                binding.root.postDelayed(this, PREWARM_INTERVAL_MS)
                return
            }
            while (prewarmIndex < prewarmIds.size) {
                val id = prewarmIds[prewarmIndex++]
                val fragment = fragmentCache.getOrPut(id) { createFragment(id)!! }
                if (fragment.isAdded || fragment === currentMainFragment) continue
                supportFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.fragment_holder, fragment, tagOf(fragment))
                    .hide(fragment)
                    .setMaxLifecycle(fragment, Lifecycle.State.STARTED)
                    .commitAllowingStateLoss()
                // 立即执行该事务，并在同一拍里补全布局/绘制，
                // 不留「已挂载但尚未补全」的窗口（避免用户此时点开而错过预热）
                supportFragmentManager.executePendingTransactions()
                warmUpHiddenFragment(fragment)
                break
            }
            if (prewarmIndex < prewarmIds.size) {
                binding.root.postDelayed(this, PREWARM_INTERVAL_MS)
            }
        }
    }

    /**
     * 把隐藏页面「首次显示」的开销提前付掉：
     *
     * 隐藏（GONE）的视图不会参与 measure/layout/draw，所以第一次显示时仍要
     * 现做一堆一次性工作——RecyclerView 首建列表项、设置页首次摆出偏好行、
     * 日志页 50KB 长文首次排版、整页首次生成渲染显示列表……这正是「每页第
     * 一次点开卡、之后都流畅」的根因。
     *
     * 这里：
     *  1) 手动 measure + layout 整棵树（把建项/排版等一次性成本做掉）；
     *  2) 让它在屏幕外“可见”过几帧（translationY 移出屏幕，用户看不到、
     *     也点不到），从而生成整页的渲染显示列表（首次绘制成本）。
     * 之后用户点开该页，就只剩一次轻量的可见性切换。
     */
    private fun warmUpHiddenFragment(fragment: ToolbarFragment) {
        if (isFinishing || isDestroyed) return
        val view = fragment.view ?: return
        if (!fragment.isHidden) return // 用户已打开该页，交给正常流程
        val holder = binding.fragmentHolder
        var width = holder.width
        var height = holder.height
        if (width <= 0 || height <= 0) {
            width = binding.root.width
            height = binding.root.height
        }
        if (width <= 0 || height <= 0) return

        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)

        view.translationY = (height + 64).toFloat()
        view.visibility = View.VISIBLE
        view.postDelayed({
            view.translationY = 0f
            if (fragment.isHidden && view.isAttachedToWindow) {
                view.visibility = View.GONE
            }
        }, WARM_FRAME_MS)
    }

    private fun syncMainControls(
        fragment: Any? = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder),
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        val showControls = fragment is ConfigurationFragment || DataStore.showBottomBar
        binding.stats.useExternalScrollDriver = fragment is ConfigurationFragment
        binding.stats.syncMainControls(
            showControls,
            DataStore.serviceState,
            showWhenConnected,
            animate,
        )
        binding.fab.animate().cancel()
        if (showControls) {
            binding.fab.show()
        } else {
            binding.fab.hideProgress()
            binding.fabProgress.hide()
            binding.fabProgress.visibility = View.INVISIBLE
            if (animate && binding.fab.isLaidOut) {
                binding.fab.hide()
            } else {
                binding.fab.visibility = View.INVISIBLE
            }
        }
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    fun driveBottomBar(scrollDy: Int) {
        binding.stats.onListScrolled(scrollDy)
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        if (id == R.id.nav_faq) {
            launchCustomTab("https://matsuridayo.github.io/")
            return false
        }
        val fragment = if (id == R.id.nav_traffic) {
            // 仪表盘：内含 WebView，保持原语义每次重建（不进缓存、不预热）
            WebviewFragment()
        } else {
            fragmentCache[id] ?: (createFragment(id)?.also { fragmentCache[id] = it }
                ?: return false)
        }
        displayFragment(fragment)
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
        animateControls: Boolean = animate,
    ) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()

        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)
        syncMainControls(
            showWhenConnected = state == BaseService.State.Connected,
            animate = animateControls,
        )
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        changeState(BaseService.State.Idle)
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
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
            Key.SHOW_BOTTOM_BAR -> syncMainControls(
                showWhenConnected = DataStore.showBottomBar,
                animate = true,
            )
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

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.root.removeCallbacks(prewarmRunnable)
        binding.drawerLayout.removeDrawerListener(drawerListener)
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(navInsetCallbacks)
        pendingCommit = null
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(
            STATE_NAV_ID,
            fragmentCache.entries.firstOrNull { it.value === currentMainFragment }?.key
                ?: R.id.nav_configuration,
        )
    }

    /**
     * 打开左侧抽屉。
     * 顶栏的 ☰ 由 TopBarController 自绘（原先是 Toolbar 的 navigationIcon，
     * 但它自带 48dp 按钮宽度、和最左边对不齐），点击后回调到这里。
     */
    fun openDrawer() {
        binding.drawerLayout.open()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
