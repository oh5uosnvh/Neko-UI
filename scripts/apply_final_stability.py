#!/usr/bin/env python3
"""Apply A01 database and rapid-delete crash fixes."""
from pathlib import Path
import re
import sys
r=Path(sys.argv[1]).resolve()

def edit(rel, old, new):
 p=r/rel; s=p.read_text()
 if new in s: return
 if old not in s: raise SystemExit(f'missing stability anchor: {rel}')
 p.write_text(s.replace(old,new,1))

p='app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt'
edit(p, 'import androidx.room.AutoMigration\n', 'import androidx.room.AutoMigration\nimport androidx.room.migration.Migration\nimport androidx.sqlite.db.SupportSQLiteDatabase\n')
edit(p, '''        AutoMigration(from = 7, to = 8),
        // mod protocols: ProxyEntity adds xhttpBean / oppaBean BLOB columns
        AutoMigration(from = 8, to = 9)
''', '''        AutoMigration(from = 7, to = 8)
''')
edit(p, '    companion object {\n', '''    companion object {
        // Some old mod builds created these columns while still reporting v8.
        // The generated unconditional ALTER then crashed on every launch.
        private val MIGRATION_8_9_SAFE = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = mutableSetOf<String>()
                db.query("PRAGMA table_info(`proxy_entities`)").use { c ->
                    val i = c.getColumnIndex("name")
                    while (c.moveToNext()) if (i >= 0) columns += c.getString(i)
                }
                if ("xhttpBean" !in columns) db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `xhttpBean` BLOB DEFAULT NULL")
                if ("oppaBean" !in columns) db.execSQL("ALTER TABLE `proxy_entities` ADD COLUMN `oppaBean` BLOB DEFAULT NULL")
            }
        }
''')
edit(p, 'Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)\n', 'Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)\n                .addMigrations(MIGRATION_8_9_SAFE)\n')

p='app/src/main/java/io/nekohasekai/sagernet/database/ProfileManager.kt'
edit(p, '''        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
''', '''        // Do not reload/deserialize the whole group after a delete. For large
        // subscriptions this overflows Android CursorWindow (and crashes while
        // searching/deleting one result). Gaps in userOrder are harmless because
        // nextOrder() uses MAX(userOrder)+1; explicit drag reorder remains intact.
''')

# Batch/ID-only DAO operations: never materialize 10k serialized beans just to
# discover unavailable IDs or delete them.
p='app/src/main/java/io/nekohasekai/sagernet/database/ProxyEntity.kt'
edit(p, '''        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<ProxyEntity>
''', '''        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder")
        fun getByGroup(groupId: Long): List<ProxyEntity>

        @Query("SELECT id FROM proxy_entities WHERE groupId = :groupId AND status != 0 AND status != 1")
        fun unavailableIds(groupId: Long): List<Long>

        @Query("DELETE FROM proxy_entities WHERE groupId = :groupId AND status != 0 AND status != 1")
        fun deleteUnavailable(groupId: Long): Int

        @Query("DELETE FROM proxy_entities WHERE id IN (:proxyIds)")
        fun deleteByIds(proxyIds: List<Long>): Int

        @Query("SELECT * FROM proxy_entities WHERE groupId = :groupId ORDER BY userOrder LIMIT :limit OFFSET :offset")
        fun getByGroupPage(groupId: Long, limit: Int, offset: Int): List<ProxyEntity>

        @Query("SELECT * FROM proxy_entities ORDER BY groupId, userOrder LIMIT :limit OFFSET :offset")
        fun getAllPage(limit: Int, offset: Int): List<ProxyEntity>
''')

p='app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt'
s=(r/p).read_text()
# Prevent duplicate confirmation dialogs while the batch DELETE is still running.
if 'private var unavailableCleanupInProgress = false' not in s:
 anchor='''    private fun onSearchQueryChanged(query: String) {'''
 s=s.replace(anchor,'    private var unavailableCleanupInProgress = false\n\n'+anchor,1)
s=s.replace('''            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {''','''            R.id.action_connection_test_delete_unavailable -> {
                if (unavailableCleanupInProgress) return@onMenuItemClick
                unavailableCleanupInProgress = true
                runOnDefaultDispatcher {''',1)
