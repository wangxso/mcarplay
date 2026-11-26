package com.mcarplay.app.airplay

import com.mcarplay.app.compat.ReceiverCompatibility

/**
 * 用户点击连接形成的一次性主屏接管意图。
 *
 * 只在用户明确发起连接时置位，并在首个资源声明获得接收端确认后消费一次。每次手动启动都会
 * 生成新的世代号，[lease] 取得固定世代；旧客户端因此不能误消费更新一轮的意图。自动重连、
 * ctrl 邀请重连和同一端点回放只有在原轮次仍活跃时由编排层明确延续，否则撤销旧意图。
 */
class ExplicitStartScreenClaim(
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    inner class Lease internal constructor(
        internal val generation: Long,
        internal val attempt: Long,
    ) {
        /** 主屏接管意图仍属于同一用户启动轮次且尚未确认成功。 */
        fun isPending(): Boolean = isLeasePending(this)

        /** 该尝试仍保留在世代中，可能已被 adopt、仍在暂存，或只是等待接替旧尝试。 */
        fun isRetained(): Boolean = isLeaseRetained(this)

        /** 仅当该意图仍属于同一世代且尚未消费时返回 true。 */
        fun consume(): Boolean = consumeLease(this)
    }

    private var nextGeneration = 0L
    private var nextAttempt = 0L
    private var pendingGeneration: Long? = null
    private val retainedAttempts = mutableSetOf<Long>()
    private var activeAttempt: Long? = null
    private var bluetoothRetryDeadlineMillis: Long? = null
    private var bluetoothRetryGeneration: Long? = null
    private var bluetoothRetryAttempt: Long? = null

    @Synchronized
    fun manualStart() {
        pendingGeneration = ++nextGeneration
        retainedAttempts.clear()
        activeAttempt = null
        bluetoothRetryDeadlineMillis = null
        bluetoothRetryGeneration = null
        bluetoothRetryAttempt = null
    }

    /** 当前手动启动世代；自动启动用它只查询同一世代的重试状态。 */
    @Synchronized
    fun generation(): Long? {
        discardExpiredBluetoothRetryLocked()
        return pendingGeneration
    }

    /**
     * 为当前意图取得固定世代；自动启动或其它连接没有手动意图时返回 null。
     *
     * 每个租约在取得时会登记为仍有后续使用可能的候选。候选可能在交接释放后才被采用，
     * 因此旧尝试的 [cancel] 不能仅因 [activeAttempt] 仍指向它而清掉整个世代。
     */
    @Synchronized
    fun lease(): Lease? {
        discardExpiredBluetoothRetryLocked()
        return pendingGeneration?.let { generation ->
            Lease(generation, ++nextAttempt).also { retainedAttempts += it.attempt }
        }
    }

    /** 端点真正取得交接权后才能成为当前尝试；仅创建候选 Lease 不会覆盖仍在收尾的旧客户端。 */
    @Synchronized
    fun adopt(lease: Lease?) {
        discardExpiredBluetoothRetryLocked()
        val currentAttempt = activeAttempt
        if (lease != null &&
            pendingGeneration == lease.generation &&
            lease.attempt in retainedAttempts &&
            (currentAttempt == null || lease.attempt >= currentAttempt)
        ) {
            // 更新尝试取得交接权后，较早的候选已经失去接续资格，不能在新尝试失败时复活。
            retainedAttempts.removeAll { it < lease.attempt }
            activeAttempt = lease.attempt
            val deferredAttempt = bluetoothRetryAttempt
            if (bluetoothRetryDeadlineMillis != null &&
                (deferredAttempt == null || deferredAttempt < lease.attempt)
            ) {
                // 通用或旧尝试的断链窗口只负责把控制权交给新尝试；新尝试接手后不能继承旧截止时间。
                clearBluetoothRetryLocked()
            }
        }
    }

    /**
     * 自动启动只延续仍属于同一手动连接轮次的意图。
     *
     * [continuesManualAttempt] 由编排层根据上一轮是否仍在进行或握手来判定。新自动行程必须传入
     * false，避免旧轮次在 `/info` 前失败后把主屏接管泄漏给下一次自动连接。
     */
    @Synchronized
    fun beginAutomatic(continuesManualAttempt: Boolean) {
        val bluetoothRetryActive = hasActiveBluetoothRetryLocked()
        if (!continuesManualAttempt && !bluetoothRetryActive) {
            clearClaimLocked()
        } else if (bluetoothRetryAttempt == null) {
            // 端点交付前没有旧配对线程需要清理；自动尝试一旦开始，通用断链标记即可消费。
            clearBluetoothRetryLocked()
        }
    }

    /**
     * 记录一次真实蓝牙断开允许后续自动重连接续当前手动意图。
     *
     * 只保留一个有界窗口，避免历史 `BT_LINK_CLOSED` 状态让很久以后的自动连接继承旧意图。
     */
    @Synchronized
    fun deferBluetoothRetry(lease: Lease? = null) {
        discardExpiredBluetoothRetryLocked()
        val generation = pendingGeneration ?: return
        if (lease != null) {
            if (lease.generation != generation || lease.attempt !in retainedAttempts) return
            val deferredAttempt = bluetoothRetryAttempt
            if (deferredAttempt != null && lease.attempt < deferredAttempt) return
        } else {
            // 没有精确 lease 的迟到旧链路不能把一个已 adopt 的新尝试改写成通用 defer。
            // 新尝试的普通失败会被误当成可自动重连的蓝牙断链并多保留 30 秒。
            if (activeAttempt != null && activeAttempt in retainedAttempts) return
            if (bluetoothRetryAttempt != null &&
                bluetoothRetryAttempt in retainedAttempts &&
                hasActiveBluetoothRetryLocked()
            ) {
                // 已精确绑定到某一尝试的断开事件不能被后到的通用失败覆盖。
                return
            }
        }
        bluetoothRetryDeadlineMillis = monotonicMillis() + BLUETOOTH_RETRY_GRACE_MILLIS
        bluetoothRetryGeneration = generation
        bluetoothRetryAttempt = lease?.attempt
    }

    /** 配对线程收尾时判断：当前手动意图是否正等待一次有界蓝牙自动重连。 */
    @Synchronized
    fun isBluetoothRetryDeferred(lease: Lease?): Boolean {
        if (lease == null ||
            !hasActiveBluetoothRetryLocked() ||
            pendingGeneration != lease.generation ||
            lease.attempt !in retainedAttempts
        ) {
            return false
        }
        if (lease.generation != bluetoothRetryGeneration) return false
        return bluetoothRetryAttempt?.let { lease.attempt == it } ?: true
    }

    private fun hasActiveBluetoothRetryLocked(): Boolean {
        val deadline = bluetoothRetryDeadlineMillis ?: return false
        if (pendingGeneration == null || monotonicMillis() >= deadline) {
            // 超过有界窗口后整条手动意图失效，不能让直接端点路径重新 lease/adopt。
            clearClaimLocked()
            return false
        }
        return true
    }

    private fun discardExpiredBluetoothRetryLocked() {
        hasActiveBluetoothRetryLocked()
    }

    private fun clearBluetoothRetryLocked() {
        bluetoothRetryDeadlineMillis = null
        bluetoothRetryGeneration = null
        bluetoothRetryAttempt = null
    }

    /** 仅撤销指定世代，不会清掉随后新手动启动形成的意图。 */
    @Synchronized
    fun cancel(lease: Lease?) {
        if (lease == null || pendingGeneration != lease.generation) return
        retainedAttempts.remove(lease.attempt)
        if (activeAttempt == lease.attempt) {
            activeAttempt = null
        }
        if (retainedAttempts.isEmpty()) {
            clearClaimLocked()
        }
    }

    @Synchronized
    fun terminalDisconnect() {
        clearClaimLocked()
    }

    private fun clearClaimLocked() {
        pendingGeneration = null
        retainedAttempts.clear()
        activeAttempt = null
        clearBluetoothRetryLocked()
    }

    @Synchronized
    private fun consumeLease(lease: Lease): Boolean {
        discardExpiredBluetoothRetryLocked()
        if (pendingGeneration != lease.generation) return false
        if (lease.attempt !in retainedAttempts) return false
        if (activeAttempt != null && activeAttempt != lease.attempt) return false
        clearClaimLocked()
        return true
    }

    @Synchronized
    private fun isLeasePending(lease: Lease): Boolean {
        discardExpiredBluetoothRetryLocked()
        return pendingGeneration == lease.generation &&
            lease.attempt in retainedAttempts &&
            (activeAttempt == null || activeAttempt == lease.attempt)
    }

    @Synchronized
    private fun isLeaseRetained(lease: Lease): Boolean {
        discardExpiredBluetoothRetryLocked()
        return pendingGeneration == lease.generation && lease.attempt in retainedAttempts
    }

    companion object {
        /** 一次 ACL 断开后允许同一手动行程自动接续的时间上限。 */
        const val BLUETOOTH_RETRY_GRACE_MILLIS = 30_000L
    }
}

