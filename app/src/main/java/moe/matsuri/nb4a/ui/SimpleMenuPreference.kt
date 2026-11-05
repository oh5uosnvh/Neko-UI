/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.preference.DropDownPreference
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ChoiceMenuPopup
import io.nekohasekai.sagernet.widget.ReselectableSpinner
import io.nekohasekai.sagernet.widget.ThemedChoiceAdapter

/**
 * Bend [DropDownPreference] to support
 * [Simple Menus](https://material.google.com/components/menus.html#menus-behavior).
 */


open class SimpleMenuPreference
@JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    defStyleRes: Int = 0
) : DropDownPreference(context!!, attrs, defStyleAttr, defStyleRes) {

    private lateinit var mAdapter: ThemedChoiceAdapter
    private var boundSpinner: Spinner? = null
    private var choicePopup: ChoiceMenuPopup? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        choicePopup?.cancelImmediately()
        val spinner = holder.itemView.findViewById<Spinner>(R.id.spinner)
        spinner.onItemSelectedListener = null
        super.onBindViewHolder(holder)
        boundSpinner = spinner
        spinner.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        // Keep DropDownPreference's adapter/selection API, but never treat layout-driven Spinner
        // selection as user input. The rounded popup below owns clicks and their completion.
        spinner.onItemSelectedListener = null
    }

    override fun onClick() {
        val spinner = boundSpinner ?: return
        if (choicePopup != null || !spinner.isAttachedToWindow || !isEnabled) return
        val values = entryValues?.map { it.toString() } ?: return
        if (mAdapter.isEmpty || values.isEmpty()) return
        val previousValue = value
        // OutboundPreference installs a specialized listener (including profile reselection).
        // Deliver to that existing hook once, AFTER exit, without mechanically selecting Spinner.
        val selectionListener = spinner.onItemSelectedListener
        val reselectable = spinner as? ReselectableSpinner
        val popupClosed = reselectable?.onPopupClosed
        spinner.onItemSelectedListener = null
        reselectable?.onPopupClosed = null
        lateinit var menu: ChoiceMenuPopup
        menu = ChoiceMenuPopup(spinner, mAdapter, findIndexOfValue(value)) { position ->
            if (choicePopup === menu) choicePopup = null
            try {
                if (position != null && boundSpinner === spinner && spinner.isAttachedToWindow &&
                    isEnabled && value == previousValue && entryValues?.map { it.toString() } == values) {
                    val newValue = values.getOrNull(position)
                    if (newValue != null) {
                        if (selectionListener != null) {
                            selectionListener.onItemSelected(spinner, null, position,
                                mAdapter.getItemId(position))
                        } else if (newValue != value && callChangeListener(newValue)) {
                            value = newValue
                        }
                    }
                }
            } finally {
                // Clear subclass reselect gates on both selection and cancellation. Restore the
                // persisted position (also after a veto) without generating another user action.
                popupClosed?.invoke()
                if (boundSpinner === spinner && spinner.isAttachedToWindow) {
                    reselectable?.onPopupClosed = popupClosed
                    spinner.onItemSelectedListener = null
                    spinner.setSelection(findIndexOfValue(value))
                    spinner.onItemSelectedListener = selectionListener
                }
            }
        }
        choicePopup = menu
        menu.show()
    }

    override fun onDetached() {
        val spinner = boundSpinner
        boundSpinner = null
        choicePopup?.cancelImmediately()
        spinner?.onItemSelectedListener = null
        (spinner as? ReselectableSpinner)?.onPopupClosed = null
        super.onDetached()
    }

    override fun createAdapter(): ArrayAdapter<CharSequence?> {
        mAdapter = ThemedChoiceAdapter(getContext()) { findIndexOfValue(value) }
        return mAdapter
    }

    override fun setValue(value: String?) {
        super.setValue(value)
        if (::mAdapter.isInitialized) mAdapter.notifyDataSetChanged()
    }
}
