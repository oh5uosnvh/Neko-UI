package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libcore.Libcore

internal object CoreOutboundIp {
    suspend fun probe(proxy: ProxyInstance, proxyName: String, urlsJson: String, timeoutMs: Long): String =
        withContext(Dispatchers.IO) { Libcore.outboundIpJSON(proxy.box, proxyName, urlsJson, timeoutMs) }
}
