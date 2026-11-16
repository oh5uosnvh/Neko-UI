package io.nekohasekai.sagernet.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DefaultRouteCatalog
import io.nekohasekai.sagernet.database.RemoteRouteUpdater
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Independent settings page for the fixed, built-in remote route sources. */
class RouteRulesSettingsActivity : ThemedActivity() {
    private lateinit var updateAll: Button
    private lateinit var status: TextView
    private var updating = false
    private val details = linkedMapOf<String, TextView>()
    private val updateButtons = arrayListOf<Button>()
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun setUpdating(value: Boolean) {
        updating = value
        updateAll.isEnabled = !value
        updateButtons.forEach { it.isEnabled = !value }
    }
    private suspend fun refreshDetails() {
        val rules = withContext(Dispatchers.IO) { SagerDatabase.rulesDao.builtinRules().associateBy { it.builtinKey } }
        if (isFinishing || isDestroyed) return
        details.forEach { (key, view) ->
            val rule = rules[key]
            view.text = when {
                rule == null -> getString(R.string.route_rule_missing)
                rule.subscriptionState == "stale" -> getString(R.string.route_configured_rules, rule.cachedRuleCount) + " · " + getString(R.string.route_update_error_detail)
                rule.cachedRuleCount > 0 -> getString(R.string.route_configured_rules, rule.cachedRuleCount)
                else -> getString(R.string.route_waiting_update)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val toolbar = Toolbar(this).apply {
            title = getString(R.string.route_rules_settings)
            setNavigationIcon(R.drawable.ic_navigation_close)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        updateAll = Button(this).apply {
            text = getString(R.string.route_update_builtin)
            setOnClickListener { updateAllSources() }
        }
        status = TextView(this).apply { setPadding(0, 8, 0, 12) }
        root.addView(TextView(this).apply {
            text = getString(R.string.route_rules_settings_summary)
            setPadding(0, 0, 0, 12)
        })
        root.addView(updateAll, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(status, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        DefaultRouteCatalog.remoteItems.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }
            val name = TextView(this).apply {
                text = item.name
                setTypeface(typeface, Typeface.BOLD)
            }
            val detail = TextView(this)
            details[item.key] = detail
            row.addView(TextView(this).apply {
                text = item.remoteUrl
                textSize = 12f
                setTextIsSelectable(true)
            })
            val button = Button(this).apply {
                text = getString(R.string.route_update_one)
                setOnClickListener { updateOne(item.key, item.name, detail, this) }
            }
            updateButtons += button
            row.addView(name)
            row.addView(detail)
            row.addView(button, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            list.addView(row)
            detail.text = getString(R.string.route_waiting_update)
        }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = getString(R.string.route_rules_settings)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { io.nekohasekai.sagernet.database.ProfileManager.ensureDefaultRoutes() }
            refreshDetails()
        }
    }

    private fun updateAllSources() {
        if (updating) return
        setUpdating(true)
        status.text = getString(R.string.route_updating)
        lifecycleScope.launch {
            try {
                val result = RemoteRouteUpdater.updateAllDetailed()
                if (isFinishing || isDestroyed) return@launch
                refreshDetails()
                showResult(result)
            } finally {
                if (!isFinishing && !isDestroyed) setUpdating(false)
            }
        }
    }

    private fun updateOne(key: String, name: String, detail: TextView, button: Button) {
        if (updating) return
        setUpdating(true)
        status.text = getString(R.string.route_updating)
        lifecycleScope.launch {
            try {
                val result = RemoteRouteUpdater.update(key)
                if (!isFinishing && !isDestroyed) {
                    detail.text = getString(R.string.route_configured_rules, result.count)
                    showMessage(getString(R.string.route_update_one_success, name, result.count))
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (!isFinishing && !isDestroyed) {
                    detail.text = getString(R.string.route_update_error_detail)
                    showMessage(getString(R.string.route_update_failed, name, error.message ?: "update failed"))
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setUpdating(false)
                    refreshDetails()
                }
            }
        }
    }

    private fun showResult(result: RemoteRouteUpdater.BatchResult) {
        val count = result.succeeded.sumOf { it.second }
        if (result.failed.isEmpty()) {
            showMessage(getString(R.string.route_update_success, result.succeeded.size, count))
        } else {
            val names = result.failed.joinToString(", ") { it.first }
            showMessage(getString(R.string.route_update_partial, result.succeeded.size, count, result.failed.size, names))
        }
    }

    private fun showMessage(message: String) {
        if (!isFinishing && !isDestroyed) {
            status.text = message
            MaterialAlertDialogBuilder(this).setTitle(R.string.route_update_result)
                .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
        }
    }
}
