package moe.matsuri.nb4a.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.widget.EditText
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import java.util.Locale

/** Decimal card-drag speed in the inclusive range 1.0–10.0. */
class DragScrollSpeedPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Preference(context, attrs) {
    private fun currentValue(): Float {
        val asText = getPersistedString(null)?.toFloatOrNull()
        val legacy = getPersistedInt(DEFAULT_VALUE.toInt()).toFloat()
        return (asText ?: legacy).coerceIn(MIN_VALUE, MAX_VALUE)
    }
    private fun display(value: Float) = String.format(Locale.US, "%.1f", value)

    init {
        summaryProvider = Preference.SummaryProvider<DragScrollSpeedPreference> { pref ->
            pref.context.getString(R.string.drag_scroll_speed_summary, pref.display(pref.currentValue()))
        }
    }

    override fun onClick() {
        val input = dialogInputEditText().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(display(currentValue()))
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val typed = input.text.toString().trim().replace(',', '.').toFloatOrNull()
                    ?: return@setPositiveButton
                val clamped = typed.coerceIn(MIN_VALUE, MAX_VALUE)
                if (clamped != typed) Toast.makeText(context,
                    context.getString(R.string.drag_scroll_speed_range_toast, MIN_VALUE, MAX_VALUE),
                    Toast.LENGTH_SHORT).show()
                if (shouldPersist()) persistString(display(clamped))
                notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    companion object {
        const val MIN_VALUE = 1f
        const val MAX_VALUE = 10f
        const val DEFAULT_VALUE = 5f
    }
}
