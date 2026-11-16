package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.RemoteRouteUpdater
import io.nekohasekai.sagernet.database.RuleEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class RemoteRouteUpdaterTest {
    private class Cache {
        var rule = RuleEntity(id = 9, builtinKey = "remote_google", domains = "full:old.example",
            cachedRuleCount = 1, enabled = true, outbound = -1, userOrder = 7)
        var cacheWrites = 0
        val dao = Proxy.newProxyInstance(RuleEntity.Dao::class.java.classLoader,
            arrayOf(RuleEntity.Dao::class.java)) { _, method, args ->
            when (method.name) {
                "builtin" -> rule.copy()
                "updateRemoteCache" -> {
                    cacheWrites++
                    rule.domains = args!![2] as String
                    rule.ip = args[3] as String
                    rule.cachedRuleCount = args[4] as Int
                    rule.skippedRuleCount = args[5] as Int
                    rule.lastUpdateError = args[6] as String
                    rule.subscriptionState = "ready"
                    1
                }
                "updateRemoteError" -> {
                    rule.subscriptionState = "stale"
                    rule.lastUpdateError = args!![2] as String
                    1
                }
                else -> error("Unexpected full-row or database operation: ${method.name}")
            }
        } as RuleEntity.Dao
    }
    private fun client(code: Int, body: String, onRequest: () -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            onRequest()
            assertTrue(chain.request().url.toString().contains("/Google/Google.yaml"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").body(body.toResponseBody()).build()
        }.build()

    @Test fun successPublishesValidatedCountWithoutOverwritingConcurrentUserEdits() = runBlocking {
        val cache = Cache()
        val result = RemoteRouteUpdater.updateWith("remote_google", cache.dao,
            client(200, "payload: ['DOMAIN,new.example', 'IP-CIDR,192.0.2.0/24']") {
                cache.rule.enabled = false; cache.rule.outbound = -2; cache.rule.userOrder = 99
            })
        assertEquals(2, result.count)
        assertEquals("full:new.example", cache.rule.domains)
        assertEquals("192.0.2.0/24", cache.rule.ip)
        assertEquals(1, cache.cacheWrites)
        assertFalse(cache.rule.enabled)
        assertEquals(-2L, cache.rule.outbound)
        assertEquals(99L, cache.rule.userOrder)
    }

    @Test fun httpFailureDoesNotEraseOldCacheAndRetryWorks() = runBlocking {
        val cache = Cache()
        try {
            RemoteRouteUpdater.updateWith("remote_google", cache.dao, client(503, "unavailable"))
            fail("HTTP failure must be visible to the UI")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("503")) }
        assertEquals("full:old.example", cache.rule.domains)
        assertEquals(1, cache.rule.cachedRuleCount)
        assertEquals(0, cache.cacheWrites)
        assertEquals("stale", cache.rule.subscriptionState)
        assertEquals(1, RemoteRouteUpdater.updateWith("remote_google", cache.dao,
            client(200, "payload: ['DOMAIN,retry.example']")).count)
    }

    @Test fun invalidYamlCannotReplaceWorkingRules() = runBlocking {
        for (body in listOf("payload: []", "payload: ['PROCESS-NAME,unsupported']", "not-a-payload")) {
            val cache = Cache()
            try {
                RemoteRouteUpdater.updateWith("remote_google", cache.dao, client(200, body))
                fail("Invalid rules must report failure")
            } catch (_: Exception) { }
            assertEquals("full:old.example", cache.rule.domains)
            assertEquals(0, cache.cacheWrites)
        }
    }

    @Test fun oversizedDownloadIsRejectedBeforeCacheWrite() = runBlocking {
        val cache = Cache()
        try {
            RemoteRouteUpdater.updateWith("remote_google", cache.dao, client(200, "x".repeat(4 * 1024 * 1024 + 1)))
            fail("Oversized download must fail")
        } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("too large")) }
        assertEquals(0, cache.cacheWrites)
        assertEquals("full:old.example", cache.rule.domains)
    }
}
