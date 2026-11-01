package com.mcarplay.app.net

/**
 * 「何时连 AirPlay」的判定（纯函数，单测钉死）。
 *
 * 帕萨特 2025（VW4211，2026-10-31 23:51–23:59 实测）7 次连入里 5 次在初始 SETUP 后被车机
 * 关控制通道：都是「已发现 _airplay._tcp、还没等到 ctrl 邀请」就直接连入，邀请在 0.3–0.6s 后
 * 才到；成功的 2 次都在 7s/13s 前刚收到邀请，失败的另一次用的是 42s 前的旧邀请。
 * 因此：新鲜邀请优先；mDNS 直连只作为不发邀请的旧机型兜底，且要先给邀请一个宽限期。
 *
 * 宽限期从「广播确认可见」起算（2026-11-01 22:07 实测）：广播 59.895 可见，旧实现按开始等待
 * 起算的 2 s 强制重公告在 1.2 s 后把它注销重注册，02.052 才再次可见，3 s 宽限又在 45 ms 后到期，
 * 未等邀请直连再次被拒。车机此前都在广播可见后 0.1–0.3 s 发邀请。
 */
internal object CtrlInviteGate {
    /**
     * 邀请在多久内算「新鲜」（按当前时刻评估，等待期间旧邀请会过期）。
     * 20s / 3s / 2s 是帕萨特单车现场观察得出的兼容阈值，不是 Apple 协议常量。
     */
    const val FRESH_INVITE_MS = 20_000L

    /** 广播可见（或重公告）后仍无邀请，再等这么久才走 mDNS 直连。 */
    const val MDNS_GRACE_MS = 3_000L

    /** 一直没确认广播可见时，等这么久补一次公告（catplay force_announce：绑网瞬间的公告常丢）。 */
    const val UNSEEN_REANNOUNCE_MS = 2_000L

    enum class Decision { INVITED, REANNOUNCE, MDNS_FALLBACK, WAIT }

    /**
     * @param advertisedAtMillis 广播最近一次确认可见的时刻（0 = 当前不可见 / 未确认）
     * @param reannouncedAtMillis 本次等待中补发公告的时刻（0 = 尚未补发；只补一次）
     */
    fun decide(
        nowMillis: Long,
        waitStartedAtMillis: Long,
        lastInviteAtMillis: Long,
        mdnsEndpointFound: Boolean,
        advertisedAtMillis: Long = 0L,
        reannouncedAtMillis: Long = 0L,
    ): Decision {
        if (isFresh(lastInviteAtMillis, nowMillis)) return Decision.INVITED
        val reannounced = reannouncedAtMillis > 0L
        val visibleSince = maxOf(waitStartedAtMillis, advertisedAtMillis, reannouncedAtMillis)
        if (!reannounced) {
            // 广播从未确认可见：catplay 的「公告丢失」场景，2 s 后补一次。
            // 已可见但宽限期内没有邀请：车机可能漏看了这次公告，直连前再给一次机会。
            val due =
                if (advertisedAtMillis <= 0L) {
                    nowMillis - waitStartedAtMillis >= UNSEEN_REANNOUNCE_MS
                } else {
                    nowMillis - visibleSince >= MDNS_GRACE_MS
                }
            return if (due) Decision.REANNOUNCE else Decision.WAIT
        }
        return if (mdnsEndpointFound && nowMillis - visibleSince >= MDNS_GRACE_MS) Decision.MDNS_FALLBACK else Decision.WAIT
    }

    fun isFresh(
        lastInviteAtMillis: Long,
        referenceMillis: Long,
    ): Boolean = lastInviteAtMillis > 0L && lastInviteAtMillis >= referenceMillis - FRESH_INVITE_MS
}
