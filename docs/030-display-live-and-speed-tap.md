# 030：显示开关即时生效 + 连接栏速度区点按测延迟

日期：2026-10-11。基线：A01 + 补丁 001-029。改动：`ui/ConfigurationFragment.kt` + `widget/ConnectBar.kt`。

## 根因

1. **“始终显示地址”需重启**：`ConfigurationFragment.alwaysShowAddress` 用 `by lazy`
   （首次访问后缓存，官方源码同病）。设置里开关 → 返回 App 仍读旧缓存。
2. **连接栏速度区无测试入口**：延迟区（`bar_delay_zone`）点按已触发
   `onTestConnection`（主连接 urlTest），速度区（`bar_speed_zone`）点按只是
   穿透到整条栏的按压底色——用户要求两处点按都触发延迟测试，且保留整框按压底色。

## 修复

1. `alwaysShowAddress` 改为实时 getter；`ConfigurationFragment.onResume` 检测
   `lastAppliedAlwaysShowAddress` 变化后 `adapter.reload(now = true)`——从设置返回
   立即生效（地址行显隐 + `hasMiddleRow` 行高跟随）。
2. `ConnectBar`：`speedZone.setOnClickListener` 与延迟区一致（`setTesting()` +
   `onTestConnection`，需已连接）；`setOnTouchListener` 触摸不消费（返回 false），
   ACTION_DOWN 把按压态镜像到整条 MaterialCardView（`isPressed` +
   `drawableHotspotChanged(event.x, event.y)`），UP/CANCEL 复位——保留「整个框」
   按压底色的既有视觉。

## 红线

- `alwaysShowAddress` 不得改回 `by lazy`。
- 速度区触摸监听必须返回 `false`（不消费，交回卡片完成点击态）。
- 群组测试（`runningTest`）期间速度区点按维持现状（无反应），不做排队。

## 验证

- 本地：全新 A01 树按序应用 30 补丁全 APPLIED；与对照树 sha256 全量一致
  （`1dece32e…`）。
- CI：run 成功 + `assert_source.sh` 新增 030 块通过（CI 在 "Apply mod patches" 后
  调用 `bash scripts/assert_source.sh upstream`）。
- 待真机：设置切换开关 → 返回立即生效；连接后点速度区 → 延迟测试触发且整条栏
  出现按压底色；长按无关行为不变。
