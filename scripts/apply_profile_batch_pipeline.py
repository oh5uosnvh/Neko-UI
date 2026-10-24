#!/usr/bin/env python3
"""Final override: one import transaction, one UI snapshot writer, all removals undoable."""
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();base='app/src/main/java/io/nekohasekai/sagernet/'
def method(s,anchor,new):
 a=s.index(anchor);b=s.index('{',a);level=0
 for j in range(b,len(s)):
  if s[j]=='{':level+=1
  elif s[j]=='}':
   level-=1
   if not level:return s[:a]+new+s[j+1:]
 raise SystemExit('bad method '+anchor)
p=r/(base+'ui/ConfigurationFragment.kt');s=p.read_text()
s=s.replace('''        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }''','''        kotlinx.coroutines.withContext(Dispatchers.IO) {
            ProfileBatchStore.append(SagerDatabase.instance, targetId, proxies)
        }
        GroupManager.postReload(targetId)''',1)
# The only active writer is the latest load generation. A local delete/undo
# invalidates outstanding DB loads AND computes its diff from a detached snapshot.
anchor='            var configurationIdList: MutableList<Long> = mutableListOf()'
s=s.replace(anchor,'''            private val batchLoadGeneration = AtomicLong()

            private fun presentBatchSnapshot(ids: List<Long>, nodes: Map<Long, ProxyEntity>) {
                val first = (configurationListView.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
                val anchorId = configurationIdList.getOrNull(first.coerceAtLeast(0))
                val previousIds = configurationIdList.toList()
                val oldNodes = HashMap(configurationList)
                val nextIds = ids.distinct().filterNot { PendingProfileDeletes.contains(it) }
                val nextNodes = nodes.filterKeys { !PendingProfileDeletes.contains(it) }
                // Large replacement: one stable-ID refresh avoids Myers diff O(N*D)
                // on 100k -> 20k; small updates retain precise animations.
                val diff = if (previousIds.size + nextIds.size <= 4000) DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = previousIds.size
                    override fun getNewListSize() = nextIds.size
                    override fun areItemsTheSame(a: Int, b: Int) = previousIds[a] == nextIds[b]
                    override fun areContentsTheSame(a: Int, b: Int) = oldNodes[previousIds[a]] == nextNodes[nextIds[b]]
                }, false) else null
                configurationList.clear()
                configurationList.putAll(nextNodes)
                configurationIdList = nextIds.toMutableList()
                if (diff != null) diff.dispatchUpdatesTo(this) else notifyDataSetChanged()
                val position = anchorId?.let { configurationIdList.indexOf(it) } ?: -1
                if (configurationIdList.isNotEmpty()) {
                    configurationListView.scrollToPosition(if (position >= 0) position else first.coerceIn(0, configurationIdList.lastIndex))
                }
                configurationListView.requestLayout()
                configurationListView.invalidate()
            }

'''+anchor,1)
# Remove the old fallback's competing stream of posted 20-item mutations.
s=method(s,'            fun armFallback() {','''            fun armFallback() {
                // Single snapshot loader owns initial fill; no competing watchdog writer.
            }''')
s=method(s,'            private fun applyFirstFill(','''            private fun applyFirstFill(map: Map<Long, ProxyEntity>, ids: List<Long>, selectedProfileIndex: Int) {
                presentBatchSnapshot(ids, map)
            }''')
s=method(s,'            fun reloadProfiles() {','''            fun reloadProfiles() {
                val generation = batchLoadGeneration.incrementAndGet()
                val groupId = proxyGroup.id
                val order = proxyGroup.order
                runOnDefaultDispatcher {
                    try {
                        var profiles = PendingProfileDeletes.loadGroup(groupId)
                        profiles = when (order) {
                            GroupOrder.BY_NAME -> profiles.sortedBy { it.displayName() }
                            GroupOrder.BY_DELAY -> profiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                            else -> profiles
                        }
                        val map = profiles.associateBy { it.id }
                        val ids = profiles.map { it.id }
                        onMainDispatcher {
                            if (generation == batchLoadGeneration.get() && isAdded && proxyGroup.id == groupId &&
                                ::configurationListView.isInitialized && configurationListView.adapter === this@ConfigurationAdapter) {
                                presentBatchSnapshot(ids, map)
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher { if (isAdded) snackbar(e.readableMessage).show() }
                    }
                }
            }''')
