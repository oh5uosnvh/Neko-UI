package io.nekohasekai.sagernet.widget

/** Same protocol semantics in one/two-column layouts; width handles truncation. */
internal object TwoColumnProtocolLabel {
    fun display(full: String, twoColumn: Boolean): String = full
}
