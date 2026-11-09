package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.parseProxyLink
import java.io.BufferedReader
import java.io.Reader

/** Bounded-memory line-node import; transaction prevents partial success on cancel. */
internal object StreamingProfileImport {
    const val BATCH_SIZE = 512
    private val schemes = listOf("vless://", "vmess://", "ss://", "ssr://", "trojan://",
        "trojan-go://", "socks://", "socks4://", "socks5://", "anytls://", "hysteria://",
        "hysteria2://", "hy2://", "tuic://", "juicity://", "snell://", "naive+", "xhttp://", "heysocks://")

    fun isNodeListPrefix(prefix: String): Boolean {
        val first = prefix.lineSequence().map { it.trim().removePrefix("\uFEFF") }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false
        return schemes.any(first::startsWith)
    }

    data class Result(val count: Int, val batches: Int, val maxBufferedNodes: Int)

    fun append(db: SagerDatabase, groupId: Long, reader: Reader, source: String,
               fileName: String, ensureActive: () -> Unit = {}): Result {
        val input = if (reader is BufferedReader) reader else reader.buffered()
        var count = 0
        var batches = 0
        var peak = 0
        db.runInTransaction {
            requireNotNull(db.groupDao().getById(groupId)) { "Import group no longer exists" }
            val buffer = ArrayList<AbstractBean>(BATCH_SIZE)
            val session = ProfileBatchSession(db, groupId, source, fileName)
            fun flush() {
                if (buffer.isEmpty()) return
                ensureActive()
                session.append(buffer)
                count += buffer.size
                batches++
                buffer.clear()
            }
            var lineNumber = 0
            while (true) {
                val raw = input.readLine() ?: break
                val line = raw.trim().removePrefix("\uFEFF")
                lineNumber++
                if (lineNumber % 256 == 0) ensureActive()
                if (line.isBlank() || line.startsWith("#")) continue
                // Never log input or exceptions containing node credentials.
                val bean = parseProxyLink(line) ?: error("Invalid node at line $lineNumber")
                buffer.add(bean)
                peak = maxOf(peak, buffer.size)
                if (buffer.size == BATCH_SIZE) flush()
            }
            flush()
            session.finish()
        }
        return Result(count, batches, peak)
    }
}
