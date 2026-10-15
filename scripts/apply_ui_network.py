#!/usr/bin/env python3
"""Post-r86 UI only. Strict anchors: fail instead of silently misapplying."""
from pathlib import Path
import re, shutil, subprocess, sys
r=Path(sys.argv[1]).resolve()
files=Path(__file__).resolve().parents[1]/'files/ui-network'
def edit(rel,old,new):
 p=r/rel;s=p.read_text()
 if new in s:return
 if s.count(old)!=1:raise SystemExit(f'UI anchor mismatch: {rel} ({s.count(old)})')
 p.write_text(s.replace(old,new,1))
cfg='app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt'
grp='app/src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt'
main='app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt'
p=r/cfg;s=p.read_text();a=s.index('                    // 018: 长按分组标签');b=s.index('                    popup.show()',a)+len('                    popup.show()')
s=s[:a]+'''                    if (!select) GroupTabMenu.show(v,
                        edit = { context?.startActivity(Intent(context, GroupSettingsActivity::class.java)
                            .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)) },
                        jump = { (activity as? MainActivity)?.openGroupAt(group.id) },
                        clear = { (activity as? ThemedActivity)?.let { GroupClearAction.confirm(it, group) } },
                        delete = { deleteGroupWithUndo(group) },
                    )'''+s[b:];p.write_text(s)
# Selection mode reuses exactly the existing quick picker (incl. order/counts).
edit(cfg,'''            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon''','''            toolbar.setTitle(titleRes)
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
            toolbar.setNavigationIcon''')
# Taller official cradle requires space for full last node card.
edit(cfg,'bottom = dp2px(72) + insets.getInsets(', 'bottom = dp2px(170) + insets.getInsets(')
edit('app/src/main/java/io/nekohasekai/sagernet/ui/TopBarController.kt','    private fun showGroupPicker(anchor: View) {','    fun showGroupPicker(anchor: View) {')
p=r/grp;s=p.read_text();a=s.index('                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)',s.index('                R.id.action_clear ->'));b=s.index('\n                }',a)
s=s[:a]+'''                    GroupClearAction.confirm(activity, proxyGroup)'''+s[b:];p.write_text(s)
# Restore the upstream ServiceButton's drawable state machine and FAB geometry;
# drop only all progress indicator dependencies and progress callbacks.
rel='app/src/main/java/io/nekohasekai/sagernet/widget/ServiceButton.kt'
s=subprocess.check_output(['git','-C',str(r),'show','f167ebc88e9a733e65380e13dab39726eba917b2:'+rel],text=True)
for imp in ['import androidx.dynamicanimation.animation.DynamicAnimation\n','import androidx.lifecycle.LifecycleOwner\n','import androidx.lifecycle.lifecycleScope\n','import com.google.android.material.progressindicator.BaseProgressIndicator\n','import kotlinx.coroutines.Job\n','import kotlinx.coroutines.delay\n']:
 s=s.replace(imp,'')
s=s.replace('FloatingActionButton(context, attrs, defStyleAttr), DynamicAnimation.OnAnimationEndListener {','FloatingActionButton(context, attrs, defStyleAttr) {')
s=s.replace('''        @DrawableRes resId: Int,
        private val onStart: BaseProgressIndicator<*>.() -> Unit = { hideProgress() }
''','''        @DrawableRes resId: Int
''').replace('            progress.onStart()\n','')
a=s.index('    private val iconConnecting by lazy {');b=s.index('    private val iconStopping by lazy',a)
s=s[:a]+'''    private val iconConnecting by lazy { AnimatedState(R.drawable.ic_service_connecting) }
    private val iconConnected by lazy { AnimatedState(R.drawable.ic_service_connected) }
'''+s[b:]
a=s.index('    private var delayedAnimation:');b=s.index('    override fun onCreateDrawableState',a);s=s[:a]+s[b:]
(r/rel).write_text(s)
# Official BottomAppBar attachment; no progress view at all.
p=r/'app/src/main/res/layout/layout_main.xml';s=p.read_text();a=s.index('        <!-- 017:');b=s.index('\n    </androidx.coordinatorlayout.widget.CoordinatorLayout>',a)
s=s[:a]+'''        <io.nekohasekai.sagernet.widget.ConnectBar
            android:id="@+id/connect_bar"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_gravity="bottom" android:layout_marginBottom="4dp"
            app:backgroundTint="?colorPrimary" app:contentInsetStart="0dp"
            app:fabAlignmentMode="center" app:hideOnScroll="false" />
        <io.nekohasekai.sagernet.widget.ServiceButton
            android:id="@+id/fab" android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:elevation="6dp" app:backgroundTint="?colorPrimary"
            app:layout_anchor="@id/connect_bar" app:pressedTranslationZ="6dp"
            app:srcCompat="@drawable/ic_service_idle" />
'''+s[b:];p.write_text(s)
edit(main,'import androidx.lifecycle.Lifecycle\n','''import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
''')
edit(main,'    private var currentMainFragment: ToolbarFragment? = null','''    private var currentMainFragment: ToolbarFragment? = null
    private var handshakeJob: Job? = null
    private var networkJob: Job? = null
    private var lastObservedState = BaseService.State.Idle''')
