# 015：设置页菜单高频点击 NPE 崩溃根治

日期：2026-10-08。基线：A01 + 补丁 001-014。改动：`ui/GroupSettingsActivity.kt`、
`ui/profile/ProfileSettingsActivity.kt`、`ui/RouteSettingsActivity.kt`。

## 根因（来自真机崩溃日志）

```
FATAL EXCEPTION: main
java.lang.NullPointerException: null cannot be cast to non-null type
  io.nekohasekai.sagernet.ui.GroupSettingsActivity.MyPreferenceFragmentCompat
  at GroupSettingsActivity.child_delegate$lambda$18(GroupSettingsActivity.kt:357)
  at ... getChild(...) → onOptionsItemSelected(...)
```

- 三个设置页共用同一反模式：
  `val child by lazy { supportFragmentManager.findFragmentById(R.id.settings)
  as MyPreferenceFragmentCompat }`。
- fragment 提交是**双重异步**：`runOnDefaultDispatcher` 里做 DB 初始化 → 回主线程
  `commit()`（再排一轮主线程消息）。在此之前 `findFragmentById` 返回 null，
  非空强转直接崩溃。
- 高频操作放大竞态：URL 测试取消后的回写/整页重载压着主线程，`commit()` 落地
  更晚；用户“跳分组 → 立刻点新建”正好命中窗口。日志里 4 次 fatal 全是此模式
  （Group 3 次 + Profile 1 次）。

## 修复

- `child` 改为**空安全只读属性**：
  `val child: MyPreferenceFragmentCompat? get() = …findFragmentById(R.id.settings)
  as? MyPreferenceFragmentCompat`（不再缓存 null：fragment 晚到也能拿到）。
- `onOptionsItemSelected` 改为 `child?.onOptionsItemSelected(item)
  ?: super.onOptionsItemSelected(item)`——未就绪时点击安全落空并交父类，不崩溃。

## 红线

- 不得改回 `by lazy` 非空强转；返回 null 时必须有兜底分支。
- 菜单可见性（onCreateOptionsMenu）不依赖 child，不受影响。

## 验证

- 冷启动进设置页 → 立即点右上角菜单/新建：不崩溃（旧版必崩）。
- 高频“取消测试 → 跳分组 → 新建”复现路径不再触发 fatal。
