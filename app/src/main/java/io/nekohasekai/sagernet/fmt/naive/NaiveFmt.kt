package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid naive link: $link")
    return NaiveBean().also {
        it.proto = proto
    }.apply {
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni")
        certificates = url.queryParameter("cert")
        extraHeaders = url.queryParameter("extra-headers")?.unUrlSafe()?.replace("\r\n", "\n")
        insecureConcurrency = url.queryParameter("insecure-concurrency")?.toIntOrNull()
        name = url.fragment
        initializeDefaultValues()
    }
}

fun NaiveBean.toUri(proxyOnly: Boolean = false): String {
    val builder = linkBuilder().host(finalAddress).port(finalPort)
    if (username.isNotBlank()) {
        builder.username(username)
        if (password.isNotBlank()) builder.password(password)
    }
    if (!proxyOnly) {
        if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
        if (certificates.isNotBlank()) builder.addQueryParameter("cert", certificates)
        if (extraHeaders.isNotBlank()) builder.addQueryParameter("extra-headers", extraHeaders)
        if (name.isNotBlank()) builder.encodedFragment(name.urlSafe())
        if (insecureConcurrency > 0) builder.addQueryParameter("insecure-concurrency", "$insecureConcurrency")
    }
    return builder.toLink(if (proxyOnly) proto else "naive+$proto", false)
}

/** Build the official sing-box 1.14 Naive outbound instead of the legacy plugin. */
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): SingBoxOption {
    return CustomSingBoxOption(JSONObject().apply {
        put("type", "naive")
        put("server", bean.serverAddress)
        put("server_port", bean.serverPort)
        put("username", bean.username)
        put("password", bean.password)
        if (bean.insecureConcurrency > 0) put("insecure_concurrency", bean.insecureConcurrency)
        if (bean.extraHeaders.isNotBlank()) {
            val headers = JSONObject()
            bean.extraHeaders.split("\n").forEach { line ->
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
                }
            }
            if (headers.length() > 0) put("extra_headers", headers)
        }
        if (bean.proto.equals("quic", ignoreCase = true)) put("quic", true)
        // Naive requires TLS. NodeHostMapping fills sni with the original
        // hostname when the dial address is replaced by a subscription host.
        put("tls", JSONObject().apply {
            put("enabled", true)
            if (bean.sni.isNotBlank()) put("server_name", bean.sni)
            if (bean.certificates.isNotBlank()) put("certificate", JSONArray().put(bean.certificates))
        })
        if (bean.sUoT == true) put("udp_over_tcp", JSONObject().put("enabled", true))
    }.toString())
}

/** Kept for old plugin users and export compatibility. */
fun NaiveBean.buildNaiveConfig(port: Int): String {
    return JSONObject().apply {
        finalAddress = finalAddress.wrapIPV6Host()
        serverAddress = serverAddress.wrapIPV6Host()
        if (sni.isNotBlank()) {
            put("host-resolver-rules", "MAP $sni $finalAddress")
            finalAddress = sni
        } else if (!serverAddress.isIpAddress()) {
            put("host-resolver-rules", "MAP $serverAddress $finalAddress")
            finalAddress = serverAddress
        }
        put("listen", "socks://$LOCALHOST:$port")
        put("proxy", toUri(true))
        if (extraHeaders.isNotBlank()) put("extra-headers", extraHeaders.split("\n").joinToString("\r\n"))
        if (DataStore.logLevel > 0) put("log", "")
        if (insecureConcurrency > 0) put("insecure-concurrency", insecureConcurrency)
    }.toStringPretty()
}
