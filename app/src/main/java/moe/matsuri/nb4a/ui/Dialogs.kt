package moe.matsuri.nb4a.ui

import android.content.Context
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

object Dialogs {
    fun logExceptionAndShow(context: Context, e: Exception, callback: Runnable) {
        Logs.e(e)
        runOnMainDispatcher {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.error_title)
                .setMessage(e.readableMessage)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    callback.run()
                }
                .show()
        }
    }

    fun message(context: Context, title: String, message: String) {
        runOnMainDispatcher {
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true)
                .show()
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                setTextIsSelectable(true)
            }
        }
    }
}

/**
 * 代码里 new 出来放进 [MaterialAlertDialogBuilder] 的裸 [android.widget.EditText]
 * 统一走这里：与 CopyableEditTextPreferenceDialog（直连 DNS 等输入弹窗）同款观感——
 * ① 背景用主题的 editTextBackground（下划线随主题着色，聚焦变主题色）；
 * ② 左右 padding 补到 ？attr/dialogPreferredPadding，输入文字与弹窗标题同一起始
 *    位置，不再贴着弹窗最左边（「卡片拖动速度」「MTU」「Log buffer」等）。
 *    注：EditText 自带的 4dp 是背景 inset 不算 padding，直接补差值即可对齐。
 */
fun Context.dialogInputEditText(): android.widget.EditText {
    val input = android.widget.EditText(this)
    val theme = obtainStyledAttributes(intArrayOf(
        android.R.attr.editTextBackground, android.R.attr.dialogPreferredPadding,
    ))
    return try {
        theme.getDrawable(0)?.let { input.background = it }
        val dialogPadding = theme.getDimensionPixelSize(1, 0)
        if (dialogPadding > 0) {
            input.setPadding(
                input.paddingStart + (dialogPadding - input.paddingStart).coerceAtLeast(0),
                input.paddingTop,
                input.paddingEnd + (dialogPadding - input.paddingEnd).coerceAtLeast(0),
                input.paddingBottom,
            )
        }
        input
    } finally {
        theme.recycle()
    }
}