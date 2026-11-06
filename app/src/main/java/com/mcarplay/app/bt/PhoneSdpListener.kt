package com.mcarplay.app.bt

import java.io.Closeable

/** 两项手机 SDP 公告共用一个生命周期；旧监听失败或迟到的恢复任务不能撤销、复活新一轮。 */
internal class PhoneSdpListener<T : Closeable>(
    private val open: () -> T,
    private val onOpened: (T) -> Unit,
    private val onClosed: () -> Unit,
) {
    private var requested = false
    private var generation = 0L
    private var recoveryAttempts = 0
    private var records: T? = null

    val isRunning: Boolean
        @Synchronized get() = records != null

    @Synchronized
    fun isCurrent(expected: T): Boolean = records === expected

    @Synchronized
    fun start(): Boolean {
        if (records != null) return true
        requested = true
        generation++
        recoveryAttempts = 0
        return openIfNeeded()
    }

    /** 蓝牙关闭时撤销公告，但保留用户本轮的连接意图；重新开启后才恢复。 */
    @Synchronized
    fun adapterUnavailable() {
        generation++
        recoveryAttempts = 0
        closeRecords()
    }

    /** 任一监听结束都同时退役两项公告。仅当前实例可以发起一次恢复。 */
    @Synchronized
    fun failed(
        expected: T,
        beforeClose: () -> Unit = {},
    ): Long? {
        if (records !== expected) return null
        generation++
        beforeClose()
        closeRecords()
        return recoveryToken()
    }

    @Synchronized
    fun recoveryToken(): Long? = generation.takeIf { requested && records == null && recoveryAttempts < MAX_RECOVERY_ATTEMPTS }

    /** false 表示已停止、已由其他任务恢复，或令牌属于旧轮次。 */
    @Synchronized
    fun recover(token: Long): Boolean {
        if (!requested || token != generation || records != null || recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return false
        recoveryAttempts++
        return openIfNeeded()
    }

    @Synchronized
    fun stop() {
        requested = false
        generation++
        closeRecords()
    }

    private fun openIfNeeded(): Boolean {
        if (records != null) return true
        val opened =
            try {
                open()
            } catch (error: Exception) {
                onClosed()
                throw error
            }
        records = opened
        try {
            onOpened(opened)
        } catch (error: Exception) {
            closeRecords()
            throw error
        }
        return true
    }

    private fun closeRecords() {
        val old = records ?: return
        records = null
        try {
            old.close()
        } finally {
            onClosed()
        }
    }

    companion object {
        const val MAX_RECOVERY_ATTEMPTS = 3
    }
}
