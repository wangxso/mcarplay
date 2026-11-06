package com.mcarplay.app.diagnostics

/**
 * 把一轮 CarPlay 连接的快照整理成「分层进度」：每层的状态与耗时、最近到达的里程碑、
 * 当前层还缺哪一步。纯函数，界面（[DiagnosticText]）与诊断报告共用同一套判断。
 *
 * 分层与「卡在哪一层」的划分参照艾瑞泽 8 车机日志的失败分层矩阵：先看到达了哪一层，
 * 再看该层缺的那一步，不把前一层的成功（例如 iAP2 认证通过）当成整条连接的成功。
 */
object ConnectionProgress {
    /** 进度条展示的层，顺序即连接顺序。PRECHECK 与 SELF_TEST 不在其中。 */
    val LAYERS =
        listOf(
            ConnectionStage.BLUETOOTH,
            ConnectionStage.WIFI,
            ConnectionStage.DISCOVERY,
            ConnectionStage.PAIRING,
            ConnectionStage.SETUP,
            ConnectionStage.CARWITH,
            ConnectionStage.STREAMING,
        )

    /**
     * 艾瑞泽 8 车机自 `TimerThread begin` 起约 30 s 未建立会话即 TIMEOUT → Stop → END。
     * 手机侧用 BT_LINK 近似对时，仅作诊断提示；不是本地断开期限，也不证明车机会自动重试。
     * 后续成功样例来自上层新一次 Start。其他车机的窗口未知。
     */
    const val ARRIZO8_CONNECT_WINDOW_MS = 30_000L

    enum class StepState { DONE, ACTIVE, FAILED, STOPPED, PENDING }

    data class Step(
        val stage: ConnectionStage,
        val state: StepState,
        /** 该层累计耗时；null = 未记录到进入该层（例如本轮从更后的层开始）。 */
        val durationMs: Long?,
    )

    /** 当前层缺的那一步（只在进行中或失败时给出）。 */
    enum class Blocker {
        BT_LINK,
        IAP2_AUTH,
        WIFI_INFO,
        WIFI_JOIN,
        RECEIVER_DISCOVERY,
        RECEIVER_ADDRESS,
        AIRPLAY_PORT,
        HOMEKIT_PAIRING,
        SESSION_SETUP,
        RECORD,
        CARWITH_FRAME,
    }

    data class Progress(
        val steps: List<Step>,
        /** 最近到达的里程碑及其距本轮开始的毫秒数。 */
        val latest: Pair<ConnectionMilestone, Long>?,
        val blocker: Blocker?,
        /** 自蓝牙链路建立起的毫秒数；只在会话激活（RECORD）前给出。 */
        val sinceBluetoothMs: Long?,
        /** 会话未激活且自蓝牙链路起已超过 [ARRIZO8_CONNECT_WINDOW_MS]。 */
        val pastHeadUnitWindow: Boolean,
    )

    /** 自检轨或尚未进入蓝牙层（权限预检失败等）时返回 null：那时只有结论码有意义。 */
    fun of(snapshot: ConnectionTimeline.Snapshot): Progress? {
        val current = LAYERS.indexOf(snapshot.stage)
        if (current < 0) return null
        val running = snapshot.status == "waiting" || snapshot.status == "streaming"
        val steps =
            LAYERS.mapIndexed { index, stage ->
                val state =
                    when {
                        index < current -> StepState.DONE
                        index > current -> StepState.PENDING
                        snapshot.status == "failed" -> StepState.FAILED
                        snapshot.status == "stopped" ->
                            if (stage == ConnectionStage.STREAMING) StepState.DONE else StepState.STOPPED
                        else -> StepState.ACTIVE
                    }
                Step(stage, state, snapshot.stageDurations[stage])
            }
        val reached = snapshot.milestones
        val latest = reached.entries.maxByOrNull { it.value }?.toPair()
        val blocker =
            if (snapshot.status == "waiting" || snapshot.status == "failed") blocker(snapshot.stage, reached.keys) else null
        val sinceBluetooth =
            reached[ConnectionMilestone.BT_LINK]
                ?.takeIf { ConnectionMilestone.RECORD !in reached && running }
                ?.let { (snapshot.elapsedMs - it).coerceAtLeast(0) }
        return Progress(
            steps = steps,
            latest = latest,
            blocker = blocker,
            sinceBluetoothMs = sinceBluetooth,
            pastHeadUnitWindow = sinceBluetooth != null && sinceBluetooth >= ARRIZO8_CONNECT_WINDOW_MS,
        )
    }

