package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val isNight = (uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val insetController = WindowCompat.getInsetsController(window, window.decorView)
            insetController.isAppearanceLightNavigationBars = false
            insetController.isAppearanceLightStatusBars =
                if (DataStore.appTheme == Theme.BLACK) !isNight else false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            statusBarTopInset = bars.top
            applyStatusBarInset(view, bars.top)
            insets
        }
    }

    /** 当前状态栏顶部高度（px），供页面视图创建时补齐顶栏内边距 */
    private var statusBarTopInset = 0

    /**
     * 给层次中**所有** AppBarLayout 补上状态栏高度的顶部内边距。
     *
     * 原实现用 findViewById 只处理第一个 appbar：官方每次 replace() 时层次里
     * 只有一个 appbar，所以成立；主界面把页面改成缓存复用（hide/show）后，多个
     * 页面的 appbar 同时存在，findViewById 永远只命中第一个，后显示的页面顶栏
     * 就会顶进状态栏。这里改为递归处理，配合页面视图创建时的即时补正。
     */
    fun applyStatusBarInset(root: View? = null, topInset: Int = statusBarTopInset) {
        if (topInset <= 0) return
        val target = root ?: findViewById<View>(android.R.id.content) ?: return
        applyStatusBarInsetTo(target, topInset)
    }

    private fun applyStatusBarInsetTo(view: View, top: Int) {
        if (view.id == R.id.appbar && view.paddingTop != top) {
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyStatusBarInsetTo(view.getChildAt(index), top)
            }
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}
