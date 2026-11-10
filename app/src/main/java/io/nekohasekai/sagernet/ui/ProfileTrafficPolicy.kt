package io.nekohasekai.sagernet.ui

/**
 * [tx] and [rx] are cumulative profile counters: persisted bytes before a live
 * update, or the live snapshot (which already includes persisted bytes).
 * Do not add live snapshots to persisted counters again, or inspect speed rates.
 */
internal fun shouldShowProfileTraffic(
    statisticsEnabled: Boolean,
    twoColumn: Boolean,
    tx: Long,
    rx: Long,
): Boolean = statisticsEnabled && !twoColumn && (tx != 0L || rx != 0L)
