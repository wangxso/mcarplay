package com.mcarplay.app.bridge

import java.util.BitSet

/**
 * 本进程已成功发出的日志 seq 账本（调试桥去重的唯一依据）。
 *
 * 背景（2026-11-04 服务端实测）：同一 seq 被存两遍，单个文件里重复行可达 28%。
 * 成因是两条发送路径各自为政：welcome 时按 since=0 回补整个环形缓冲，而实时
 * 冲刷队列里的同一批行（tap 同时写入 ring 与 pending）又发一遍；每次重连再全量
 * 回补一次。现在两条路径都先问账本，发送成功后才记账——同一进程内一个 seq 至多
 * 成功发出一次；发送失败的行不记账，下次 welcome 回补时补上。
 *
 * 账本**只记「已成功发出」**，不推断任何别的状态（PR #30 评审两轮教训）：
 * - 不能按缓冲里的最小 seq 推进下界：[com.mcarplay.app.util.DebugFileLog] 在写锁
 *   外分配 seq 再回调 tap，seq 1 可以在 2..5001 塞满缓冲之后才到达；
 * - 不能把环形缓冲淘汰的行记为已处理：它可能仍在实时冲刷队列里等着发送。
 * 因此用「下界 + 下界以上的位图」：下界只越过连续已发的 seq，空洞（迟到、或断线
 * 期间被淘汰而再也发不出去的行）原样保留。
 *
 * 空洞可能永久存在（断线期间被淘汰的行），所以位图跨度封顶 [maxSpan]（默认 1M
 * seq = 128KB）：跨度用尽时下界批量前移 [maxSpan]/4，越过的空洞视为丢失——那是
 * 一条日志在百万行之前就该发出却没发出，早已不在任何发送路径里。批量前移让位图
 * 复制摊薄到每 25 万行一次。
 *
 * 线程安全：方法互斥。查账→发送→记账的原子性由调用方的发送锁保证。
 */
class LogSeqLedger(
    private val maxSpan: Int = DEFAULT_MAX_SPAN,
) {
    /** seq ≤ floor 的一律视为已发出（或已放弃）。 */
    @get:Synchronized
    var floor: Long = 0
        private set

    /** 第 i 位 = seq (floor + 1 + i) 已发出。 */
    private var above = BitSet()

    /** 下界以上已发出的 seq 数（即等待空洞补齐的乱序 seq；测试与诊断用）。 */
    val sparseCount: Int
        @Synchronized get() = above.cardinality()

    @Synchronized
    fun isSent(seq: Long): Boolean {
        if (seq <= floor) return true
        val offset = seq - floor - 1
        return offset < maxSpan && above.get(offset.toInt())
    }

    /** 从候选里挑出未发过的行（同批内重复 seq 只保留第一条），保持原顺序。 */
    @Synchronized
    fun <T> unsent(
        entries: List<T>,
        seqOf: (T) -> Long,
    ): List<T> {
        val seen = HashSet<Long>()
        return entries.filter { entry ->
            val seq = seqOf(entry)
            !isSent(seq) && seen.add(seq)
        }
    }

    /** 该 seq 已成功发出。 */
    @Synchronized
    fun markSent(seq: Long) {
        if (seq <= floor) return
        var offset = seq - floor - 1
        if (offset >= maxSpan) {
            // 跨度用尽：最老的空洞视为丢失，批量腾出位图跨度
            shift(maxOf(offset - maxSpan + 1, (maxSpan / 4).toLong()).toInt())
            if (seq <= floor) return
            offset = seq - floor - 1
        }
        above.set(offset.toInt())
        shift(above.nextClearBit(0))
    }

    /** 下界前移 [count] 个 seq，并继续吞掉随后的连续已发段。 */
    private fun shift(count: Int) {
        var n = count
        while (n > 0) {
            floor += n
            above = above.get(n, maxOf(n, above.length()))
            n = above.nextClearBit(0)
        }
    }

    private companion object {
        const val DEFAULT_MAX_SPAN = 1_000_000
    }
}