s=s.replace('''                    if (unavailableIds.isNotEmpty()) {
                        onMainDispatcher {''','''                    if (unavailableIds.isNotEmpty()) {
                        onMainDispatcher {''',1)
# If no IDs, immediately unlock. On confirmation completion, unlock after DB delete.
s=s.replace('''                    }
                }
            }

            R.id.action_remove_duplicate -> {''','''                    } else {
                        onMainDispatcher { unavailableCleanupInProgress = false }
                    }
                }
            }

            R.id.action_remove_duplicate -> {''',1)
s=s.replace('''                                    runOnDefaultDispatcher {
                                        SagerDatabase.proxyDao.deleteUnavailable(groupId)
                                    }
''','''                                    runOnDefaultDispatcher {
                                        SagerDatabase.proxyDao.deleteUnavailable(groupId)
                                        onMainDispatcher { unavailableCleanupInProgress = false }
                                    }
''',1)
# Canceling the dialog must also unlock the action.
s=s.replace('''                                .setNegativeButton(R.string.no, null)
                                .show()''','''                                .setNegativeButton(R.string.no) { _, _ ->
                                    unavailableCleanupInProgress = false
                                }
                                .setOnCancelListener {
                                    unavailableCleanupInProgress = false
                                }
                                .show()''',1)
# If a race causes dialog creation to fail, don't leave the guard stuck.
s=s.replace('''                                }
                                .setNegativeButton(R.string.no, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_tcp_ping''','''                                }
                                .setNegativeButton(R.string.no) { _, _ ->
                                    unavailableCleanupInProgress = false
                                }
                                .setOnCancelListener {
                                    unavailableCleanupInProgress = false
                                }
                                .show()
                        }
                    } else {
                        onMainDispatcher { unavailableCleanupInProgress = false }
                    }
                }
            }

            R.id.action_connection_tcp_ping''',1)
(r/p).write_text(s)
# Page all/group queries so reload and global search never allocate one huge CursorWindow.
helper='''            private fun loadGroupProfilesPaged(groupId: Long): List<ProxyEntity> {
                val result = ArrayList<ProxyEntity>()
                var offset = 0
                while (true) {
                    val page = SagerDatabase.proxyDao.getByGroupPage(groupId, 256, offset)
                    if (page.isEmpty()) break
                    result.addAll(page)
                    if (page.size < 256) break
                    offset += page.size
                }
                return result
            }

            private fun loadAllProfilesPaged(): List<ProxyEntity> {
                val result = ArrayList<ProxyEntity>()
                var offset = 0
                while (true) {
                    val page = SagerDatabase.proxyDao.getAllPage(256, offset)
                    if (page.isEmpty()) break
                    result.addAll(page)
                    if (page.size < 256) break
                    offset += page.size
                }
                return result
            }

'''
anchor='''            fun reloadProfiles() {
'''
if helper not in s:
 if anchor not in s: raise SystemExit('missing reloadProfiles anchor')
 s=s.replace(anchor,helper+anchor,1)
s=s.replace('var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)','var newProfiles = loadGroupProfilesPaged(proxyGroup.id)',1)
s=s.replace('val allProfiles = SagerDatabase.proxyDao.getAll()','val allProfiles = loadAllProfilesPaged()',1)
# Clear-search must restore the already-held adapter cache, not query all beans again.
s=s.replace('''                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }''','''                if (name.isEmpty()) {
                    configurationIdList.clear()
                    configurationIdList.addAll(configurationList.keys)
                    notifyDataSetChanged()
                    return
                }''',1)
# Replace unavailable scan (full entities) with ID-only query and one batch delete.
old='''                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {'''
new='''                    val groupId = DataStore.currentGroupId()
                    val unavailableIds = SagerDatabase.proxyDao.unavailableIds(groupId)
                    if (unavailableIds.isNotEmpty()) {'''
if old in s:
    s=s.replace(old,new,1)
# Replace the large confirmation body with one safe SQL batch operation and cache cleanup.
start_marker='                                    // 014: 与滑动删除同一撤销通道'
start=s.find(start_marker, s.find('R.id.action_connection_test_delete_unavailable'))
if start < 0:
    # Already converted by a previous run.
    start=-1
    end=-1
