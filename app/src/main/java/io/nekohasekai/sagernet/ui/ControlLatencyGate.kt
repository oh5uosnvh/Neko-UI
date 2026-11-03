package io.nekohasekai.sagernet.ui

/** Main-thread request identity: an old test must not publish into a new connection/profile. */
internal class ControlLatencyGate {
    data class Request(val generation: Long, val selectedId: Long, val runningId: Long)
    private var generation = 0L
    private var current: Request? = null

    fun begin(selectedId: Long, runningId: Long): Request? {
        if (current != null) return null
        return Request(++generation, selectedId, runningId).also { current = it }
    }

    fun finish(request: Request, connected: Boolean, selectedId: Long, runningId: Long): Boolean {
        if (current != request) return false
        current = null
        return connected && request.selectedId == selectedId && request.runningId == runningId
    }

    fun invalidate() {
        generation++
        current = null
    }
}
