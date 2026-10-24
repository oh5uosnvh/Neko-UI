#!/usr/bin/env python3
from pathlib import Path
import sys
r=Path(sys.argv[1]).resolve();p=r/'app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt';s=p.read_text()
def rep(old,new):
 global s
 if new in s:return
 if old not in s:raise SystemExit('missing migration anchor')
 s=s.replace(old,new,1)
rep('import androidx.room.AutoMigration\n','import androidx.room.AutoMigration\nimport androidx.room.migration.Migration\nimport androidx.sqlite.db.SupportSQLiteDatabase\n')
rep('''        AutoMigration(from = 7, to = 8),
        // mod protocols: ProxyEntity adds xhttpBean / oppaBean BLOB columns
        AutoMigration(from = 8, to = 9)
''','''        AutoMigration(from = 7, to = 8)
''')
rep('    companion object {\n','''    companion object {
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
rep('Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)\n','Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)\n                .addMigrations(MIGRATION_8_9_SAFE)\n')
p.write_text(s);print('PASS: safe Room 8->9 migration only; undo/delete logic untouched')
