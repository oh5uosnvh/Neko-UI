package io.nekohasekai.sagernet.widget

/** Display-only names for the two-column protocol/latency row. Entity/export/menu names stay full. */
internal object TwoColumnProtocolLabel {
    fun display(full: String, twoColumn: Boolean): String {
        if (!twoColumn) return full
        return when (full.lowercase(java.util.Locale.ROOT).replace(" ", "")) {
            "wireguard" -> "WG"
            "shadowtls" -> "st"
            "hysteria", "hysteria1" -> "hy"
            "hysteria2" -> "hy2"
            "shadowsocks" -> "ss"
            "shadowsocksr" -> "ssr"
            else -> full
        }
    }
}
