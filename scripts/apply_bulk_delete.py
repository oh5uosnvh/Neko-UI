#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量清理不可用节点：消除 O(n²) 删除路径。

在**已打完 33 个 UI 补丁的上游源码树根目录**运行：

  python3 scripts/apply_bulk_delete.py ../upstream

问题：
  1. 每删一个节点都 `configurationIdList.indexOf()`（线性扫描）；
  2. 每删一个都 `notifyItemRemoved()`，触发整表重排 + refreshFromPosition；
  3. 兜底分支与隐藏节点分支逐条 `deleteProfile2()`，一条一次 DELETE；
  4. `commit()` 逐条删库。
  2000+ 节点删上千个 → 上百万次比较 + 上千轮布局，ANR/闪退，且中途被杀
  会留下“UI 已清空、库里还在”的状态。

改法：
  * DAO 增加只取 id 的查询与一条批量 DELETE；
  * 适配器增加 removeAll(ids)：单次遍历 + 单次 notifyDataSetChanged；
  * 三条删除路径（可见/隐藏/无撤销兜底）全部批量；
  * commit() 用一条 deleteProxy(list)。
"""

from __future__ import annotations

import pathlib
import sys

CF = "app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt"
PE = "app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt"

DAO_ANCHOR = '''        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)
'''
DAO_ADDED = '''        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId")
        fun deleteByGroup(groupId: Long)

        /* Bulk cleanup of timed-out nodes. Ids only: loading thousands of rows
           would deserialize every bean for data the caller never reads. */
        @Query("SELECT id FROM proxy_entities WHERE groupId = :groupId AND status != 0 AND status != 1")
        fun unavailableIds(groupId: Long): List<Long>

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId AND status != 0 AND status != 1")
        fun deleteUnavailable(groupId: Long): Int
'''

ADAPTER_ANCHOR = '''            fun remove(pos: Int) {'''
ADAPTER_ADDED = '''            /** Removes every id in one pass and returns the rows with their
             *  original positions so undo can put them back. */
            fun removeAll(ids: Collection<Long>): List<Pair<Int, ProxyEntity>> {
                if (ids.isEmpty() || configurationIdList.isEmpty()) return emptyList()
                val wanted = ids.toHashSet()
                val removed = ArrayList<Pair<Int, ProxyEntity>>()
                val kept = ArrayList<Long>(configurationIdList.size)
                configurationIdList.forEachIndexed { index, id ->
                    if (id in wanted) removed.add(index to getItem(id)) else kept.add(id)
                }
                if (removed.isEmpty()) return emptyList()
                configurationIdList = kept
                configurationList.keys.removeAll(wanted)
                notifyDataSetChanged()
                return removed
            }

            fun remove(pos: Int) {'''

QUERY_OLD = '''                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {'''
QUERY_NEW = '''                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = profiles.filter { it.status != 0 && it.status != 1 }
                    if (toClear.isNotEmpty()) {'''

LOOP_OLD = '''                                    if (currentAdapter != null) {
                                        for (profile in toClear) {
                                            val index =
                                                currentAdapter.configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                // 逐个移除再取下一个 index，避免位移错位
                                                currentAdapter.remove(index)
                                                visible.add(index to profile)
                                            } else {
                                                invisible.add(profile)
                                            }
                                        }
                                    } else {
                                        invisible.addAll(toClear)
                                    }'''
LOOP_OLD_NO_COMMENT = '''                                    if (currentAdapter != null) {
                                        for (profile in toClear) {
                                            val index =
                                                currentAdapter.configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                currentAdapter.remove(index)
                                                visible.add(index to profile)
                                            } else {
                                                invisible.add(profile)
                                            }
                                        }
                                    } else {
                                        invisible.addAll(toClear)
                                    }'''

LOOP_NEW = '''                                    if (currentAdapter != null) {
                                        // One pass, one refresh: per-item indexOf+remove is
                                        // O(n) each and re-lays out the list every time.
                                        visible.addAll(currentAdapter.removeAll(toClear.map { it.id }))
                                        val visibleIds = visible.mapTo(HashSet()) { it.second.id }
                                        invisible.addAll(toClear.filterNot { it.id in visibleIds })
                                    } else {
                                        invisible.addAll(toClear)
                                    }'''

DELETE_OLD = '''                                    if (visible.isNotEmpty() && groupFragment?.isUndoReady() == true) {
                                        groupFragment.undoManager.remove(visible)
                                    } else {
                                        visible.forEach {
                                            runOnDefaultDispatcher {
                                                ProfileManager.deleteProfile2(
                                                    it.second.groupId, it.second.id
                                                )
                                            }
                                        }
                                    }
                                    invisible.forEach {
                                        runOnDefaultDispatcher {
                                            ProfileManager.deleteProfile2(it.groupId, it.id)
                                        }
                                    }'''
DELETE_NEW = '''                                    fun deleteNow(items: List<ProxyEntity>) {
                                        if (items.isEmpty()) return
                                        runOnDefaultDispatcher {
                                            // One DELETE for the whole batch.
                                            SagerDatabase.proxyDao.deleteProxy(items)
                                            if (items.any { it.id == DataStore.selectedProxy }) {
                                                DataStore.selectedProxy = 0L
                                            }
                                        }
                                    }
                                    if (visible.isNotEmpty() &&
                                        groupFragment?.isUndoReady() == true
                                    ) {
                                        groupFragment.undoManager.remove(visible)
                                        deleteNow(invisible)
                                    } else {
                                        // No undo channel: nothing to restore, so drop
                                        // every row in one statement.
                                        deleteNow(visible.map { it.second } + invisible)
                                    }'''

COMMIT_OLD = '''            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }'''
COMMIT_NEW = '''            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                if (profiles.isEmpty()) return
                runOnDefaultDispatcher {
                    // Single statement instead of one DELETE + listener round
                    // trip per row.
                    SagerDatabase.proxyDao.deleteProxy(profiles)
                    if (profiles.any { it.id == DataStore.selectedProxy }) {
                        DataStore.selectedProxy = 0L
                    }
                }
            }'''


def patch(path: pathlib.Path, pairs, name: str) -> None:
    text = path.read_text(encoding="utf-8")
    for old, new in pairs:
        if old == DELETE_OLD:
            if DELETE_NEW.strip() in text and old not in text:
                continue
            if old not in text:
                raise SystemExit(f"[bulk-delete] 锚点缺失于 {name}: {old.splitlines()[0][:70]}")
            text = text.replace(old, new)  # both cleanup branches
            continue
        if old in (LOOP_OLD, LOOP_OLD_NO_COMMENT):
            if old not in text:
                continue
            text = text.replace(old, LOOP_NEW)
            continue
        if new.strip() in text:
            continue
        if old not in text:
            raise SystemExit(f"[bulk-delete] 锚点缺失于 {name}: {old.splitlines()[0][:70]}")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "upstream")
    cf = root / CF
    pe = root / PE
    if not cf.is_file():
        raise SystemExit(f"[bulk-delete] 找不到 {cf}")

    # ProxyEntity.kt is guarded by scripts/audit_ui_network.py (no schema or
    # serialization drift). The existing deleteProxy(List<ProxyEntity>) already
    # issues one DELETE for a batch, so the DAO is left untouched.
    patch(
        cf,
        [
            (ADAPTER_ANCHOR, ADAPTER_ADDED),
            (QUERY_OLD, QUERY_NEW),
            (LOOP_OLD, LOOP_NEW),
            (LOOP_OLD_NO_COMMENT, LOOP_NEW),
            (DELETE_OLD, DELETE_NEW),
            (COMMIT_OLD, COMMIT_NEW),
        ],
        CF,
    )

    text = cf.read_text(encoding="utf-8")
    assert "fun removeAll(ids: Collection<Long>)" in text
    assert "deleteProxy(items)" in text
    assert "deleteNow(visible.map { it.second } + invisible)" in text
    assert text.count("indexOf(profile.id)") <= 2, "仍有逐项 indexOf 残留"
    print("[bulk-delete] applied: O(n) list removal + batched delete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())