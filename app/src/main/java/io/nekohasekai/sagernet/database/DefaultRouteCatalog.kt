package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app

/**
 * The only built-in route resources. Keep this list authoritative: reset and updates
 * must never discover arbitrary rule providers, and ProxyMedia is intentionally absent.
 */
object DefaultRouteCatalog {
    data class Item(
        val key: String,
        val nameRes: Int,
        val domains: String = "",
        val ip: String = "",
        val port: String = "",
        val network: String = "",
        val outbound: Long = 0L,
        val remoteUrl: String? = null,
    ) {
        val name: String get() = app.getString(nameRes)
        val enabled: Boolean get() = true
        // Remote YAML is parsed into domains/IPs by RemoteRouteUpdater; never feed it
        // to sing-box as a binary rule-set merely because it has a URL.
        val ruleset: String get() = ""
    }

    private const val JSDELIVR =
        "https://fastly.jsdelivr.net/gh/blackmatrix7/ios_rule_script@master/rule/Clash/"

    private fun remote(key: String, nameRes: Int, folder: String) = Item(
        key = key,
        nameRes = nameRes,
        remoteUrl = "$JSDELIVR$folder/$folder.yaml",
        outbound = 0L,
    )

    /** Strict display/reset order: 5 original resources + 14 remote resources. */
    val items: List<Item> = listOf(
        Item("block_quic", R.string.route_opt_block_quic, port = "443", network = "udp", outbound = -2L),
        Item("block_ads", R.string.route_opt_block_ads, domains = "geosite:category-ads-all", outbound = -2L),
        Item("play_store_cn", R.string.route_builtin_play_store, domains = "domain:googleapis.cn\ndomain:xn--ngstr-lra8j.com\ndomain:xn--ngstr-cn-8za9o.com"),
        remote("remote_google", R.string.route_builtin_google, "Google"),
        remote("remote_twitter", R.string.route_builtin_twitter, "Twitter"),
        remote("remote_facebook", R.string.route_builtin_facebook, "Facebook"),
        remote("remote_instagram", R.string.route_builtin_instagram, "Instagram"),
        remote("remote_tiktok", R.string.route_builtin_tiktok, "TikTok"),
        remote("remote_telegram", R.string.route_builtin_telegram, "Telegram"),
        remote("remote_youtube", R.string.route_builtin_youtube, "YouTube"),
        remote("remote_netflix", R.string.route_builtin_netflix, "Netflix"),
        remote("remote_openai", R.string.route_builtin_openai, "OpenAI"),
        remote("remote_claude", R.string.route_builtin_claude, "Claude"),
        remote("remote_gemini", R.string.route_builtin_gemini, "Gemini"),
        remote("remote_apple", R.string.route_builtin_apple, "Apple"),
        remote("remote_microsoft", R.string.route_builtin_microsoft, "Microsoft"),
        remote("remote_emby", R.string.route_builtin_emby, "Emby"),
        Item("bypass_domain_cn", R.string.route_builtin_domain_cn, domains = "geosite:cn", outbound = -1L),
        Item("bypass_ip_cn", R.string.route_builtin_ip_cn, ip = "geoip:cn", outbound = -1L),
    )

    val remoteItems: List<Item> get() = items.filter { it.remoteUrl != null }

    fun defaults(index: Int, item: Item): RuleEntity = RuleEntity(
        name = item.name,
        config = "",
        userOrder = index.toLong(),
        enabled = item.enabled,
        domains = item.domains,
        ip = item.ip,
        port = item.port,
        network = item.network,
        ruleset = item.ruleset,
        outbound = item.outbound,
        builtinKey = item.key,
        sourceUrl = item.remoteUrl.orEmpty(),
    )
}
