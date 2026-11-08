package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.snell.parseSnell
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.juicity.parseJuicity
import io.nekohasekai.sagernet.fmt.oppa.parseOppaNode
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}


// 重名了喵
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}


fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

fun String.linesNoComments(): List<String> {
    return split('\n').map { it.trim() }.filterNot { it.startsWith("#") }
}

fun parseProxyLink(value: String): AbstractBean? {
    val entities = ArrayList<AbstractBean>(1)
    with(value) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        if (startsWith("sn://")) {
            runCatching {
                entities.add(parseUniversal(this))
            }
        } else if (startsWith("socks://") || startsWith("socks4://") || startsWith("socks4a://") || startsWith(
                "socks5://"
            )
        ) {
            runCatching {
                entities.add(parseSOCKS(this))
            }
        } else if (matches("(http|https)://.*".toRegex())) {
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                val clashUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("install-config")
                    .addQueryParameter("url", this)
                    .build()
                    .toString()
                    .replaceFirst("https://", "clash://")
                throw (SubscriptionFoundException(clashUrl))
            }
        } else if (startsWith("vmess://")) {
            runCatching {
                entities.add(parseV2Ray(this))
            }
        } else if (startsWith("vless://")) {
            runCatching {
                entities.add(parseV2Ray(this))
            }
        } else if (startsWith("heysocks://")) {
            runCatching {
                entities.add(moe.matsuri.nb4a.proxy.xhttp.parseXhttpLink(this))
            }
        } else if (startsWith("oppa://")) {
            // oppa:// without '@' = base64(JSON) provider subscription payload
            val isProvider = !substringAfter("oppa://").contains('@')
            if (isProvider) {
                throw SubscriptionFoundException(this)
            }
            runCatching {
                entities.add(parseOppaNode(this))
            }
        } else if (startsWith("trojan://")) {
            runCatching {
                entities.add(parseTrojan(this))
            }
        } else if (startsWith("trojan-go://")) {
            runCatching {
                entities.add(parseTrojanGo(this))
            }
        } else if (startsWith("ss://")) {
            runCatching {
                entities.add(parseShadowsocks(this))
            }
        } else if (startsWith("ssr://")) {
            runCatching {
                entities.add(parseShadowsocksR(this))
            }
        } else if (startsWith("naive+")) {
            runCatching {
                entities.add(parseNaive(this))
            }
        } else if (startsWith("hysteria://")) {
            runCatching {
                entities.add(parseHysteria1(this))
            }
        } else if (startsWith("hysteria2://") || startsWith("hy2://")) {
            runCatching {
                entities.add(parseHysteria2(this))
            }
        } else if (startsWith("tuic://")) {
            runCatching {
                entities.add(parseTuic(this))
            }
        } else if (startsWith("juicity://")) {
            runCatching {
                entities.add(parseJuicity(this))
            }
        } else if (startsWith("snell://")) {
            runCatching {
                entities.add(parseSnell(this))
            }
        } else if (startsWith("anytls://")) {
            runCatching {
                entities.add(parseAnytls(this))
            }
        }
    }
    return entities.firstOrNull()?.apply { initializeDefaultValues() }
}

suspend fun parseProxies(text: String): List<AbstractBean> {
    val entities = ArrayList<AbstractBean>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val whole = parseProxyLink(line)
        // Ordinary newline node lists must not be parsed twice or copied into
        // lines/links/beansByLine collections. Keep legacy space-list support.
        if (' ' !in line) {
            whole?.let(entities::add)
        } else {
            val separated = line.split(' ').mapNotNull { parseProxyLink(it.trim()) }
            if (separated.size > (if (whole == null) 0 else 1)) entities.addAll(separated)
            else whole?.let(entities::add)
        }
    }
    return entities
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
