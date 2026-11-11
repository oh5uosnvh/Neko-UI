package io.nekohasekai.sagernet.group

/** One request failure for the UI; UA negotiation details never belong in a snackbar. */
internal object SubscriptionRequestError {
    fun describe(error: Exception): String {
        val message = error.message?.trim().orEmpty()
        // Native Go errors already include the operation, URL and concrete cause
        // (for example Get "https://example.test/sub": EOF). Do not wrap or
        // append every UA attempt to that useful error.
        return when (error) {
            is java.net.UnknownHostException -> message.ifBlank { "DNS/域名解析失败" }
            is java.net.ConnectException -> message.ifBlank { "连接代理或服务器失败" }
            is java.net.SocketTimeoutException -> message.ifBlank { "连接超时" }
            else -> message.ifBlank { error.javaClass.simpleName }
        }.take(1024)
    }

    fun http(status: Int, statusMessage: String): String {
        val message = statusMessage.trim().removePrefix("HTTP ")
        return if (message.startsWith("$status")) "HTTP $message"
        else "HTTP $status${message.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()}"
    }
}
