package com.mcarplay.app.audio

/**
 * 判定 UDP 发送失败是短暂断路还是链路已断。
 *
 * 路由规则重建、网卡重新配置时 sendto 会短暂返回 ENETUNREACH/EHOSTUNREACH；
 * 这类失败只丢当前包，连续失败超过 [graceNanos] 才判定链路已断。
 */
internal class UdpSendGate(
    private val graceNanos: Long = DEFAULT_GRACE_NANOS,
    private val clock: () -> Long = System::nanoTime,
) {
    private var failingSince = 0L
    private var failing = false

    /** 连续失败中已丢弃的包数。 */
    var dropped = 0
        private set

    /** 记录一次失败；返回 true 表示仍在宽限期内，调用方丢包后继续。 */
    fun onFailure(): Boolean {
        val now = clock()
        if (!failing) {
            failing = true
            failingSince = now
            dropped = 0
        }
        dropped++
        return now - failingSince < graceNanos
    }

    /** 记录一次成功；从失败中恢复时返回本轮丢弃的包数，否则返回 null。 */
    fun onSuccess(): Int? {
        if (!failing) return null
        failing = false
        return dropped.also { dropped = 0 }
    }

    /** 当前连续失败已持续的毫秒数（未失败时为 0）。 */
    fun failingMillis(): Long = if (failing) (clock() - failingSince) / 1_000_000L else 0L

    companion object {
        const val DEFAULT_GRACE_NANOS = 3_000_000_000L
    }
}
