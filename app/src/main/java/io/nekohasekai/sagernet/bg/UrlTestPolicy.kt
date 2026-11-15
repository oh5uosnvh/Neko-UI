package io.nekohasekai.sagernet.bg

/** Honor the configured batch concurrency; never allocate idle workers. */
internal fun urlTestWorkerCount(requested: Int, nodeCount: Int): Int =
    requested.coerceAtLeast(1).coerceAtMost(nodeCount.coerceAtLeast(0))
