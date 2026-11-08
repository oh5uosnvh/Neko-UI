package io.nekohasekai.sagernet.ui

/** A connect click is consumed by Connecting/Connected, never a permanent ON mandate. */
internal class ToggleStartRequest {
    private var requestedEpoch: Long? = null
    fun request(epoch: Long) { requestedEpoch = epoch }
    fun cancel() { requestedEpoch = null }
    fun pending(currentEpoch: Long): Boolean {
        if (requestedEpoch != currentEpoch) requestedEpoch = null
        return requestedEpoch != null
    }
    fun acknowledgeStarted() { requestedEpoch = null }
}
