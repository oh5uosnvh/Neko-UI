package moe.matsuri.nb4a.proxy.xhttp

import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * 黑石 xhttp 节点 -> sing-box outbound JSON (type=xhttp, 由 libcore/protocol/xhttp 注册).
 *
 * 拨号目标是鉴权网关 (serverAddress/serverPort 已在解析时填 gateway);
 * tls/servername/xhttp-opts 等官方配置字段全是伪装, 直接忽略.
 */
fun buildSingBoxOutboundXhttpBean(bean: XhttpBean): SingBoxOption {
    val json = JSONObject().apply {
        put("type", "xhttp")
        put("server", bean.serverAddress)
        put("server_port", bean.serverPort)
        if (bean.password.isNotBlank()) put("password", bean.password)
        // sess 为空时不下发，客户端会自动随机生成 68 字节前缀。
        if (bean.sess.isNotBlank()) put("sess", bean.sess)
        val effAuth = bean.effectiveAuth()
        if (effAuth.isNotBlank()) put("auth", effAuth)
        if (bean.uuid.isNotBlank()) put("uuid", bean.uuid)
    }
    return CustomSingBoxOption(json.toString(2))
}

fun XhttpBean.toUri(): String {
    val sb = StringBuilder("xhttp://")
        .append(serverAddress.wrapIPV6Host())
        .append(":").append(serverPort)
    if (password.isNotBlank()) sb.append("?password=").append(password)
    // 只有用户显式覆盖时才带（兼容手工填的特殊节点）。
    if (sess.isNotBlank()) sb.append(if (sb.contains('?')) "&" else "?").append("sess=").append(sess)
    if (auth.isNotBlank()) sb.append(if (sb.contains('?')) "&" else "?").append("auth=").append(auth)
    if (!name.isNullOrBlank()) sb.append("#").append(name.urlSafe())
    return sb.toString()
}

fun parseXhttpLink(url: String): XhttpBean {
    val normalized = when {
        url.startsWith("xhttp://") -> url
        url.startsWith("heysocks://") -> url.replaceFirst("heysocks://", "xhttp://")
        else -> error("Unsupported xhttp link: $url")
    }
    val link = normalized.replaceFirst("xhttp://", "https://a:a@").toHttpUrlOrNull()
        ?: error("Invalid xhttp URL: $url")

    return XhttpBean().apply {
        serverAddress = link.host
        serverPort = if (link.port > 0) link.port else 443
        name = link.fragment ?: "xhttp $serverAddress:$serverPort"
        password = link.queryParameter("password") ?: ""
        sess = link.queryParameter("sess")
            ?: link.queryParameter("fake_net_tcp") ?: ""
        auth = link.queryParameter("auth")
            ?: link.queryParameter("token") ?: ""
        paddingLen = link.queryParameter("padding_len") ?: "8-64"
    }
}
