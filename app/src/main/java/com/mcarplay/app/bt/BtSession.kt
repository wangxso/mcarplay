package com.mcarplay.app.bt

import android.content.Context
import com.mcarplay.app.compat.ReceiverDetectionRuntime
import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.ConnectionDiagnostics
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.DeviceIdentity

/**
 * BT 控制会话核心流程（Server/Client 共用，此前两份近乎复制）：
 * 构建发送端会话 → Phase 1 交付端点 → 保活泵（阻塞直至链路断开）。
 * socket 封装 / activeChannel 登记 / 生命周期收尾由两条路径各自处理。
 */
internal fun Iap2LinkChannel.runSenderSession(
    context: Context,
    timeoutMillis: Long,
    log: (String) -> Unit,
    onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit,
    onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onLinkClosed: () -> Unit = {},
    /** 本进程已知的车机热点 SSID（无新凭据时沿用，避免每轮拆链）。 */
    knownCarSsid: () -> String? = { null },
    /** 车机是否已在 WiFi 上发过 ctrl 邀请（无凭据时据此不拆链）。 */
    receiverInvited: () -> Boolean = { false },
    /** 0x1D01 画像判定的链路代际（CLASSIC_MIB3/EVENT_LINK），Phase 1 完成后回调。 */
    onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
    peerAddress: String? = null,
    observation: ReceiverObservation? = ReceiverDetectionRuntime.observation(peerAddress),
    /** 会话对象建好、开始 bring-up 之前回调；供退役链路时发 0xEA01（见 [retireControlLink]）。 */
    onSession: (Iap2SenderSession) -> Unit = {},
) {
    val session =
        Iap2SenderSession(
            this,
            { line -> log(line) },
            resolveBluetoothMac(context, log),
            DeviceIdentity.deviceName(context),
            { wifi -> if (ReceiverDetectionRuntime.isCurrent(observation)) onWifiCredentials(wifi) },
            { update ->
                if (ReceiverDetectionRuntime.isCurrent(observation)) onEndpointUpdate(update.copy(observation = observation))
            },
            knownCarSsid,
            receiverInvited,
            // MFi 挑战长度按车机身份持久化（省掉 20B 静默 6 秒的试探）
            context.applicationContext,
            receiverBinding = observation?.binding,
            rememberMfi = !ReceiverDetectionRuntime.collecting(observation),
            onMilestone = { milestone ->
                if (ReceiverDetectionRuntime.isCurrent(observation)) ConnectionDiagnostics.milestoneCurrent(milestone)
            },
        )
    onSession(session)
    val endpoint = session.run(timeoutMillis)
    onGeneration(session.linkGeneration)
    if (!ReceiverDetectionRuntime.isCurrent(observation)) return
    onEndpoint(endpoint.copy(observation = observation))
    // Phase 2 过渡：保持控制通道存活记录配件消息；AirPlay 配对由 onEndpoint 回调发起
    session.pumpIncomingForever()
    // 链路断开（保活泵退出）：通知主控释放「会话处理中」——否则车机拆链后重连带来的
    // 新端点会被僵尸轮次堵门（2026-10-22 奥迪 3315 真机定谳，见 SessionController.onSenderLinkClosed）
    if (ReceiverDetectionRuntime.isCurrent(observation)) onLinkClosed()
}

/** 退役链路时，0xEA01 交给 socket 后到关闭 RFCOMM 之间的冲刷宽限。 */
internal const val EA_STOP_FLUSH_MILLIS = 200L

/**
 * 退役一条 BT iAP2 控制链路。
 *
 * [eaStopReason] 非空、且 [session] 本轮开过 EA 会话时，在后台线程先发 `0xEA01`，
 * 留 [EA_STOP_FLUSH_MILLIS] 让帧离开本机蓝牙栈，再执行 [closeLink]；
 * 其余情况与原来一样在调用线程立即 [closeLink]。
 * 写 socket 可能阻塞，所以发送不放在调用线程（用户断开可能在主线程收尾）。
 */
internal fun retireControlLink(
    session: Iap2SenderSession?,
    eaStopReason: String?,
    closeLink: () -> Unit,
) {
    if (eaStopReason == null || session == null || !session.hasEaSessionsToStop()) {
        closeLink()
        return
    }
    Bg.run("iap2-ea-stop") {
        try {
            if (session.stopEaSessions(eaStopReason) > 0) Thread.sleep(EA_STOP_FLUSH_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            closeLink()
        }
    }
}

/**
 * 会话启动时解析真实蓝牙 MAC（0x4300 / 0x4E0E 用）。
 *
 * **必须 forceRetry**：`DeviceIdentity` 对解析失败有 10 秒静默窗口，而 BT 会话往往在
 * Shizuku 刚就绪的同一毫秒构造——首次解析（Shizuku 尚未 ready）失败后，10 秒内的所有
 * 调用都直接返回占位 `02:00:00:00:00:00`。奥迪会拿 0x4300 的传输标识与 RFCOMM 对端
 * 核对，占位值导致它**静默丢弃**（链路层照常 ACK、CSM 层不再回应）。
 * 2026-10-19 奥迪 0.1.7 日志即此：整轮都是占位值，车机始终没下发 0x4301。
 */
private fun resolveBluetoothMac(
    context: Context,
    log: (String) -> Unit,
): String {
    val cached = DeviceIdentity.cachedBluetoothMac()
    if (cached != null) return cached
    val resolved = DeviceIdentity.resolve(context, forceRetry = true)
    val mac = resolved.mac
    if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
        log(
            "BT: ⚠ 未能解析真实蓝牙 MAC（仍是占位值）——车机会校验 0x4300 的传输标识，" +
                "占位值可能导致它不下发 0x4301。请启动 Shizuku 或在「本机蓝牙地址」中手动填写",
        )
    } else {
        log("BT: 本机蓝牙 MAC=$mac 来源=${DeviceIdentity.sourceLabel(resolved.source)}（0x4300/0x4E0E 与 ctrl 广播 TXT id 同源）")
    }
    return mac
}
