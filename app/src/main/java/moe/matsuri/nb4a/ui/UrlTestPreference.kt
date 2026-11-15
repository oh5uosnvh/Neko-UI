package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.res.TypedArrayUtils
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import io.nekohasekai.sagernet.bg.UrlTestTimeout
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

class UrlTestPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    ),
    defStyleRes: Int = 0
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    var concurrent: EditText? = null
    var timeout: EditText? = null

    /** Also called by the dialog before dismissal, so invalid input stays editable. */
    internal fun validateTimeout(): Boolean {
        val field = timeout ?: return false
        val valid = UrlTestTimeout.parseMillis(field.text?.toString().orEmpty()) != null
        field.error = if (valid) null else context.getString(
            R.string.connection_test_timeout_invalid, UrlTestTimeout.MIN_MILLIS,
        )
        return valid
    }

    init {
        dialogLayoutResource = R.layout.layout_urltest_preference_dialog

        setOnBindEditTextListener {
            concurrent = it.rootView.findViewById(R.id.edit_concurrent)
            concurrent?.apply {
                setText(DataStore.connectionTestConcurrent.toString())
            }
            timeout = it.rootView.findViewById(R.id.edit_timeout)
            timeout?.apply {
                hint = context.getString(R.string.connection_test_timeout_hint, UrlTestTimeout.MIN_MILLIS)
                setText(DataStore.connectionTestTimeout.toString())
                doAfterTextChanged { validateTimeout() }
            }
            it.rootView.findViewById<LinearLayout>(R.id.concurrent_layout)?.isVisible = true
            it.rootView.findViewById<LinearLayout>(R.id.timeout_layout)?.isVisible = true
        }

        setOnPreferenceChangeListener { _, _ ->
            // Reject the whole edit before writing concurrency or the URL as well.
            // Never reinterpret decimal seconds or silently reset malformed input.
            if (!validateTimeout()) return@setOnPreferenceChangeListener false
            val newTimeout = UrlTestTimeout.parseMillis(timeout?.text?.toString().orEmpty())
                ?: return@setOnPreferenceChangeListener false
            concurrent?.apply {
                var newConcurrent = text?.toString()?.toIntOrNull()
                if (newConcurrent == null || newConcurrent <= 0) {
                    newConcurrent = 5
                }
                DataStore.connectionTestConcurrent = newConcurrent
            }
            DataStore.connectionTestTimeout = newTimeout
            true
        }
    }

}
