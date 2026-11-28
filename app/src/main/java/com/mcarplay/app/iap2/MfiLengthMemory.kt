package com.mcarplay.app.iap2

import android.content.Context

/**
 * MFi 挑战长度的**按车机持久化记忆**。
 *
 * 为什么需要（2026-10-19 奥迪 MMI 3315 实测）：20B 挑战在该车机上静默 6 秒无应答，
 * 改发 32B 后 0.64 秒就回签名——每次连接白等 6 秒。而不同接收端对长度的偏好相反：
 *  - DiPlay（Carlinkit AuthV3）：只接受 **32B**，长度不符直接断链；
 *  - Audi MMI 6318：20B 秒答，32B 曾 0.4 秒拆链；
 *  - Audi MMI 3315：20B 静默，**32B** 才应答。
 * 所以既不能全局写死某一档，也不能只靠进程内记忆（App 重启即丢，每次重连都重付
 * 6 秒）。这里按「车机身份（model + manufacturer）」持久化：
 *
 *  - [Entry.succeeded]：最近一次**成功拿到签名**的长度 → 下次直接首选，省掉试探；
 *  - [Entry.silent]：尚未拿到签名，且等待超时或链路断开的长度 → 排到后面；
 *  - [Entry.rejected]：0xAA04 明确拒绝的长度 → 排到最后。
 *  - [Entry.successDrops]：成功档位在等待签名时的连续断链次数。
 *  - [Entry.triedAlternates]：两次连续断链后，已在新连接上优先试过的其他档位。
 *
 * 排序规则见 [orderAttempts]：成功档位优先，连续断链后每条新连接先试一个未试过的档位，随后回试成功档位。其余沿用
 * 调用方给的基线顺序（真车 20B 优先 / 测试台 32B 优先），最后才是 silent 与
 * rejected。若基线里所有档位都被判过 rejected，则全部照常尝试，避免永久卡死。
 */
object MfiLengthMemory {
    /** 单个车机的 MFi 记忆。 */
    data class Entry(
        val succeeded: Int? = null,
        val silent: Set<Int> = emptySet(),
        val rejected: Set<Int> = emptySet(),
        val successDrops: Int = 0,
        val triedAlternates: Set<Int> = emptySet(),
    ) {
        val isEmpty: Boolean get() = succeeded == null && silent.isEmpty() && rejected.isEmpty()

        internal val demoted: Boolean get() = successDrops >= DROP_LIMIT
    }

    private const val FILE = "mfi_length_memory"
    private const val KEY_OK = "ok_"
    private const val KEY_SILENT = "silent_"
    private const val KEY_REJECTED = "rejected_"
    private const val KEY_SUCCESS_DROPS = "success_drops_"
    private const val KEY_TRIED_ALTERNATES = "tried_alternates_"
    private const val DROP_LIMIT = 2

