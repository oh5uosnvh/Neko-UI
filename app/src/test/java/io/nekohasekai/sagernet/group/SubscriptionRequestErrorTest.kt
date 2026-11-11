package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SubscriptionRequestErrorTest {
    @Test fun nativeEofIsPreservedWithoutUaOrAggregation() {
        val original = "Get \"https://example.test/sub\": EOF"
        assertEquals(original, SubscriptionRequestError.describe(Exception(original)))
        assertFalse(SubscriptionRequestError.describe(Exception(original)).contains(" → "))
    }

    @Test fun nativeDnsAndTimeoutKeepTheirConcreteReason() {
        for (message in listOf("Get \"https://example.test/sub\": dial tcp: lookup example.test: no such host", "Get \"https://example.test/sub\": context deadline exceeded")) {
            assertEquals(message, SubscriptionRequestError.describe(Exception(message)))
        }
        assertEquals("DNS/域名解析失败", SubscriptionRequestError.describe(java.net.UnknownHostException()))
        assertEquals("连接超时", SubscriptionRequestError.describe(java.net.SocketTimeoutException()))
    }

    @Test fun statusDoesNotDuplicateTheNumericCode() {
        assertEquals("HTTP 403 Forbidden", SubscriptionRequestError.http(403, "403 Forbidden"))
        assertEquals("HTTP 404 Not Found", SubscriptionRequestError.http(404, "HTTP 404 Not Found"))
        assertEquals("HTTP 500", SubscriptionRequestError.http(500, ""))
    }

    @Test fun noUaFailuresAreJoinedInTheLiveRequestPath() {
        val root = java.io.File(System.getProperty("user.dir"))
        val source = sequenceOf(root, root.parentFile).map {
            java.io.File(it, "app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt")
        }.first { it.isFile }.readText()
        assertFalse(source.contains("failures.distinct().joinToString"))
        assertFalse(source.contains("failures += \"\$ua"))
    }
}
