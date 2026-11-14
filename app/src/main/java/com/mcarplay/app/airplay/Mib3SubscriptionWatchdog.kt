package com.mcarplay.app.airplay

/**
 * MIB3 突发后「车机是否订阅」的观察窗口（纯逻辑，便于单测）。
 *
 * 真机（奥迪 MIB3）在 0xAA05 突发送出后立即回订阅：0x4157（通信）、0x4154（通话）、
 * 0x4170（列表）、0x5000（NowPlaying）、0x6800（StartHID）。issue #62（奔驰 MB_E-P-4，
 * AirPlay 480.7.4）里这些**一条都没有**，同时车机不显示画面、点图标无反应——这个组合
 * 值得在报告里明确写出来，而不是只靠「没有订阅日志」这种缺席证据。
 *
 * 本类不做超时线程：宿主每 200 ms 的节拍线程调用 [poll]，窗口到期只报告一次。
 */
internal class Mib3SubscriptionWatchdog(
    /** 突发后等首批订阅的窗口长度（毫秒）。 */
    private val windowMillis: Long,
) {
    private var armedAtMs = -1L
    private var reported = false
    private val seen = LinkedHashSet<Int>()

    /** 是否已布防（突发至少发过一次）。 */
    @get:Synchronized
    val armed: Boolean get() = armedAtMs >= 0

    /** 布防（突发发出时调用）；重复调用只刷新起点，不重置已见订阅。 */
    @Synchronized
    fun arm(nowMillis: Long) {
        armedAtMs = nowMillis
        reported = false
    }

    /** 记录一条车机发来的订阅类消息号。 */
    @Synchronized
    fun observe(messageId: Int) {
        seen += messageId
    }

    @Synchronized
    fun seenIds(): Set<Int> = seen.toSet()

    /**
     * 节拍调用：窗口到期且一条订阅都没有时返回一行说明；否则 null。
     * 只报告一次；已见订阅时不报告。未布防时返回 null。
     */
    @Synchronized
    fun poll(nowMillis: Long): String? {
        if (armedAtMs < 0 || reported) return null
        if (nowMillis - armedAtMs < windowMillis) return null
        reported = true
        if (seen.isNotEmpty()) return null
        return "突发后 ${windowMillis / 1000}s 车机未发任何订阅" +
            "（0x4157/0x4154/0x4170/0x5000/0x6800 均无）——车机可能未把本会话当作有效 CarPlay 会话"
    }

    /** 会话结束时的汇总（无论是否有订阅都给出一行，便于报告对照）。 */
    @Synchronized
    fun summary(nowMillis: Long): String {
        if (armedAtMs < 0) return "MIB3 突发未发送"
        val elapsed = (nowMillis - armedAtMs).coerceAtLeast(0)
        val ids =
            if (seen.isEmpty()) {
                "无"
            } else {
                seen.sorted().joinToString(" ") { "0x%04x".format(it) }
            }
        return "突发后 ${elapsed}ms 收到订阅：$ids"
    }

    companion object {
        /** 默认观察窗口：真机 1 s 内给首批，60 s 足以定性。 */
        const val WINDOW_MILLIS = 60_000L
    }
}
