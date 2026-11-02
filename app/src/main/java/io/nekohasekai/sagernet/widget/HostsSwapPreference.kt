package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import androidx.core.content.res.TypedArrayUtils
import androidx.core.view.ViewCompat
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R

/** Keeps the row's editor and its trailing mapping action as separate click targets. */
class HostsSwapPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, R.attr.editTextPreferenceStyle, android.R.attr.editTextPreferenceStyle,
    ),
    defStyleRes: Int = 0,
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    var onSwap: () -> Unit = {}

    var mapped: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    var canSwap: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyChanged()
        }

    init {
        widgetLayoutResource = R.layout.layout_hosts_swap_widget
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val button = holder.findViewById(R.id.hosts_swap) as Button
        button.isEnabled = isEnabled && canSwap
        button.isActivated = mapped
        button.alpha = if (button.isEnabled) 1f else 0.38f
        val colors = context.obtainStyledAttributes(
            intArrayOf(if (mapped) R.attr.colorPrimary else android.R.attr.textColorSecondary),
        )
        try {
            colors.getColorStateList(0)?.let { button.setTextColor(it) }
        } finally {
            colors.recycle()
        }
        button.contentDescription = context.getString(
            when {
                !canSwap -> R.string.subscription_hosts_swap
                mapped -> R.string.subscription_hosts_restore
                else -> R.string.subscription_hosts_apply
            },
        )
        ViewCompat.setStateDescription(
            button, context.getString(
                when {
                    !canSwap -> R.string.subscription_dns_unset
                    mapped -> R.string.subscription_hosts_mapping_on
                    else -> R.string.subscription_hosts_mapping_off
                },
            ),
        )
        button.setOnClickListener {
            if (isEnabled && canSwap) onSwap()
        }
    }
}
