package io.nekohasekai.sagernet.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import io.nekohasekai.sagernet.R

/**
 * 设置/编辑「参数选项行」没有按压底色，其余界面（启动胶囊、路由/分组/节点卡片等）
 * 的按压底色保持系统默认——见 themes.xml 的 ThemeOverlay.SagerNet.SettingsHighlight。
 *
 * PreferenceGroupAdapter 用 RecyclerView.context 填充每一行，所以给
 * onCreateRecyclerView 收到的 inflater 包一层只覆盖 colorControlHighlight 的
 * ContextThemeWrapper：它会先继承基础主题（绿色开关样式、配色变体等）再叠加
 * overlay，其余属性原样继承。
 *
 * 各 PreferenceFragmentCompat 宿主里的用法：
 * ```
 * override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup,
 *                                   savedInstanceState: Bundle?): RecyclerView =
 *     super.onCreateRecyclerView(settingsRowsInflater(inflater, parent), parent, savedInstanceState)
 * ```
 */
fun settingsRowsInflater(
    inflater: LayoutInflater,
    parent: android.view.ViewGroup,
): LayoutInflater = inflater.cloneInContext(
    ContextThemeWrapper(parent.context, R.style.ThemeOverlay_SagerNet_SettingsHighlight)
)
