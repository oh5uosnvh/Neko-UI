package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.hasKaringEnvelope
import io.nekohasekai.sagernet.database.toKaringRule
import io.nekohasekai.sagernet.database.applyKaringRule
import io.nekohasekai.sagernet.database.KaringRuleSetItem
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.AppListPreference
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.CopyableEditTextPreferenceDialog
import io.nekohasekai.sagernet.widget.OutboundPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.EditConfigPreference

@Suppress("UNCHECKED_CAST")
class KaringRouteSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_settings_activity,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    fun init(packageName: String?) {
        RuleEntity().apply {
            if (!packageName.isNullOrBlank()) {
                packages = setOf(packageName)
                name = app.getString(R.string.route_for, PackageCache.loadLabel(packageName))
            }
        }.init()
    }

    fun RuleEntity.init() {
        DataStore.routeName = name
        DataStore.serverConfig = config
        DataStore.routeDomain = domains
        DataStore.routeIP = ip
        DataStore.routePort = port
        DataStore.routeSourcePort = sourcePort
        DataStore.routeNetwork = network
        DataStore.routeSource = source
        DataStore.routeProtocol = protocol
        DataStore.routeRuleset = ruleset
        DataStore.routeOutboundRule = outbound
        DataStore.routeOutbound = when (outbound) {
            0L -> 0
            -1L -> 1
            -2L -> 2
            else -> OutboundPreference.VALUE_SELECT_PROFILE.toInt()
        }
        val model = toKaringRule()
        DataStore.routeDomain = model.domain.joinToString("\n")
        DataStore.routePort = model.port.joinToString("\n")
        DataStore.routeSourcePort = model.sourcePorts.joinToString("\n")
        DataStore.routeNetwork = model.network.joinToString("\n")
        DataStore.routeSource = model.sourceIpCidr.joinToString("\n")
        DataStore.routeProtocol = model.protocol.joinToString("\n")
        DataStore.routePackages = model.packageNames.joinToString("\n")
        // Keep the independent manual input separate from the installed-app picker.
        // Existing package IDs are initially shown in the picker; IDs that are not
        // installed are restored by the result callback instead of being discarded.
        DataStore.routePackagesManual = ""
        // New editor-created rules default to OR; existing envelopes (including
        // explicit AND) are never reinterpreted by the UI.
        DataStore.routeLogic = if (!hasKaringEnvelope() || model.or) "or" else "and"
        DataStore.routeBuiltinRulesets = model.ruleSetBuildIn.joinToString("\n")
        DataStore.routeExternalRulesets = model.ruleSetItems.joinToString("\n") { it.url }
        DataStore.routeDomainSuffix = model.domainSuffix.joinToString("\n")
        DataStore.routeDomainKeyword = model.domainKeyword.joinToString("\n")
        DataStore.routeDomainRegex = model.domainRegex.joinToString("\n")
        DataStore.routeIpCidr = model.ipCidr.joinToString("\n")
        DataStore.routeNetworkType = model.networkType.joinToString("\n")
        DataStore.routeWifiSsid = model.wifiSsid.joinToString("\n")
        DataStore.routeWifiBssid = model.wifiBssid.joinToString("\n")
        DataStore.routePortRange = model.portRange.joinToString("\n")
    }

    private fun multiline(value: String): List<String> = value.lineSequence()
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()

    fun RuleEntity.serialize() {
        // Read the original envelope before updating mirrors; copy retains process fields,
        // import metadata, unknown preset JSON and rule-set item types.
        require(DataStore.editingId == 0L || hasKaringEnvelope()) {
            "This is not a Karing rule; open it in the original route editor."
        }
        val previous = if (hasKaringEnvelope()) toKaringRule() else io.nekohasekai.sagernet.database.KaringRuleModel()
        name = DataStore.routeName
        outbound = when (DataStore.routeOutbound) {
            0 -> 0L
            1 -> -1L
            2 -> -2L
            else -> DataStore.routeOutboundRule
        }
        fun edited(value: String, original: List<String>) = if (value == original.joinToString("\n")) original else multiline(value)
        val external = if (DataStore.routeExternalRulesets == previous.ruleSetItems.joinToString("\n") { it.url }) previous.ruleSetItems else
            multiline(DataStore.routeExternalRulesets).map { url -> previous.ruleSetItems.firstOrNull { it.url == url } ?: KaringRuleSetItem(url) }
        applyKaringRule(previous.copy(
            or = DataStore.routeLogic == "or",
            ruleSetBuildIn = edited(DataStore.routeBuiltinRulesets, previous.ruleSetBuildIn),
            ruleSetItems = external,
            packageNames = edited(DataStore.routePackages + "\n" + DataStore.routePackagesManual, previous.packageNames),
            domain = edited(DataStore.routeDomain, previous.domain),
            domainSuffix = edited(DataStore.routeDomainSuffix, previous.domainSuffix),
            domainKeyword = edited(DataStore.routeDomainKeyword, previous.domainKeyword),
            domainRegex = edited(DataStore.routeDomainRegex, previous.domainRegex),
            ipCidr = edited(DataStore.routeIpCidr, previous.ipCidr),
            networkType = edited(DataStore.routeNetworkType, previous.networkType),
            network = edited(DataStore.routeNetwork, previous.network),
            sourceIpCidr = edited(DataStore.routeSource, previous.sourceIpCidr),
            sourcePorts = edited(DataStore.routeSourcePort, previous.sourcePorts),
            wifiSsid = edited(DataStore.routeWifiSsid, previous.wifiSsid),
            wifiBssid = edited(DataStore.routeWifiBssid, previous.wifiBssid),
            protocol = edited(DataStore.routeProtocol, previous.protocol),
            port = edited(DataStore.routePort, previous.port),
            portRange = edited(DataStore.routePortRange, previous.portRange),
        ))
        if (DataStore.editingId == 0L) enabled = true
    }

    fun needSave(): Boolean {
        return DataStore.dirty
    }

    private var missingPackages = emptyList<String>()
    private val selectApps = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _, _ ->
        // AppListActivity only writes installed packages. Preserve manually entered or
        // uninstalled IDs that it cannot display or intentionally deselect.
        DataStore.routePackages = (DataStore.routePackages.lineSequence().filter { it.isNotBlank() } + missingPackages.asSequence()).distinct().joinToString("\n")
        child?.refreshPackages()
    }
    private val selectProfile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { code, data ->
        if (code == Activity.RESULT_OK && data != null) {
            val id = data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0)
            if (id > 0) {
                DataStore.routeOutboundRule = id
                DataStore.routeOutbound = OutboundPreference.VALUE_SELECT_PROFILE.toInt()
            }
            child?.refreshOutbound()
        } else child?.refreshOutbound()
    }

    fun openApps() {
        missingPackages = DataStore.routePackages.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.filter {
            try { packageManager.getApplicationInfo(it, 0); false } catch (_: android.content.pm.PackageManager.NameNotFoundException) { true }
        }.toList()
        selectApps.launch(Intent(this, AppListActivity::class.java))
    }
    fun openProfile() {
        selectProfile.launch(Intent(this, ProfileSelectActivity::class.java).apply {
            ProfileManager.getProfile(DataStore.routeOutboundRule)?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) }
        })
    }

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cag_route)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        if (savedInstanceState != null) {
            missingPackages = savedInstanceState.getStringArrayList("missingPackages")?.toList().orEmpty()
            DataStore.profileCacheStore.registerChangeListener(this)
            return
        }
        val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
        DataStore.editingId = editingId
        runOnDefaultDispatcher {
            if (editingId == 0L) init(intent.getStringExtra(EXTRA_PACKAGE_NAME)) else {
                val entity = SagerDatabase.rulesDao.getById(editingId)
                if (entity == null || !entity.hasKaringEnvelope()) {
                    onMainDispatcher {
                        Toast.makeText(this@KaringRouteSettingsActivity, R.string.karing_wrong_editor, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@runOnDefaultDispatcher
                }
                entity.init()
            }
            onMainDispatcher {
                supportFragmentManager.beginTransaction().replace(R.id.settings, EditorFragment()).commit()
                DataStore.dirty = false
                DataStore.profileCacheStore.registerChangeListener(this@KaringRouteSettingsActivity)
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("missingPackages", ArrayList(missingPackages))
        super.onSaveInstanceState(outState)
    }
    private val child: EditorFragment? get() = supportFragmentManager.findFragmentById(R.id.settings) as? EditorFragment

    suspend fun saveAndExit() {
        if (!needSave() && DataStore.editingId != 0L) { onMainDispatcher { finish() }; return }
        if (!needSave()) {
            onMainDispatcher { MaterialAlertDialogBuilder(this@KaringRouteSettingsActivity).setTitle(R.string.empty_route).setMessage(R.string.empty_route_notice).setPositiveButton(android.R.string.ok, null).show() }
            return
        }
        try {
            if (DataStore.editingId == 0L) {
                ProfileManager.createRule(RuleEntity().apply { serialize() })
                if (intent.hasExtra(EXTRA_PACKAGE_NAME)) setResult(RESULT_OK, Intent())
            } else {
                val entity = SagerDatabase.rulesDao.getById(DataStore.editingId) ?: return
                ProfileManager.updateRule(entity.apply { serialize() })
            }
        } catch (error: IllegalArgumentException) {
            onMainDispatcher { MaterialAlertDialogBuilder(this@KaringRouteSettingsActivity).setTitle(R.string.error_title).setMessage(error.message).setPositiveButton(android.R.string.ok, null).show() }
            return
        }
        onMainDispatcher { finish() }
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.apply).setIcon(R.drawable.ic_action_done).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { currentFocus?.clearFocus(); runOnDefaultDispatcher { saveAndExit() }; true }
        else -> super.onOptionsItemSelected(item)
    }
    override fun onBackPressed() {
        if (!needSave()) { super.onBackPressed(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.unsaved_changes_prompt)
            .setPositiveButton(R.string.yes) { _, _ -> runOnDefaultDispatcher { saveAndExit() } }
            .setNegativeButton(R.string.no) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null).show()
    }
    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }
    override fun onDestroy() { DataStore.profileCacheStore.unregisterChangeListener(this); super.onDestroy() }
    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) DataStore.dirty = true
    }

    class EditorFragment : androidx.fragment.app.Fragment() {
        private val host get() = requireActivity() as KaringRouteSettingsActivity
        private lateinit var packages: android.widget.EditText
        private lateinit var outbound: android.widget.Spinner
        private var updating = false
        private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

        override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?): View {
            val context = requireContext()
            val scroll = android.widget.ScrollView(context)
            val content = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(32))
                isFocusableInTouchMode = true
            }
            scroll.addView(content)
            fun field(title: String, example: String, value: String, set: (String) -> Unit, select: (() -> Unit)? = null, single: Boolean = false): android.widget.EditText {
                val row = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                val box = com.google.android.material.textfield.TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                    hint = title
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(0, dp(6), 0, dp(6))
                }
                val edit = com.google.android.material.textfield.TextInputEditText(context).apply {
                    inputType = if (single) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    minLines = if (single) 1 else 3
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    setText(value)
                    setOnFocusChangeListener { _, focused ->
                        box.hint = if (focused && text.isNullOrEmpty()) example else title
                    }
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            box.hint = if (isFocused && s.isNullOrEmpty()) example else title
                            if (!updating) set(s?.toString().orEmpty())
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                }
                box.addView(edit); row.addView(box)
                if (select != null) row.addView(android.widget.ImageButton(context).apply {
                    setImageResource(R.drawable.karing_editor_chevron)
                    background = null
                    contentDescription = getString(R.string.karing_select_description, title)
                    setOnClickListener { select() }
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
                })
                content.addView(row)
                return edit
            }
            field(getString(R.string.route_name), getString(R.string.karing_example_name), DataStore.routeName, { DataStore.routeName = it }, single = true)
            val logic = android.widget.RadioGroup(context).apply { orientation = android.widget.RadioGroup.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            logic.addView(android.widget.TextView(context).apply { text = getString(R.string.route_logic); setPadding(0, 0, dp(12), 0) })
            val or = android.widget.RadioButton(context).apply { id = View.generateViewId(); text = "OR" }
            val and = android.widget.RadioButton(context).apply { id = View.generateViewId(); text = "AND" }
            logic.addView(or); logic.addView(and); logic.check(if (DataStore.routeLogic == "or") or.id else and.id)
            logic.setOnCheckedChangeListener { _, id -> DataStore.routeLogic = if (id == or.id) "or" else "and" }
            content.addView(logic)
            lateinit var builtins: android.widget.EditText
            builtins = field(getString(R.string.route_builtin_rulesets), "geosite:github\ngeoip:github", DataStore.routeBuiltinRulesets, { DataStore.routeBuiltinRulesets = it }, {
                io.nekohasekai.sagernet.widget.RouteBuiltinPreference.showSelector(host, DataStore.routeBuiltinRulesets) { value ->
                    DataStore.routeBuiltinRulesets = value
                    updating = true; builtins.setText(value); updating = false
                }
            })
            field(getString(R.string.route_external_rulesets), "https://example.com/rules.srs", DataStore.routeExternalRulesets, { DataStore.routeExternalRulesets = it })
            packages = field(getString(R.string.karing_package_ids), "com.example.app", DataStore.routePackages, { DataStore.routePackages = it }, { host.openApps() })
            field(getString(R.string.route_packages_manual), "com.example.manual", DataStore.routePackagesManual, { DataStore.routePackagesManual = it })
            field("domain", "example.com", DataStore.routeDomain, { DataStore.routeDomain = it })
            field("domain suffix", "example.com", DataStore.routeDomainSuffix, { DataStore.routeDomainSuffix = it })
            field("domain keyword", "example", DataStore.routeDomainKeyword, { DataStore.routeDomainKeyword = it })
            field("domain regex", "^example\\.com$", DataStore.routeDomainRegex, { DataStore.routeDomainRegex = it })
            field("IP CIDR", "192.0.2.0/24", DataStore.routeIpCidr, { DataStore.routeIpCidr = it })
            field("dst port", "443", DataStore.routePort, { DataStore.routePort = it })
            field("port range", "1000:2000", DataStore.routePortRange, { DataStore.routePortRange = it })
            field("src ip", "192.0.2.0/24", DataStore.routeSource, { DataStore.routeSource = it })
            field("src port", "53", DataStore.routeSourcePort, { DataStore.routeSourcePort = it })
            field("network", "tcp\nudp", DataStore.routeNetwork, { DataStore.routeNetwork = it })
            field("network type", "wifi", DataStore.routeNetworkType, { DataStore.routeNetworkType = it })
            field("Wi-Fi SSID", "My Wi-Fi", DataStore.routeWifiSsid, { DataStore.routeWifiSsid = it })
            field("Wi-Fi BSSID", "00:11:22:33:44:55", DataStore.routeWifiBssid, { DataStore.routeWifiBssid = it })
            field("protocol", "http", DataStore.routeProtocol, { DataStore.routeProtocol = it })
            content.addView(android.widget.TextView(context).apply { text = getString(R.string.karing_outbound); setPadding(0, dp(12), 0, 0) })
            outbound = android.widget.Spinner(context).apply {
                adapter = android.widget.ArrayAdapter.createFromResource(context, R.array.outbound_entry, android.R.layout.simple_spinner_dropdown_item)
                setSelection(DataStore.routeOutbound.coerceIn(0, 3))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (updating || position == DataStore.routeOutbound) return
                        if (position == 3) host.openProfile() else DataStore.routeOutbound = position
                    }
                }
            }
            content.addView(outbound)
            // An explicit button permits re-selecting a profile when already selected.
            content.addView(android.widget.Button(context).apply { text = getString(R.string.karing_select_profile); setOnClickListener { host.openProfile() } })
            content.requestFocus()
            return scroll
        }
        fun refreshPackages() { if (::packages.isInitialized) { updating = true; packages.setText(DataStore.routePackages); updating = false } }
        fun refreshOutbound() { if (::outbound.isInitialized) { updating = true; outbound.setSelection(DataStore.routeOutbound.coerceIn(0, 3)); updating = false } }
    }
}
