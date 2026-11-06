package io.nekohasekai.sagernet.ui

import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.widget.FlMenu

/**
 * 把菜单挂到任意锚点视图下方弹出。
 *
 * 顶栏的 📄+ 与 ⋮ 是自绘图标，不是 AppCompat 的 action item，所以要自己
 * 指定锚点弹菜单。菜单本身仍然挂在（已隐藏的）Toolbar 上，这里取它的子菜单。
 *
 * 弹出统一走 FlMenu 单窗口引擎（FlClash 弹簧入场 + 缩小淡化退场）：
 * 带 ▶ 的项目（📄+ 的「手动设置」、⋮ 的「排序与外观」等）在同一窗口内切页，
 * 不再经 MenuPopupHelper 级联弹出第二个窗口。
 */
object AppCompatPopupWindowHelper {

    fun show(
        menu: Menu?,
        anchor: View,
        onClick: (MenuItem) -> Boolean,
        fallback: () -> Unit = {},
    ) {
        if (menu == null) {
            fallback()
            return
        }
        try {
            FlMenu.show(anchor, menu, onClick)
        } catch (e: Throwable) {
            Logs.w(e)
            try {
                val pm = PopupMenu(anchor.context, anchor)
                cloneMenu(menu, pm.menu)
                pm.setOnMenuItemClickListener { onClick(it) }
                pm.show()
            } catch (e2: Throwable) {
                Logs.w(e2)
                fallback()
            }
        }
    }

    /** 极简菜单克隆（兜底路径，正常走不到） */
    private fun cloneMenu(src: Menu, dst: Menu) {
        for (i in 0 until src.size()) {
            val it = src.getItem(i)
            val added = dst.add(it.groupId, it.itemId, it.order, it.title)
            added.isCheckable = it.isCheckable
            added.isChecked = it.isChecked
            added.isEnabled = it.isEnabled
            it.icon?.let { ic -> added.icon = ic }
            if (it.hasSubMenu()) {
                it.subMenu?.let { sub -> cloneMenu(sub, added.subMenu!!) }
            }
        }
    }
}