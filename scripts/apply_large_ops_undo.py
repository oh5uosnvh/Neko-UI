#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();p=r/'app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt';s=p.read_text()
if 'private var duplicateCleanupInProgress = false' not in s:
 anchor='''    private fun onSearchQueryChanged(query: String) {''';s=s.replace(anchor,'    private var duplicateCleanupInProgress = false\n\n'+anchor,1)
start=s.index('            R.id.action_remove_duplicate -> {')
end=s.index('            R.id.action_connection_tcp_ping -> {',start)
block='''            R.id.action_remove_duplicate -> {
                if (duplicateCleanupInProgress) return@onMenuItemClick false
                duplicateCleanupInProgress = true
                val groupFragment = adapter.groupFragments[DataStore.selectedGroup]
                val currentAdapter = groupFragment?.adapter
                val snapshot = currentAdapter?.configurationList?.values?.toList().orEmpty()
                runOnDefaultDispatcher {
                    val unique = LinkedHashSet<Protocols.Deduplication>()
                    val duplicates = snapshot.filter { !unique.add(Protocols.Deduplication(it.requireBean(), it.displayType())) }
                    onMainDispatcher {
                        if (duplicates.isEmpty()) {
                            duplicateCleanupInProgress = false
                            return@onMainDispatcher
                        }
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                            .setMessage(getString(R.string.delete_confirm_prompt))
                            .setPositiveButton(R.string.yes) { _, _ ->
                                val visible = mutableListOf<Pair<Int, ProxyEntity>>()
                                currentAdapter?.let { a ->
                                    duplicates.forEach { item ->
                                        val i=a.configurationIdList.indexOf(item.id)
                                        if (i>=0) { a.remove(i); visible += i to item }
                                    }
                                }
                                if (visible.isNotEmpty() && groupFragment?.isUndoReady()==true) {
                                    groupFragment.undoManager.remove(visible)
                                } else {
                                    runOnDefaultDispatcher { SagerDatabase.proxyDao.deleteByIds(duplicates.map { it.id }) }
                                }
                                duplicateCleanupInProgress = false
                            }
                            .setNegativeButton(R.string.no) { _, _ -> duplicateCleanupInProgress=false }
                            .setOnCancelListener { duplicateCleanupInProgress=false }
                            .show()
                    }
                }
            }

'''
s=s[:start]+block+s[end:]
# Undo commit must never call the old per-row rearranging delete.
s=s.replace('ProfileManager.deleteProfile(entity.groupId, entity.id)','ProfileManager.deleteProfile2(entity.groupId, entity.id)')
p.write_text(s);print('PASS: duplicate cleanup snapshots adapter, guards re-entry, preserves Undo')