/**
 * 同一条显式启动意图可能同时存在邀请重连与晚到端点重试。
 *
 * 只有最后一个任务结束时才允许做失败清理，避免先结束的任务把仍存活任务的重试状态清零。
 */
internal class ExplicitStartClaimRetryTracker {
    private class GenerationState {
        var inFlight = 0
        val handedOffLeases = linkedSetOf<ExplicitStartScreenClaim.Lease>()
        val failedLeases = linkedSetOf<ExplicitStartScreenClaim.Lease>()
    }

    private val generations = mutableMapOf<Long, GenerationState>()

    @Synchronized
    fun begin(lease: ExplicitStartScreenClaim.Lease?): Boolean {
        if (lease == null) return false
        generations.getOrPut(lease.generation) { GenerationState() }.inFlight++
        return true
    }

    @Synchronized
    fun isActive(generation: Long?): Boolean = generation != null && (generations[generation]?.inFlight ?: 0) > 0

    /**
     * 自动启动与最后一个重试任务在同一把锁内完成交接。
     *
     * 只要当前世代仍有重试，就立即生成一个自动接续候选。后续失败重试即使撤销自己的租约，
     * 也不能把这个尚未 adopt 的候选一起清掉。
     */
    @Synchronized
    fun leaseForAutomaticContinuation(
        generation: Long?,
        claim: ExplicitStartScreenClaim,
        existingLease: ExplicitStartScreenClaim.Lease?,
        ordinaryContinuation: Boolean,
    ): ExplicitStartScreenClaim.Lease? {
        if (generation == null) return null
        val retryActive = (generations[generation]?.inFlight ?: 0) > 0
        if (existingLease?.isRetained() == true) {
            if (retryActive || ordinaryContinuation || claim.isBluetoothRetryDeferred(existingLease)) {
                return existingLease
            }
            claim.cancel(existingLease)
        }
        if (retryActive) return claim.lease()
        return null
    }

