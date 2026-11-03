package com.mcarplay.app.bt

import android.os.SystemClock

/**
 * 上车自动连接的环节耗时：车机蓝牙 ACL 到达时起表，各环节**第一次**发生时记一行「距 ACL +N ms」。
 *
 * 用来判断 iAP2 SDP 记录是否赶在车机来连之前注册好：记录只在进程活着时存在，而真 iPhone 在艾瑞泽 8 上
 * 车机点选后约 0.37 s 就连手机的 iAP2 服务（findings「真 iPhone 连接全过程」第 1 步）。
 * 手机看不到车机何时查自己的 SDP，只能看到车机连入或断开，所以要和车机日志或 btsnoop 对照。
 *
 * 只记 ACL 后 [WINDOW_MS] 内的环节；手动连接不起表，不出这些行。返回要写的日志行（无设备标识），
 * 由调用方用各自的日志通道写出。
 */
class AutoConnectTimeline(
    private val nowMs: () -> Long,
) {
    enum class Stage(
        val label: String,
    ) {
        SERVICE_STARTED("前台服务收到自动启动"),
        LISTEN_STARTED("开始注册 iAP2 SDP 记录"),
        SDP_REGISTERED("iAP2 SDP 记录与监听就绪"),
        OUTBOUND_STARTED("首次主动连车机"),
        OUTBOUND_CONNECTED("主动连车机成功"),
        OUTBOUND_FAILED("主动连车机首次失败"),
        CAPABILITY_CONNECTED("车机连入 CarPlay 能力记录"),
        INBOUND_IAP2("车机连入 iAP2"),
        ACL_DISCONNECTED("车机蓝牙断开"),
    }

    private var armedAtMs: Long? = null
    private val seen = mutableSetOf<Stage>()

    /** 车机 ACL 到达（每次都重新起表）。[processAgeMs] = 进程已启动多久，用来区分冷启动。 */
    @Synchronized
    fun arm(processAgeMs: Long?): String {
        armedAtMs = nowMs()
        seen.clear()
        val process =
            when {
                processAgeMs == null -> "进程启动时刻未知"
                processAgeMs <= COLD_START_MS -> "进程由本次广播冷启动，${processAgeMs.coerceAtLeast(0)}ms 前起"
                else -> "进程已运行 ${processAgeMs / 1000}s"
            }
        return "AUTO-TIMING: 车机 ACL 到达，开始计时（$process）"
    }

    /** 记一个环节；未起表、超出窗口或该环节已记过时返回 null。 */
    @Synchronized
    fun mark(
        stage: Stage,
        detail: String = "",
    ): String? {
        val armedAt = armedAtMs ?: return null
        val elapsed = (nowMs() - armedAt).coerceAtLeast(0)
        if (elapsed > WINDOW_MS) {
            armedAtMs = null
            return null
        }
        if (!seen.add(stage)) return null
        return "AUTO-TIMING: ${stage.label} +${elapsed}ms（距车机 ACL）$detail"
    }

    companion object {
        /** 冷启动判定：ACL 广播把被杀进程拉起来时，进程年龄通常只有几百毫秒。 */
        const val COLD_START_MS = 5_000L

        /** 上车建链（含等车机回连）一般在一分钟内见分晓；超出后的环节不再计入本次 ACL。 */
        const val WINDOW_MS = 120_000L
    }
}

/** 进程内共用的自动连接计时：接收器、前台服务和蓝牙收发两端都往这里记。 */
object AutoConnectTiming {
    val timeline = AutoConnectTimeline { SystemClock.elapsedRealtime() }
}
