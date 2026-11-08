package io.nekohasekai.sagernet.ui

import java.io.Writer

internal class ClipboardLimitExceeded : RuntimeException()

/** Stop before materializing a ClipData larger than a safe Binder payload. */
internal class ClipboardLimitWriter(private val maxChars: Int) : Writer() {
    private val value = StringBuilder()
    override fun write(chars: CharArray, offset: Int, length: Int) {
        if (value.length.toLong() + length > maxChars) throw ClipboardLimitExceeded()
        value.append(chars, offset, length)
    }
    override fun write(text: String) {
        if (value.length.toLong() + text.length > maxChars) throw ClipboardLimitExceeded()
        value.append(text)
    }
    override fun flush() = Unit
    override fun close() = Unit
    override fun toString(): String = value.toString()
}
