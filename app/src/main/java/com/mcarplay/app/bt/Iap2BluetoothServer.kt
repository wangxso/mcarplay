package com.mcarplay.app.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.mcarplay.app.compat.ReceiverDetectionRuntime
import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.transport.StreamDuplexStream
import com.mcarplay.app.util.Bg
import java.io.IOException
import java.util.UUID

/**
 * 手机侧（iPhone 角色）的 iAP2 蓝牙 RFCOMM 监听。
 *
 * 真实无线 CarPlay 里 iPhone 配对后监听这个 SDP 服务等待车机回连；
 * 测试台上 DiPlay/xcertplay 会扫描已配对的 PHONE_SMART 设备并主动连入。
 *
 * 连入后：链路协商（marker/同步/ACK）→ 完整发送端 bring-up（Phase 1），
 * 成功时回调 [onEndpoint] 交出热点 SSID/密码与 AirPlay 端点。
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 0x5703 一到即触发（绑 WiFi + 广播 _carplay-ctrl），真车 0x4301 的前置条件。 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** Phase 1 之后车机补发 0x4301（CarPlay 窗口晚开）时交给主控热更新端点。 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 发送端链路断开（保活泵退出）时通知主控（释放僵尸轮次，见 SessionController.onSenderLinkClosed）。 */
    private val onLinkClosed: () -> Unit = {},
    /** 本进程已知的车机热点 SSID（本轮无新凭据时沿用，避免每轮 8 秒拆链）。 */
    private val knownCarSsid: () -> String? = { null },
    /** 车机是否已在 WiFi 上发过 ctrl 邀请（无凭据时据此不拆链）。 */
    private val receiverInvited: () -> Boolean = { false },
    /** 0x1D01 画像判定的链路代际（Phase 1 完成后回调，AirPlay 阶段分派用）。 */
    private val onGeneration: (ReceiverProfile.LinkGeneration) -> Unit = {},
    /** 目标车机（动态读取：拒绝非目标设备的连入；null=来者不拒）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    private val diagnostics: BluetoothDiagnostics = BluetoothDiagnostics({}),
) {
    companion object {
        /**
         * iPhone 的 iAP2 服务 UUID（Oligo Security 公布；与 xcertplay
         * CarPlayController.IAP2_IPHONE_UUID 一致）。配件端是 …decacaff。
         */
        val IPHONE_IAP2_UUID: UUID = PhoneSdpRecords.IPHONE_IAP2_UUID
        private const val SESSION_TIMEOUT_MILLIS = 60_000L
    }

    private var diagnosticsEpoch = 0L
    private val listener =
        PhoneSdpListener(
            open = ::openRecords,
            onOpened = ::listenRecords,
            onClosed = { diagnostics.serverStopped(diagnosticsEpoch) },
        )

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    /** [activeChannel] 上的会话对象（与之同锁维护），退役时据此发 0xEA01。 */
    private var activeSession: Iap2SenderSession? = null
    private val channelLock = Any()

    val isRunning: Boolean get() = listener.isRunning

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    fun start(): Boolean =
        try {
            listener.start()
        } catch (error: IOException) {
            log("BT: 监听失败 - ${BluetoothFailure.describe(error)}")
            listener.recoveryToken()?.let(::recoverListener)
            false
        } catch (error: SecurityException) {
            log("BT: 监听失败 - ${BluetoothFailure.describe(error)}")
            false
        }

    /** 复用客户端的系统状态广播；仅恢复仍由用户请求的公告，不重新启动媒体或连接车机。 */
    internal fun onAdapterStateChanged(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> listener.adapterUnavailable()
            BluetoothAdapter.STATE_ON -> listener.recoveryToken()?.let(::recoverListener)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecords(): PhoneSdpRecords<BluetoothServerSocket> {
        if (!hasPermission()) throw SecurityException("BLUETOOTH_CONNECT not granted")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) throw IOException("Bluetooth adapter unavailable")
        val diagnosticEpoch = diagnostics.epoch().also { diagnosticsEpoch = it }
        return PhoneSdpRecords.open { name, uuid ->
            val service =
                if (uuid == PhoneSdpRecords.IPHONE_IAP2_UUID) {
                    BluetoothDiagnostics.Service.IAP2
                } else {
                    BluetoothDiagnostics.Service.CARPLAY
                }
            val startedAt = SystemClock.elapsedRealtime()
            try {
                adapter.listenUsingRfcommWithServiceRecord(name, uuid).also {
                    diagnostics.registration(diagnosticEpoch, service, SystemClock.elapsedRealtime() - startedAt)
                }
            } catch (error: Exception) {
                diagnostics.registration(diagnosticEpoch, service, SystemClock.elapsedRealtime() - startedAt, error)
                throw error
            }
        }
    }

    private fun listenRecords(records: PhoneSdpRecords<BluetoothServerSocket>) {
        val diagnosticEpoch = diagnosticsEpoch
        diagnostics.listener(diagnosticEpoch, BluetoothDiagnostics.Service.IAP2, listening = true)
        diagnostics.listener(diagnosticEpoch, BluetoothDiagnostics.Service.CARPLAY, listening = true)
        log("BT: 监听 iAP2 并发布无线 CarPlay 手机能力，等待接收端回连…")
        Thread({ acceptLoop(records, diagnosticEpoch) }, "iap2-bt-accept").apply {
            isDaemon = true
            start()
        }
        Thread({ acceptCapabilityLoop(records, diagnosticEpoch) }, "carplay-sdp-accept").apply {
            isDaemon = true
            start()
        }
    }

    private fun recoverListener(token: Long) {
        Bg.run("bt-sdp-recover") {
            for ((index, delay) in listOf(0L, 500L, 1_500L).withIndex()) {
                try {
                    if (delay > 0) Thread.sleep(delay)
                    // 手动停止、蓝牙再次关闭或另一任务已恢复时，旧任务直接退役。
                    if (listener.recover(token)) log("BT: 已恢复本机 iAP2/CarPlay 公告（不代表车机已识别）")
                    return@run
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@run
                } catch (error: IOException) {
                    log("BT: 公告恢复 ${index + 1}/3 失败 - ${BluetoothFailure.describe(error)}")
                } catch (error: SecurityException) {
                    log("BT: 公告恢复需要蓝牙权限 - ${BluetoothFailure.describe(error)}")
                    return@run
                }
            }
        }
    }

    private fun listenerFailed(
        records: PhoneSdpRecords<BluetoothServerSocket>,
        diagnosticEpoch: Long,
        service: BluetoothDiagnostics.Service,
        error: IOException,
    ) {
        var retired = false
        val token =
            listener.failed(records) {
                retired = true
                diagnostics.listener(diagnosticEpoch, service, listening = false, error = error)
                log("BT: ${service.name} 监听结束，本机公告已退役 - ${BluetoothFailure.describe(error)}")
            }
        if (token != null) {
            recoverListener(token)
        } else if (retired) {
            log("BT: 公告自动恢复已达 3 次上限，请检查蓝牙后重新启动连接")
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(
        records: PhoneSdpRecords<BluetoothServerSocket>,
        diagnosticEpoch: Long,
    ) {
        while (listener.isCurrent(records)) {
            val socket: BluetoothSocket =
                try {
                    records.iap2.accept()
                } catch (error: IOException) {
                    listenerFailed(records, diagnosticEpoch, BluetoothDiagnostics.Service.IAP2, error)
                    break
                }
            if (!listener.isCurrent(records)) {
                runCatching { socket.close() }
                break
            }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // 指定了目标车机时拒绝其它设备的连入（用户配对了多台车机：非目标车机
            // 的控制器也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            val match = peerMatch(target, peerAddress)
            diagnostics.incoming(diagnosticEpoch, BluetoothDiagnostics.Service.IAP2, peerAddress, match)
            if (target != null && match == BluetoothDiagnostics.PeerMatch.OTHER) {
                log(
                    "BT: 忽略非目标设备连入 name=$peerName addr=${peerAddress ?: "?"}" +
                        "（当前目标：「${target.name}」）",
                )
                runCatching { socket.close() }
                continue
            }
            log("BT: 接收端已连入 name=$peerName，开始 iAP2 发送端会话")
            val observation = ReceiverDetectionRuntime.observation(peerAddress)
            Thread({ runSession(socket, observation) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** 该 UUID 仅声明 CarPlay 能力；真正的 iAP2 字节流仍由 …cafe 服务承载。 */
    @SuppressLint("MissingPermission")
    private fun acceptCapabilityLoop(
        records: PhoneSdpRecords<BluetoothServerSocket>,
        diagnosticEpoch: Long,
    ) {
        while (listener.isCurrent(records)) {
            try {
                records.carPlay.accept().use { socket ->
                    if (listener.isCurrent(records)) {
                        val address = runCatching { socket.remoteDevice?.address }.getOrNull()
                        diagnostics.incoming(
                            diagnosticEpoch,
                            BluetoothDiagnostics.Service.CARPLAY,
                            address,
                            peerMatch(targetProvider(), address),
                        )
                    }
                }
            } catch (error: IOException) {
                listenerFailed(records, diagnosticEpoch, BluetoothDiagnostics.Service.CARPLAY, error)
                break
            }
        }
    }

    private fun peerMatch(
        target: TargetDevicePrefs.Target?,
        address: String?,
    ): BluetoothDiagnostics.PeerMatch =
        when {
            target == null -> BluetoothDiagnostics.PeerMatch.UNSELECTED
            TargetDevicePrefs.matches(target, address) -> BluetoothDiagnostics.PeerMatch.TARGET
            else -> BluetoothDiagnostics.PeerMatch.OTHER
        }

    private fun runSession(
        socket: BluetoothSocket,
        observation: ReceiverObservation?,
    ) {
        val stream =
            StreamDuplexStream(socket.inputStream, socket.outputStream) {
                runCatching { socket.close() }
            }
        val channel = Iap2LinkChannel(stream, name = "iap2-bt") { line -> log(line) }
        synchronized(channelLock) {
            activeChannel = channel
            BtSessionGate.inboundActive = true
        }
        try {
            channel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                onEndpoint,
                onWifiCredentials,
                onEndpointUpdate,
                onLinkClosed,
                knownCarSsid,
                receiverInvited,
                onGeneration,
                peerAddress = socket.remoteDevice?.address,
                observation = observation,
                onSession = { session ->
                    synchronized(channelLock) { if (activeChannel === channel) activeSession = session }
                },
            )
        } catch (error: IOException) {
            log("SESSION 失败: ${error.message}")
        } finally {
            synchronized(channelLock) {
                if (activeChannel === channel) {
                    activeChannel = null
                    activeSession = null
                    BtSessionGate.inboundActive = false
                }
            }
            channel.close()
            log("SESSION 结束，等待接收端重连（BT 监听仍在运行）")
        }
    }

    /**
     * 主动结束当前 BT 控制会话（不影响监听）。
     *
     * [eaStopReason] 非空时先结束本轮 EA 会话（0xEA01，见 [retireControlLink]）；
     * 为 null 时立即关闭，用于 AirPlay 配对前复现控制通道退役状态。
     */
    fun detachCurrentSession(eaStopReason: String? = null) {
        val (channel, session) =
            synchronized(channelLock) {
                (activeChannel to activeSession).also {
                    activeChannel = null
                    activeSession = null
                    BtSessionGate.inboundActive = false
                }
            }
        channel ?: return
        retireControlLink(session, eaStopReason) { channel.close() }
        log(
            if (eaStopReason == null) {
                "BT: 已主动断开当前控制会话（模拟 tunnel handoff 后的退役）"
            } else {
                "BT: 已主动断开当前控制会话（$eaStopReason）"
            },
        )
    }

    fun stop() {
        listener.stop()
        log("BT: 监听已停止")
    }
}
