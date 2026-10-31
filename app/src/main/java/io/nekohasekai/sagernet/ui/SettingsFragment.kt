package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.widget.ListListener

class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    /** 问题1-B：切回设置页时回到顶部（官方重建观感） */
    override fun resetScrollState() {
        val prefFrag = childFragmentManager.findFragmentById(R.id.settings) as? androidx.preference.PreferenceFragmentCompat
            ?: parentFragmentManager.findFragmentById(R.id.settings) as? androidx.preference.PreferenceFragmentCompat
        prefFrag?.listView?.scrollToPosition(0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.settings)

        parentFragmentManager.beginTransaction()
            .replace(R.id.settings, SettingsPreferenceFragment())
            .commitAllowingStateLoss()
    }

}