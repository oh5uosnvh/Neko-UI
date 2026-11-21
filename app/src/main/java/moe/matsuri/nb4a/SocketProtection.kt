package moe.matsuri.nb4a

/** A failed VpnService.protect must fail the Go dial rather than loop into VPN. */
internal fun requireSocketProtection(fd: Int, protect: ((Int) -> Boolean)?) {
    val action = protect ?: throw IllegalStateException("socket protect: VPN service unavailable")
    check(action(fd)) { "socket protect failed" }
}
