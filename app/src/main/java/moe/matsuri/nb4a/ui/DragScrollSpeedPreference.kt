package moe.matsuri.nb4a.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.widget.EditText
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

/**
 * 「卡片拖动速度」偏好（REQ-04）。
 *
 * 点击弹出数字输入对话框：仅接受正整数，范围 1~10；越界时钳制到边界
 * 并 toast 提示范围。写入 DataStore（dragScrollSpeed）后即时生效——
 * 三类列表（配置页 / 分组页 / 路由页，经 GroupDragScroll）每次滚动都
 * 现读偏好值，并随 Room 数据库持久化。
 */
class DragScrollSpeedPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private fun currentValue(): Int =
        getPersistedInt(DEFAULT_VALUE).coerceIn(MIN_VALUE, MAX_VALUE)

    init {
        summaryProvider = Preference.SummaryProvider<DragScrollSpeedPreference> { pref ->
            // 系数 = V / 10.0f，展示当前档位让"越大越快"一眼可读
            pref.context.getString(R.string.drag_scroll_speed_summary, pref.currentValue())
        }
    }

    override fun onClick() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentValue().toString())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val parsed = input.text.toString().trim().toIntOrNull()
                    ?: return@setPositiveButton // 非法/空输入：不修改
                val clamped = parsed.coerceIn(MIN_VALUE, MAX_VALUE)
                if (clamped != parsed) {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.drag_scroll_speed_range_toast, MIN_VALUE, MAX_VALUE,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                if (shouldPersist()) persistInt(clamped)
                notifyChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val MIN_VALUE = 1
        const val MAX_VALUE = 10
        const val DEFAULT_VALUE = 5
    }
}
