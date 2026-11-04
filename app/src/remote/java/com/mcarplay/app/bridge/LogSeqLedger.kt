package com.mcarplay.app.bridge

import java.util.BitSet

/**
 * 本进程日志 seq 的处理账本（调试桥去重的唯一依据）。
 *
 * 背景（2026-11-04 服务端实测）：同一 seq 被存两遍，单个文件里重复行可达 28%。
 * 成因是两条发送路径各自为政：welcome 时按 since=0 回补整个环形缓冲，而实时
 * 冲刷队列里的同一批行（tap 同时写入 ring 与 pending）又发一遍；每次重连再全量
 * 回补一次。现在两条路径都先问账本，发送成功后才记账——同一进程内一个 seq 至多
 * 成功发出一次；发送失败的行不记账，下次 welcome 回补时补上。
 *
 * 「已处理」= 已成功发出（[markSent]），或已被环形缓冲淘汰、再也发不出去
 * （[markDropped]）。两者都必须是**真实到达过 tap 的 seq**：
 * [com.mcarplay.app.util.DebugFileLog] 在写锁外分配 seq 再回调 tap，线程可能在
 * 两步之间被挂起，seq 1 可以在 2..5001 塞满缓冲之后才到达。因此不能用单一「最高
 * seq」水位，也不能按缓冲里的最小 seq 推进下界（PR #30 评审）——下界只越过连续
 * 已处理的 seq，迟到的空洞一直保留到它真正到达。
 *
 * 存储：下界 + 下界以上的位图（每个 seq 1 bit）。仅当某个 seq 被分配后始终没进
 * tap（监听器异常）时空洞才会永久存在；为此位图跨度封顶 [maxSpan]，超过后最老的
 * 空洞才被视为丢失——那意味着一条日志在 tap 门口卡了上百万行之久。
 *
 * 线程安全：方法互斥（tap 线程记淘汰，发送线程查账/记账）。查账→发送→记账的
 * 原子性由调用方的发送锁保证。
 */
class LogSeqLedger(
    private val maxSpan: Int = DEFAULT_MAX_SPAN,
) {
    /** seq ≤ floor 的一律视为已处理。 */
    @get:Synchronized
    var floor: Long = 0
        private set

    /** 第 i 位 = seq (floor + 1 + i) 已处理。 */
    private var above = BitSet()

    /** 下界以上已处理的 seq 数（即等待空洞补齐的乱序 seq；测试与诊断用）。 */
    val sparseCount: Int
        @Synchronized get() = above.cardinality()

    @Synchronized
    fun isSent(seq: Long): Boolean {
        if (seq <= floor) return true
        val offset = seq - floor - 1
        return offset < maxSpan && above.get(offset.toInt())
    }

    /** 从候选里挑出未处理的行（同批内重复 seq 只保留第一条），保持原顺序。 */
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
    fun markSent(seq: Long) = markHandled(seq)

    /** 该 seq 已到达 tap、但在发出前被环形缓冲淘汰，再也发不出去。 */
    @Synchronized
    fun markDropped(seq: Long) = markHandled(seq)

    private fun markHandled(seq: Long) {
        if (seq <= floor) return
        var offset = seq - floor - 1
        if (offset >= maxSpan) {
            // 兜底：最老的空洞卡得太久，视为丢失，腾出位图跨度
            shift((offset - maxSpan + 1).toInt())
            if (seq <= floor) return
            offset = seq - floor - 1
        }
        above.set(offset.toInt())
        shift(above.nextClearBit(0))
    }

    /** 下界前移 [count] 个 seq，并继续吞掉随后的连续已处理段。 */
    private fun shift(count: Int) {
        var n = count
        while (n > 0) {
            floor += n
            above = above.get(n, maxOf(n, above.length()))
            n = above.nextClearBit(0)
        }
    }

    private companion object {
        /** 1M seq = 位图 128KB。 */
        const val DEFAULT_MAX_SPAN = 1_000_000
    }
}
