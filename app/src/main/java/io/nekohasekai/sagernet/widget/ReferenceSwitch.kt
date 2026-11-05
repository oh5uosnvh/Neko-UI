package io.nekohasekai.sagernet.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Parcelable
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.Switch
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R
import kotlin.math.roundToInt

/**
 * _worker.js ordinary switch: 44x24dp capsule, 18dp thumb, 3dp inset, 20dp travel.
 * Extending the framework Switch is intentional: Preference 1.2 checks `is Switch`
 * before wiring its listener. Only rendering/animation are replaced, not checked state.
 */
class ReferenceSwitch @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.switchStyle,
) : Switch(context, attrs, defStyleAttr) {
    private var ready = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private var motion: ValueAnimator? = null
    private var targetChecked = false
    private var bindingOwner: Any? = null
    private var suppressAnimation = false
    private var bindingDepth = 0
    private var checkedUpdateDepth = 0
    private var drawableStateDepth = 0
    private var pendingBoundUpdate: Runnable? = null
    internal var thumbFraction = 0f
        private set
    internal var trackFraction = 0f
        private set
    internal val isAnimating get() = motion != null

    init {
        // Do not let framework minimums, optical insets, tint or ripple define geometry.
        thumbDrawable = null
        trackDrawable = null
        buttonDrawable = null
        background = null
        stateListAnimator = null
        elevation = 0f
        showText = false
        text = null
        setPadding(0, 0, 0, 0)
        minimumWidth = dp(TRACK_WIDTH).roundToInt()
        minimumHeight = dp(TOUCH_HEIGHT).roundToInt()
        ready = true
        settle()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // No Switch.onMeasure: it enforces two thumb widths plus drawable padding.
        setMeasuredDimension(
            resolveSize(dp(TRACK_WIDTH).roundToInt() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(dp(TOUCH_HEIGHT).roundToInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    internal fun trackBounds(): RectF {
        val left = paddingLeft + (width - paddingLeft - paddingRight - dp(TRACK_WIDTH)) / 2f
        val top = paddingTop + (height - paddingTop - paddingBottom - dp(TRACK_HEIGHT)) / 2f
        return RectF(left, top, left + dp(TRACK_WIDTH), top + dp(TRACK_HEIGHT))
    }

    internal fun thumbCenterX(): Float {
        val fraction = if (layoutDirection == LAYOUT_DIRECTION_RTL) 1f - thumbFraction else thumbFraction
        return trackBounds().left + dp(INSET + THUMB_SIZE / 2f + TRAVEL * fraction)
    }

    internal fun trackColor(): Int = ColorUtils.blendARGB(
        ContextCompat.getColor(context, R.color.switch_track_off),
        ContextCompat.getColor(context, R.color.switch_checked_green), trackFraction,
    )

    override fun onDraw(canvas: Canvas) {
        // No super: no native label, thumb, shadow, glow, or press feedback.
        track.set(trackBounds())
        paint.color = trackColor()
        paint.alpha = if (isEnabled) 255 else 97
        canvas.drawRoundRect(track, dp(TRACK_HEIGHT / 2f), dp(TRACK_HEIGHT / 2f), paint)
        paint.color = Color.WHITE
        paint.alpha = if (isEnabled) 255 else 97
        canvas.drawCircle(thumbCenterX(), track.centerY(), dp(THUMB_SIZE / 2f), paint)
    }

    override fun setChecked(checked: Boolean) {
        checkedUpdateDepth++
        try {
            super.setChecked(checked) // listener/veto/reentrant updates own the real boolean
        } finally {
            checkedUpdateDepth--
        }
        if (!ready) return // called by the framework constructor
        super.jumpDrawablesToCurrentState() // cancel the hidden framework 250ms animator
        if (bindingDepth == 0) updateMotion()
    }

    private fun updateMotion() {
        if (suppressAnimation || !canAnimate()) settle() else animateToChecked()
    }

    /** New/recycled items settle; a refresh of the same item retains its live transition. */
    fun bind(owner: Any, update: () -> Unit) {
        val previous = suppressAnimation
        val newOwner = bindingOwner != owner
        suppressAnimation = previous || newOwner
        bindingDepth++
        try {
            update()
        } finally {
            bindingDepth--
            bindingOwner = owner
            suppressAnimation = previous
            if (newOwner || suppressAnimation || !isAttachedToWindow) settle()
            else if (bindingDepth == 0 && isShown) {
                cancelBoundUpdate()
                updateMotion()
            } else if (bindingDepth == 0) {
                // RecyclerView 1.3 temporarily removes the row's parent while rebinding
                // a scrapped holder. isAttachedToWindow/isLaidOut remain true, but
                // isShown is false until LinearLayoutManager adds the SAME row back.
                // Resolve visibility after that traversal, not inside setChecked.
                pendingBoundUpdate?.let(::removeCallbacks)
                val updateMotion = Runnable {
                    pendingBoundUpdate = null
                    updateMotion()
                }
                pendingBoundUpdate = updateMotion
                post(updateMotion)
            }
        }
    }

    private fun animationsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    private fun canAnimate() = isAttachedToWindow && isLaidOut && isShown &&
        windowVisibility == VISIBLE && isEnabled && animationsEnabled()

    private fun animateToChecked() {
        if (targetChecked == isChecked) return // same-value binds never restart motion
        val startThumb = thumbFraction
        val startTrack = trackFraction
        cancelMotion()
        targetChecked = isChecked
        val end = if (isChecked) 1f else 0f
        val animator = ValueAnimator.ofFloat(0f, 1f)
        motion = animator
        animator.duration = DURATION_MS
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener {
            if (motion !== it) return@addUpdateListener
            if (!canAnimate()) {
                settle()
            } else {
                val time = it.animatedValue as Float
                thumbFraction = startThumb + (end - startThumb) * THUMB_CURVE.getInterpolation(time)
                trackFraction = startTrack + (end - startTrack) * TRACK_CURVE.getInterpolation(time)
                invalidate()
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (motion === animation) settle()
            }
        })
        animator.start()
    }

    private fun cancelMotion() {
        val old = motion
        motion = null // stale cancel/end callbacks cannot settle a rapid reversal
        old?.removeAllUpdateListeners()
        old?.removeAllListeners()
        old?.cancel()
    }

    private fun cancelBoundUpdate() {
        pendingBoundUpdate?.let(::removeCallbacks)
        pendingBoundUpdate = null
    }

    private fun settle() {
        cancelBoundUpdate()
        cancelMotion()
        targetChecked = isChecked
        thumbFraction = if (isChecked) 1f else 0f
        trackFraction = thumbFraction
        invalidate()
    }

    override fun drawableStateChanged() {
        drawableStateDepth++
        try {
            super.drawableStateChanged()
        } finally {
            drawableStateDepth--
        }
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        // Android 15 View.drawableStateChanged may request a drawable jump while
        // checked/pressed state is being dispatched. It must still update native
        // drawables, but cannot commit our independent, non-drawable transition.
        // Explicit jumps outside state/binding dispatch retain their usual meaning.
        if (ready && bindingDepth == 0 && checkedUpdateDepth == 0 && drawableStateDepth == 0) settle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        settle()
    }

    override fun onDetachedFromWindow() {
        settle()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // A VISIBLE callback during a scrapped-holder bind is not an actual hide.
        if (ready && visibility != VISIBLE) settle()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready && visibility != VISIBLE) settle()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (ready && !enabled) settle()
        invalidate()
    }

    override fun performClick(): Boolean = if (isEnabled) super.performClick() else false

    override fun onRestoreInstanceState(state: Parcelable?) {
        suppressAnimation = true
        try {
            super.onRestoreInstanceState(state) // CompoundButton saves checked, never visual progress
        } finally {
            suppressAnimation = false
            settle()
        }
    }

    companion object {
        internal const val TRACK_WIDTH = 44f
        internal const val TRACK_HEIGHT = 24f
        internal const val THUMB_SIZE = 18f
        internal const val INSET = 3f
        internal const val TRAVEL = 20f
        internal const val TOUCH_HEIGHT = 48f
        internal const val DURATION_MS = 300L
        internal val THUMB_CURVE = PathInterpolator(.4f, 0f, .2f, 1f)
        internal val TRACK_CURVE = PathInterpolator(.25f, .1f, .25f, 1f)
    }
}
