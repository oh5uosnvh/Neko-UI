package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.assets.KaringResourceSettings
import io.nekohasekai.sagernet.assets.KaringRuleFamily

/** Use the same theme-provided SwitchCompat styling as the app's other switches. */
internal fun createKaringResourceSwitchRow(
    context: Context,
    title: Int,
    checked: Boolean,
    change: (Boolean) -> Unit,
): View {
    val row = LayoutInflater.from(context).inflate(R.layout.karing_resource_switch_row, null, false)
    row.findViewById<TextView>(R.id.karing_resource_switch_label).setText(title)
    row.findViewById<SwitchCompat>(R.id.karing_resource_switch_control).apply {
        showText = false
        textOn = ""
        textOff = ""
        contentDescription = context.getString(title)
        isChecked = checked
        setOnCheckedChangeListener { _, value -> change(value) }
    }
    return row
}

class KaringResourceSettingsDialog : DialogFragment() {
    companion object {
        fun newInstance(family: KaringRuleFamily) = KaringResourceSettingsDialog().apply {
            arguments = Bundle().apply { putString("family", family.wireName) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val family = KaringRuleFamily.parse(requireArguments().getString("family").orEmpty())
            ?: throw IllegalArgumentException("Unknown resource family")
        val settings = KaringResourceSettings(context)
        val options = settings.get(family)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun addSwitch(title: Int, checked: Boolean, change: (Boolean) -> Unit) {
            content.addView(
                createKaringResourceSwitchRow(context, title, checked, change),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addSwitch(R.string.karing_resource_enable, options.enabled) { settings.setEnabled(family, it) }
        if (family != KaringRuleFamily.ACL) {
            addSwitch(R.string.karing_resource_auto_append, options.autoAppendRegion) {
                settings.setAutoAppendRegion(family, it)
            }
        }
        val remoteRow = createKaringResourceSwitchRow(
            context, R.string.karing_resource_remote, options.useRemote,
        ) { settings.setUseRemote(family, it) } as LinearLayout
        val infoButton = AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_baseline_info_24)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = getString(R.string.karing_resource_remote_info)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                MaterialAlertDialogBuilder(context).setTitle(R.string.karing_resource_remote)
                    .setMessage(KaringResourceSettings.remoteBase(family))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
        // 文字与感叹号紧贴；空白区域位于感叹号和开关之间。
        remoteRow.addView(infoButton, 1, LinearLayout.LayoutParams(dp(40), dp(48)))
        content.addView(remoteRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        val title = when (family) {
            KaringRuleFamily.GEOSITE -> "GeoSite"
            KaringRuleFamily.GEOIP -> "GeoIP"
            KaringRuleFamily.ACL -> "ACL"
        }
        return MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(R.string.karing_resource_reconnect_hint)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }
}
