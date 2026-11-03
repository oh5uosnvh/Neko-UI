package io.nekohasekai.sagernet.ui

/** The latency slot is NOT a log viewer. Detailed failures belong to the official Snackbar. */
internal object LatencyFailureLabel {
    enum class Kind { TIMEOUT, UNAVAILABLE, RESET }
    fun kind(error: String): Kind {
        val lower = error.lowercase(java.util.Locale.ROOT)
        return when {
            lower.contains("timeout") || lower.contains("timed out") || lower.contains("deadline") || error.contains("超时") || error.contains("逾時") -> Kind.TIMEOUT
            lower.contains("reset") || error.contains("重置") || error.contains("重設") -> Kind.RESET
            else -> Kind.UNAVAILABLE
        }
    }
}
