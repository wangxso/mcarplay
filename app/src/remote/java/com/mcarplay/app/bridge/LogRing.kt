package com.mcarplay.app.bridge

import java.util.ArrayDeque

/**
 * 环形日志缓冲（断线重连后按 seq 回补给服务端）。
 *
 * 被淘汰的行逐条记进 [ledger] 为「已处理」：只有真实到达过的 seq 才会被淘汰，
 * 所以账本下界不会越过尚未到达 tap 的迟到 seq（见 [LogSeqLedger]）。
 */
class LogRing(
    private val capacity: Int,
    private val ledger: LogSeqLedger,
) {
    private val lock = Any()
    private val entries = ArrayDeque<Pair<Long, String>>()

    fun add(
        seq: Long,
        line: String,
    ) {
        synchronized(lock) {
            entries.addLast(seq to line)
            while (entries.size > capacity) ledger.markDropped(entries.removeFirst().first)
        }
    }

    /** 缓冲中 seq > [since] 的行，按到达顺序。 */
    fun since(since: Long): List<Pair<Long, String>> = synchronized(lock) { entries.filter { it.first > since } }
}
