package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.TagGroup
import io.nekohasekai.sagernet.database.TagGroupStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.dialogInputEditText

/** Shared trimmed-name policy; edit excludes itself and keeps internal whitespace/case. */
internal object TagGroupNames {
    fun error(input: String, tags: List<TagGroup>, editingId: Long? = null): Int? {
        val normalized = input.trim()
        if (normalized.isEmpty()) return R.string.tag_name_empty
        if (editingId != null && tags.any { it.id == editingId && it.name == normalized }) return null
        if (tags.any { it.id != editingId && it.name.trim() == normalized }) {
            return R.string.tag_name_exists
        }
        return null
    }
}

/** Neko's existing underlined, theme-aware input, not a custom outlined form. */
class TagGroupNameDialogFragment : DialogFragment() {
    private var input: EditText? = null
    private var saving = false
    private var generation = 0
    private var saveJob: kotlinx.coroutines.Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val editing = requireArguments().containsKey(ARG_ID)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (editing) R.string.tag_rename else R.string.tag_add)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
        val edit = builder.context.dialogInputEditText().apply {
            id = android.R.id.edit
            hint = getString(R.string.tag_name)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(savedInstanceState?.getString(STATE_TEXT)
                ?: requireArguments().getString(ARG_NAME).orEmpty())
            setSelection(text.length)
        }
        input = edit
        return builder.setView(edit).create()
    }

    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        alert.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { save() }
        input?.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { save(); true } else false
        }
        focusInput()
    }

    private fun focusInput() {
        val edit = input ?: return
        edit.requestFocus()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val imm = edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun save() {
        val edit = input ?: return
        if (saving) return
        val text = edit.text.toString()
        val id = arguments?.takeIf { it.containsKey(ARG_ID) }?.getLong(ARG_ID)
        // Empty input needs no DB call. Positive button never auto-dismisses on failure.
        if (text.trim().isEmpty()) { edit.error = getString(R.string.tag_name_empty); focusInput(); return }
        saving = true
        val token = generation
        val alert = dialog as? AlertDialog
        alert?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = false
        edit.error = null
        // Production is a child of TagGroupFragment: cancel IO with the list view as well
        // as onDismiss. A standalone restored/test dialog falls back to its own lifecycle.
        val scope = parentFragment?.viewLifecycleOwner?.lifecycleScope ?: lifecycleScope
        saveJob = scope.launch {
            var errorRes: Int? = null
            try {
                withContext(Dispatchers.IO) {
                    errorRes = TagGroupNames.error(text, TagGroupStore.tags(), id)
                    if (errorRes == null) {
                        if (id == null) TagGroupStore.create(text.trim())
                        else {
                            checkNotNull(TagGroupStore.get(id)) { "tag_missing" }
                            TagGroupStore.rename(id, text.trim())
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Store validation is authoritative for concurrent create/rename races.
                errorRes = when (failure.message) {
                    "标签组名称已存在" -> R.string.tag_name_exists
                    "标签组名称不能为空" -> R.string.tag_name_empty
                    "tag_missing" -> R.string.tag_missing
                    else -> R.string.tag_operation_failed
                }
            }
            if (token != generation || !isAdded || dialog?.isShowing != true || input !== edit) return@launch
            saving = false
            alert?.getButton(DialogInterface.BUTTON_POSITIVE)?.isEnabled = true
            if (errorRes != null) {
                edit.error = getString(errorRes!!)
                focusInput()
            } else {
                (parentFragment as? TagGroupFragment)?.onNameSaved(id != null)
                dismiss()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TEXT, input?.text?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDismiss(dialog: DialogInterface) {
        generation++ // Cancel/back must never receive a late positive-button UI callback.
        saveJob?.cancel()
        saveJob = null
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        generation++
        saving = false
        input = null
        super.onDestroyView()
    }

    companion object {
        const val DIALOG_TAG = "tag_group_name"
        private const val ARG_ID = "tag_name_id"
        private const val ARG_NAME = "tag_name_value"
        private const val STATE_TEXT = "tag_name_text"
        fun newInstance(id: Long? = null, name: String = "") = TagGroupNameDialogFragment().apply {
            arguments = Bundle().apply {
                if (id != null) putLong(ARG_ID, id)
                putString(ARG_NAME, name)
            }
        }
    }
}
