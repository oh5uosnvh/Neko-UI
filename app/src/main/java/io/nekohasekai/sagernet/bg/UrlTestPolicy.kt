package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/** Honor the configured batch concurrency; never allocate idle workers. */
internal fun urlTestWorkerCount(requested: Int, nodeCount: Int): Int =
    requested.coerceAtLeast(1).coerceAtMost(nodeCount.coerceAtLeast(0))

/**
 * Structured ownership of a batch. A slot cannot dequeue the next node until
 * [test] has returned, including native cleanup. Queue residence isn't a probe
 * budget, and cancellation leaves queued nodes completely unstarted.
 */
internal suspend fun <T> runUrlTestBatch(nodes: List<T>, requested: Int, test: suspend (T) -> Unit) = coroutineScope {
    val queue = ConcurrentLinkedQueue(nodes)
    List(urlTestWorkerCount(requested, nodes.size)) {
        launch {
            while (true) {
                ensureActive()
                val node = queue.poll() ?: break
                ensureActive()
                test(node)
            }
        }
    }.joinAll()
}
