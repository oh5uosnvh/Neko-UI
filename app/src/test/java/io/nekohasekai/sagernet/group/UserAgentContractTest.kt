package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.widget.UserAgentPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * REQ-07 订阅多 User-Agent 拼接契约测试（纯 JVM，无 Android 环境依赖）。
 *
 * buildUserAgent(enabledFlags, defaults) 即 RawUpdater 传给请求构建函数
 * setUserAgent(header) 的入参来源：
 *  - (a) 三个候选全关 -> 回退 Nets.kt 默认 UA（ktx.USER_AGENT）；
 *  - (b) 仅 mihomo.party 开 -> 发送 "mihomo.party/v2.0.0 (clash.meta)"；
 *  - (c) a + c 开 -> 按列表顺序用 ";" 拼接且不含 b（clash-verge）；
 *  - (d) 拼接结果整体作为请求 User-Agent header 传入。
 */
class UserAgentContractTest {

    private val candidates = listOf(
        "mihomo.party/v2.0.0 (clash.meta)",
        "clash-verge/v2.4.2",
        USER_AGENT,
    )

    // (a) 三个全关 -> 回退默认 UA
    @Test
    fun allDisabledFallsBackToDefaultUserAgent() {
        val ua = UserAgentPreference.buildUserAgent(listOf(false, false, false), candidates)
        assertEquals(USER_AGENT, ua)
    }

    // (b) 仅 a（mihomo.party）开
    @Test
    fun mihomoOnlySendsMihomoUserAgent() {
        val ua = UserAgentPreference.buildUserAgent(listOf(true, false, false), candidates)
        assertEquals("mihomo.party/v2.0.0 (clash.meta)", ua)
    }

    // (c) a + c 开 -> 按序分号拼接，且不含 b
    @Test
    fun mihomoAndNekoJoinedInOrderWithoutClashVerge() {
        val ua = UserAgentPreference.buildUserAgent(listOf(true, false, true), candidates)
        assertEquals("mihomo.party/v2.0.0 (clash.meta);$USER_AGENT", ua)
        assertFalse(ua.contains("clash-verge"))
    }

    // (d) 拼接结果整体作为请求构建函数的 User-Agent header 入参
    @Test
    fun joinedResultIsPassedAsRequestHeader() {
        val header = UserAgentPreference.buildUserAgent(
            listOf(true, true, false),
            UserAgentPreference.userAgentCandidates(),
        )
        assertEquals("mihomo.party/v2.0.0 (clash.meta);clash-verge/v2.4.2", header)
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
}
