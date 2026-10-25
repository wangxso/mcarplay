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
 * 6 秒）。这里按「车机身份（model + manufacturer）」持久化三件事：
 *
 *  - [Entry.succeeded]：最近一次**成功拿到签名**的长度 → 下次直接首选，省掉试探；
 *  - [Entry.silent]：窗口内无应答的长度 → 排到后面（但仍保留为兜底，车机固件可能变）；
 *  - [Entry.rejected]：0xAA04 明确拒绝或**导致拆链**的长度 → 排到最后。
 *
 * 排序规则见 [orderAttempts]（纯函数，便于单测）：成功档位永远第一；其余沿用
 * 调用方给的基线顺序（真车 20B 优先 / 测试台 32B 优先），最后才是 silent 与
 * rejected。若基线里所有档位都被判过 rejected，则全部照常尝试，避免永久卡死。
 */
object MfiLengthMemory {
    /** 单个车机的 MFi 记忆。 */
    data class Entry(
        val succeeded: Int? = null,
        val silent: Set<Int> = emptySet(),
        val rejected: Set<Int> = emptySet(),
    ) {
        val isEmpty: Boolean get() = succeeded == null && silent.isEmpty() && rejected.isEmpty()
    }

    private const val FILE = "mfi_length_memory"
    private const val KEY_OK = "ok_"
    private const val KEY_SILENT = "silent_"
    private const val KEY_REJECTED = "rejected_"

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
        )
    }

    fun recordSuccess(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
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
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(silent = entry.silent + length, rejected = entry.rejected - length)
            }
        }
    }

    fun recordRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(rejected = entry.rejected + length, silent = entry.silent - length)
            }
        }
    }

    /**
     * 硬拒绝：链路在该长度的挑战期间被对端直接断开（区别于 0xAA04 拒绝字/静默）。
     * 这是强证据——即使长度等于已记「成功」也必须推翻：MFi 应答存在归属歧义
     * （上一档超窗后才到的迟到应答会被记到当前档头上，2026-10-24 DiPlay 实测：
     * 32B 迟到应答记成 20B 成功，之后每轮 20B 被断链、永久锁死）。
     */
    fun recordHardRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            val succeeded = if (entry.succeeded == length) null else entry.succeeded
            Entry(
                succeeded = succeeded,
                silent = (entry.silent - length),
                rejected = entry.rejected + length,
            )
        }
    }

    /** 打印用：把记忆压成一行日志（便于对照实测）。 */
    fun describe(entry: Entry): String =
        if (entry.isEmpty) {
            "无"
        } else {
            buildList {
                entry.succeeded?.let { add("成功=${it}B") }
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
        // 「成功但又被硬拒绝」= 记忆矛盾（迟到应答误记），不能继续享受第一名——
        // 降级为普通档参与排序（排在被拒档之前）
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
        return (listOfNotNull(succeeded) + normal + silentOrdered + rejectedOrdered).distinct()
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
            val current = load(context, identityText)
            val next = transform(current)
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit()
                .apply {
                    if (next.succeeded != null) putInt(KEY_OK + key, next.succeeded) else remove(KEY_OK + key)
                    putString(KEY_SILENT + key, next.silent.sorted().joinToString(","))
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                }.apply()
        }
    }

    private fun readLengths(raw: String?): Set<Int> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.toSet()
            .orEmpty()
}
