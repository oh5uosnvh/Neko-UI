package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.ceil

/**
 * Read-only endpoint label. Text remains the full source (including accessibility),
 * while onDraw fits only the host against the FINAL content width.
 *
 * Do not replace TextView.text during measure/layout: same-size RecyclerView binds
 * can skip both callbacks, and replacing text makes the framework layout stale.
 */
class EndpointTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var cachedKey: List<Any?>? = null
    private var fittedLayout: StaticLayout? = null
    private var drawingScale = 1f
    private var leftInkInset = 0f
    private var horizontalExtent = 0f
    private var verticalExtent = 0f
    private var topInkInset = 0f
    private val inkBounds = Rect()
    internal var renderedX = 0f
        private set
    internal var renderedY = 0f
        private set
    internal var renderedVerticalScale = 1f
        private set

    init {
        isSingleLine = true
        setHorizontallyScrolling(false)
        ellipsize = null
        textDirection = TEXT_DIRECTION_LTR
    }

    internal fun fitForWidth(value: String, width: Int): String =
        EndpointLabelFormatter.fit(value, width.toFloat()) { Layout.getDesiredWidth(it, paint) }

    override fun onDraw(canvas: Canvas) {
        val available = width - compoundPaddingLeft - compoundPaddingRight
        if (available <= 0 || text.isNullOrEmpty()) {
            cachedKey = null
            fittedLayout = null
            return
        }
        // Preserve the source but normalize controls in this one-line label.
        val rawSource = text.toString()
        val source = if (rawSource.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
            rawSource.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("")
        } else rawSource
        // Use the live Paint for cache comparison; allocate a snapshot only on
        // misses. TextPaint.set() also copies TextPaint-specific properties.
        val live = paint
        val key = listOf(source, available, live.textSize, live.textScaleX,
            live.textSkewX, live.typeface, live.flags, live.letterSpacing,
            live.fontFeatureSettings, live.textLocale, includeFontPadding,
            live.isElegantTextHeight, live.hinting,
            if (Build.VERSION.SDK_INT >= 24) live.textLocales else null,
            if (Build.VERSION.SDK_INT >= 26) live.fontVariationSettings else null)
        if (key != cachedKey) {
            val metrics = TextPaint().apply { set(live) }
            val budget = (available - 1).coerceAtLeast(1).toFloat()
            val display = EndpointLabelFormatter.fit(source, budget) { value ->
                metrics.getTextBounds(value, 0, value.length, inkBounds)
                maxOf(Layout.getDesiredWidth(value, metrics), inkBounds.right.toFloat()) -
                    minOf(0, inkBounds.left)
            }
            val naturalWidth = ceil(Layout.getDesiredWidth(display, metrics).toDouble())
                .toInt().coerceAtLeast(1)
            metrics.getTextBounds(display, 0, display.length, inkBounds)
            leftInkInset = -minOf(0, inkBounds.left).toFloat()
            horizontalExtent = maxOf(naturalWidth.toFloat(), inkBounds.right.toFloat()) + leftInkInset
            drawingScale = minOf(1f, budget / horizontalExtent)
            // Only pathological port lists wider than the ENTIRE row use the
            // port-only scale fallback. Normal endpoints keep the configured font.
            fittedLayout = if (Build.VERSION.SDK_INT >= 23) {
                StaticLayout.Builder.obtain(display, 0, display.length, metrics, naturalWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.LTR)
                    .setIncludePad(includeFontPadding)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(display, metrics, naturalWidth, Layout.Alignment.ALIGN_NORMAL,
                    1f, 0f, includeFontPadding)
            }
            val label = requireNotNull(fittedLayout)
            val baseline = label.getLineBaseline(0)
            topInkInset = -minOf(0, baseline + inkBounds.top).toFloat()
            verticalExtent = maxOf(label.height, baseline + inkBounds.bottom).toFloat() + topInkInset
            cachedKey = key
        }
        val label = fittedLayout ?: return
        label.paint.color = currentTextColor
        label.paint.drawableState = drawableState
        // The parent address row is intentionally 24dp. Cap the exceptional
        // large-font rendering to this real row height rather than let its
        // ancestors clip the top/bottom of the port glyphs.
        val parentHeight = (parent as? android.view.View)?.height?.takeIf { it > 0 } ?: height
        val contentHeight = (minOf(height, parentHeight) - compoundPaddingTop - compoundPaddingBottom).coerceAtLeast(0)
        if (contentHeight == 0) return
        val verticalScale = minOf(1f, (contentHeight - 1).coerceAtLeast(1) / verticalExtent)
        val drawnHeight = verticalExtent * verticalScale
        val top = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.CENTER_VERTICAL -> compoundPaddingTop + (contentHeight - drawnHeight).coerceAtLeast(0f) / 2f
            Gravity.BOTTOM -> compoundPaddingTop + (contentHeight - drawnHeight).coerceAtLeast(0f)
            else -> compoundPaddingTop.toFloat()
        }
        renderedX = compoundPaddingLeft + leftInkInset * drawingScale
        renderedY = top + topInkInset * verticalScale
        renderedVerticalScale = verticalScale
        val checkpoint = canvas.save()
        try {
            canvas.clipRect(compoundPaddingLeft, compoundPaddingTop,
                width - compoundPaddingRight, compoundPaddingTop + contentHeight)
            canvas.translate(compoundPaddingLeft.toFloat(), top)
            canvas.scale(drawingScale, verticalScale)
            canvas.translate(leftInkInset, topInkInset)
            label.draw(canvas)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    /** Actual last rendered layout, for pixel-boundary regression assertions. */
    internal val renderedLayout: Layout? get() = fittedLayout
    internal val renderedScale: Float get() = drawingScale
}