a=(r/main).read_text().index('        binding.connectBar.onTestConnection = {');s=(r/main).read_text();b=s.index('        if (currentMainFragment == null)',a)
s=s[:a]+'''        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop) SagerNet.stopService() else launchConnect()
        }
        binding.connectBar.onTestConnection = { runHandshakeTests() }
'''+s[b:];(r/main).write_text(s)
# Keep general urlTest consumers unchanged; two sequential requests only for tray.
anchor='    suspend fun importSubscription(uri: Uri) {'
edit(main,anchor,'''    private fun runHandshakeTests() {
        if (!DataStore.serviceState.connected) return
        handshakeJob?.cancel()
        val profileId = DataStore.currentProfile
        handshakeJob = lifecycleScope.launch {
            binding.connectBar.setTesting()
            var lastSuccess: Int? = null
            repeat(2) { attempt ->
                if (!DataStore.serviceState.connected || DataStore.currentProfile != profileId) return@launch
                try {
                    val elapsed = withContext(Dispatchers.IO) { connection.service?.urlTest() ?: error("not started") }
                    lastSuccess = elapsed
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Logs.w(e) }
                if (attempt == 0) delay(300)
            }
            if (DataStore.serviceState.connected && DataStore.currentProfile == profileId) {
                val elapsed = lastSuccess
                if (elapsed != null) binding.connectBar.showDelay(elapsed) else binding.connectBar.showDelayError()
            }
        }
    }

    private fun refreshNetworkCard() {
        networkJob?.cancel()
        val profileId = DataStore.currentProfile
        binding.connectBar.renderNetwork()
        networkJob = lifecycleScope.launch {
            try {
                IpQualityLookup.prefetch(profileId)
                if (DataStore.currentProfile == profileId) binding.connectBar.renderNetwork()
            } catch (e: CancellationException) { throw e }
        }
    }

'''+anchor)
p=r/main;s=p.read_text();a=s.index('        DataStore.serviceState = state',s.index('    private fun changeState('));b=s.index('        if (msg != null)',a)
s=s[:a]+'''        val previousState = lastObservedState
        lastObservedState = state
        DataStore.serviceState = state
        refreshConfigurationProfileState()
        binding.fab.changeState(state, previousState, animate)
        binding.connectBar.changeState(state, animate)
        if (state == BaseService.State.Connected && previousState != state) {
            refreshNetworkCard()
            handshakeJob?.cancel()
            lifecycleScope.launch {
                delay(500)
                if (lastObservedState == BaseService.State.Connected && handshakeJob?.isActive != true) runHandshakeTests()
            }
        } else if (state != BaseService.State.Connected && previousState != state) {
            handshakeJob?.cancel()
            refreshNetworkCard()
        }
'''+s[b:];p.write_text(s)
edit(main,'''        binding.connectBar.visibility =
            if (currentMainFragment is ConfigurationFragment) View.VISIBLE else View.GONE''','''        val controlsVisible = currentMainFragment is ConfigurationFragment
        binding.connectBar.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        binding.fab.visibility = if (controlsVisible) View.VISIBLE else View.GONE''')
edit(main,'        syncConnectBar()\n        changeState(', '        syncConnectBar()\n        refreshNetworkCard()\n        changeState(')
edit(main,'''        DataStore.currentProfile = id
        refreshConfigurationProfileState()''','''        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        refreshNetworkCard()
        runHandshakeTests()''')
# Shared privacy setting, restored across process/activity restarts.
dialog='app/src/main/java/io/nekohasekai/sagernet/ui/OutboundIpDialogFragment.kt'
edit(dialog,'    private var loading = false','''    private var loading = false
    private var rawIp: String? = null''')
edit(dialog,'        // 019: 仅 IP 地址 / 组织 / ASN 三行点击复制', '''        view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.neko_hide_ip_switch).apply {
            isChecked = NetworkPrivacy.hidden(requireContext())
            setOnCheckedChangeListener { _, checked ->
                NetworkPrivacy.prefs(requireContext()).edit().putBoolean(NetworkPrivacy.HIDE_IP, checked).apply()
                ipValue.text = NetworkPrivacy.display(rawIp, checked)
                (activity as? MainActivity)?.binding?.connectBar?.renderNetwork()
            }
        }
        // 019: 仅 IP 地址 / 组织 / ASN 三行点击复制''')
edit('app/src/main/res/layout/layout_outbound_ip_dialog.xml', '        <!-- ── 基础信息 ── -->', '''        <com.google.android.material.switchmaterial.SwitchMaterial
            android:id="@+id/neko_hide_ip_switch" android:layout_width="match_parent"
            android:layout_height="wrap_content" android:minHeight="48dp"
            android:text="@string/neko_hide_ip" />
        <!-- ── 基础信息 ── -->''')
edit(dialog,'        ipValue.text = q.ip','''        rawIp = q.ip
        ipValue.text = NetworkPrivacy.display(q.ip, NetworkPrivacy.hidden(requireContext()))''')
edit(dialog,'''            val text = valueView.text?.toString().orEmpty()''','''            if (valueView === ipValue && NetworkPrivacy.hidden(requireContext())) return@setOnClickListener
            val text = valueView.text?.toString().orEmpty()''')
edit(dialog,'        ipValue.setText(R.string.ip_loading)','''        rawIp = null
        if (NetworkPrivacy.hidden(requireContext())) ipValue.text = "*****" else ipValue.setText(R.string.ip_loading)''')
# GroupClearAction uses only application DB operations; no schema changes.
shutil.copytree(files,r/'app/src',dirs_exist_ok=True)
print('PASS: compact menu, shared undo clear, official FAB without spinner, network privacy card, selection picker')
