package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.assets.KaringResourceSettings
import io.nekohasekai.sagernet.assets.KaringRuleFamily

private const val SWITCH_TRACK_WIDTH_DP = 34
private const val SWITCH_TRACK_HEIGHT_DP = 14
private const val SWITCH_THUMB_SIZE_DP = 20

/**
 * A SwitchCompat row whose label is deliberately independent of the switch.
 *
 * MaterialSwitch's optional on/off text path has device/theme-specific behavior
 * on Android 15. The resource dialog does not need that text, so keeping the
 * label in a TextView also prevents SwitchCompat from measuring a null label.
 */
internal fun createKaringResourceSwitchRow(
    context: Context,
    title: Int,
    checked: Boolean,
    change: (Boolean) -> Unit,
): View {
    val row = LayoutInflater.from(context).inflate(
        R.layout.karing_resource_switch_row,
        null,
        false,
    )
    row.findViewById<TextView>(R.id.karing_resource_switch_label).setText(title)
    val control = row.findViewById<SwitchCompat>(R.id.karing_resource_switch_control)
    configureKaringResourceSwitch(context, control, checked, change)
    return row
}

/** Configure a non-null, tinted thumb/track before the first measure pass. */
internal fun configureKaringResourceSwitch(
    context: Context,
    control: SwitchCompat,
    checked: Boolean,
    change: (Boolean) -> Unit,
) {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density + .5f).toInt()

    val activated = themeColor(context, androidx.appcompat.R.attr.colorControlActivated, Color.WHITE)
    val normal = themeColor(context, androidx.appcompat.R.attr.colorControlNormal, Color.GRAY)
    val thumbColors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(activated, normal),
    )
    val trackColors = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(withAlpha(activated, 0x80), withAlpha(normal, 0x66)),
    )
    val thumb = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setSize(dp(SWITCH_THUMB_SIZE_DP), dp(SWITCH_THUMB_SIZE_DP))
    }
    val track = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(SWITCH_TRACK_HEIGHT_DP / 2).toFloat()
        setSize(dp(SWITCH_TRACK_WIDTH_DP), dp(SWITCH_TRACK_HEIGHT_DP))
    }
    DrawableCompat.setTintList(thumb, thumbColors)
    DrawableCompat.setTintList(track, trackColors)
    control.apply {
        // Keep these non-null even if a future theme supplies an incomplete style.
        thumbDrawable = thumb
        trackDrawable = track
        showText = false
        textOn = ""
        textOff = ""
        minWidth = dp(48)
        minimumHeight = dp(48)
        isChecked = checked
        setOnCheckedChangeListener { _, value -> change(value) }
    }
}

private fun themeColor(context: Context, attribute: Int, fallback: Int): Int {
    val value = TypedValue()
    return if (context.theme.resolveAttribute(attribute, value, true)) {
        when {
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> AppCompatResources.getColorStateList(context, value.resourceId)?.defaultColor
                ?: fallback
            else -> fallback
        }
    } else {
        fallback
    }
}

private fun withAlpha(color: Int, alpha: Int): Int = ColorUtilsCompat.setAlphaComponent(color, alpha)

/** Small local equivalent of ColorUtils.setAlphaComponent; avoids another dependency. */
private object ColorUtilsCompat {
    fun setAlphaComponent(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
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
        val remoteRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        remoteRow.addView(
            createKaringResourceSwitchRow(
                context,
                R.string.karing_resource_remote,
                options.useRemote,
            ) { settings.setUseRemote(family, it) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
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