    private fun blocker(
        stage: ConnectionStage,
        reached: Set<ConnectionMilestone>,
    ): Blocker? =
        when (stage) {
            ConnectionStage.BLUETOOTH ->
                when {
                    ConnectionMilestone.BT_LINK !in reached -> Blocker.BT_LINK
                    ConnectionMilestone.IAP2_AUTHENTICATED !in reached -> Blocker.IAP2_AUTH
                    ConnectionMilestone.WIFI_CREDENTIALS !in reached -> Blocker.WIFI_INFO
                    else -> null
                }
            ConnectionStage.WIFI -> Blocker.WIFI_JOIN.takeIf { ConnectionMilestone.WIFI_JOINED !in reached }
            ConnectionStage.DISCOVERY ->
                when {
                    ConnectionMilestone.CTRL_INVITED !in reached && ConnectionMilestone.RECEIVER_RESOLVED !in reached ->
                        Blocker.RECEIVER_DISCOVERY
                    ConnectionMilestone.RECEIVER_RESOLVED !in reached -> Blocker.RECEIVER_ADDRESS
                    else -> null
                }
            ConnectionStage.PAIRING ->
                when {
                    ConnectionMilestone.AIRPLAY_CONNECTED !in reached -> Blocker.AIRPLAY_PORT
                    ConnectionMilestone.PAIR_VERIFY !in reached -> Blocker.HOMEKIT_PAIRING
                    else -> null
                }
            ConnectionStage.SETUP ->
                when {
                    ConnectionMilestone.SESSION_SETUP !in reached -> Blocker.SESSION_SETUP
                    ConnectionMilestone.RECORD !in reached -> Blocker.RECORD
                    else -> null
                }
            ConnectionStage.CARWITH -> Blocker.CARWITH_FRAME.takeIf { ConnectionMilestone.CARWITH_FRAME !in reached }
            else -> null
        }

    /** 进度条各状态的标记字符；未到达的层不加标记。 */
    fun mark(state: StepState): String =
        when (state) {
            StepState.DONE -> "✓"
            StepState.ACTIVE -> "●"
            StepState.FAILED -> "✗"
            StepState.STOPPED -> "■"
            StepState.PENDING -> ""
        }

    /** 10 s 以内保留一位小数（握手各步多在亚秒到数秒），更长取整秒。 */
    fun formatDuration(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        return if (value < 10_000) "${value / 1000}.${value % 1000 / 100}s" else "${value / 1000}s"
    }

    /** 诊断报告用的固定格式（不随界面语言）：各层耗时与里程碑偏移，便于和车机侧 logcat 对时。 */
    fun reportLines(snapshot: ConnectionTimeline.Snapshot): String {
        val stages =
            ConnectionStage.entries
                .filter { it in snapshot.stageDurations }
                .joinToString(" ") { stage ->
                    "${stage.name}=${snapshot.stageDurations[stage]}ms" + if (stage == snapshot.stage) "*" else ""
                }
        val milestones =
            snapshot.milestones.entries
                .sortedBy { it.value }
                .joinToString(" ") { "${it.key.name}=+${it.value}ms" }
                .ifEmpty { "none" }
        val progress = of(snapshot)
        return "STAGE_DURATIONS $stages\nMILESTONES $milestones" +
            (progress?.blocker?.let { "\nBLOCKED_AT ${snapshot.stage.name}:${it.name}" } ?: "")
    }
}
