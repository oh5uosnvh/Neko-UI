package io.nekohasekai.sagernet.widget

import android.content.Context
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
        if (width <= 0 || (paint.measureText(value) <= width && value.length <= 28)) return value
        val split = value.lastIndexOf(':')
        val suffix = if (split > 0 && split < value.lastIndex &&
            value.substring(split + 1).all { it.isDigit() }) value.substring(split) else ""
        val host = if (suffix.isEmpty()) value else value.substring(0, split)
        val ending = if (suffix.isEmpty()) "" else "…$suffix"
        if (paint.measureText(ending) > width) return ending.takeLast(1)
        var low = 0
        var high = host.length
        var best = ending
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = host.substring(0, mid) + ending
            if (paint.measureText(candidate) <= width) {
                best = candidate
                low = mid + 1
            } else high = mid - 1
        }
        return best
    }
}
