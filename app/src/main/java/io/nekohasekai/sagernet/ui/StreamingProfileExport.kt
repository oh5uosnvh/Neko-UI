package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.SagerDatabase
import java.io.Writer
import java.util.concurrent.Callable

/** Constant-size entity page and buffered Writer; no full group or link String. */
internal object StreamingProfileExport {
    const val PAGE_SIZE = 512
    data class Result(val count: Int, val maxPageSize: Int)

    fun write(db: SagerDatabase, groupId: Long, output: Writer,
              ensureActive: () -> Unit = {}): Result = db.runInTransaction(Callable {
        requireNotNull(db.groupDao().getById(groupId)) { "Export group no longer exists" }
        val dao = db.proxyDao()
        var afterOrder = dao.firstOrder(groupId) ?: return@Callable Result(0, 0)
        var afterId = Long.MIN_VALUE
        var count = 0
        var peak = 0
        while (true) {
            ensureActive()
            val page = dao.orderedPageAfter(groupId, afterOrder, afterId, PAGE_SIZE)
            if (page.isEmpty()) break
            peak = maxOf(peak, page.size)
            for (profile in page) {
                if (count > 0) output.write("\n")
                output.write(profile.toStdLink(compact = true))
                count++
            }
            val last = page.last()
            afterOrder = last.userOrder
            afterId = last.id
        }
        Result(count, peak)
    })
}
