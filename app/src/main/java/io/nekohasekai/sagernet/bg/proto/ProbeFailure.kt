package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * A failure from a one-shot connection probe.
 *
 * The original throwable remains the cause for diagnostics, but [message] is
 * deliberately safe to put in a snackbar or a status record.  In particular,
 * never log this exception as a Throwable: doing so would print its cause and
 * could expose the native error before it has been redacted.
 */
class ProbeFailure private constructor(
    val stage: Stage,
    cause: Throwable,
    hint: String? = null,
) : IOException(displayMessage(stage, cause, hint), cause) {

    // Keep the complete native text available only inside this failure object;
    // it must not become the user-facing message or a log line by accident.
    @Suppress("unused")
    private val actualMessage: String = cause.message ?: cause.toString()

    enum class Stage(val value: String) {
        CONFIG("config"),
        CORE_CREATE("core-create"),
        CORE_START("core-start"),
        HTTP_REQUEST("http-request"),
    }

    companion object {
        /** Wrap an operation failure, while allowing coroutine cancellation through. */
        fun from(stage: Stage, cause: Throwable, hint: String? = null): ProbeFailure {
            if (cause is CancellationException) throw cause
            return cause as? ProbeFailure ?: ProbeFailure(stage, cause, hint)
        }

        private fun displayMessage(stage: Stage, cause: Throwable, hint: String?): String {
            val detail = sanitize(cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName)
            return buildString {
                append("Probe failed at ${stage.value} (NekoBox/${BuildConfig.VERSION_NAME}): ")
                append(detail)
                if (!hint.isNullOrBlank()) append("; ").append(sanitize(hint))
            }
        }

        /** Remove endpoint and credential material without hiding the error class/detail. */
        internal fun sanitize(message: String): String {
            var safe = message

            // URLs include hosts, paths and query tokens, so redact the whole value first.
            safe = URL.replace(safe, "[redacted-url]")
            safe = UUID.replace(safe, "[redacted-uuid]")
            safe = SENSITIVE_VALUE.replace(safe) { "${it.groupValues[1]}[redacted]" }
            safe = BRACKETED_IPV6.replace(safe) { redactHost(it.value) }
            safe = IPV4.replace(safe) { redactHost(it.value) }
            safe = HOSTNAME.replace(safe) { redactHost(it.value) }
            return safe
        }

        private fun redactHost(value: String): String {
            val port = when {
                value.startsWith("[") -> value.substringAfterLast(']').takeIf { it.startsWith(":") } ?: ""
                value.count { it == ':' } == 1 -> value.substringAfterLast(':').let { ":$it" }
                else -> ""
            }
            return "[redacted-host]$port"
        }

        private val URL = Regex(
            "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\\\"'<>]+"
        )
        private val UUID = Regex(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b"
        )
        private val SENSITIVE_VALUE = Regex(
            "(?i)(\\b(?:uuid|token|access[_-]?token|refresh[_-]?token|password|passwd|secret|path|ws[_-]?path|websocket[_-]?path|url)\\s*[:=]\\s*[\\\"']?)[^,\\s}\\\"']+"
        )
        private val BRACKETED_IPV6 = Regex("\\[[0-9a-f:]+\\](?::\\d{1,5})?", RegexOption.IGNORE_CASE)
        private val IPV4 = Regex("(?<![\\w.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?![\\w.])")
        private val HOSTNAME = Regex(
            "(?<![\\w@])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}(?::\\d{1,5})?",
            RegexOption.IGNORE_CASE,
        )
    }
}
