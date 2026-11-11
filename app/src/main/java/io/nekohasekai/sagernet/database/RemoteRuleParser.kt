package io.nekohasekai.sagernet.database

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import io.nekohasekai.sagernet.ktx.isIpAddress

internal object RemoteRuleParser {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun url(value: String): String {
        val parsed = value.trim().toHttpUrlOrNull() ?: error("规则链接不是有效的 HTTP(S) 地址")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "规则链接不能包含账号密码" }
        require(parsed.fragment.isNullOrEmpty()) { "规则链接不能包含片段" }
        return parsed.toString()
    }
    fun parse(text: String): RemoteRouteUpdater.Result {
        require(text.toByteArray().size <= MAX_BYTES) { "规则文件超过 4 MiB" }
        val options = LoaderOptions().apply { maxAliasesForCollections = 0; codePointLimit = MAX_BYTES }
        val root = Yaml(SafeConstructor(options)).load<Any?>(text) as? Map<*, *> ?: error("不是有效的 YAML 规则文档")
        val payload = root["payload"] as? List<*> ?: error("规则文档缺少 payload 列表")
        val domains = linkedSetOf<String>(); val ips = linkedSetOf<String>(); val reasons = linkedSetOf<String>()
        var skipped = 0
        fun domain(raw: String, kind: String) {
            val v = raw.trim().lowercase().trimEnd('.')
            require(v.isNotBlank() && v.length <= 253 && !v.any { it.isWhitespace() || it in ",/:" }) { "域名规则格式错误" }
            domains += "$kind:$v"
        }
        fun ip(raw: String) {
            val parts = raw.trim().split('/')
            require(parts.size == 2 && parts[0].isIpAddress()) { "IP 网段格式错误: $raw" }
            val prefix = parts[1].toIntOrNull() ?: error("IP 掩码格式错误")
            require(prefix in 0..if (':' in parts[0]) 128 else 32) { "IP 掩码越界" }
            ips += raw.trim()
        }
        payload.forEach { raw ->
            require(raw is String) { "payload 只能包含规则字符串" }
            val line = raw.trim(); if (line.isBlank()) return@forEach
            val p = line.split(',').map { it.trim() }
            if (p.size == 1) {
                when {
                    '/' in line -> ip(line)
                    line.startsWith("+.") -> domain(line.removePrefix("+."), "domain")
                    line.startsWith("*.") -> domain(line.removePrefix("*."), "domain")
                    line.startsWith('.') -> domain(line.removePrefix("."), "domain")
                    else -> domain(line, "full")
                }
            } else when (p[0].uppercase()) {
                "DOMAIN" -> domain(p[1], "full")
                "DOMAIN-SUFFIX" -> domain(p[1], "domain")
                "DOMAIN-KEYWORD" -> domain(p[1], "keyword")
                "IP-CIDR", "IP-CIDR6" -> ip(p[1])
                else -> { skipped++; reasons += "不支持 ${p[0]}" }
            }
        }
        require(domains.isNotEmpty() || ips.isNotEmpty()) { "没有可用的域名或 IP 规则；未替换原缓存" }
        return RemoteRouteUpdater.Result(domains.size + ips.size, skipped, reasons.joinToString(", "), domains.toList(), ips.toList())
    }
}
