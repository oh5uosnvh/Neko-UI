package io.nekohasekai.sagernet.ui.theme

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import java.lang.ref.WeakReference

/**
 * Native equivalent of the supplied HTML's old-screen overlay, not a WebView.
 * Only a user night-mode change creates a session. The real new hierarchy is never
 * faded, recoloured or blurred. A process-local bitmap crosses recreation by an
 * opaque saved-state ticket; no bitmap or Activity is put in a Bundle/static cache.
 */
internal object NightThemeSnapshot {
    const val DURATION_MS = 420L
    internal const val TIMEOUT_MS = 5000L
    private const val STATE_KEY = "neko.nightSnapshot.ticket"
    private val handler = Handler(Looper.getMainLooper())
    private var nextTicket = 0L
    private var current: Session? = null

    internal enum class Phase { CAPTURED, WAITING_FOR_DRAW, FADING, FINISHED }

    /** Capture synchronously BEFORE persistence, delegate application or recreation. */
    fun begin(activity: Activity, targetNight: Int): Session? {
        current?.finish() // latest request wins; never capture a previous overlay
        if (!animationsEnabled(activity) || activity.isFinishing || activity.isDestroyed) return null
        val decor = activity.window.decorView
        val oldNight = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (oldNight == targetNight || decor.width <= 0 || decor.height <= 0 ||
            !decor.isAttachedToWindow || decor.windowToken == null) return null
        val bitmap = capture(decor) ?: return null
        return Session(++nextTicket, activity, bitmap, targetNight).also {
            current = it
            it.mount(activity, ready = false)
            handler.postDelayed(it.timeout, TIMEOUT_MS)
        }
    }

    internal fun capture(decor: View): Bitmap? {
        // A full-resolution ARGB snapshot; do not allocate an unbounded tablet bitmap.
        if (decor.width <= 0 || decor.height <= 0 ||
            decor.width.toLong() * decor.height > 8_000_000L) return null
        var bitmap: Bitmap? = null
        return try {
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            bitmap
        } catch (_: RuntimeException) {
            bitmap?.recycle()
            null
        } catch (_: OutOfMemoryError) {
            bitmap?.recycle()
            null
        }
    }

