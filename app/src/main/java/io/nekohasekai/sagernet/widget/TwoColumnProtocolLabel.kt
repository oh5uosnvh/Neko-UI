package io.nekohasekai.sagernet.widget

/** Only the two ambiguous long names are abbreviated in two-column cards. */
internal object TwoColumnProtocolLabel {
    fun display(full: String, twoColumn: Boolean): String = when {
        !twoColumn -> full
        full.equals("Shadowsocks", ignoreCase = true) -> "SS"
        full.equals("ShadowsocksR", ignoreCase = true) -> "SSR"
        else -> full
    }
}
