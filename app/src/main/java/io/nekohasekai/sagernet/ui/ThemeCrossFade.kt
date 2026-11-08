package io.nekohasekai.sagernet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.view.doOnPreDraw

/** HTML theme-transition equivalent: switch first, fade the old frame only. */
internal object ThemeCrossFade {
    const val DURATION_MS = 420L
    private var pending: Bitmap? = null

    /** Capture before Activity.recreate; the old decor itself will be destroyed. */
    fun transition(activity: androidx.appcompat.app.AppCompatActivity, apply: () -> Unit) {
        val root = activity.window.decorView as? ViewGroup
        if (root != null && root.width > 0 && root.height > 0) {
            pending?.recycle()
            pending = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(pending!!))
        }
        apply()
    }

    /** Called by the recreated Activity after its new content is attached. */
    fun consumeAndFade(root: ViewGroup) {
        val old = pending ?: return
        pending = null
        val overlay = ImageView(root.context).apply {
            setImageBitmap(old)
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
            alpha = 1f
            translationZ = 1000f
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        root.addView(overlay)
        overlay.animate().alpha(0f).setDuration(DURATION_MS).setInterpolator(LinearInterpolator())
            .withEndAction {
                root.removeView(overlay)
                overlay.setImageDrawable(null)
                old.recycle()
            }.start()
    }
}
