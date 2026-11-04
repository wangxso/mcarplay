package com.mcarplay.app.bt

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内的蓝牙 ACL 状态（地址 → 最近一次 ACL 广播）。由清单接收器 [CarBluetoothReceiver]
 * 写入——ACL 广播在隐式广播豁免清单里，进程活着时每次连上/断开都会送达。
 * 进程重启后为空（= 未知），由 [OutboundProbePolicy] 按未知处理。
 */
object BtAclTracker {
    data class Observation(
        val connected: Boolean,
        /** `SystemClock.elapsedRealtime()`（含深睡，Doze 期间也走时）。 */
        val atMillis: Long,
    )

    private val states = ConcurrentHashMap<String, Observation>()

    fun record(
        address: String,
        connected: Boolean,
        nowMillis: Long,
    ) {
        states[key(address)] = Observation(connected, nowMillis)
    }

    /** null = 本进程未见过该设备的 ACL 广播。 */
    fun observe(address: String): Observation? = states[key(address)]

    /** 测试用。 */
    internal fun clear() = states.clear()

    private fun key(address: String) = address.uppercase(Locale.ROOT)
}

/**
 * 出站 RFCOMM 探测（[Iap2BluetoothClient] 主动连车机）什么时候该跑。
 *
 * 背景（2026-11-03/04 远程日志，Xiaomi 2509FPN0BC + 奇瑞艾瑞泽 8）：车机 ACL 断开、
 * CarPlay 会话结束（「推流终止——恢复蓝牙探测」）之后，客户端仍整夜每分钟主动连一次车机
 * （00:16–10:18 每小时约 55 次 READ_FAILED），连续两晚。车机重新上电时 ACL 连上，清单
 * 接收器会重新拉起会话，所以出站探测只需在下面几种情况下跑：
 * - 该设备 ACL 已连上（车机在线）；
 * - 刚刚 [arm] 过（用户点连接 / ACL 自动拉起 / 会话结束后的短暂宽限），且在那之后
 *   没有收到该设备的 ACL 断开；ACL 状态未知（本进程没见过广播）时只看窗口。
 *   本应用自己引起的断开（[beginSelfLinkChange]/[endSelfLinkChange] 包住的主动断 HFP/A2DP、出站 RFCOMM 尝试）
 *   不算车机离线，窗口照常有效（PR #29 审查：DISCONNECT_PROFILES 后首次 RFCOMM 失败，
 *   后续重试不能被自己触发的 ACL 断开挡掉）。
 * 其余情况暂停，不再发起任何连接（不耗电、不在车机侧刷连接记录）。
 */
class OutboundProbePolicy {
    companion object {
        /** 用户点「连接」或 ACL 自动拉起后，不看 ACL 也要探测的时长。 */
        const val USER_WINDOW_MILLIS = 180_000L

        /** CarPlay 会话结束后的短暂宽限（车上掉线时给自动重连留余地；车机已熄火则到点即停）。 */
        const val SESSION_END_GRACE_MILLIS = 120_000L

        /** 本应用操作蓝牙链路后，其 ACL 断开广播可能晚到的时长（广播异步、断开有延迟）。 */
        const val SELF_LINK_TAIL_MILLIS = 10_000L
    }

    private var armedAt = Long.MIN_VALUE
    private var armedUntil = Long.MIN_VALUE
    private var selfLinkFrom = Long.MIN_VALUE
    private var selfLinkUntil = Long.MIN_VALUE
    private var selfLinkDepth = 0

    /** 打开一个探测窗口（替换此前的窗口）。 */
    @Synchronized
    fun arm(
        nowMillis: Long,
        durationMillis: Long,
    ) {
        armedAt = nowMillis
        armedUntil = nowMillis + durationMillis
    }

    /**
     * 本应用开始改动蓝牙链路（主动断配置文件、出站 RFCOMM 连接），与 [endSelfLinkChange] 成对调用。
     * 操作进行中（不论阻塞多久）范围一直有效；结束后再延 [SELF_LINK_TAIL_MILLIS] 接住晚到的广播。
     * 上一段的尾巴内开始的新操作并入同一段，起点不变。这段时间内的 ACL 断开视为自己引起。
     * 代价：这段时间里车机恰好真的离线，会继续探测到窗口结束（最多 [USER_WINDOW_MILLIS]），仍然有界。
     */
    @Synchronized
    fun beginSelfLinkChange(nowMillis: Long) {
        if (selfLinkDepth == 0 && nowMillis > selfLinkUntil) selfLinkFrom = nowMillis
        selfLinkDepth++
        selfLinkUntil = Long.MAX_VALUE
    }

    /** 见 [beginSelfLinkChange]。 */
    @Synchronized
    fun endSelfLinkChange(nowMillis: Long) {
        if (selfLinkDepth > 0) selfLinkDepth--
        if (selfLinkDepth == 0) selfLinkUntil = nowMillis + SELF_LINK_TAIL_MILLIS
    }

    /**
     * @param acl 该设备最近一次 ACL 广播；null = 未知
     */
    @Synchronized
    fun shouldProbe(
        acl: BtAclTracker.Observation?,
        nowMillis: Long,
    ): Boolean {
        if (acl?.connected == true) return true
        if (nowMillis >= armedUntil) return false
        if (acl == null || armedAt > acl.atMillis) return true
        // 本应用自己断开配置文件 / 出站尝试引起的 ACL 断开：不是车机离线，继续重试
        if (acl.atMillis in selfLinkFrom..selfLinkUntil) return true
        // 窗口打开之后车机自己断开 ACL：已离线（熄火/下车），立即停
        return false
    }
}
