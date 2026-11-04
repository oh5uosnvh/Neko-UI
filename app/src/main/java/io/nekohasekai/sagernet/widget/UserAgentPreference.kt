package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
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
 * REQ-07/REQ-08 multi-UA subscription User-Agent selector.
 *
 * Two interaction areas on one row:
 *  - Pencil icon (R.id.ua_edit, the edit area): opens the built-in UA
 *    candidate switch dialog; every enabled candidate is joined with ";"
 *    (in list order) into the User-Agent sent with subscription requests.
 *    When all candidates are disabled the default UA (ktx.USER_AGENT) is used.
 *  - Tapping the row itself (non-pencil area): opens a free-form custom UA
 *    input dialog. The value is stored globally (Key.CUSTOM_USER_AGENT) and
 *    wins over the built-in switches whenever it is non-blank.
 *
 * Summary: shows the (truncated) custom UA when set, otherwise the built-in
 * summary string.
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
        // REQ-08: custom UA (non-blank) is what actually gets sent, so show it;
        // otherwise describe the built-in switches behaviour.
        val custom = DataStore.customUserAgent
        if (!custom.isNullOrBlank()) {
            return if (custom.length <= SUMMARY_MAX_LENGTH) custom
            else custom.take(SUMMARY_MAX_LENGTH) + "…"
        }
        return context.getString(R.string.user_agent_builtin_summary)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val edit = holder.findViewById(R.id.ua_edit)
        // Pencil (edit area) keeps the built-in UA candidate switch dialog.
        edit.setOnClickListener { showDialog() }
        edit.isEnabled = isEnabled
        edit.alpha = if (isEnabled) 1f else 0.38f
    }

    override fun onClick() {
        // Tapping the row itself (outside the pencil) opens the free-form
        // custom UA input dialog (REQ-08).
        showCustomUserAgentDialog()
    }

    private fun showDialog() {
        // Preference.context is the preference's themed wrapper, never the host
        // Activity: tryToShow()'s `context as Activity` cast throws and the catch
        // swallows it, so the pencil appeared completely dead. Resolve the Activity
        // from the wrapper chain and show through it, keeping the themed context
        // for styling the dialog itself.
        val activity = resolveActivity() ?: return
        val context = context
        // Flags follow userAgentCandidates() order: clash-verge, mihomo, neko.
        val flags = booleanArrayOf(
            DataStore.userAgentClashVerge,
            DataStore.userAgentMihomo,
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
                DataStore.userAgentClashVerge = switches[0].isChecked
                DataStore.userAgentMihomo = switches[1].isChecked
                DataStore.userAgentNeko = switches[2].isChecked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .let { dialog ->
                if (!activity.isFinishing && !activity.isDestroyed) dialog.show()
            }
    }

    /**
     * REQ-08: free-form custom UA dialog opened by tapping the row. Multi-line
     * EditText prefilled with the current global custom UA; OK persists it.
     * Empty input clears the custom UA so the built-in switches take over.
     */
    private fun showCustomUserAgentDialog() {
        val activity = resolveActivity() ?: return
        val context = context
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), 0)
        }
        val input = EditText(context).apply {
            setText(DataStore.customUserAgent)
            hint = context.getString(R.string.user_agent_custom_hint)
            // Multi-line: UA strings can get long; newlines are never valid in
            // a header value, so strip them on save.
            minLines = 1
            maxLines = 4
            setSingleLine(false)
        }
        container.addView(
            input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.subscription_user_agent)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Header values must not contain line breaks; collapse them so a
                // stray newline can never split the User-Agent header.
                DataStore.customUserAgent =
                    input.text.toString().lines().joinToString(" ").trim()
                notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .let { dialog ->
                if (!activity.isFinishing && !activity.isDestroyed) dialog.show()
            }
    }

    private fun resolveActivity(): android.app.Activity? {
        return generateSequence<android.content.Context>(context) {
            (it as? android.content.ContextWrapper)?.baseContext
        }.filterIsInstance<android.app.Activity>().firstOrNull()
    }

    companion object {

        // REQ-07: built-in UA candidates, in fixed order. clash-verge first
        // (many airports switch response format on a leading "clash" UA),
        // mihomo (clash.meta) second, NekoBox default last as fallback.
        const val UA_MIHOMO = "mihomo.party/v2.0.0 (clash.meta)"
        const val UA_CLASH_VERGE = "clash-verge/v2.4.2"

        fun userAgentCandidates(): List<String> = listOf(UA_CLASH_VERGE, UA_MIHOMO, USER_AGENT)

        private const val SUMMARY_MAX_LENGTH = 64

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