    @Synchronized
    fun finish(
        handedOffNow: Boolean,
        lease: ExplicitStartScreenClaim.Lease?,
        onLastFinished: (Collection<ExplicitStartScreenClaim.Lease>) -> Unit,
    ) {
        if (lease == null) return
        val state = checkNotNull(generations[lease.generation]) { "finish called without a matching begin" }
        check(state.inFlight > 0) { "finish called without a matching begin" }
        if (handedOffNow) {
            state.handedOffLeases += lease
            state.failedLeases -= lease
        } else if (lease !in state.handedOffLeases) {
            state.failedLeases += lease
        }
        state.inFlight--
        if (state.inFlight == 0) {
            val failed = state.failedLeases.toList()
            generations.remove(lease.generation)
            onLastFinished(failed)
        }
    }
}

/** 仅策略允许、存在用户明确启动意图且主屏当前不在手机时，才请求接管。 */
internal fun needsExplicitStartScreenClaim(
    explicitStart: Boolean,
    policy: ReceiverCompatibility.Policy,
    phoneOwnsScreen: Boolean,
): Boolean = explicitStart && policy.claimScreenOnExplicitStart && !phoneOwnsScreen

/**
 * 自动启动是否仍属于用户刚刚发起、尚未终止的连接轮次。
 *
 * 只有正在协商/推流的轮次、仍在执行端点交接或显式重试的流程可以自行延续。蓝牙重连必须由
 * [ExplicitStartScreenClaim.deferBluetoothRetry] 基于真实断链事件单独登记，避免仅凭历史失败码
 * 把旧意图泄漏给很久以后的自动启动。
 */
internal fun automaticStartContinuesManualTrip(
    previousStatus: String?,
    sessionStarting: Boolean,
    claimRetryInFlight: Boolean,
): Boolean =
    sessionStarting ||
        claimRetryInFlight ||
        previousStatus == "waiting" ||
        previousStatus == "streaming" ||
        previousStatus == "receiving_source"

/**
 * 配对清理是否应撤销手动主屏接管意图。
 *
 * 已恢复交接、已捕获同一端点等待回放、已排入 ctrl 邀请重试，或正在有界等待蓝牙重连时，意图
 * 必须留给后续尝试；这些重试最终失败时会自行撤销。
 */
internal fun shouldCancelExplicitStartScreenClaim(
    recovered: Boolean,
    pendingEndpoint: Boolean,
    retryScheduled: Boolean,
    bluetoothRetryDeferred: Boolean,
): Boolean = !recovered && !pendingEndpoint && !retryScheduled && !bluetoothRetryDeferred
