package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import androidx.appcompat.graphics.drawable.DrawableWrapperCompat
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R

/** Inflation fallback; actual row drawables are sanitized by [withoutSettingsRowPress]. */
fun settingsRowsInflater(
    inflater: LayoutInflater,
    parent: android.view.ViewGroup,
): LayoutInflater = inflater.cloneInContext(
    ContextThemeWrapper(parent.context, R.style.ThemeOverlay_SagerNet_SettingsHighlight)
)

/**
 * Only installed on settings/edit Preference lists, never cards, navigation or popup menus.
 * AndroidX caches the original row background in PreferenceViewHolder.resetState(), so an
 * inflation overlay (or a one-off removal) is insufficient. Sanitize attached rows again
 * before drawing a rebound holder, including nested widget buttons such as the UA pencil.
 */
fun RecyclerView.withoutSettingsRowPress(): RecyclerView = apply {
    val cleaner = object : RecyclerView.OnChildAttachStateChangeListener,
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private var observer: ViewTreeObserver? = null
        private fun clean() {
            for (index in 0 until childCount) clearSettingsRowPress(getChildAt(index))
        }
        override fun onChildViewAttachedToWindow(view: View) = clearSettingsRowPress(view)
        override fun onChildViewDetachedFromWindow(view: View) = Unit
        override fun onPreDraw(): Boolean { clean(); return true }
        override fun onViewAttachedToWindow(view: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = viewTreeObserver.also { it.addOnPreDrawListener(this) }
            clean()
        }
        override fun onViewDetachedFromWindow(view: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
        }
    }
    addOnChildAttachStateChangeListener(cleaner)
    addOnAttachStateChangeListener(cleaner)
    if (isAttachedToWindow) cleaner.onViewAttachedToWindow(this)
}

internal fun clearSettingsRowPress(view: View) {
    // EditText's selector is its underline/focus/error affordance, not a row ripple.
    if (view is EditText) return
    view.background?.let { background ->
        val quiet = quietSettingsDrawable(background)
        if (quiet !== background) {
            val left = view.paddingLeft
            val top = view.paddingTop
            val right = view.paddingRight
            val bottom = view.paddingBottom
            view.background = quiet
            view.setPadding(left, top, right, bottom)
        }
    }
    if (Build.VERSION.SDK_INT >= 23) {
        view.foreground?.let { foreground ->
            val quiet = quietSettingsDrawable(foreground)
            if (quiet !== foreground) view.foreground = quiet
        }
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) clearSettingsRowPress(view.getChildAt(index))
    }
}

private val transparentSettingsRipple = ColorStateList.valueOf(Color.TRANSPARENT)

private fun quietSettingsDrawable(drawable: Drawable): Drawable {
    if (drawable is SettingsIdleDrawable) return drawable
    // Mutate before changing tint/state: shared card/menu drawable ConstantStates stay intact.
    val isolated = drawable.mutate()
    if (isolated is RippleDrawable) isolated.setColor(transparentSettingsRipple)
    if (isolated is LayerDrawable) {
        for (index in 0 until isolated.numberOfLayers) {
            val child = isolated.getDrawable(index)
            // Keep content, masks, inset geometry and selected/checked state layers.
            if (child is RippleDrawable) child.mutate().let {
                (it as RippleDrawable).setColor(transparentSettingsRipple)
            }
        }
    }
    return if (isolated.isStateful || isolated is RippleDrawable) SettingsIdleDrawable(isolated)
    else isolated
}

/** Keep enabled/checked/selected/focus state and normal surface, filter only touch press. */
private class SettingsIdleDrawable(drawable: Drawable) : DrawableWrapperCompat(drawable) {
    override fun setState(stateSet: IntArray): Boolean =
        super.setState(stateSet.filter { it != android.R.attr.state_pressed }.toIntArray())
}
