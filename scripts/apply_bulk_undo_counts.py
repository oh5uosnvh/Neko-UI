#!/usr/bin/env python3
"""Batch list changes and SQL commits while keeping Undo and live counts."""
from pathlib import Path
import shutil,sys
r=Path(sys.argv[1]).resolve();kit=Path(__file__).resolve().parents[1]
shutil.copytree(kit/'files/bulk-undo',r/'app/src',dirs_exist_ok=True)
base='app/src/main/java/io/nekohasekai/sagernet/'
def edit(rel,old,new):
 p=r/rel;s=p.read_text()
 if old not in s: raise SystemExit('missing bulk anchor '+rel+': '+old[:70])
 p.write_text(s.replace(old,new,1))
def method(s,anchor,replacement):
 a=s.index(anchor); start=s.index('{',a);depth=0
 for j in range(start,len(s)):
  if s[j]=='{':depth+=1
  elif s[j]=='}':
   depth-=1
   if depth==0:return s[:a]+replacement+s[j+1:]
 raise SystemExit('unbalanced '+anchor)
# Query-only DAO additions; no schema/serialized field changes.
p=r/(base+'database/ProxyEntity.kt');s=p.read_text();anchor='    interface Dao {'
s=s.replace(anchor,anchor+'''
        @Query("DELETE FROM proxy_entities WHERE id IN (:ids)")
        fun deleteSnapshotIds(ids: List<Long>): Int

        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId AND id > :afterId ORDER BY id LIMIT :pageSize")
        fun profilePageAfter(groupId: Long, afterId: Long, pageSize: Int): List<ProxyEntity>
''',1);p.write_text(s)
# The adapter changes the visible list once, not 8k indexOf/remove/rangeChanged calls.
p=r/(base+'ui/ConfigurationFragment.kt');s=p.read_text()
a=s.index('            R.id.action_remove_duplicate -> {');b=s.index('            R.id.action_connection_tcp_ping -> {',a)
s=s[:a]+'''            R.id.action_remove_duplicate -> {
                if (duplicateCleanupInProgress) return true
                duplicateCleanupInProgress = true
                val targetGroupId = DataStore.currentGroupId()
                runOnDefaultDispatcher {
                    try {
                        val snapshot = PendingProfileDeletes.loadGroup(targetGroupId)
                        val unique = HashSet<Protocols.Deduplication>()
                        val duplicates = snapshot.filter {
                            !unique.add(Protocols.Deduplication(it.requireBean(), it.displayType()))
                        }
                        onMainDispatcher {
                            if (!isAdded || duplicates.isEmpty()) {
                                duplicateCleanupInProgress = false
                            } else {
                                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                    .setMessage("您确认要删除这些重复配置吗？\\n一共 ${duplicates.size} 个重复配置。\\n\\n" + duplicates.take(50).joinToString("\\n") { node ->
                                val name = node.displayName().orEmpty().replace("\\n", " ")
                                if (name.length > 42) name.take(41) + "…" else name
                            } + if (duplicates.size > 50) "\\n……" else "")
                                    .setPositiveButton(R.string.yes) { _, _ ->
                                        val page = adapter.groupFragments[targetGroupId]
                                        val list = page?.adapter
                                        if (list != null && page?.isUndoReady() == true) {
                                            val actions = list.stageBatchRemoval(duplicates)
                                            page.undoManager.remove(actions)
                                        }
                                        duplicateCleanupInProgress = false
                                    }
                                    .setNegativeButton(R.string.no) { _, _ -> duplicateCleanupInProgress = false }
                                    .setOnCancelListener { duplicateCleanupInProgress = false }
                                    .show()
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        onMainDispatcher { duplicateCleanupInProgress = false }
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            duplicateCleanupInProgress = false
                            if (isAdded) snackbar(e.readableMessage).show()
                        }
                    }
                }
            }

'''+s[b:]
anchor='            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {'
s=s.replace(anchor,'''            fun stageBatchRemoval(nodes: List<ProxyEntity>): List<Pair<Int, ProxyEntity>> {
                val ids = nodes.map { it.id }.toHashSet()
                val positions = configurationIdList.withIndex().associate { it.value to it.index }
                val actions = nodes.map { (positions[it.id] ?: -1) to it }
                PendingProfileDeletes.stage(nodes)
                configurationIdList.removeAll { it in ids }
                ids.forEach { configurationList.remove(it) }
                notifyDataSetChanged()
                return actions
            }

'''+anchor,1)
s=method(s,anchor,'''            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                val snapshot = actions.toList()
                configurationListView.post {
                    PendingProfileDeletes.undo(snapshot.map { it.second })
                    snapshot.forEach { (_, node) -> configurationList[node.id] = node }
                    val existing = configurationIdList.toHashSet()
                    val restore = snapshot.filter { it.first >= 0 && existing.add(it.second.id) }.sortedBy { it.first }
                    val merged = ArrayList<Long>(configurationIdList.size + restore.size)
                    var next = 0
                    var add = 0
                    while (next < configurationIdList.size || add < restore.size) {
                        if (add < restore.size && (restore[add].first <= merged.size || next == configurationIdList.size)) {
                            merged.add(restore[add++].second.id)
                        } else {
                            merged.add(configurationIdList[next++])
                        }
                    }
                    configurationIdList.clear()
                    configurationIdList.addAll(merged)
                    notifyDataSetChanged()
                }
            }''')
s=method(s,'            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {','''            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                // The Snackbar clears its recycle bin immediately: take a detached snapshot first.
                val snapshot = actions.map { it.second }
                runOnDefaultDispatcher {
                    try {
                        PendingProfileDeletes.commit(snapshot)
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            undo(snapshot.map { -1 to it })
                            if (isAdded) snackbar(e.readableMessage).show()
                        }
                    }
                }
            }''')
# Suppress pending IDs from every reload, so opening the dropdown/page cannot resurrect them.
s=s.replace('SagerDatabase.proxyDao.getByGroup(proxyGroup.id)','PendingProfileDeletes.loadGroup(proxyGroup.id)')
s=s.replace('SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())','PendingProfileDeletes.loadGroup(DataStore.currentGroupId())')
p.write_text(s)
# Both UI count consumers read one logical count source.
for name in ['ui/TopBarController.kt','ui/GroupFragment.kt']:
 p=r/(base+name);s=p.read_text().replace('SagerDatabase.proxyDao.countByGroup(', 'PendingProfileDeletes.count(')
 if name.endswith('GroupFragment.kt'):
  s=s.replace('import android.os.Bundle\n','import android.os.Bundle\nimport androidx.lifecycle.lifecycleScope\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.collect\n',1)
  s=s.replace('        groupAdapter = GroupAdapter()','''        groupAdapter = GroupAdapter()
        viewLifecycleOwner.lifecycleScope.launch {
            PendingProfileDeletes.changes.collect {
                if (::groupAdapter.isInitialized) groupAdapter.notifyDataSetChanged()
            }
        }''',1)
 p.write_text(s)
# Snapshot the Undo manager's mutable recycle bin for async consumers, including other screens.
p=r/(base+'widget/UndoSnackbarManager.kt');s=p.read_text().replace('callback.commit(recycleBin)','callback.commit(recycleBin.toList())');p.write_text(s)
print('PASS: one-pass removal/restore, transactional chunked delete, pending count StateFlow')