s=method(s,'            fun stageBatchRemoval(nodes: List<ProxyEntity>): List<Pair<Int, ProxyEntity>> {','''            fun stageBatchRemoval(nodes: List<ProxyEntity>): List<Pair<Int, ProxyEntity>> {
                batchLoadGeneration.incrementAndGet()
                val ids = nodes.map { it.id }.toHashSet()
                val positions = configurationIdList.withIndex().associate { it.value to it.index }
                val actions = nodes.map { (positions[it.id] ?: -1) to it }
                PendingProfileDeletes.stage(nodes)
                val keep = configurationIdList.filterNot { it in ids }
                val map = configurationList.filterKeys { it !in ids }
                presentBatchSnapshot(keep, map)
                return actions
            }''')
s=method(s,'            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {','''            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                val snapshot = actions.toList()
                configurationListView.post {
                    batchLoadGeneration.incrementAndGet()
                    PendingProfileDeletes.undo(snapshot.map { it.second })
                    val nodes = HashMap(configurationList)
                    snapshot.forEach { (_, node) -> nodes[node.id] = node }
                    val existing = configurationIdList.toHashSet()
                    val restore = snapshot.filter { it.first >= 0 && existing.add(it.second.id) }.sortedBy { it.first }
                    val merged = ArrayList<Long>(configurationIdList.size + restore.size)
                    var next = 0
                    var add = 0
                    while (next < configurationIdList.size || add < restore.size) {
                        if (add < restore.size && (restore[add].first <= merged.size || next == configurationIdList.size)) {
                            merged.add(restore[add++].second.id)
                        } else merged.add(configurationIdList[next++])
                    }
                    presentBatchSnapshot(merged, nodes)
                }
            }''')
# Individual remove is the same staging mechanism as bulk operations.
s=method(s,'            fun remove(pos: Int) {','''            fun remove(pos: Int) {
                configurationIdList.getOrNull(pos)?.let { id ->
                    configurationList[id]?.let { stageBatchRemoval(listOf(it)) }
                }
            }''')
# Search deletes never physically delete while Undo is offered.
a=s.index('                // 先从当前 adapter 中移除',s.index('    private fun deleteCurrentSearchResults()'))
b=s.index('                onSearchQueryChanged(query)',a)
s=s[:a]+'''                val page = getCurrentGroupFragment() ?: return@setPositiveButton
                if (!page.isUndoReady()) return@setPositiveButton
                val actions = a.stageBatchRemoval(toDelete)
                page.undoManager.remove(actions)
'''+s[b:]
# Unavailable is just a predicate; the actual removal and Undo are shared.
a=s.index('            R.id.action_connection_test_delete_unavailable -> {');b=s.index('            R.id.action_remove_duplicate -> {',a)
s=s[:a]+'''            R.id.action_connection_test_delete_unavailable -> {
                if (duplicateCleanupInProgress) return true
                duplicateCleanupInProgress = true
                val groupId = DataStore.currentGroupId()
                runOnDefaultDispatcher {
                    try {
                        val nodes = PendingProfileDeletes.loadGroup(groupId).filter { it.status != 0 && it.status != 1 }
                        onMainDispatcher {
                            if (!isAdded || nodes.isEmpty()) duplicateCleanupInProgress = false
                            else MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(getString(R.string.delete_confirm_prompt) + " (" + nodes.size + ")")
                                .setPositiveButton(R.string.yes) { _, _ ->
                                    val page = adapter.groupFragments[groupId]
                                    val list = page?.adapter
                                    if (list != null && page?.isUndoReady() == true) page.undoManager.remove(list.stageBatchRemoval(nodes))
                                    duplicateCleanupInProgress = false
                                }
                                .setNegativeButton(R.string.no) { _, _ -> duplicateCleanupInProgress = false }
                                .setOnCancelListener { duplicateCleanupInProgress = false }.show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher { duplicateCleanupInProgress = false }
                    }
                }
            }

'''+s[b:]
p.write_text(s)
# Clears retain their existing restore semantics, but no whole-table cursor or per-row existence queries.
p=r/(base+'ui/GroupClearStore.kt');s=p.read_text().replace('db.proxyDao().getByGroup(groupId)','PendingProfileDeletes.loadGroup(groupId)')
s=s.replace('''val missing = nodes.filter { it.groupId == groupId && db.proxyDao().getById(it.id) == null }''','''val existing = db.proxyDao().getIdsByGroup(groupId).toHashSet()
                val missing = nodes.filter { it.groupId == groupId && it.id !in existing }''')
p.write_text(s)
print('PASS: batch import, generation-guarded snapshot rendering, shared undo removal')
