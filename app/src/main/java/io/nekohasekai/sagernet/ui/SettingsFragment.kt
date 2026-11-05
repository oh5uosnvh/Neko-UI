package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ListListener

class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    /** Explicit reset only: cached navigation keeps its existing scroll position. */
    override fun resetScrollState() {
        val prefFrag = childFragmentManager.findFragmentById(R.id.settings) as? androidx.preference.PreferenceFragmentCompat
        prefFrag?.listView?.scrollToPosition(0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.settings)

        // Finish the nested page before the parent is measured/animated. A posted
        // transaction on the activity manager inflated every preference mid-slide,
        // and could leave an orphaned settings view across cached-page recreation.
        if (childFragmentManager.findFragmentById(R.id.settings) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.settings, SettingsPreferenceFragment())
                .commitNow()
        }
    }

}