    fun animationsEnabled(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return false
        return Settings.Global.getFloat(activity.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    fun save(activity: Activity, state: Bundle) {
        current?.takeIf { it.owns(activity) }?.let { state.putLong(STATE_KEY, it.ticket) }
    }

    /** Called in the base onCreate, before a subclass can inflate/draw its content. */
    fun created(activity: Activity, state: Bundle?) {
        val session = current ?: return
        if (state?.getLong(STATE_KEY, -1L) != session.ticket) return
        if (activity.javaClass.name != session.activityClass || activity.taskId != session.taskId ||
            !session.recreating || !animationsEnabled(activity)) {
            session.finish()
            return
        }
        session.mount(activity, ready = true)
    }

    fun beforeRecreate(activity: Activity, newConfig: Configuration) {
        current?.takeIf { it.owns(activity) }?.let {
            val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night != it.targetNight) it.finish() else it.recreating = true
        }
    }

    /** A delegate that updates in-place can also finish without waiting for timeout. */
    fun applied(activity: Activity, session: Session?) {
        if (session == null || current !== session || session.recreating) return
        if (activity.isChangingConfigurations) session.recreating = true
        else session.awaitDraw()
    }

    fun destroyed(activity: Activity) {
        current?.takeIf { it.owns(activity) }?.let {
            if (activity.isChangingConfigurations && it.recreating &&
                it.phase != Phase.FADING) it.unmount() else it.finish()
        }
    }

    internal class SnapshotDrawable(private val bitmap: Bitmap) : Drawable() {
        private val paint = Paint()
        var opacity: Float = 1f
            set(value) { field = value; paint.alpha = (value * 255).toInt(); invalidateSelf() }
        override fun draw(canvas: Canvas) {
            if (!bitmap.isRecycled) canvas.drawBitmap(bitmap, null, bounds, paint)
        }
        override fun setAlpha(alpha: Int) { opacity = alpha / 255f }
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    internal class Session internal constructor(
        val ticket: Long,
        activity: Activity,
        internal val bitmap: Bitmap,
        val targetNight: Int,
    ) {
        val activityClass: String = activity.javaClass.name
        val taskId: Int = activity.taskId
        private var owner = WeakReference(activity)
        internal var phase = Phase.CAPTURED
            private set
        internal var recreating = false
        internal var drawable: SnapshotDrawable? = null
            private set
        internal var animator: ValueAnimator? = null
            private set
        private var decor: View? = null
        private var observer: ViewTreeObserver? = null
        private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        private var draw: ViewTreeObserver.OnDrawListener? = null
        private var fadeTask: Runnable? = null
        internal val timeout = Runnable { finish() }

        fun owns(activity: Activity) = owner.get() === activity

        internal fun mount(activity: Activity, ready: Boolean) {
            unmount()
            owner = WeakReference(activity)
            recreating = false
            val root = activity.window.decorView
            decor = root
            drawable = SnapshotDrawable(bitmap).also {
                it.setBounds(0, 0, bitmap.width, bitmap.height)
                root.overlay.add(it)
            }
            if (ready) awaitDraw()
        }

        internal fun awaitDraw() {
            if (phase == Phase.FINISHED || phase == Phase.FADING || preDraw != null) return
            phase = Phase.WAITING_FOR_DRAW
            val root = decor ?: return
            observer = root.viewTreeObserver
            preDraw = ViewTreeObserver.OnPreDrawListener {
                val activity = owner.get()
                if (activity == null || activity.isFinishing || activity.isDestroyed ||
                    !animationsEnabled(activity)) {
                    finish()
                } else if (!recreating &&
                    (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == targetNight &&
                    root.width > 0 && root.height > 0 && !root.isLayoutRequested) {
                    if (root.width != bitmap.width || root.height != bitmap.height) {
                        // Rotation/resizing: never stretch an old portrait screen across landscape.
                        finish()
                    } else {
                        removePreDraw()
                        // Keep alpha=1 THROUGH the first real draw of the ready hierarchy.
                        // Starting from onPreDraw would race that frame, revealing blank content.
                        val task = Runnable {
                            fadeTask = null
                            removeDraw()
                            if (phase != Phase.FINISHED) startFade()
                        }
                        fadeTask = task
                        draw = ViewTreeObserver.OnDrawListener { root.post(task) }
                        observer?.addOnDrawListener(draw)
                    }
                }
                true
            }
            observer?.addOnPreDrawListener(preDraw)
            root.invalidate()
        }

        private fun startFade() {
            val activity = owner.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed ||
                !animationsEnabled(activity)) { finish(); return }
            phase = Phase.FADING
            animator = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = DURATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    if (!animationsEnabled(activity)) finish()
                    else drawable?.opacity = it.animatedValue as Float
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { finish() }
                })
                start()
            }
        }

        private fun removePreDraw() {
            preDraw?.let { if (observer?.isAlive == true) observer?.removeOnPreDrawListener(it) }
            preDraw = null
        }

        private fun removeDraw() {
            draw?.let { if (observer?.isAlive == true) observer?.removeOnDrawListener(it) }
            draw = null
        }

        internal fun unmount() {
            removePreDraw()
            removeDraw()
            fadeTask?.let { decor?.removeCallbacks(it) }
            fadeTask = null
            drawable?.let { decor?.overlay?.remove(it) }
            drawable = null
            decor = null
            observer = null
        }

        fun finish() {
            if (phase == Phase.FINISHED) return
            phase = Phase.FINISHED
            animator?.apply { removeAllUpdateListeners(); removeAllListeners(); cancel() }
            animator = null
            unmount()
            owner.clear()
            handler.removeCallbacks(timeout)
            // Do not recycle a bitmap still referenced by an in-flight RenderThread
            // display list. Dropping our sole ownership lets Android reclaim it safely.
            if (current === this) current = null
        }
    }
}
