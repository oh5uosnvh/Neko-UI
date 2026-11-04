package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.USER_AGENT

/**
 * REQ-07 multi-UA subscription User-Agent selector.
 *
 * The row shows a fixed summary (the free-form EditTextPreference input was
 * removed; the previously stored value is no longer used and not migrated).
 * Tapping the row or its trailing edit icon opens a dialog with the built-in
 * UA candidates; every enabled candidate is joined with ";" (in list order)
 * into the User-Agent sent with subscription requests. When all candidates
 * are disabled the default UA (ktx.USER_AGENT) is used.
 */
class UserAgentPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context, R.attr.editTextPreferenceStyle, android.R.attr.editTextPreferenceStyle
    )
) : Preference(context, attrs, defStyle) {

    public override fun notifyChanged() {
        super.notifyChanged()
    }

    init {
        widgetLayoutResource = R.layout.preference_user_agent_widget
    }

    override fun getSummary(): CharSequence? {
        return context.getString(R.string.user_agent_builtin_summary)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val edit = holder.findViewById(R.id.ua_edit)
        edit.setOnClickListener { showDialog() }
        edit.isEnabled = isEnabled
        edit.alpha = if (isEnabled) 1f else 0.38f
    }

    override fun onClick() {
        showDialog()
    }

    private fun showDialog() {
        // Preference.context is the preference's themed wrapper, never the host
        // Activity: tryToShow()'s `context as Activity` cast throws and the catch
        // swallows it, so the pencil appeared completely dead. Resolve the Activity
        // from the wrapper chain and show through it, keeping the themed context
        // for styling the dialog itself.
        val activity = generateSequence<android.content.Context>(context) {
            (it as? android.content.ContextWrapper)?.baseContext
        }.filterIsInstance<android.app.Activity>().firstOrNull() ?: return
        val context = context
        val flags = booleanArrayOf(
            DataStore.userAgentMihomo,
            DataStore.userAgentClashVerge,
            DataStore.userAgentNeko,
        )
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
        }
        val switches = ArrayList<SwitchMaterial>(userAgentCandidates().size)
        userAgentCandidates().forEachIndexed { index, ua ->
            val label = TextView(context).apply {
                text = ua
                maxLines = 2
                textSize = 16f
            }
            val switch = SwitchMaterial(context).apply {
                isChecked = flags[index]
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (48 * density).toInt()
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                addView(
                    label, LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                )
                addView(
                    switch, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                setOnClickListener { switch.toggle() }
            }
            container.addView(row)
            switches.add(switch)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.subscription_user_agent)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.userAgentMihomo = switches[0].isChecked
                DataStore.userAgentClashVerge = switches[1].isChecked
                DataStore.userAgentNeko = switches[2].isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .let { dialog ->
                if (!activity.isFinishing && !activity.isDestroyed) dialog.show()
            }
    }

    companion object {

        // REQ-07: built-in UA candidates, in fixed order.
        const val UA_MIHOMO = "mihomo.party/v2.0.0 (clash.meta)"
        const val UA_CLASH_VERGE = "clash-verge/v2.4.2"

        fun userAgentCandidates(): List<String> = listOf(UA_MIHOMO, UA_CLASH_VERGE, USER_AGENT)

        /**
         * Pure helper (unit-testable): joins every enabled candidate in list
         * order with ";". When all are disabled, falls back to the default
         * User-Agent ([USER_AGENT], the Nets.kt default string).
         */
        fun buildUserAgent(
            enabledFlags: List<Boolean>,
            defaults: List<String> = userAgentCandidates(),
        ): String {
            val enabled = enabledFlags.withIndex()
                .filter { it.value }
                .mapNotNull { defaults.getOrNull(it.index)?.takeIf(String::isNotBlank) }
            if (enabled.isNotEmpty()) {
                return enabled.joinToString(";")
            }
            return USER_AGENT
        }
    }
}
