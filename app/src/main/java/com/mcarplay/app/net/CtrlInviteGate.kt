package com.mcarplay.app.net

/**
 * 「何时连 AirPlay」的判定（纯函数，单测钉死）。
 *
 * 帕萨特 2025（VW4211，2026-10-31 23:51–23:59 实测）7 次连入里 5 次在初始 SETUP 后被车机
 * 关控制通道：都是「已发现 _airplay._tcp、还没等到 ctrl 邀请」就直接连入，邀请在 0.3–0.6s 后
 * 才到；成功的 2 次都在 7s/13s 前刚收到邀请，失败的另一次用的是 42s 前的旧邀请。
 * 因此：新鲜邀请优先；mDNS 直连只作为不发邀请的旧机型兜底，且要先给邀请一个宽限期。
 */
internal object CtrlInviteGate {
    /** 邀请在多久内算「新鲜」（相对开始等待的时刻往前追溯）。 */
    const val FRESH_INVITE_MS = 20_000L

    /** 已发现 _airplay._tcp 但无邀请时，先等这么久再走 mDNS 直连。 */
    const val MDNS_GRACE_MS = 3_000L

    enum class Decision { INVITED, MDNS_FALLBACK, WAIT }

    fun decide(
        nowMillis: Long,
        waitStartedAtMillis: Long,
        lastInviteAtMillis: Long,
        mdnsEndpointFound: Boolean,
    ): Decision =
        when {
            isFresh(lastInviteAtMillis, waitStartedAtMillis) -> Decision.INVITED
            mdnsEndpointFound && nowMillis - waitStartedAtMillis >= MDNS_GRACE_MS -> Decision.MDNS_FALLBACK
            else -> Decision.WAIT
        }

    fun isFresh(
        lastInviteAtMillis: Long,
        referenceMillis: Long,
    ): Boolean = lastInviteAtMillis > 0L && lastInviteAtMillis >= referenceMillis - FRESH_INVITE_MS
}
