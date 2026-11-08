package io.nekohasekai.sagernet.widget

/** Display-only fitting: host may shrink, the complete port specification may not. */
internal object EndpointLabelFormatter {
    private val ports = Regex("\\d+(?:[-:]\\d+)?(?:,\\d+(?:[-:]\\d+)?)*")

    fun fit(value: String, width: Float, measure: (String) -> Float): String {
        if (value.isEmpty() || width <= 0f) return ""
        if (measure(value) <= width) return value
        // Bracketed IPv6 has an unambiguous delimiter. For DNS/IPv4, use the
        // first colon so Hysteria's colon-separated port ranges stay intact.
        val delimiter = if (value.startsWith("[")) {
            value.indexOf("]:").takeIf { it >= 0 }?.plus(1) ?: -1
        } else value.indexOf(':')
        val hasPort = delimiter >= 0 && ports.matches(value.substring(delimiter + 1))
        val host = if (hasPort) value.substring(0, delimiter) else value
        val suffix = if (hasPort) value.substring(delimiter) else ""
        val bracketed = host.startsWith("[") && host.endsWith("]")
        val body = if (bracketed) host.substring(1, host.length - 1) else host
        fun candidate(end: Int): String = if (bracketed) {
            "[${body.substring(0, end)}…]$suffix"
        } else body.substring(0, end) + "…" + suffix

        val minimum = candidate(0)
        // The renderer scales only this exceptional port-only fallback when
        // even the complete suffix exceeds the content width. Never crop it.
        if (measure(minimum) > width) return suffix.ifEmpty { "…" }
        val count = body.codePointCount(0, body.length)
        var low = 0
        var high = count
        var best = minimum
        while (low <= high) {
            val mid = (low + high) ushr 1
            val next = candidate(body.offsetByCodePoints(0, mid))
            if (measure(next) <= width) {
                best = next
                low = mid + 1
            } else high = mid - 1
        }
        return best
    }
}
