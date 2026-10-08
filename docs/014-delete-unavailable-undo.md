# 014：清理不可用/去重删除接入“还原”snackbar

日期：2026-10-08。基线：A01 + 补丁 001-013。改动仅 `ui/ConfigurationFragment.kt`
（`action_connection_test_delete_unavailable` 与 `action_remove_duplicate` 两处 yes 分支
+ `GroupFragment.isUndoReady()`）。

## 根因

- 官方（starifly/A01）这两处批量删除本来就是裸删：手动
  `configurationIdList.removeAt + notifyItemRemoved` + 立即 `deleteProfile2`，
  **从未接 undoManager**（滑动删除走的是 `removeProfile → undoManager.remove`）。
- 卡片被删、库已清空，snackbar 的“还原”无从谈起——不是回写丢了，是根本没走撤销通道。

## 修复

- 与滑动删除同一契约：
  `currentAdapter.remove(index)`（视觉移除）→ `undoManager.remove(index to profile)`
  （“已删除 N 项 | 还原”）→ snackbar 消失后才 `commit()` → `deleteProfile` 真删库；
  点“还原”则 `undo()` 按原位插回，库不动。
- 可见性边界：被过滤隐藏（indexOf < 0）的节点无卡可还原，维持旧的立即删除语义，
  保证“清理不可用”仍清干净整组。
- `GroupFragment.isUndoReady()`：select 模式下 undoManager 未初始化，降级为立即删除
  （与官方滑动删除的 `if (select) return` 语义对齐）。
- 逐个 `remove` 再取下一个 `indexOf`，规避批量删除的索引位移错位。

## 红线

- 撤销插入必须走 `undoManager` 的 reversed 顺序语义；不得改成先删库再“提示”。
- `undoManager.remove(visible)` 的入参必须是 **(原始 index, profile)** 对。

## 验证

- 清理不可用 → 确认 → 卡片消失 + “已删除 N 项/还原” → 点还原卡片原位回归、
  数据库未删；等 snackbar 消失 → 数据库已删、无重复删除崩溃。
- 去重删除同样验证。
