package moe.matsuri.nb4a.ui

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreferenceDialogFragmentCompat

/** AndroidX closes a preference dialog even when callChangeListener rejects its value. */
class UrlTestPreferenceDialog : EditTextPreferenceDialogFragmentCompat() {
    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        alert.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val urlTest = preference as UrlTestPreference
            if (urlTest.validateTimeout()) {
                // Keep AndroidX's normal URL persistence and cancel semantics.
                onClick(alert, DialogInterface.BUTTON_POSITIVE)
                dismiss()
            } else {
                urlTest.timeout?.requestFocus()
            }
        }
    }

    companion object {
        const val TAG = "moe.matsuri.nb4a.ui.UrlTestPreferenceDialog"

        fun newInstance(key: String, target: Fragment) = UrlTestPreferenceDialog().apply {
            arguments = android.os.Bundle().apply { putString(ARG_KEY, key) }
            setTargetFragment(target, 0)
        }
    }
}
