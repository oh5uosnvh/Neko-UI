package io.nekohasekai.sagernet.widget

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import android.widget.PopupWindow
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.ListPopupWindow
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.doOnPreDraw
import java.lang.reflect.Field
import java.util.WeakHashMap

/** Android equivalent of the FlClash pre2 menu's anchor-grown opening spring. */
object PopupEntrance {
    internal const val DURATION_MS = 450L
    // Keep the menu content visible while the platter grows; a tiny 0.01
    // starting scale made a screenshot during the 450ms spring look empty.
    private const val INITIAL_SCALE = .82f
    private const val STIFFNESS = 400.0
    private const val DAMPING_RATIO = .754

    internal fun springProgress(seconds: Double): Float {
        val natural = sqrt(STIFFNESS)
        val damped = natural * sqrt(1.0 - DAMPING_RATIO * DAMPING_RATIO)
        return (1.0 - exp(-DAMPING_RATIO * natural * seconds) *
            (cos(damped * seconds) + DAMPING_RATIO * natural / damped * sin(damped * seconds))).toFloat()
    }
    private val running = WeakHashMap<View, ValueAnimator>()
    private val waiting = WeakHashMap<View, View.OnAttachStateChangeListener>()

    /**
     * Reveal the attached popup surface from its anchor edge. The caller must invoke this
     * after the popup window is shown; doing it before attachment would expose one full-size
     * frame and makes the platform window animation visibly overlap this reveal.
     */
    fun animate(view: View, aboveAnchor: Boolean = false, duration: Long = DURATION_MS,
                anchor: View? = null, anchorPoint: Pair<Int, Int>? = null) {
        stop(view)
        if (!view.isAttachedToWindow) {
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    waiting.remove(v)
                    v.post { if (v.isAttachedToWindow) animate(v, aboveAnchor, duration, anchor, anchorPoint) }
                }
                override fun onViewDetachedFromWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    waiting.remove(v)
                    reset(v)
                }
            }
            waiting[view] = listener
            view.addOnAttachStateChangeListener(listener)
            return
        }
        if (view.width == 0 || view.height == 0) {
            view.doOnPreDraw { if (it.isAttachedToWindow) animate(it, aboveAnchor, duration, anchor, anchorPoint) }
            return
        }
        // Keep labels visible throughout the morph. FlClash's platter hides its
        // source snapshot, but this Android popup has no captured source image;
        // fading the whole surface would produce a blank menu during the spring.
        view.alpha = 1f
        view.scaleX = INITIAL_SCALE
        view.scaleY = INITIAL_SCALE
        val origin = IntArray(2)
        val anchorOrigin = IntArray(2)
        if (anchorPoint != null && view.width > 0 && view.height > 0) {
            view.getLocationOnScreen(origin)
            view.pivotX = (anchorPoint.first - origin[0]).toFloat().coerceIn(0f, view.width.toFloat())
            view.pivotY = (anchorPoint.second - origin[1]).toFloat().coerceIn(0f, view.height.toFloat())
        } else if (anchor != null && anchor.isAttachedToWindow && view.width > 0 && view.height > 0) {
            view.getLocationOnScreen(origin)
            anchor.getLocationOnScreen(anchorOrigin)
            view.pivotX = (anchorOrigin[0] + anchor.width / 2f - origin[0]).coerceIn(0f, view.width.toFloat())
            view.pivotY = (anchorOrigin[1] + anchor.height / 2f - origin[1]).coerceIn(0f, view.height.toFloat())
        } else {
            view.pivotX = view.width / 2f
            view.pivotY = if (aboveAnchor) view.height.toFloat() else 0f
        }
        lateinit var animator: ValueAnimator
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                if (running[v] === animator) running.remove(v)
                animator.removeAllListeners()
                animator.removeAllUpdateListeners()
                animator.cancel()
            }
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                val spring = springProgress(fraction * duration / 1000.0)
                // Do not fade the entire content tree: unlike FlClash's
                // source-image platter, Android has no snapshot underlay.
                view.alpha = 1f
                view.scaleX = (INITIAL_SCALE + (1f - INITIAL_SCALE) * spring).coerceAtLeast(.001f)
                view.scaleY = view.scaleX
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.removeOnAttachStateChangeListener(attachListener)
                    if (running[view] === animator) running.remove(view)
                    reset(view)
                }
            })
        }
        view.addOnAttachStateChangeListener(attachListener)
        running[view] = animator
        animator.start()
    }

    /** Cancel a reveal when its owning popup is dismissed/rebound. */
    fun cancel(view: View) = stop(view)

    /**
     * Remove the framework WindowManager animation from a ListPopupWindow. The caller owns
     * the content reveal, so Android's window transition must not run underneath it.
     */
    fun disableWindowAnimation(popup: ListPopupWindow) {
        popup.animationStyle = 0
        popupWindowOf(popup)?.apply {
            animationStyle = 0
            enterTransition = null
            exitTransition = null
        }
    }

    /** Configure the real list before showing a PopupMenu/MenuPopupHelper. */
    fun prepare(helper: MenuPopupHelper) {
        try {
            popupOf(helper)?.let(::disableMenuPopupWindows)
        } catch (_: Throwable) {
            // AppCompat keeps these implementation details private on some versions.
            // The normal themed popup remains the safe fallback in that case.
        }
    }

    /** Reveal the actual menu list after its window has attached. */
    fun reveal(helper: MenuPopupHelper) {
        try {
            popupOf(helper)?.let(::disableMenuPopupWindows)
            val anchor = findField(helper.javaClass, "mAnchorView")?.get(helper) as? View
            helper.listView?.let { animate(popupSurface(it), anchor = anchor) }
        } catch (_: Throwable) {
            // Best effort: menu content and behavior must remain available if internals change.
        }
    }

    /** Same preparation/reveal path for the rare PopupMenu fallback. */
    fun show(popup: PopupMenu) {
        val helper = popupHelperOf(popup)
        if (helper != null) prepare(helper)
        popup.show()
        if (helper != null) reveal(helper)
    }

    /** Disable the framework animation and reveal a standalone ListPopupWindow. */
    fun showAndReveal(popup: ListPopupWindow, aboveAnchor: Boolean = false) {
        disableWindowAnimation(popup)
        popup.show()
        popup.listView?.let { animate(popupSurface(it), aboveAnchor, anchor = popup.anchorView) }
    }

    /** Animate the PopupWindow content root, including its background and padding. */
    private fun popupSurface(view: View): View {
        var current = view
        while (current.parent is ViewGroup) {
            val parent = current.parent as ViewGroup
            if (parent.javaClass.name.contains("PopupDecorView")) break
            current = parent
        }
        return current
    }

    private fun disableMenuPopupWindows(menuPopup: Any) {
        // StandardMenuPopup owns one MenuPopupWindow as mPopup.
        findField(menuPopup.javaClass, "mPopup")?.get(menuPopup)?.let { value ->
            if (value is ListPopupWindow) disableWindowAnimation(value)
        }
        // CascadingMenuPopup creates one MenuPopupWindow per visible submenu. Their style
        // is already supplied before show; this also handles a submenu opened later.
        val showing = findField(menuPopup.javaClass, "mShowingMenus")?.get(menuPopup)
        if (showing is Iterable<*>) for (info in showing) {
            if (info == null) continue
            findField(info.javaClass, "window")?.get(info)?.let { value ->
                if (value is ListPopupWindow) disableWindowAnimation(value)
            }
        }
    }

    private fun popupHelperOf(popup: PopupMenu): MenuPopupHelper? = try {
        findField(PopupMenu::class.java, "mPopup")?.get(popup) as? MenuPopupHelper
    } catch (_: Throwable) {
        null
    }

    private fun popupOf(helper: MenuPopupHelper): Any? = try {
        helper.getPopup()
    } catch (_: Throwable) {
        null
    }

    private fun popupWindowOf(popup: ListPopupWindow): PopupWindow? =
        findField(ListPopupWindow::class.java, "mPopup")?.get(popup) as? PopupWindow

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun stop(view: View) {
        waiting.remove(view)?.let(view::removeOnAttachStateChangeListener)
        running.remove(view)?.let { animator ->
            animator.removeAllListeners()
            animator.removeAllUpdateListeners()
            animator.cancel()
        }
        reset(view)
    }

    private fun reset(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }
}
