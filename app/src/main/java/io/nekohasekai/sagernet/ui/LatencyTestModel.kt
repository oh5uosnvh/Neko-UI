package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity

/** The two real executors already owned by ConfigurationFragment. */
enum class LatencyTestMethod { URL, TCP }
enum class LatencyTestScope { ALL, FAILED, UNTESTED }

data class LatencyTestRequest(
    val groupId: Long,
    val method: LatencyTestMethod,
    val scope: LatencyTestScope,
)

/** Explicit status classification: future status values must not become UNTESTED. */
object LatencyTestStatus {
    fun isFailed(status: Int) = status == 2 || status == 3
    fun isUntested(status: Int) = status == 0
    fun matches(status: Int, scope: LatencyTestScope) = when (scope) {
        LatencyTestScope.ALL -> true
        LatencyTestScope.FAILED -> isFailed(status)
        LatencyTestScope.UNTESTED -> isUntested(status)
    }
}

/** One immutable target selection, made before any target status is reset. */
object LatencyTestSelector {
    fun select(
        profiles: Iterable<ProxyEntity>,
        request: LatencyTestRequest,
        activeIds: Set<Long> = emptySet(),
        supports: (ProxyEntity) -> Boolean,
    ): List<ProxyEntity> = profiles.asSequence()
        .filter { it.groupId == request.groupId }
        .filter { LatencyTestStatus.matches(it.status, request.scope) }
        .filter { it.id !in activeIds }
        .filter(supports)
        .distinctBy { it.id }
        .toList()
}

/** Testing and result-destructive actions share one busy-state visibility gate. */
object LatencyTestMenuPolicy {
    private val affected = intArrayOf(
        io.nekohasekai.sagernet.R.id.action_connection_tcp_ping,
        io.nekohasekai.sagernet.R.id.action_connection_url_test,
        io.nekohasekai.sagernet.R.id.action_connection_test_clear_results,
        io.nekohasekai.sagernet.R.id.action_connection_test_delete_unavailable,
    )
    fun apply(menu: android.view.Menu, busy: Boolean) {
        affected.forEach { id -> menu.findItem(id)?.isVisible = !busy }
    }
    fun isCleanup(id: Int) = id == io.nekohasekai.sagernet.R.id.action_connection_test_clear_results ||
        id == io.nekohasekai.sagernet.R.id.action_connection_test_delete_unavailable
}

/** Reservation is deliberately type-free at the FlMenu boundary. */
interface LatencyMenuReservation {
    fun reserveTestRequest(itemId: Int): Boolean
    fun cancelReservedTestRequest()
}
