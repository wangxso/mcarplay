package com.mcarplay.app

import com.mcarplay.app.compat.ReceiverObservation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks which RFCOMM observation owns the one in-flight CarPlay handoff.
 *
 * A newer link can be "current" for receiver detection without owning the endpoint that
 * already started Wi-Fi binding or AirPlay pairing. Only the owning link may cancel it.
 */
internal class SessionHandoffOwnership(
    private val active: AtomicBoolean,
) {
    private val lock = Any()
    private var owner: ReceiverObservation? = null
    private var wifiPeer: String? = null
    private var wifiDetached = false
    private var accepting = true
    private var pendingRetry: (() -> Unit)? = null

    /**
     * 获取交接所有权；[onAcquired] 在同一锁内执行，供调用方清除旧的取消标志。
     * 断连先通过 [blockNewHandoffs] 关闭入口，因此不会在该回调之后再被旧端点覆盖。
     * 交接正忙时保留最新的 [retryWhenReleased]，由正常释放的调用方在锁外执行。
     */
    fun begin(
        observation: ReceiverObservation?,
        retryWhenReleased: (() -> Unit)? = null,
        wifiPeer: String? = null,
        onAcquired: () -> Unit = {},
    ): Boolean =
        synchronized(lock) {
            if (!accepting) return@synchronized false
            if (!active.compareAndSet(false, true)) {
                if (retryWhenReleased != null) pendingRetry = retryWhenReleased
                return@synchronized false
            }
            owner = observation
            this.wifiPeer = wifiPeer
            onAcquired()
            true
        }

    fun endIfOwnedBy(observation: ReceiverObservation?): Boolean {
        val retry =
            synchronized(lock) {
                if (observation != owner) return false
                if (!active.compareAndSet(true, false)) return false
                clearOwner()
                pendingRetry.also { pendingRetry = null }
            }
        retry?.invoke()
        return true
    }

    fun owns(observation: ReceiverObservation?): Boolean =
        synchronized(lock) {
            active.get() && owner == observation
        }

    fun setWifiPeer(
        observation: ReceiverObservation?,
        address: String,
    ) {
        synchronized(lock) {
            if (active.get() && owner == observation) wifiPeer = address
        }
    }

    fun keepWifiAfterLinkLoss(observation: ReceiverObservation?): Boolean =
        synchronized(lock) {
            if (wifiPeer == null || owner != observation) return@synchronized false
            wifiDetached = true
            true
        }

    fun keepWifiAfterAclLoss(address: String?): Boolean =
        synchronized(lock) {
            if (address == null || !address.equals(wifiPeer, ignoreCase = true)) return@synchronized false
            wifiDetached = true
            true
        }

    fun wifiDetached(): Boolean = synchronized(lock) { wifiDetached }

    fun wifiDetached(observation: ReceiverObservation?): Boolean = detachedWifiPeer(observation) != null

    fun detachedWifiPeer(observation: ReceiverObservation?): String? =
        synchronized(lock) { wifiPeer.takeIf { wifiDetached && owner == observation } }

    /** 只有归属链路能取消交接；状态变更与回调在同一锁内完成。 */
    fun cancelIfOwnedBy(
        observation: ReceiverObservation?,
        isAlreadyCancelled: () -> Boolean = { false },
        onCancel: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (!active.get() || owner != observation) return@synchronized false
            active.set(false)
            clearOwner()
            pendingRetry = null
            if (isAlreadyCancelled()) return@synchronized false
            onCancel()
            true
        }

    /** 取消/终止路径无条件释放，不依赖链路关闭通知或接收轮次是否仍为 current。 */
    fun release() {
        synchronized(lock) {
            clearOwner()
            pendingRetry = null
            active.set(false)
        }
    }

    /** 进入断开/替换流程：原子地禁止新交接并释放现有所有权。 */
    fun blockNewHandoffs() {
        synchronized(lock) {
            accepting = false
            clearOwner()
            pendingRetry = null
            active.set(false)
        }
    }

    /** 断开清理完成，允许下一轮连接重新获取交接。 */
    fun allowNewHandoffs() {
        synchronized(lock) {
            accepting = true
        }
    }

    private fun clearOwner() {
        owner = null
        wifiPeer = null
        wifiDetached = false
    }
}
