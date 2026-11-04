package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.widget.UserAgentPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * REQ-07/REQ-08 订阅多 User-Agent 拼接契约测试（纯 JVM，无 Android 环境依赖）。
 *
 * buildUserAgent(enabledFlags, defaults) 即 RawUpdater 传给请求构建函数
 * setUserAgent(header) 的入参来源（RawUpdater 的优先级链：
 * 逐订阅 customUserAgent → 全局自定义 customUserAgent → 本函数）：
 *  - (a) 三个候选全关 -> 回退 Nets.kt 默认 UA（ktx.USER_AGENT）；
 *  - (b) 仅 clash-verge 开 -> 发送 "clash-verge/v2.4.2"；
 *  - (c) a + c 开 -> 按列表顺序用 ";" 拼接且不含 b（mihomo）；
 *  - (d) 拼接结果整体作为请求 User-Agent header 传入。
 *
 * 候选顺序契约（REQ-08 调整）：clash-verge/v2.4.2 在前、mihomo.party 在后、
 * NekoBox 默认 UA 兜底。很多机场按 UA 前缀/包含关系决定返回格式（包含
 * clash → 返回 YAML），因此第一段必须是 clash-verge。
 */
class UserAgentContractTest {

    private val candidates = listOf(
        "clash-verge/v2.4.2",
        "mihomo.party/v2.0.0 (clash.meta)",
        USER_AGENT,
    )

    // (a) 三个全关 -> 回退默认 UA
    @Test
    fun allDisabledFallsBackToDefaultUserAgent() {
        val ua = UserAgentPreference.buildUserAgent(listOf(false, false, false), candidates)
        assertEquals(USER_AGENT, ua)
    }

    // (b) 仅 clash-verge 开（顺序调整后第一段）
    @Test
    fun clashVergeOnlySendsClashVergeUserAgent() {
        val ua = UserAgentPreference.buildUserAgent(listOf(true, false, false), candidates)
        assertEquals("clash-verge/v2.4.2", ua)
    }

    // (b2) 仅 mihomo 开
    @Test
    fun mihomoOnlySendsMihomoUserAgent() {
        val ua = UserAgentPreference.buildUserAgent(listOf(false, true, false), candidates)
        assertEquals("mihomo.party/v2.0.0 (clash.meta)", ua)
    }

    // (c) clash-verge + neko 开 -> 按序分号拼接，且不含 mihomo
    @Test
    fun clashVergeAndNekoJoinedInOrderWithoutMihomo() {
        val ua = UserAgentPreference.buildUserAgent(listOf(true, false, true), candidates)
        assertEquals("clash-verge/v2.4.2;$USER_AGENT", ua)
        assertFalse(ua.contains("mihomo"))
    }

    // (d) 拼接结果整体作为请求构建函数的 User-Agent header 入参
    @Test
    fun joinedResultIsPassedAsRequestHeader() {
        val header = UserAgentPreference.buildUserAgent(
            listOf(true, true, false),
            UserAgentPreference.userAgentCandidates(),
        )
        // REQ-08 顺序契约：clash-verge 排最前，mihomo 其后
        assertEquals("clash-verge/v2.4.2;mihomo.party/v2.0.0 (clash.meta)", header)
        // 顺序契约的机器校验：candidates 第一段必须是 clash-verge、最后是 NekoBox
        val cands = UserAgentPreference.userAgentCandidates()
        assertEquals("clash-verge/v2.4.2", cands.first())
        assertEquals(USER_AGENT, cands.last())
        // header 进入 RawUpdater setUserAgent(...) 前不再有额外加工：
        // 空开关组合必须回退默认 UA 而非空串
        assertEquals(
            USER_AGENT,
            UserAgentPreference.buildUserAgent(
                listOf(false, false, false),
                UserAgentPreference.userAgentCandidates(),
            ),
        )
    }

    // REQ-08: RawUpdater.parseProfileTitleHeader —— "profile-title" 响应头解析
    // （纯 JVM 函数，直接验证 base64 与 URL-safe/padding 兼容性）
    @Test
    fun profileTitleHeaderPlain() {
        assertEquals("慈善机场", RawUpdater.parseProfileTitleHeader("慈善机场"))
        assertEquals("Example Airport", RawUpdater.parseProfileTitleHeader("Example Airport"))
    }

    @Test
    fun profileTitleHeaderBase64Standard() {
        assertEquals("慈善机场", RawUpdater.parseProfileTitleHeader("base64:5oWI5ZaE5py65Zy6"))
        assertEquals("ab", RawUpdater.parseProfileTitleHeader("base64:YWI="))
        // 无 padding 也必须可解码
        assertEquals("ab", RawUpdater.parseProfileTitleHeader("base64:YWI"))
    }

    @Test
    fun profileTitleHeaderBase64UrlSafe() {
        // URL-safe 字母表：'_' '-' 替代标准字母表中的 '/' '+'，含/不含 padding 均可
        // （'Examples??' 的标准 base64 为 "RXhhbXBsZXM/Pw=="）
        assertEquals("Examples??", RawUpdater.parseProfileTitleHeader("base64:RXhhbXBsZXM_Pw=="))
        assertEquals("Examples??", RawUpdater.parseProfileTitleHeader("base64:RXhhbXBsZXM_Pw"))
    }

    @Test
    fun profileTitleHeaderInvalidReturnsBlank() {
        assertEquals("", RawUpdater.parseProfileTitleHeader(""))
        assertEquals("", RawUpdater.parseProfileTitleHeader("base64:"))
        assertEquals("", RawUpdater.parseProfileTitleHeader("base64:!!!not-base64!!!"))
        assertEquals("", RawUpdater.parseProfileTitleHeader("   "))
    }
}