else:
    show_marker='                                .show()'
    end=s.find(show_marker, start)
    if end >= 0: end += len(show_marker)
replacement='''                                    val currentAdapter =
                                        getCurrentGroupFragment()?.adapter
                                    val removed = unavailableIds.toHashSet()
                                    if (currentAdapter != null) {
                                        val positions = currentAdapter.configurationIdList
                                            .mapIndexedNotNull { i, id -> if (id in removed) i else null }
                                            .asReversed()
                                        positions.forEach { currentAdapter.remove(it) }
                                    }
                                    runOnDefaultDispatcher {
                                        SagerDatabase.proxyDao.deleteUnavailable(groupId)
                                        onMainDispatcher { unavailableCleanupInProgress = false }
                                    }
                                }
                                .setNegativeButton(R.string.no) { _, _ ->
                                    unavailableCleanupInProgress = false
                                }
                                .setOnCancelListener {
                                    unavailableCleanupInProgress = false
                                }
                                .show()'''
if start >= 0 and end >= 0:
    s=s[:start]+replacement+s[end:]
# Ensure the generated dialog unlocks on negative/cancel and when the query is empty.
if 'unavailableCleanupInProgress = false' in s and '.setNegativeButton(R.string.no, null)' in s:
    s=s.replace('''                                .setNegativeButton(R.string.no, null)
                                .show()''','''                                .setNegativeButton(R.string.no) { _, _ ->
                                    unavailableCleanupInProgress = false
                                }
                                .setOnCancelListener {
                                    unavailableCleanupInProgress = false
                                }
                                .show()''',1)
(r/p).write_text(s)
print('PASS: ID-only unavailable cleanup, cached clear-search, no per-row rearrange')
edit(p, '''                val removeAction = {
                    currentAdapter.remove(index)
                    undoManager.remove(index to proxyEntity)
                }''', '''                val removeAction = {
                    val currentIndex = currentAdapter.configurationIdList.indexOf(proxyEntity.id)
                    if (currentIndex in currentAdapter.configurationIdList.indices) {
                        currentAdapter.remove(currentIndex)
                        undoManager.remove(currentIndex to proxyEntity)
                    }
                }''')
edit(p, '''            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
                refreshFromPosition(pos - 1)
            }''', '''            fun remove(pos: Int) {
                if (pos !in configurationIdList.indices) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
                refreshFromPosition((pos - 1).coerceAtLeast(0))
            }''')
edit(p, '''                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    notifyItemRemoved(index)
                    refreshFromPosition(index - 1)''', '''                    val currentIndex = configurationIdList.indexOf(profileId)
                    if (currentIndex !in configurationIdList.indices) return@post
                    configurationIdList.removeAt(currentIndex)
                    configurationList.remove(profileId)
                    notifyItemRemoved(currentIndex)
                    refreshFromPosition((currentIndex - 1).coerceAtLeast(0))''')

p='app/src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt'
edit(p, '''                val index = viewHolder.bindingAdapterPosition
                val group = (viewHolder as GroupHolder).proxyGroup
                groupAdapter.remove(index)''', '''                val index = viewHolder.bindingAdapterPosition
                if (index !in groupAdapter.groupList.indices) return
                val group = (viewHolder as GroupHolder).proxyGroup
                groupAdapter.remove(index)''')
edit(p, '''        fun remove(index: Int) {
            val item = groupList.removeAt(index)
            fullList.removeAll { it.id == item.id }
            notifyItemRemoved(index)
        }''', '''        fun remove(index: Int) {
            if (index !in groupList.indices) return
            val item = groupList.removeAt(index)
            fullList.removeAll { it.id == item.id }
            notifyItemRemoved(index)
        }''')
edit(p, '''                    groupList.removeAt(index)
                    notifyItemRemoved(index)''', '''                    val currentIndex = groupList.indexOfFirst { it.id == groupId }
                    if (currentIndex !in groupList.indices) return@onMainDispatcher
                    groupList.removeAt(currentIndex)
                    notifyItemRemoved(currentIndex)''')
print('PASS: A01 idempotent migration and rapid-delete guards applied')
