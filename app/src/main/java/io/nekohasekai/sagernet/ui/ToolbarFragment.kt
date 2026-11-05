package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationOnClickListener { anchor ->
            (activity as MainActivity).openNavigationMenu(anchor)
        }
    }

    /** Explicit reset hook only; cached menu navigation must retain every page's scroll state. */
    open fun resetScrollState() {}

    override fun onCreateAnimator(transit: Int, enter: Boolean, nextAnim: Int): Animator? =
        view?.let { (activity as? MainActivity)?.createPageAnimator(it, enter, nextAnim) }
            ?: super.onCreateAnimator(transit, enter, nextAnim)

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
