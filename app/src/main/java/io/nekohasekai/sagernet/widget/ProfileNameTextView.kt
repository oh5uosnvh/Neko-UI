package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Rect
import android.text.method.TransformationMethod
import android.util.AttributeSet
import android.view.View
import android.view.ViewParent
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Opportunities ONLY inside ASCII runs; no boundary inside any Unicode cluster.
 * Emoji, flags, combining sequences, ZWJ, whitespace and explicit newlines are
 * copied verbatim. The result is a display transformation, never an entity name.
 */
object TwoColumnNameWrap {
    fun display(source: CharSequence): CharSequence {
        if (source.length < 2) return source
        var result: StringBuilder? = null
        for (i in 1 until source.length) {
            if (source[i - 1] in '!'..'~' && source[i] in '!'..'~') {
                if (result == null) result = StringBuilder(source.length * 2).append(source, 0, i)
                result.append('\u200B')
            }
            result?.append(source[i])
        }
        return result ?: source
    }
}

/** Raw TextView.text stays intact for accessibility/search/export. Font size is unchanged. */
class ProfileNameTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var transformedSource: String? = null
    private var transformedValue: CharSequence? = null
    private fun displayName(source: CharSequence): CharSequence {
        val key = source.toString()
        if (key == transformedSource) return transformedValue ?: source
        return TwoColumnNameWrap.display(source).also { transformedSource = key; transformedValue = it }
    }
    private val nameTransformation = object : TransformationMethod {
        override fun getTransformation(source: CharSequence?, view: View?): CharSequence? = source?.let(::displayName)
        override fun onFocusChanged(view: View?, sourceText: CharSequence?, focused: Boolean,
            direction: Int, previouslyFocusedRect: Rect?) = Unit
    }
    var twoColumnDisplay: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            transformationMethod = if (value) nameTransformation else null
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Follow the real layout manager, not a saved preference/snapshot. An
        // adapter rebind/mode change is enough; no database/string mutation.
        var ancestor: ViewParent? = parent
        while (ancestor != null && ancestor !is RecyclerView) ancestor = ancestor.parent
        if (ancestor is RecyclerView) {
            val manager = ancestor.layoutManager as? GridLayoutManager
            twoColumnDisplay = manager != null && manager.spanCount > 1
        }
        // Long ASCII node names must wrap in single-column cards too. The
        // transformation inserts zero-width break opportunities while keeping
        // the raw TextView text unchanged for accessibility/export.
        if (transformationMethod !== nameTransformation) {
            transformationMethod = nameTransformation
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

}
