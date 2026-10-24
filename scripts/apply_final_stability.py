#!/usr/bin/env python3
"""Apply A01 database and rapid-delete crash fixes."""
from pathlib import Path
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

p='app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt'
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
