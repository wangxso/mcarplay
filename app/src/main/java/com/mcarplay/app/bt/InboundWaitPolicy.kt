package com.mcarplay.app.bt

import java.util.UUID

/**
 * 「改为等车机回连」判定：车机 SDP 没有 …caff 时，手机主动 RFCOMM 会在几十毫秒内
 * `read failed, read ret: -1`（2026-11-02 艾瑞泽 8 雅实测 34 次全部如此）。这类车机只能由
 * 车机端发起（用户在车机蓝牙列表点手机 → 回连手机的 …cafe），手机每 3 秒连一次毫无用处，
 * 还可能与车机回连争抢 RFCOMM。
 *
 * 只在**真的快速失败过**之后才改为等待：帕萨特 SDP 同样没有 …caff 却能连上（2026-10-31），
 * 首连成功就永远不会进入本策略。等待期间每 [PROBE_INTERVAL_MILLIS] 仍放行一次出站，
 * 以防车机稍后才开放服务。状态只在一轮 [Iap2BluetoothClient.start] 内有效。
 */
internal class InboundWaitPolicy(
    private val now: () -> Long,
) {
    private class Peer {
        var fastFailures = 0
        var waitingSince: Long? = null
        var lastProbeAt = 0L
        var postConnectFastDrops = 0
        var postConnectWaitUntil = 0L
    }

    private val peers = HashMap<String, Peer>()

    @Synchronized
    fun reset() = peers.clear()

    @Synchronized
    fun isWaiting(address: String): Boolean {
        val peer = peers[address] ?: return false
        return peer.waitingSince != null || now() < peer.postConnectWaitUntil
    }

    /**
     * 是否跳过本次出站：
     * - 若连续多次「RFCOMM 已连通但在端点交付前被对端秒断」，即使 [boundedDial] 为 true 也退避
     *   [POST_CONNECT_WAIT_MILLIS]，给接收端回连让出无竞争监听窗口，避免在回连前踩满 3 次快速失败熔断；
     * - 否则按原有 SDP 无 `…caff` 的等待策略每 [PROBE_INTERVAL_MILLIS] 放行一次探测。
     */
    @Synchronized
    fun skipOutbound(
        address: String,
        boundedDial: Boolean = false,
    ): Boolean {
        val peer = peers[address] ?: return false
        val at = now()
        if (peer.postConnectWaitUntil > 0L) {
            if (at < peer.postConnectWaitUntil) return true
            peer.postConnectWaitUntil = 0L
        }
        if (boundedDial || peer.waitingSince == null) return false
        if (at - peer.lastProbeAt < PROBE_INTERVAL_MILLIS) return true
        peer.lastProbeAt = at
        return false
    }

    /** 出站会话真正交付端点：车机确实接受主动连入，清除该地址的全部状态。 */
    @Synchronized
    fun connected(address: String) {
        peers.remove(address)
    }

    /**
     * 记录一次「RFCOMM `connect()` 成功，但在端点交付前即被对端快速关闭」的出站轮次。
     *
     * 连续 [POST_CONNECT_DROP_THRESHOLD] 次（早于自动重连熔断的 3 次阈值）即退避 [POST_CONNECT_WAIT_MILLIS]，
     * 防止像 MacPlay S2 那样在 ~8s 内连打 3 次出站把 `Iap2BluetoothServer` 连同会话一起熔断关停，
     * 错失接收端 ~12s 的主动回连。长耗时协商超时（如 60s/90s）不计入本退避，保留原有熔断路径。
     */
    @Synchronized
    fun postConnectDroppedBeforeEndpoint(
        address: String,
        elapsedMs: Long,
    ): Boolean {
        val peer = peers.getOrPut(address) { Peer() }
        if (elapsedMs !in 0 until POST_CONNECT_FAST_DROP_MILLIS) {
            peer.postConnectFastDrops = 0
            return false
        }
        peer.postConnectFastDrops++
        if (peer.postConnectFastDrops < POST_CONNECT_DROP_THRESHOLD) return false
        peer.postConnectFastDrops = 0
        peer.postConnectWaitUntil = now() + POST_CONNECT_WAIT_MILLIS
        return true
    }

    /**
     * 记录一次出站失败；返回 true 表示此次**刚转入**等待（调用方据此提示用户，只提示一次）。
     * @param reason [BluetoothFailure.describe] 的固定原因（如 `READ_FAILED`）
     */
    @Synchronized
    fun failed(
        address: String,
        services: List<UUID>,
        reason: String?,
        elapsedMs: Long,
    ): Boolean {
        val peer = peers.getOrPut(address) { Peer() }
        if (peer.waitingSince != null) return false
        if (Iap2BluetoothClient.ACCESSORY_IAP2_UUID in services || !isFastReadFailure(reason, elapsedMs)) {
            peer.fastFailures = 0
            return false
        }
        peer.fastFailures++
        val threshold = if (INBOUND_ONLY_SIGNATURE in services) 1 else FAST_FAILURES_TO_WAIT
        if (peer.fastFailures < threshold) return false
        val at = now()
        peer.waitingSince = at
        peer.lastProbeAt = at
        return true
    }

    companion object {
        /**
         * 艾瑞泽 8 两台车机（2026-10-31、2026-11-02）SDP 都带这个厂商 UUID、都没有 …caff，
         * 成功会话均为车机回连。有它时首次快速失败即转入等待，没有时需连续 [FAST_FAILURES_TO_WAIT] 次。
         */
        val INBOUND_ONLY_SIGNATURE: UUID = UUID.fromString("ec884348-cd41-40a2-9727-575d50bf1fd3")

        const val FAST_FAILURES_TO_WAIT = 3

        /** 实测 43–127ms；1 秒以上的失败可能是真超时/射频问题，不据此放弃出站。 */
        const val FAST_FAILURE_MILLIS = 1_000L

        const val PROBE_INTERVAL_MILLIS = 60_000L

        /** RFCOMM 连通后在端点交付前连续快速断开达此阈值时，暂停出站给回连让路（须小于自动熔断阈值 3）。 */
        const val POST_CONNECT_DROP_THRESHOLD = 2

        /** 连通后不足 5 秒即无端点断开视为对端拒绝出站（实测 MacPlay S2 约 90ms）；60s/90s 协商超时不计入。 */
        const val POST_CONNECT_FAST_DROP_MILLIS = 5_000L

        /** 对端拒绝出站后的静默等待窗口（覆盖接收端约 12s 的 SDP 刷新与回连窗口）。 */
        const val POST_CONNECT_WAIT_MILLIS = 30_000L

        fun isFastReadFailure(
            reason: String?,
            elapsedMs: Long,
        ): Boolean = reason == "READ_FAILED" && elapsedMs in 0 until FAST_FAILURE_MILLIS
    }
}
