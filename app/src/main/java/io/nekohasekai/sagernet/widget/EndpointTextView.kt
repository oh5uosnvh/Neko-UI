package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** One-line endpoint label that truncates the host before the port suffix. */
class EndpointTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var sourceText = ""
    private var applying = false

    init {
        isSingleLine = true
        // Never let TextView ellipsize the complete endpoint after the host-only
        // formatter has preserved the port suffix.
        ellipsize = null
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
        if (!applying) sourceText = text?.toString().orEmpty()
        super.onTextChanged(text, start, before, count)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Let TextView resolve the parent's real width first. In a RecyclerView
        // the first pass can use a provisional column width; fitting against a
        // desired width here is what used to leave only ":4"/":321" after the
        // later layout pass clipped the stale result.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (sourceText.isEmpty() || applying) return

        val measuredWidthBeforeFit = measuredWidth
        val measuredHeightBeforeFit = measuredHeight
        if (refit(measuredWidthBeforeFit)) {
            // Keep the dimensions selected by the parent. A shorter fitted
            // string must not make an AT_MOST/wrap-content item narrower, or a
            // second RecyclerView pass will feed a different width back here.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(measuredWidthBeforeFit, measuredHeightBeforeFit)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && sourceText.isNotEmpty() && !applying) refit(w)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // onSizeChanged is normally enough, but onLayout is the authoritative
        // point for a RecyclerView child after margins/decorations have been
        // applied. Fit before TextView builds its layout for this final width.
        if (sourceText.isNotEmpty() && !applying) refit(right - left)
        super.onLayout(changed, left, top, right, bottom)
    }

    /** Rebuild the display value from the unmodified endpoint for this width. */
    private fun refit(viewWidth: Int): Boolean {
        val available = (viewWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val display = fitEndpoint(sourceText, available)
        if (display == text?.toString()) return false
        applying = true
        super.setText(display, BufferType.NORMAL)
        applying = false
        return true
    }

    internal fun fitForWidth(value: String, width: Int): String = fitEndpoint(value, width)

    private fun fitEndpoint(value: String, width: Int): String {
        // Use a resolved, independent Paint. During an early RecyclerView
        // measure Robolectric/AppCompat can expose a paint whose measureText()
        // returns a tiny placeholder width (e.g. 32px for a long endpoint),
        // which incorrectly bypasses host fitting and later clips the port.
        val metrics = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = this@EndpointTextView.textSize.takeIf { it > 0f }
                ?: 14f * resources.displayMetrics.scaledDensity
            typeface = this@EndpointTextView.typeface
            textScaleX = this@EndpointTextView.textScaleX
        }
        fun measure(text: String): Float {
            val measured = metrics.measureText(text)
            // A pre-layout font backend can return a placeholder width for a
            // long string. Keep a conservative glyph-width floor so the host
            // is fitted instead of letting the platform clip the port later.
            val floor = text.codePointCount(0, text.length) * metrics.textSize * 0.45f
            return maxOf(measured, floor)
        }
        if (width <= 0 || measure(value) <= width) return value
        // displayAddress() already brackets IPv6 hosts. Keep the complete port
        // suffix, including protocol-specific multi-port values such as
        // `:443,8443`; only the host may be shortened.
        val match = Regex("^(.*):(\\d+(?:,\\d+)*)$").matchEntire(value)
        val suffix = match?.groupValues?.get(2)?.let { ":$it" } ?: ""
        val host = match?.groupValues?.get(1) ?: value
        val ending = if (suffix.isEmpty()) "…" else "…$suffix"
        if (measure(ending) > width) return suffix.ifEmpty { ending }
        var low = 0
        var high = host.length
        var best = ending
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = host.substring(0, mid) + ending
            if (measure(candidate) <= width) {
                best = candidate
                low = mid + 1
            } else high = mid - 1
        }
        return best
    }
}
