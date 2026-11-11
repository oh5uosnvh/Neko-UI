package io.nekohasekai.sagernet.database

import android.content.Intent
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceStartPolicy

/** Commit notification, not a success notification. The running :bg service
 * builds the latest full snapshot, attempts native hot apply, and may perform
 * a clearly-labelled preflight/reconnect fallback when capabilities changed.
 * No config, rule URLs or node credentials cross the broadcast boundary.
 */
object RuntimeRouteUpdater {
    const val ACTION_APPLY = "io.nekohasekai.sagernet.APPLY_ROUTE_RUNTIME"
    const val EXTRA_REASON = "route_update_reason"
    enum class Reason { RULES, ENABLED, OUTBOUND, ORDER, RESET, REMOTE, GLOBAL_MODE }

    fun request(reason: Reason = Reason.RULES) {
        if (!DataStore.serviceState.started) return
        val epoch = ServiceStartPolicy.fence.snapshot()
        if (!ServiceStartPolicy.fence.permitsAutomatic(epoch)) return
        SagerNet.application.sendBroadcast(ServiceStartPolicy.tag(
            Intent(ACTION_APPLY).setPackage(SagerNet.application.packageName)
                .putExtra(EXTRA_REASON, reason.name), "route-runtime", epoch,
        ))
    }
}
