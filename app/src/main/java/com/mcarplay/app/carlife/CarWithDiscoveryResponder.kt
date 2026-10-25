package com.mcarplay.app.carlife

import java.io.BufferedInputStream
import java.io.EOFException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * CarWith's fixed EasyConn probe endpoint. It only accepts a complete
 * 16-byte-header request with command 0x70000010 and no payload.
 */
class CarWithDiscoveryResponder(
    private val log: (String) -> Unit,
    private val onAccepted: () -> Unit,
    private val port: Int = ExternalHuDiscoveryProtocol.PORT,
) {
    private class Listener(
        val server: ServerSocket,
    ) {
        val clients = mutableSetOf<Socket>()
    }

    @Volatile
    private var listener: Listener? = null

    val boundPort: Int? get() = listener?.server?.localPort

    internal val activeClientCount: Int
        @Synchronized get() = listener?.clients?.size ?: 0

    @Volatile
    var state: String = "idle"
        private set

    @Volatile
    var accepted: Boolean = false
        private set

    @Volatile
    var boundAddress: Inet4Address? = null
        private set

    @Synchronized
    fun start(bindAddress: Inet4Address): Boolean {
        if (listener != null && boundAddress == bindAddress) return true
        stop()
        accepted = false
        var server: ServerSocket? = null
        return try {
            val socket =
                ServerSocket().also { server = it }.apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddress, port))
                }
            val active = Listener(socket)
            listener = active
            boundAddress = bindAddress
            state = "10930 已监听"
            thread(name = "carwith-easyconn", isDaemon = true) { acceptLoop(active) }
            log("EASYCONN: 已监听 ${bindAddress.hostAddress}:${socket.localPort}")
            true
        } catch (error: Exception) {
            runCatching { server?.close() }
            listener = null
            boundAddress = null
            state = "10930 监听失败: ${error.message ?: error.javaClass.simpleName}"
            log("EASYCONN: $state")
            false
        }
    }

    @Synchronized
    fun stop() {
        val active = listener
        listener = null
        accepted = false
        boundAddress = null
        active?.let { closeListener(it) }
        state = "stopped"
        if (active != null) log("EASYCONN: 10930 已停止")
    }

    private fun acceptLoop(active: Listener) {
        while (listener === active) {
            val client =
                try {
                    active.server.accept()
                } catch (error: Exception) {
                    synchronized(this) {
                        if (listener === active) {
                            stop()
                            state = "10930 接收失败: ${error.message ?: error.javaClass.simpleName}"
                            log("EASYCONN: $state")
                        }
                    }
                    break
                }
            val admitted =
                synchronized(this) {
                    if (listener !== active || active.clients.size >= MAX_CLIENTS) {
                        false
                    } else {
                        active.clients.add(client)
                    }
                }
            if (!admitted) {
                runCatching { client.close() }
                continue
            }
            thread(name = "carwith-easyconn-client", isDaemon = true) {
                serve(active, client)
            }
        }
    }

    private fun serve(
        active: Listener,
        client: Socket,
    ) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = CLIENT_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val header = ByteArray(16)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) {
                    if (offset == 0) return
                    throw EOFException("truncated EasyConn header")
                }
                offset += count
            }
            val frame =
                runCatching { ExternalHuDiscoveryProtocol.decodeFrame(header) }
                    .getOrElse { error ->
                        log("EASYCONN: 拒绝非法帧 ${error.message ?: error.javaClass.simpleName}")
                        return
                    } ?: return
            if (!ExternalHuDiscoveryProtocol.isValidRequest(frame)) {
                log("EASYCONN: 忽略非 0x70000010 命令 0x${frame.kind.toString(16)}")
                return
            }
            if (ExternalHuDiscoveryProtocol.frameLength(header) != header.size) {
                log("EASYCONN: 忽略带载荷的 0x70000010")
                return
            }
            if (listener !== active) return
            val output = client.getOutputStream()
            output.write(ExternalHuDiscoveryProtocol.encodeResponse())
            output.flush()
            synchronized(this) {
                if (listener !== active) return
                accepted = true
                state = "10930 已接受 CarWith 探测"
                log("EASYCONN: 已接受 $peer 的 0x70000010，已回 0x70000011")
                onAccepted()
            }
        } catch (error: Exception) {
            if (listener === active) log("EASYCONN: $peer 处理失败 ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { client.close() }
            synchronized(this) { active.clients.remove(client) }
        }
    }

    private fun closeListener(active: Listener) {
        runCatching { active.server.close() }
        active.clients.forEach { client -> runCatching { client.close() } }
        active.clients.clear()
    }

    private companion object {
        const val CLIENT_TIMEOUT_MS = 5_000
        const val MAX_CLIENTS = 8
    }
}
