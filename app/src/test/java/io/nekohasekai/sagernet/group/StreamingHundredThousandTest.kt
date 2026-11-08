package io.nekohasekai.sagernet.group

import android.app.Application
import androidx.room.Room
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.io.Writer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class StreamingHundredThousandTest {
    private fun database() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
        .allowMainThreadQueries().build()

    @Test fun hundredThousandRealVlessLinksImportAndExportWithBoundedPages() {
        val db = database()
        try {
            val group = ProxyGroup(name = "stress")
            group.id = db.groupDao().createGroup(group)
            val external = System.getenv("NEKO_STRESS_FILE")?.let(::File)?.takeIf { it.isFile }
            val reader: Reader = external?.bufferedReader() ?: object : Reader() {
                private var index = 0
                private var current = ""
                private var offset = 0
                override fun read(target: CharArray, start: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (offset == current.length) {
                        if (index == 100_000) return -1
                        index++
                        current = "vless://00000000-0000-4000-8000-000000000001@node$index.example:443?encryption=none&security=tls&sni=tls.example&type=ws&path=%2F#Node$index\n"
                        offset = 0
                    }
                    val count = minOf(length, current.length - offset)
                    current.toCharArray(target, start, offset, offset + count)
                    offset += count
                    return count
                }
                override fun close() = Unit
            }
            val start = System.nanoTime()
            val imported = reader.use { StreamingProfileImport.append(db, group.id, it, "file", "stress.txt") }
            val importMs = (System.nanoTime() - start) / 1_000_000
            assertEquals(100_000, imported.count)
            assertEquals(100_000L, db.proxyDao().countByGroup(group.id))
            assertTrue(imported.maxBufferedNodes <= 512)
            var lines = 0
            var chars = 0L
            val output = object : Writer() {
                override fun write(cbuf: CharArray, off: Int, len: Int) {
                    chars += len
                    for (i in off until off + len) if (cbuf[i] == '\n') lines++
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
            val exportStart = System.nanoTime()
            val exported = StreamingProfileExport.write(db, group.id, output)
            val exportMs = (System.nanoTime() - exportStart) / 1_000_000
            assertEquals(100_000, exported.count)
            assertEquals(99_999, lines)
            assertTrue(exported.maxPageSize <= 512)
            assertTrue(chars > 5_000_000)
            println("STRESS nodes=100000 source=${if (external == null) "generated" else "user-file"} import_ms=$importMs export_ms=$exportMs buffered=${imported.maxBufferedNodes} page=${exported.maxPageSize} chars=$chars")
        } finally { db.close() }
    }

    @Test fun malformedFileRollsBackEarlierBatchesWithoutPartialImport() {
        val db = database()
        try {
            val group = ProxyGroup(name = "rollback")
            group.id = db.groupDao().createGroup(group)
            val line = "vless://00000000-0000-4000-8000-000000000001@node.example:443#Node\n"
            assertThrows(IllegalStateException::class.java) {
                StreamingProfileImport.append(db, group.id, StringReader(line.repeat(600) + "invalid-node"), "file", "test.txt")
            }
            assertEquals(0L, db.proxyDao().countByGroup(group.id))
        } finally { db.close() }
    }

    @Test fun oversizedClipboardWriterFailsBeforeAllocatingWholeGroup() {
        val writer = ClipboardLimitWriter(10)
        writer.write("1234567890")
        assertThrows(ClipboardLimitExceeded::class.java) { writer.write("1") }
        assertEquals("1234567890", writer.toString())
    }
}
