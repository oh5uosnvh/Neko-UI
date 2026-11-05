package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.R

/** Same persistence, dependency and native Switch listener as AndroidX; only scopes binding. */
open class ReferenceSwitchPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, R.attr.switchPreferenceStyle, android.R.attr.switchPreferenceStyle,
    ),
    defStyleRes: Int = 0,
) : SwitchPreference(context, attrs, defStyleAttr, defStyleRes) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        val widget = holder.findViewById(android.R.id.switch_widget) as? ReferenceSwitch
        if (widget == null) super.onBindViewHolder(holder)
        else widget.bind(this) { super.onBindViewHolder(holder) }
    }
}
