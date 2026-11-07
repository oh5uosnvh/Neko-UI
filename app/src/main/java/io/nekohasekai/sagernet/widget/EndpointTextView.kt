package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Paint
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.max

/** One-line endpoint label that truncates the host before the port suffix. */
class EndpointTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var sourceText = ""
    private var applying = false

    init {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
        if (!applying) sourceText = text?.toString().orEmpty()
        super.onTextChanged(text, start, before, count)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (sourceText.isEmpty() || applying) return
        val available = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val display = fitEndpoint(sourceText, available)
        if (display != text?.toString()) {
            applying = true
            super.setText(display, BufferType.NORMAL)
            applying = false
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    internal fun fitForWidth(value: String, width: Int): String = fitEndpoint(value, width)

    private fun fitEndpoint(value: String, width: Int): String {
        // fitForWidth() is also used before the first layout pass. Use a
        // dedicated snapshot of the TextView metrics instead of relying on the
        // mutable internal TextView paint, which can still have zero metrics.
        val metrics = android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = this@EndpointTextView.textSize.takeIf { it > 0f } ?: 14f * resources.displayMetrics.scaledDensity
            typeface = this@EndpointTextView.typeface
            textScaleX = this@EndpointTextView.textScaleX
        }
        fun measure(text: String) = metrics.measureText(text)
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
