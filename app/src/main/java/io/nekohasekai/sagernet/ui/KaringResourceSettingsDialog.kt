package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.assets.KaringResourceSettings
import io.nekohasekai.sagernet.assets.KaringRuleFamily

/**
 * Configure a resource switch before it can be measured.
 *
 * MaterialSwitch extends AppCompat's SwitchCompat. On Android 15 the latter
 * measures textOn/textOff whenever showText is true; a programmatically-created
 * switch has null labels and therefore reaches StaticLayout with null text.
 */
internal fun configureKaringResourceSwitch(
    control: MaterialSwitch,
    title: Int,
    checked: Boolean,
    change: (Boolean) -> Unit,
) {
    control.showText = false
    control.setText(title)
    control.isChecked = checked
    control.setOnCheckedChangeListener { _, value -> change(value) }
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
        fun addSwitch(title: Int, checked: Boolean, change: (Boolean) -> Unit): MaterialSwitch {
            val control = MaterialSwitch(context).apply {
                // AppCompat's SwitchCompat.measure path assumes textOn/textOff are non-null
                // when showText is enabled. MaterialSwitch inherits that path, and Android 15
                // reaches it even though these programmatic switches do not use ON/OFF labels.
                // Disable that optional label rendering before the first measure pass.
                showText = false
                textOn = ""
                textOff = ""
                setText(title)
                isChecked = checked
                setPadding(0, dp(12), 0, dp(12))
                setOnCheckedChangeListener { _, value -> change(value) }
            }
            content.addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return control
        }
        addSwitch(R.string.karing_resource_enable, options.enabled) { settings.setEnabled(family, it) }
        if (family != KaringRuleFamily.ACL) {
            addSwitch(R.string.karing_resource_auto_append, options.autoAppendRegion) { settings.setAutoAppendRegion(family, it) }
        }
        val remoteRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val remote = MaterialSwitch(context).apply {
            // Keep the same invariant for the remote-resource switch.
            showText = false
            textOn = ""
            textOff = ""
            setText(R.string.karing_resource_remote)
            isChecked = options.useRemote
            setOnCheckedChangeListener { _, value -> settings.setUseRemote(family, value) }
        }
        remoteRow.addView(remote, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        remoteRow.addView(AppCompatImageButton(context).apply {
            setImageResource(R.drawable.ic_baseline_info_24)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = getString(R.string.karing_resource_remote_info)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                MaterialAlertDialogBuilder(context).setTitle(R.string.karing_resource_remote)
                    .setMessage(KaringResourceSettings.remoteBase(family))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(remoteRow)
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
