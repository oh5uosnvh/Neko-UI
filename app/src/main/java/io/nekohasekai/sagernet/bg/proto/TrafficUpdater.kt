package io.nekohasekai.sagernet.bg.proto

internal fun applyTrafficDelta(
    item: TrafficUpdater.TrafficLooperData,
    rx: Long,
    tx: Long,
    profileStatisticsEnabled: Boolean,
) {
    item.rx += rx
    item.tx += tx
    if (profileStatisticsEnabled) {
        item.profileRx += rx
        item.profileTx += tx
    }
}

class TrafficUpdater(
    private val box: libcore.BoxInstance,
    val items: List<TrafficLooperData>, // contain "bypass"
) {

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        // rx/tx are live counters used by the speed display. Profile counters
        // deliberately advance only while profile traffic statistics is enabled.
        var tx: Long = 0,
        var rx: Long = 0,
        var profileTx: Long = tx,
        var profileRx: Long = rx,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
        var hasTrafficDelta: Boolean = false,
    )

    private fun updateOne(item: TrafficLooperData, profileStatisticsEnabled: Boolean): TrafficLooperData {
        // last update
        val now = System.currentTimeMillis()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        if (interval <= 0) {
            item.rxRate = 0
            item.txRate = 0
            return TrafficLooperData(tag = item.tag)
        }

        // query
        val tx = box.queryStats(item.tag, "uplink")
        val rx = box.queryStats(item.tag, "downlink")

        // add diff
        applyTrafficDelta(item, rx, tx, profileStatisticsEnabled)
        item.rxRate = rx * 1000 / interval
        item.txRate = tx * 1000 / interval

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    fun updateAll(profileStatisticsEnabled: Boolean = true) {
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        items.forEach { item ->
            item.hasTrafficDelta = false
            if (item.ignore) return@forEach
            val diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                val newDiff = updateOne(item, profileStatisticsEnabled)
                updated[item.tag] = newDiff
                item.hasTrafficDelta = newDiff.rx != 0L || newDiff.tx != 0L
            } else {
                applyTrafficDelta(item, diff.rx, diff.tx, profileStatisticsEnabled)
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
                item.hasTrafficDelta = diff.rx != 0L || diff.tx != 0L
            }
        }
//        Logs.d(JavaUtil.gson.toJson(items))
//        Logs.d(JavaUtil.gson.toJson(updated))
    }
}
