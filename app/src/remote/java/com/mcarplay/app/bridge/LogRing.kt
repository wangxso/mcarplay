package com.mcarplay.app.bridge

import java.util.ArrayDeque

/**
 * 环形日志缓冲（断线重连后按 seq 回补给服务端）。
 *
 * 淘汰只影响回补：被淘汰的行可能仍在实时冲刷队列里等着发送，所以淘汰**不**改
 * 发送账本（见 [LogSeqLedger]）。
 */
class LogRing(
    private val capacity: Int,
) {
    private val lock = Any()
    private val entries = ArrayDeque<Pair<Long, String>>()

    fun add(
        seq: Long,
        line: String,
    ) {
        synchronized(lock) {
            entries.addLast(seq to line)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /** 缓冲中 seq > [since] 的行，按到达顺序。 */
    fun since(since: Long): List<Pair<Long, String>> = synchronized(lock) { entries.filter { it.first > since } }
}
