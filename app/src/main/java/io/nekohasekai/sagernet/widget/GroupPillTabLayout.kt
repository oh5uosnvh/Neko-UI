package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.tabs.TabLayout
import io.nekohasekai.sagernet.R

/** During a click crossfade the coordinator, not Mediator, owns horizontal centering. */
class GroupPillTabLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = R.attr.tabStyle,
) : TabLayout(context, attrs, defStyleAttr) {
    var manualCentering = false
    override fun setScrollPosition(position: Int, offset: Float, updateSelectedText: Boolean) {
        if (!manualCentering) super.setScrollPosition(position, offset, updateSelectedText)
    }
    override fun setScrollPosition(position: Int, offset: Float, updateSelectedText: Boolean,
                                   updateIndicator: Boolean) {
        if (!manualCentering) super.setScrollPosition(position, offset, updateSelectedText, updateIndicator)
    }
    override fun selectTab(tab: Tab?, updateIndicator: Boolean) {
        // Preserve selected Tab metadata/callbacks, but do not launch a competing scroll animator.
        super.selectTab(tab, updateIndicator && !manualCentering)
    }
}
