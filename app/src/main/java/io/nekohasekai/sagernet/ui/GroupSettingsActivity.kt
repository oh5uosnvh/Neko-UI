package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.*
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.GroupDnsStore
import io.nekohasekai.sagernet.group.GroupDnsUi
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.SubscriptionDns
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.HostsSwapPreference
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.CopyableEditTextPreferenceDialog
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.SimpleMenuPreference

@Suppress("UNCHECKED_CAST")
class GroupSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference
    private lateinit var hostsPreference: HostsSwapPreference
    private val dnsPreferences = linkedMapOf<String, EditTextPreference>()
    private var automaticDns = GroupDnsUi.Automatic()

    fun ProxyGroup.init() {
        val subscription = GroupDnsStore.prepare(this)
        DataStore.groupName = name ?: ""
        DataStore.groupType = type
        DataStore.groupOrder = order
        DataStore.groupIsSelector = isSelector

        DataStore.frontProxy = frontProxy
        DataStore.landingProxy = landingProxy
        DataStore.frontProxyTmp =
            if (frontProxy >= 0) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.landingProxyTmp =
            if (landingProxy >= 0) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0

        DataStore.subscriptionLink = subscription.link
        DataStore.subscriptionForceResolve = subscription.forceResolve
        DataStore.subscriptionDeduplication = subscription.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = subscription.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = subscription.customUserAgent
        DataStore.subscriptionAutoUpdate = subscription.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = subscription.autoUpdateDelay
        DataStore.subscriptionFilterMode = subscription.filterMode
        DataStore.subscriptionFilterRegex = subscription.filterRegex
        DataStore.subscriptionServerDns = subscription.serverDnsResolver ?: ""
        DataStore.subscriptionDirectDns = subscription.directDnsResolver ?: ""
        DataStore.subscriptionPrivateDns = subscription.privateDnsConfig ?: ""
        DataStore.subscriptionHosts = subscription.hostsConfig ?: ""
        DataStore.subscriptionImportedDns = subscription.importedDnsConfig.orEmpty()
        DataStore.subscriptionDnsSource = subscription.importedDnsSource.orEmpty()
        DataStore.subscriptionApplyHosts = subscription.applyHosts != false
    }

    fun ProxyGroup.serialize() {
        // Prepare the live object before changing its type, including legacy basic-node snapshots.
        // Never overwrite imported data with the cache captured when this page opened.
        val settings = GroupDnsStore.prepare(this)
        name = DataStore.groupName.takeIf { it.isNotBlank() } ?: "My group"
        type = DataStore.groupType
        order = DataStore.groupOrder
        isSelector = DataStore.groupIsSelector

        frontProxy =
            if (DataStore.frontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.frontProxy
            } else {
                -1
            }
        landingProxy =
            if (DataStore.landingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.landingProxy
            } else {
                -1
            }

        settings.serverDnsResolver = DataStore.subscriptionServerDns
        settings.directDnsResolver = DataStore.subscriptionDirectDns
        settings.privateDnsConfig = DataStore.subscriptionPrivateDns
        settings.hostsConfig = DataStore.subscriptionHosts
        settings.applyHosts = DataStore.subscriptionApplyHosts
        if (type == GroupType.SUBSCRIPTION) {
            settings.apply {
                link = DataStore.subscriptionLink
                forceResolve = DataStore.subscriptionForceResolve
                deduplication = DataStore.subscriptionDeduplication
                updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
                customUserAgent = DataStore.subscriptionUserAgent
                autoUpdate = DataStore.subscriptionAutoUpdate
                autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
                filterMode = DataStore.subscriptionFilterMode
                filterRegex = DataStore.subscriptionFilterRegex
            }
        }
    }

    private var isFromClipboard = false

    fun needSave(): Boolean {
        return DataStore.dirty
    }

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.group_preferences)

        frontProxyPreference = findPreference(Key.GROUP_FRONT_PROXY)!!
        frontProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.frontProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(
                            this@GroupSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.frontProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    true
                }
            }
        }
        landingProxyPreference = findPreference(Key.GROUP_LANDING_PROXY)!!
        landingProxyPreference.apply {
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.landingProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(
                            this@GroupSettingsActivity, ProfileSelectActivity::class.java
                        ).apply {
                            ProfileManager.getProfile(DataStore.landingProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    true
                }
            }
        }

        val groupType = findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!
        val groupSubscription = findPreference<PreferenceCategory>(Key.GROUP_SUBSCRIPTION)!!
        val subscriptionOnlyKeys = listOf(
            Key.SUBSCRIPTION_LINK,
            Key.SUBSCRIPTION_FORCE_RESOLVE,
            Key.SUBSCRIPTION_DEDUPLICATION,
            Key.SUBSCRIPTION_FILTER_MODE,
            Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY,
            Key.SUBSCRIPTION_USER_AGENT,
            Key.SUBSCRIPTION_AUTO_UPDATE,
            Key.SUBSCRIPTION_AUTO_UPDATE_DELAY,
        )

        fun updateGroupType(groupType: Int = DataStore.groupType) {
            val isSubscription = groupType == GroupType.SUBSCRIPTION
            // Keep the DNS category and all four editors visible, including empty basic groups.
            // The update category contains subscription-only items, so hide its otherwise empty title.
            findPreference<PreferenceCategory>(Key.SUBSCRIPTION_UPDATE)!!.isVisible = isSubscription
            subscriptionOnlyKeys.forEach { key ->
                findPreference<Preference>(key)!!.isVisible = isSubscription
            }
            findPreference<Preference>(Key.SUBSCRIPTION_FILTER_REGEX)!!.isVisible =
                isSubscription && DataStore.subscriptionFilterMode != SubscriptionFilterMode.DISABLED
            groupSubscription.setTitle(
                if (isSubscription) R.string.subscription_settings else R.string.cag_dns,
            )
            refreshDnsSummaries(groupType)
        }
        updateGroupType()
        groupType.setOnPreferenceChangeListener { _, newValue ->
            updateGroupType((newValue as String).toInt())
            true
        }

        val subscriptionAutoUpdate =
            findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!

        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            val delay = (newValue as String).toIntOrNull()
            if (delay == null) {
                false
            } else {
                delay >= 15
            }
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }

        val subscriptionFilterMode =
            findPreference<SimpleMenuPreference>(Key.SUBSCRIPTION_FILTER_MODE)!!
        val subscriptionFilterRegex =
            findPreference<EditTextPreference>(Key.SUBSCRIPTION_FILTER_REGEX)!!

        fun updateFilterMode(filterMode: Int = DataStore.subscriptionFilterMode) {
            subscriptionFilterRegex.isVisible = DataStore.groupType == GroupType.SUBSCRIPTION &&
                    filterMode != SubscriptionFilterMode.DISABLED
        }
        updateFilterMode()
        subscriptionFilterMode.setOnPreferenceChangeListener { _, newValue ->
            updateFilterMode((newValue as String).toInt())
            true
        }

        automaticDns = GroupDnsUi.automatic(DataStore.subscriptionImportedDns)
        dnsPreferences.clear()
        hostsPreference = findPreference<HostsSwapPreference>(Key.SUBSCRIPTION_HOSTS)!!.apply {
            onSwap = { swapHostsMapping() }
        }

        fun configureDnsPreference(key: String) {
            val preference = findPreference<EditTextPreference>(key)!!
            dnsPreferences[key] = preference
            preference.isVisible = true
            // Summaries describe provenance only; private resolver URLs stay inside the editor.
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                getString(dnsSummaryResource(dnsState(key, it.text.orEmpty()).summary))
            }
            var editorState = dnsState(key, preference.text.orEmpty())
            var initialContent = editorState.content
            preference.setOnBindEditTextListener { editText ->
                editorState = dnsState(key, preference.text.orEmpty())
                editText.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                editText.setSingleLine(false)
                editText.setHorizontallyScrolling(false)
                editText.minLines = 3
                // Set only the editor, never preference.text: automatic values are not overrides.
                editText.setText(editorState.content)
                editText.setSelection(editText.length())
                initialContent = editText.text.toString()
            }
            preference.setOnPreferenceChangeListener { _, newValue ->
                val raw = newValue as String
                val edited = editorState.edited(raw, initialContent)
                if (edited == null) {
                    // Opening, copying, cancelling or saving unchanged keeps the original manual value.
                    false
                } else {
                    val error = subscriptionDnsError(key, edited)
                    if (error != null) {
                        Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                        false
                    } else if (edited != raw) {
                        // Clearing an imported value must not immediately inherit it again.
                        preference.text = edited
                        if (key == Key.SUBSCRIPTION_HOSTS) refreshHostsWidget(edited)
                        false
                    } else {
                        // EditTextPreference persists the untrimmed original text after this callback.
                        if (key == Key.SUBSCRIPTION_HOSTS) refreshHostsWidget(raw)
                        true
                    }
                }
            }
        }
        configureDnsPreference(Key.SUBSCRIPTION_SERVER_DNS)
        configureDnsPreference(Key.SUBSCRIPTION_DIRECT_DNS)
        configureDnsPreference(Key.SUBSCRIPTION_PRIVATE_DNS)
        configureDnsPreference(Key.SUBSCRIPTION_HOSTS)
        refreshHostsWidget()
    }

    private fun dnsState(
        key: String,
        manual: String,
        groupType: Int = DataStore.groupType,
    ): GroupDnsUi.State = GroupDnsUi.state(
        manual = manual,
        automatic = when (key) {
            Key.SUBSCRIPTION_SERVER_DNS -> automaticDns.server
            Key.SUBSCRIPTION_DIRECT_DNS -> automaticDns.direct
            Key.SUBSCRIPTION_PRIVATE_DNS -> automaticDns.privateDns
            Key.SUBSCRIPTION_HOSTS -> automaticDns.hosts
            else -> ""
        },
        source = DataStore.subscriptionDnsSource,
        isSubscription = groupType == GroupType.SUBSCRIPTION,
    )

    private fun dnsSummaryResource(summary: GroupDnsUi.Summary): Int = when (summary) {
        GroupDnsUi.Summary.UNSET -> R.string.subscription_dns_unset
        GroupDnsUi.Summary.SUBSCRIPTION -> R.string.subscription_dns_from_subscription
        GroupDnsUi.Summary.FILE -> R.string.subscription_dns_from_file
        GroupDnsUi.Summary.CUSTOM -> R.string.subscription_dns_custom
    }

    private fun refreshDnsSummaries(groupType: Int = DataStore.groupType) {
        dnsPreferences.forEach { (key, preference) ->
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                getString(dnsSummaryResource(dnsState(key, it.text.orEmpty(), groupType).summary))
            }
        }
    }

    private fun refreshHostsWidget(manual: String = DataStore.subscriptionHosts) {
        if (!::hostsPreference.isInitialized) return
        val state = GroupDnsUi.hostsState(
            dnsState(Key.SUBSCRIPTION_HOSTS, manual), DataStore.subscriptionApplyHosts,
        )
        hostsPreference.mapped = state.mapped
        hostsPreference.canSwap = state.canSwap
    }

    private var mappingBusy = false

    private fun cachedDnsSettings() = SubscriptionBean().apply {
        initializeDefaultValues()
        serverDnsResolver = DataStore.subscriptionServerDns
        directDnsResolver = DataStore.subscriptionDirectDns
        privateDnsConfig = DataStore.subscriptionPrivateDns
        hostsConfig = DataStore.subscriptionHosts
        applyHosts = DataStore.subscriptionApplyHosts
    }

    private fun swapHostsMapping() {
        if (mappingBusy || !::hostsPreference.isInitialized || !hostsPreference.canSwap) return
        if (GroupUpdater.updating.contains(DataStore.editingId)) {
            Toast.makeText(this, R.string.subscription_hosts_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val groupId = DataStore.editingId
        val oldMode = DataStore.subscriptionApplyHosts
        val updated = cachedDnsSettings().apply { applyHosts = !oldMode }
        if (groupId == 0L) {
            DataStore.subscriptionApplyHosts = !oldMode
            DataStore.dirty = true
            refreshHostsWidget()
            return
        }
        mappingBusy = true
        hostsPreference.canSwap = false
        runOnDefaultDispatcher {
            try {
                GroupDnsStore.saveDnsAndMappings(SagerDatabase.instance, groupId, updated)
                GroupManager.postReload(groupId)
                GroupManager.postUpdate(groupId)
                onMainDispatcher {
                    DataStore.subscriptionApplyHosts = !oldMode
                    DataStore.dirty = true
                    Toast.makeText(this@GroupSettingsActivity,
                        if (!oldMode) R.string.subscription_hosts_mapping_on else R.string.subscription_hosts_mapping_off,
                        Toast.LENGTH_SHORT).show()
                }
            } catch (error: Exception) {
                Logs.w(error)
                onMainDispatcher {
                    Toast.makeText(this@GroupSettingsActivity, R.string.subscription_hosts_failed, Toast.LENGTH_LONG).show()
                }
            } finally {
                onMainDispatcher { mappingBusy = false; refreshHostsWidget() }
            }
        }
    }

    private fun subscriptionDnsError(key: String, raw: String): String? = when (key) {
        Key.SUBSCRIPTION_SERVER_DNS -> {
            if (SubscriptionDns.isValidEndpointList(raw)) null else getString(R.string.subscription_server_dns_invalid)
        }
        Key.SUBSCRIPTION_DIRECT_DNS -> {
            if (SubscriptionDns.isValidEndpointList(raw)) null else getString(R.string.subscription_direct_dns_invalid)
        }
        Key.SUBSCRIPTION_PRIVATE_DNS -> SubscriptionDns.validatePrivateConfig(raw)?.let { error ->
            getString(R.string.subscription_dns_config_invalid, getString(R.string.subscription_private_dns), error)
        }
        Key.SUBSCRIPTION_HOSTS -> SubscriptionDns.validateHostsConfig(raw)?.let { error ->
            getString(R.string.subscription_dns_config_invalid, getString(R.string.subscription_hosts), error)
        }
        else -> null
    }

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as GroupSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class GroupIdArg(val groupId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<GroupIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_group_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    GroupManager.deleteGroup(arg.groupId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "id"
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"
        const val EXTRA_GROUP_SUBSCRIPTION_LINK = "subscription_link"
        const val EXTRA_GROUP_NAME = "group_name"
    }

    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.group_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            isFromClipboard = intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)
            val subscriptionLink = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
            val importedGroupName = intent.getStringExtra(EXTRA_GROUP_NAME)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    val group = ProxyGroup()
                    group.init()

                    // 如果有订阅链接，设置为订阅类型并填充链接
                    if (!subscriptionLink.isNullOrEmpty()) {
                        DataStore.groupType = GroupType.SUBSCRIPTION
                        DataStore.subscriptionLink = subscriptionLink
                        if (!importedGroupName.isNullOrBlank()) {
                            DataStore.groupName = importedGroupName
                        }
                        DataStore.dirty = true
                    }
                } else {
                    val entity = SagerDatabase.groupDao.getById(editingId)
                    if (entity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
                }
            }

        }

    }

    suspend fun saveAndExit() {
        if (mappingBusy) return
        if (GroupUpdater.updating.contains(DataStore.editingId)) {
            onMainDispatcher { Toast.makeText(this@GroupSettingsActivity, R.string.subscription_hosts_busy, Toast.LENGTH_SHORT).show() }
            return
        }
        // Both basic and subscription groups persist the same four DNS overrides.
        val dnsValues = listOf(
            Key.SUBSCRIPTION_SERVER_DNS to DataStore.subscriptionServerDns,
            Key.SUBSCRIPTION_DIRECT_DNS to DataStore.subscriptionDirectDns,
            Key.SUBSCRIPTION_PRIVATE_DNS to DataStore.subscriptionPrivateDns,
            Key.SUBSCRIPTION_HOSTS to DataStore.subscriptionHosts,
        )
        for ((key, value) in dnsValues) {
            val error = subscriptionDnsError(key, value) ?: continue
            onMainDispatcher {
                Toast.makeText(this@GroupSettingsActivity, error, Toast.LENGTH_LONG).show()
            }
            return
        }

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            val newGroup = GroupManager.createGroup(ProxyGroup().apply { serialize() })
            // 问题3：新建分组后自动设为选中分组，回到配置页直接定位到它
            DataStore.selectedGroup = newGroup.id
            DataStore.editingGroup = newGroup.id
            if (newGroup.type == GroupType.SUBSCRIPTION && !newGroup.subscription?.link.isNullOrEmpty()) {
                GroupUpdater.startUpdate(newGroup, true)
            }
        } else if (needSave()) {
            val entity = SagerDatabase.groupDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            val keepUserInfo = (entity.type == GroupType.SUBSCRIPTION &&
                    DataStore.groupType == GroupType.SUBSCRIPTION &&
                    entity.subscription?.link == DataStore.subscriptionLink)
            if (!keepUserInfo) {
                entity.subscription?.subscriptionUserinfo = "";
            }
            try {
                entity.serialize()
                SagerDatabase.instance.runInTransaction {
                    SagerDatabase.groupDao.updateGroup(entity)
                    GroupDnsStore.applyMappings(SagerDatabase.instance, entity)
                }
            } catch (error: Exception) {
                Logs.w(error)
                onMainDispatcher { Toast.makeText(this@GroupSettingsActivity, R.string.subscription_hosts_failed, Toast.LENGTH_LONG).show() }
                return
            }
            GroupManager.postReload(editingId)
            GroupManager.postUpdate(entity)
            if (entity.type == GroupType.SUBSCRIPTION) io.nekohasekai.sagernet.bg.SubscriptionUpdater.reconfigureUpdater()
        }

        finish()

    }

    // 015: fragment 提交是双重异步（DB 协程 + commit()），高频操作下手速快于
    // fragment attach 时 findFragmentById 返回 null，旧的非空强转直接 NPE 崩溃
    // （崩溃日志 GroupSettingsActivity.child_delegate$lambda）。未就绪时交给父类。
    val child: MyPreferenceFragmentCompat?
        get() = supportFragmentManager.findFragmentById(R.id.settings) as? MyPreferenceFragmentCompat

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        child?.onOptionsItemSelected(item) ?: super.onOptionsItemSelected(item)

    override fun onBackPressed() {
        if (needSave()) {
            UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
        } else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
        when (key) {
            Key.SUBSCRIPTION_HOSTS, "subscriptionApplyHosts" -> runOnUiThread {
                refreshHostsWidget()
            }
            "subscriptionImportedDns" -> runOnUiThread {
                automaticDns = GroupDnsUi.automatic(DataStore.subscriptionImportedDns)
                refreshDnsSummaries()
                refreshHostsWidget()
            }
            Key.GROUP_TYPE, "subscriptionDnsSource" -> runOnUiThread {
                refreshDnsSummaries()
            }
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        override fun onCreateRecyclerView(
            inflater: android.view.LayoutInflater, parent: android.view.ViewGroup, savedInstanceState: Bundle?,
        ): androidx.recyclerview.widget.RecyclerView =
            super.onCreateRecyclerView(settingsRowsInflater(inflater, parent), parent, savedInstanceState)

        var activity: GroupSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as GroupSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is EditTextPreference) {
                CopyableEditTextPreferenceDialog.newInstance(preference.key, this)
                    .show(parentFragmentManager, CopyableEditTextPreferenceDialog.DIALOG_TAG)
                return
            }
            super.onDisplayPreferenceDialog(preference)
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(GroupIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.frontProxy = profile.id
            onMainDispatcher {
                frontProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(
                it.data!!.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            ) ?: return@runOnDefaultDispatcher
            DataStore.landingProxy = profile.id
            onMainDispatcher {
                landingProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
            }
        }
    }

}
