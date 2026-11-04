package com.mcarplay.app.bridge

import java.util.TreeSet

/**
 * 本进程已成功发出的日志 seq 账本（调试桥去重的唯一依据）。
 *
 * 背景（2026-11-04 服务端实测）：同一 seq 被存两遍，单个文件里重复行可达 28%。
 * 成因是两条发送路径各自为政：welcome 时按 since=0 回补整个环形缓冲，而实时
 * 冲刷队列里的同一批行（tap 同时写入 ring 与 pending）又发一遍；每次重连再全量
 * 回补一次。现在两条路径都先问账本，发送成功后才记账——同一进程内一个 seq 至多
 * 成功发出一次；发送失败的行不记账，下次 welcome 回补时补上。
 *
 * 不用单一「最高 seq」水位：[com.mcarplay.app.util.DebugFileLog] 在写锁外分配
 * seq 并回调 tap，并发写日志时 tap 到达顺序可能与 seq 顺序不一致（11 先于 10），
 * 单水位会把迟到的 10 当成已发而丢掉。这里用「下界 + 下界以上的离散集合」：
 * 连续段自动并入下界，集合只保存乱序/空洞附近的少量 seq。
 *
 * 非线程安全：调用方在同一把发送锁内使用（查账、发送、记账必须原子）。
 */
class LogSeqLedger(
    private val maxSparse: Int = DEFAULT_MAX_SPARSE,
) {
    /** seq ≤ floor 的一律视为已处理（已发出或已不可能再发）。 */
    var floor: Long = 0
        private set

    private val above = TreeSet<Long>()

    /** 下界以上已记账的离散 seq 数（测试与诊断用）。 */
    val sparseCount: Int get() = above.size

    fun isSent(seq: Long): Boolean = seq <= floor || seq in above

    /** 从候选里挑出未发过的行（同批内重复 seq 只保留第一条），保持原顺序。 */
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

    fun markSent(seq: Long) {
        if (seq <= floor) return
        above.add(seq)
        compact()
    }

    /**
     * 把下界抬到 [seq]：比它更早的行已无法再发（例如已被环形缓冲淘汰），
     * 不必继续占用集合。只升不降。
     */
    fun advanceFloorTo(seq: Long) {
        if (seq <= floor) return
        floor = seq
        compact()
    }

    private fun compact() {
        while (above.isNotEmpty() && above.first() <= floor + 1) {
            floor = maxOf(floor, above.pollFirst()!!)
        }
        // 兜底：长期存在的空洞（某 seq 永远没进 tap）不能让集合无限增长。上限取
        // 环形缓冲容量的两倍：比最近 maxSparse 个已发 seq 还早的空洞早已被缓冲淘汰，
        // 视为已处理不会丢掉任何还能发出去的行
        while (above.size > maxSparse) {
            floor = above.pollFirst()!!
            while (above.isNotEmpty() && above.first() <= floor + 1) {
                floor = maxOf(floor, above.pollFirst()!!)
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_SPARSE = 10_000
    }
}
