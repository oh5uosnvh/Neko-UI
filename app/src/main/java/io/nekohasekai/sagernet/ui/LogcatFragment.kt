package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.utils.SendLog

class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    lateinit var binding: LayoutLogcatBinding

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_log)

        toolbar.inflateMenu(R.menu.logcat_menu)
        toolbar.setOnMenuItemClickListener(this)

        binding = LayoutLogcatBinding.bind(view)

        if (Build.VERSION.SDK_INT >= 23) {
            binding.textview.breakStrategy = 0 // simple
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        reloadSession()
    }

    private fun getColorForLine(line: String): ForegroundColorSpan {
        var color = ForegroundColorSpan(Color.GRAY)
        when {
            line.contains("INFO[") || line.contains(" [Info]") -> {
                color = ForegroundColorSpan((0xFF86C166).toInt())
            }

            line.contains("ERROR[") || line.contains(" [Error]") -> {
                color = ForegroundColorSpan(Color.RED)
            }

            line.contains("WARN[") || line.contains(" [Warning]") -> {
                color = ForegroundColorSpan(Color.RED)
            }
        }
        return color
    }

    /** 上次日志内容的哈希：内容没变就不重设文本，避免反复重排 50KB 长文 */
    private var lastLogHash = 0

    private fun reloadSession() {
        // 读日志 + 逐行构建高亮 span 是纯 IO/CPU 工作，放后台线程做，
        // 主线程只贴结果（原先同步占主线程，是切到日志页卡顿的原因之一）。
        runOnDefaultDispatcher {
            val bytes = SendLog.getNekoLog(50 * 1024)
            val hash = bytes.contentHashCode()
            if (hash == lastLogHash) return@runOnDefaultDispatcher
            val span = SpannableString(String(bytes))
            var offset = 0
            for (line in span.lines()) {
                val color = getColorForLine(line)
                span.setSpan(
                    color, offset, offset + line.length, SPAN_EXCLUSIVE_EXCLUSIVE
                )
                offset += line.length + 1
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                lastLogHash = hash
                binding.textview.text = span
                binding.textview.clearFocus()
                // 等 textview 完成最终 layout 再滚动到底部
                binding.textview.doOnLayout {
                    binding.scroolview.scrollTo(0, binding.textview.height)
                }
            }
        }
    }

    /** 问题1-B：切回日志页时滚到底部（与进入时的行为一致） */
    override fun resetScrollState() {
        if (::binding.isInitialized) {
            binding.scroolview.scrollTo(0, binding.textview.height)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 重新显示时刷新日志（后台构建，不影响抽屉动画）
        if (!hidden) reloadSession()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_clear_logcat -> {
                runOnDefaultDispatcher {
                    try {
                        Libcore.nekoLogClear()
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        binding.textview.text = ""
                    }
                }

            }

            R.id.action_send_logcat -> {
                val context = requireContext()
                runOnDefaultDispatcher {
                    SendLog.sendLog(context, "NB4A")
                }
            }

            R.id.action_refresh -> {
                reloadSession()
            }
        }
        return true
    }

}