package io.nekohasekai.sagernet.bg

/** User-facing URLTest budgets are integer milliseconds, never seconds or an HTTP remainder. */
internal object UrlTestTimeout {
    // Even a fast HEAD needs room for core setup, DNS and transport handshakes.
    // This is an input floor, NOT a floor to reapply after setup has spent the budget.
    const val MIN_MILLIS = 500
    const val DEFAULT_MILLIS = 3000

    fun parseMillis(text: String): Int? {
        val value = text.trim()
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        return value.toIntOrNull()?.takeIf { it >= MIN_MILLIS }
    }

    /** Compatibility for old preferences/backups and callers bypassing the settings dialog. */
    fun normalizeMillis(value: Int): Int = when {
        value <= 0 -> DEFAULT_MILLIS
        value < MIN_MILLIS -> MIN_MILLIS
        else -> value // In particular, never expand a valid 3000 ms budget or cap large values.
    }
}
