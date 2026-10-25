package com.mcarplay.app.carlife

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Experimental, user-started HU emulator for CarWith's wireless path.
 *
 * It deliberately does not bypass CarWith's signature checks or launch a
 * non-exported activity. It only exposes the RFCOMM endpoint that a paired HU
 * would normally provide. Success here proves the SDP/Wi-Fi stage, not a
 * CarLife video stream or real-vehicle CarPlay compatibility.
 */
class ExternalHuSdpBridge(
    context: Context,
    private val log: (String) -> Unit,
    private val onStateChanged: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val serverSocket = AtomicReference<BluetoothServerSocket?>()
    private val activeSocket = AtomicReference<BluetoothSocket?>()

    @Volatile
    var state: String = "idle"
        private set(value) {
            field = value
            onStateChanged()
        }

    @Volatile
    private var pendingAdvertisement: ExternalHuSdpProtocol.HuAdvertisement? = null

    val advertisement: ExternalHuSdpProtocol.HuAdvertisement?
        get() = pendingAdvertisement

    val isListening: Boolean get() = running.get() && serverSocket.get() != null

    val isConnected: Boolean get() = running.get() && activeSocket.get()?.isConnected == true

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            state = "缺少 BLUETOOTH_CONNECT"
            log("HU-SDP: $state")
            return false
        }
        val adapter =
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
        if (adapter == null || !adapter.isEnabled) {
            running.set(false)
            state = "蓝牙未开启"
            log("HU-SDP: $state")
            return false
        }
        return try {
            val server =
                adapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    ExternalHuSdpProtocol.SERVICE_UUID,
                )
            serverSocket.set(server)
            state = "等待车机连接"
            thread(name = "carwith-hu-sdp", isDaemon = true) { serve(server) }
            log("HU-SDP: 已注册 ${ExternalHuSdpProtocol.SERVICE_UUID}")
            true
        } catch (error: Exception) {
            running.set(false)
            serverSocket.set(null)
            state = "监听失败: ${safeMessage(error)}"
            log("HU-SDP: $state")
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        pendingAdvertisement = null
        state = "stopped"
        runCatching { serverSocket.getAndSet(null)?.close() }
        runCatching { activeSocket.getAndSet(null)?.close() }
        log("HU-SDP: 已停止")
    }

    fun setAdvertisement(advertisement: ExternalHuSdpProtocol.HuAdvertisement) {
        pendingAdvertisement = advertisement
        val network = advertisement.network
        log("HU-SDP: 已设置车机公告 ${network.name}/${network.ssid}")
        onStateChanged()
    }

    fun clearAdvertisement() {
        pendingAdvertisement = null
        onStateChanged()
    }

    @SuppressLint("MissingPermission")
    private fun serve(server: BluetoothServerSocket) {
        try {
            while (running.get()) {
                val socket =
                    try {
                        server.accept()
                    } catch (error: Exception) {
                        if (!running.get()) return
                        throw error
                    }
                activeSocket.set(socket)
                handleConnection(socket)
                activeSocket.compareAndSet(socket, null)
                runCatching { socket.close() }
            }
        } catch (error: Exception) {
            if (running.get()) {
                state = "监听失败: ${safeMessage(error)}"
                log("HU-SDP: $state")
            }
        } finally {
            serverSocket.compareAndSet(server, null)
            runCatching { server.close() }
            running.set(false)
            if (state != "stopped") state = "已停止"
        }
    }

    private fun handleConnection(socket: BluetoothSocket) {
        val peer = runCatching { socket.remoteDevice.address }.getOrElse { "unknown" }
        state = "车机已连接，等待建网请求"
        log("HU-SDP: 车机已连接 $peer")
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            while (running.get()) {
                val frame = readFrame(input) ?: break
                handleFrame(frame, output)
            }
        } catch (_: EOFException) {
            log("HU-SDP: 车机已断开")
        } catch (error: Exception) {
            if (running.get()) log("HU-SDP: 连接异常 ${safeMessage(error)}")
        } finally {
            state = "等待车机连接"
        }
    }

    private fun handleFrame(
        frame: ExternalHuSdpProtocol.Frame,
        output: OutputStream,
    ) {
        when (frame.kind) {
            ExternalHuSdpProtocol.CONNECT_TYPE -> {
                log("HU-SDP: RX CONNECT_TYPE")
                writeFrame(output, frame.kind + 1, ExternalHuSdpProtocol.joinJsonStatus(0))
            }
            ExternalHuSdpProtocol.CLIENT_INFO -> {
                val info =
                    ExternalHuSdpProtocol.parseJson(
                        frame.body,
                        setOf("packageName", "netInterface"),
                    )
                val packageName = info?.get("packageName") ?: "?"
                val interfaceCount =
                    (info?.get("netInterface") as? org.json.JSONArray)?.length() ?: 0
                log("HU-SDP: RX CLIENT_INFO package=$packageName interfaces=$interfaceCount")
                writeFrame(output, frame.kind + 1, ExternalHuSdpProtocol.joinJsonStatus(0))
            }
            ExternalHuSdpProtocol.REQUEST_BUILD_NET -> {
                log("HU-SDP: RX REQUEST_BUILD_NET")
                val advertisement = pendingAdvertisement
                if (advertisement == null) {
                    state = "需要先设置车机公告"
                    writeFrame(
                        output,
                        frame.kind + 1,
                        ExternalHuSdpProtocol.joinJsonStatus(
                            ExternalHuSdpProtocol.STATUS_AUTH_PENDING,
                        ),
                    )
                } else {
                    state = "已通告 Wi-Fi Direct，等待 CarWith 加入"
                    writeFrame(
                        output,
                        frame.kind + 1,
                        ExternalHuSdpProtocol.carInfoBody(advertisement),
                    )
                }
            }
            ExternalHuSdpProtocol.NOTIFY_AP_INFO -> {
                log("HU-SDP: RX NOTIFY_AP_INFO")
                writeFrame(output, frame.kind + 1, ExternalHuSdpProtocol.joinJsonStatus(0))
            }
            ExternalHuSdpProtocol.BUILD_NET_FINISH -> {
                state = "CarWith 已完成 P2P 建网，等待 mDNS/10930"
                log("HU-SDP: RX BUILD_NET_FINISH")
                writeFrame(output, frame.kind + 1, ExternalHuSdpProtocol.joinJsonStatus(0))
            }
            else -> log("HU-SDP: RX 未识别命令 0x${frame.kind.toString(16)}")
        }
    }

    private fun readFrame(input: InputStream): ExternalHuSdpProtocol.Frame? {
        val header = ByteArray(16)
        var offset = 0
        while (offset < header.size) {
            val count = input.read(header, offset, header.size - offset)
            if (count < 0) {
                if (offset == 0) return null
                throw EOFException("Truncated SDP header")
            }
            offset += count
        }
        val length = readIntLe(header, 4)
        val kind = readIntLe(header, 0)
        val checksum = readIntLe(header, 8)
        require(length in header.size..header.size + ExternalHuSdpProtocol.MAX_BODY_SIZE) {
            "Invalid SDP frame length $length"
        }
        require(checksum == (length xor kind)) { "Invalid SDP frame checksum" }
        val body = ByteArray(length - header.size)
        var bodyOffset = 0
        while (bodyOffset < body.size) {
            val count = input.read(body, bodyOffset, body.size - bodyOffset)
            if (count < 0) throw EOFException("Truncated SDP body")
            bodyOffset += count
        }
        return ExternalHuSdpProtocol.decodeFrame(header + body)
    }

    private fun writeFrame(
        output: OutputStream,
        kind: Int,
        body: ByteArray,
    ) {
        synchronized(output) {
            output.write(ExternalHuSdpProtocol.encodeFrame(kind, body))
            output.flush()
        }
        log("HU-SDP: TX 0x${kind.toString(16)} ${body.size}B")
    }

    private fun readIntLe(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun safeMessage(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private companion object {
        const val SERVICE_NAME = "mcarplay CarWith HU"
    }
}