    /** 车机身份 → 存储键。只用稳定字段，避免蓝牙名里的可变后缀影响命中。 */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    /** 读取某车机的记忆（无 context 时退化为进程内内存表，便于单测）。 */
    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        val ok = prefs.getInt(KEY_OK + key, 0).takeIf { it > 0 }
        return Entry(
            succeeded = ok,
            silent = readLengths(prefs.getString(KEY_SILENT + key, null)),
            rejected = readLengths(prefs.getString(KEY_REJECTED + key, null)),
            successDrops = if (ok == null) 0 else prefs.getInt(KEY_SUCCESS_DROPS + key, 0),
            triedAlternates = if (ok == null) emptySet() else readLengths(prefs.getString(KEY_TRIED_ALTERNATES + key, null)),
        )
    }

    /** 为一条新链路选档，并推进连续断链后的回试顺序。 */
    internal fun beginAttempts(
        context: Context?,
        identityText: String,
        baseline: List<Int>,
    ): Pair<Entry, List<Int>> =
        synchronized(memory) {
            val entry = load(context, identityText)
            val order = orderAttempts(baseline, entry)
            if (!entry.demoted) return@synchronized entry to order

            val first = order.first()
            val next =
                if (first == entry.succeeded) {
                    entry.copy(successDrops = 0, triedAlternates = emptySet())
                } else {
                    entry.copy(triedAlternates = entry.triedAlternates + first)
                }
            save(context, receiverKey(identityText), next)
            next to order
        }

    fun recordSuccess(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        if (length <= 0) return
        update(context, identityText) { entry ->
            // 成功即证伪该档位的 silent/rejected 旧结论（固件升级后行为可能变）
            Entry(succeeded = length, silent = entry.silent - length, rejected = entry.rejected - length)
        }
    }

    fun recordSilent(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            when {
                entry.succeeded == length && !entry.demoted -> entry.copy(successDrops = 0)
                entry.succeeded == length || length in entry.rejected -> entry
                else -> entry.copy(silent = entry.silent + length)
            }
        }
    }

    fun recordLinkDrop(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            when {
                length in entry.rejected -> entry
                entry.succeeded == length -> entry.copy(successDrops = minOf(DROP_LIMIT, entry.successDrops + 1))
                else -> entry.copy(silent = entry.silent + length)
            }
        }
    }

    fun recordRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            entry.copy(
                succeeded = entry.succeeded?.takeUnless { it == length },
                rejected = entry.rejected + length,
                silent = entry.silent - length,
                successDrops = if (entry.succeeded == length) 0 else entry.successDrops,
                triedAlternates = if (entry.succeeded == length) emptySet() else entry.triedAlternates,
            )
        }
    }

    /** 链路由当前挑战主动断开：长度必须退出成功位并进入硬拒绝，避免下次仍排第一。 */
    fun recordHardRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        if (length <= 0) return
        recordRejected(context, identityText, length)
    }

    /**
     * 已成功过的长度的等待窗口。2026-11-02 艾瑞泽 8：车机读自身 MFi 芯片偶发卡住，内部约 35 s 才超时
     * （真 iPhone 也碰到过）；3–6 s 就换档重发的挑战只会在车机里排队，每个再耗 35 s。
     */
    const val KNOWN_GOOD_WINDOW_MS = 40_000L

    /**
     * 本次挑战的等待窗口（纯函数）：仍优先的成功长度等足 [KNOWN_GOOD_WINDOW_MS]；
     * 静默过的长度缩到 [silentRetryWindow]；其余用调用方给的基础窗口。
     */
    fun challengeWindow(
        length: Int,
        entry: Entry,
        baseWindow: Long,
        silentRetryWindow: Long,
    ): Long =
        when {
            length == entry.succeeded && length !in entry.rejected && !entry.demoted -> maxOf(baseWindow, KNOWN_GOOD_WINDOW_MS)
            length in entry.silent -> minOf(baseWindow, silentRetryWindow)
            else -> baseWindow
        }

    /** 打印用：把记忆压成一行日志（便于对照实测）。 */
    fun describe(entry: Entry): String =
        if (entry.isEmpty) {
            "无"
        } else {
            buildList {
                entry.succeeded?.let { add("成功=${it}B") }
                if (entry.successDrops > 0) add("成功档断链=${entry.successDrops}次")
                if (entry.silent.isNotEmpty()) add("静默=${entry.silent.sorted().joinToString("/")}B")
                if (entry.rejected.isNotEmpty()) add("被拒=${entry.rejected.sorted().joinToString("/")}B")
            }.joinToString("，")
        }

    /**
     * 计算本轮的尝试顺序（纯函数）。
     *
     * @param baseline 调用方按接收端类型给出的基线顺序（长度列表）。
     * @param entry 该车机的持久化记忆。
     * @return 依次尝试的长度；保证非空且覆盖 baseline 的全部档位。
     */
    fun orderAttempts(
        baseline: List<Int>,
        entry: Entry,
    ): List<Int> {
        // 兼容记忆中同时标为成功与被拒的档位，以拒绝为准。
        val succeeded = entry.succeeded?.takeIf { it !in entry.rejected }
        val rest = baseline.filter { it != succeeded }
        // 只有「还存在没被判死的档位」时才把 rejected 排到最后；
        // 全部 rejected 说明与记忆矛盾（换固件/换车同一身份），照常全试。
        val allRejected = rest.isNotEmpty() && rest.all { it in entry.rejected }
        val rejected = if (allRejected) emptySet() else entry.rejected.filter { it in baseline }.toSet()
        val silent = entry.silent.filter { it in rest && it !in rejected }.toSet()
        val normal = rest.filter { it !in silent && it !in rejected }
        val silentOrdered = rest.filter { it in silent }
        val rejectedOrdered = rest.filter { it in rejected }
        val others = normal + silentOrdered + rejectedOrdered
        val remembered = listOfNotNull(succeeded)
        val alternates = if (entry.demoted && succeeded != null) others.filter { it !in entry.triedAlternates } else emptyList()
        return (alternates + remembered + others).distinct()
    }

    // ---- 内部 ----

    private val memory = HashMap<String, Entry>()

    private fun prefs(context: Context?): android.content.SharedPreferences? = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun update(
        context: Context?,
        identityText: String,
        transform: (Entry) -> Entry,
    ) {
        val key = receiverKey(identityText)
        synchronized(memory) {
            save(context, key, transform(load(context, identityText)))
        }
    }

    private fun save(
        context: Context?,
        key: String,
        entry: Entry,
    ) {
        memory[key] = entry
        prefs(context)
            ?.edit()
            ?.apply {
                if (entry.succeeded != null) putInt(KEY_OK + key, entry.succeeded) else remove(KEY_OK + key)
                putString(KEY_SILENT + key, entry.silent.sorted().joinToString(","))
                putString(KEY_REJECTED + key, entry.rejected.sorted().joinToString(","))
                if (entry.successDrops > 0) putInt(KEY_SUCCESS_DROPS + key, entry.successDrops) else remove(KEY_SUCCESS_DROPS + key)
                if (entry.triedAlternates.isNotEmpty()) {
                    putString(KEY_TRIED_ALTERNATES + key, entry.triedAlternates.sorted().joinToString(","))
                } else {
                    remove(KEY_TRIED_ALTERNATES + key)
                }
            }?.apply()
    }

    private fun readLengths(raw: String?): Set<Int> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.toSet()
            .orEmpty()
}
