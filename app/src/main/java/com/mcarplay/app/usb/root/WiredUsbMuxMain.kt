package com.mcarplay.app.usb.root

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.mcarplay.app.transport.Iap2LinkChannel
import com.mcarplay.app.usb.Ep0Reply
import com.mcarplay.app.usb.FfsEventType
import com.mcarplay.app.usb.FunctionFs
import com.mcarplay.app.usb.LockdownUsbServer
import com.mcarplay.app.usb.UsbMuxControlRequests
import com.mcarplay.app.usb.UsbMuxFrameDecoder
import com.mcarplay.app.usb.UsbMuxFrameSequence
import com.mcarplay.app.usb.UsbMuxProtocol
import com.mcarplay.app.usb.UsbMuxTcpConnection
import com.mcarplay.app.usb.UsbMuxTcpDuplexStream
import com.mcarplay.app.usb.UsbMuxTcpSegment
import com.mcarplay.app.usb.UsbSetupPacket
import com.mcarplay.app.usb.WiredCarPlayStartFile
import com.mcarplay.app.usb.WiredIap2Session
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 复合 gadget 中 USBMUX FunctionFS function 的 root 用户态。
 *
 * 该进程必须先写入描述符，脚本才能把 gadget 绑定到 UDC。进程同时保留 ep0 并处理
 * Apple 厂商控制请求；启用配置后读取 bulk OUT、记录 USBMUX framing，并先完成 v2 版本握手。
 *
 * bulk OUT 的读取线程从不写 bulk IN：FunctionFS 写会阻塞到主机取走数据，而 DiPlay 的
 * usbmux 读线程会同步写 TCP ACK（2 秒超时）。两边的读线程同时卡在写上就会互锁，
 * 所以所有出站帧都进入队列，由独立写线程发送。
 */
object WiredUsbMuxMain {
    private const val POLL_MILLIS = 200
    private const val MAX_EVENT_BATCH = 8

    @JvmStatic
    fun main(args: Array<String>) {
        val code =
            try {
                if (args.firstOrNull() != "serve") {
                    usage()
                } else {
                    val ep0 = option(args, "--ep0")
                    val ready = option(args, "--ready")
                    if (ep0 == null || ready == null) {
                        usage()
                    } else {
                        Server(
                            ep0File = File(ep0),
                            readyFile = File(ready),
                            usbTransportIdentifier = option(args, "--transport-id") ?: DEFAULT_TRANSPORT_ID,
                            udcName = option(args, "--udc")?.takeIf(UDC_NAME::matches),
                            udcBindFile = option(args, "--udc-file")?.let(::File),
                            startFile = option(args, "--start-file")?.let(::File),
                        ).run()
                    }
                }
            } catch (t: Throwable) {
                out("错误：${t.javaClass.simpleName}: ${t.message}")
                1
            }
        exitProcess(code)
    }

    private fun usage(): Int {
        out(
            "用法：serve --ep0 <functionfs-ep0> --ready <ready-file> [--transport-id <id>] " +
                "[--udc <name>] [--udc-file <configfs-UDC>] [--start-file <path>]",
        )
        return 1
    }

    private class Server(
        private val ep0File: File,
        private val readyFile: File,
        private val usbTransportIdentifier: String,
        private val udcName: String?,
        private val udcBindFile: File?,
        private val startFile: File?,
    ) {
        private val decoder = UsbMuxFrameDecoder()
        private var ep0: FileDescriptor? = null

        @Volatile
        private var bulkOut: FileDescriptor? = null

        @Volatile
        private var bulkIn: FileDescriptor? = null
        private var reader: Thread? = null
        private var writer: Thread? = null
        private val outbound = LinkedBlockingQueue<ByteArray>()

        @Volatile
        private var lastReenumerationAt = 0L
        private val writeLock = Any()
        private val frameSequence = UsbMuxFrameSequence()
        private val connections = mutableMapOf<Int, UsbMuxTcpConnection>()
        private val lockdown by lazy { LockdownUsbServer(::out, usbTransportIdentifier) }

        fun run(): Int {
            val fd = Os.open(ep0File.path, OsConstants.O_RDWR, 0)
            ep0 = fd
            writeFully(fd, FunctionFs.usbMuxDescriptors())
            writeFully(fd, FunctionFs.strings("USB Multiplexor"))
            startFile?.delete()
            readyFile.parentFile?.mkdirs()
            readyFile.writeText("ready\n")
            out("USBMUX descriptors ready ep0=${ep0File.path} udc=${udcName ?: "未知"}")
            while (true) {
                pumpEp0(fd)
                openBulkIfReady()
            }
        }

        private fun pumpEp0(fd: FileDescriptor) {
            if (!poll(fd, POLL_MILLIS)) return
            val bytes = ByteArray(FunctionFs.EVENT_SIZE * MAX_EVENT_BATCH)
            val count =
                try {
                    Os.read(fd, bytes, 0, bytes.size)
                } catch (error: ErrnoException) {
                    throw IllegalStateException("读取 FunctionFS ep0 失败", error)
                }
            for (event in FunctionFs.parseEvents(bytes, count)) {
                when (event.type) {
                    FfsEventType.SETUP -> replyToControl(event.setup!!)
                    FfsEventType.ENABLE -> {
                        out("FFS ENABLE")
                        openBulkIfReady()
                    }
                    FfsEventType.DISABLE -> {
                        out("FFS DISABLE")
                        closeBulk()
                    }
                    else -> out("FFS ${event.type}")
                }
            }
        }

        private fun replyToControl(setup: UsbSetupPacket) {
            out("SETUP $setup")
            val fd = ep0 ?: return
            val reenumerate = UsbMuxControlRequests.isCarPlayModeRequest(setup) && canReenumerate()
            try {
                when (val reply = UsbMuxControlRequests.replyTo(setup, reenumerateForCarPlay = reenumerate)) {
                    is Ep0Reply.WriteIn -> {
                        writeFully(fd, reply.data)
                        if (setup.request == UsbMuxControlRequests.REQUEST_GET_MODE) {
                            out("USBMUX 当前模式=${UsbMuxProtocol.INITIAL_MODE.joinToString(":")}")
                        } else if (reenumerate) {
                            out("USBMUX CarPlay 模式请求已接受，即将模拟重枚举")
                            scheduleReenumeration()
                        } else if (setup.request == UsbMuxControlRequests.REQUEST_SET_MODE) {
                            out("USBMUX 模式切换返回 0x${reply.data.single().toInt() and 0xFF}，继续当前配置")
                        }
                    }
                    is Ep0Reply.AckOut -> {
                        if (setup.length == 0) {
                            Os.read(fd, ByteArray(0), 0, 0)
                        } else {
                            Os.read(fd, ByteArray(setup.length), 0, setup.length)
                        }
                    }
                    Ep0Reply.Stall -> {
                        stall(setup, fd)
                        if (setup.kind == 2 && setup.isDeviceToHost) {
                            out("USBMUX 未支持的厂商 IN 请求 bRequest=0x%02x".format(setup.request))
                        }
                    }
                }
            } catch (error: ErrnoException) {
                out("ep0 应答失败：${error.message}")
            }
        }

        private fun canReenumerate(): Boolean {
            if (udcName == null && udcBindFile == null) return false
            val elapsed = System.currentTimeMillis() - lastReenumerationAt
            return elapsed >= REENUMERATION_COOLDOWN_MILLIS
        }

        /**
         * 车机关闭连接后等待新的 ATTACHED 广播。soft_connect 断开/恢复 D+ 上拉即可让主机重新
         * 枚举，function 与 NCM 网卡保持绑定；内核不支持时退回 configfs UDC 解绑再绑定。
         */
        private fun scheduleReenumeration() {
            lastReenumerationAt = System.currentTimeMillis()
            Thread(
                {
                    try {
                        Thread.sleep(REENUMERATION_REPLY_GRACE_MILLIS)
                        val softConnect = udcName?.let { File("/sys/class/udc/$it/soft_connect") }
                        val softConnected =
                            if (softConnect != null && softConnect.exists() && writeSysfs(softConnect, "disconnect")) {
                                Thread.sleep(REENUMERATION_GAP_MILLIS)
                                writeSysfs(softConnect, "connect")
                            } else {
                                false
                            }
                        val method =
                            if (softConnected) {
                                "soft_connect"
                            } else {
                                val bind = checkNotNull(udcBindFile) { "没有可用的 UDC 控制节点" }
                                val name = udcName ?: bind.readText().trim()
                                check(name.isNotEmpty()) { "UDC 名称未知" }
                                check(writeSysfs(bind, "")) { "UDC 解绑失败" }
                                Thread.sleep(REENUMERATION_GAP_MILLIS)
                                check(writeSysfs(bind, name)) { "UDC 重新绑定失败" }
                                "UDC 重新绑定"
                            }
                        out("USBMUX 已通过 $method 模拟重枚举")
                    } catch (error: Throwable) {
                        out("USBMUX 模拟重枚举失败：${error.javaClass.simpleName}: ${error.message}")
                    }
                },
                "mcarplay-usbmux-reenumerate",
            ).apply {
                isDaemon = true
                start()
            }
        }

        private fun writeSysfs(
            file: File,
            value: String,
        ): Boolean =
            runCatching { file.writeText("$value\n") }
                .onFailure { out("写入 ${file.path} 失败：${it.message}") }
                .isSuccess

        private fun stall(
            setup: UsbSetupPacket,
            fd: FileDescriptor,
        ) {
            if (setup.isDeviceToHost) {
                Os.read(fd, ByteArray(0), 0, 0)
            } else {
                Os.write(fd, ByteArray(0), 0, 0)
            }
        }

        private fun openBulkIfReady() {
            if (bulkOut != null && bulkIn != null) {
                // 端点保持打开时读线程也可能已退出（坏帧、DISABLE→ENABLE 时旧线程尚未结束），
                // 主循环每轮都检查一次，否则 USBMUX 接收会永久停止。
                startWriter()
                startReader()
                return
            }
            val base = ep0File.parentFile ?: return
            try {
                if (bulkOut == null) {
                    val path = File(base, "ep1")
                    if (!path.exists()) return
                    bulkOut = Os.open(path.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
                    out("USBMUX bulk OUT 已打开 ${path.path}")
                }
                if (bulkIn == null) {
                    val path = File(base, "ep2")
                    if (!path.exists()) return
                    bulkIn = Os.open(path.path, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
                    out("USBMUX bulk IN 已打开 ${path.path}")
                }
                startWriter()
                startReader()
            } catch (error: ErrnoException) {
                out("USBMUX bulk 打开失败 errno=${error.errno}: ${error.message}")
                closeBulk()
            }
        }

        private fun startReader() {
            if (reader?.isAlive == true) return
            if (bulkOut == null) return
            // 只有旧读线程已结束才会走到这里，解码器不再被并发访问；新线程从流边界重新开始。
            decoder.reset()
            reader =
                Thread(
                    {
                        try {
                            readLoop()
                        } catch (error: Throwable) {
                            out("USBMUX reader 退出：${error.javaClass.simpleName}: ${error.message}")
                        }
                    },
                    "mcarplay-usbmux-reader",
                ).apply {
                    isDaemon = true
                    start()
                }
        }

        private fun startWriter() {
            if (writer?.isAlive == true) return
            writer =
                Thread(::writeLoop, "mcarplay-usbmux-writer").apply {
                    isDaemon = true
                    start()
                }
        }

        private fun writeLoop() {
            while (true) {
                val bytes = outbound.poll(POLL_MILLIS.toLong(), TimeUnit.MILLISECONDS) ?: continue
                val fd = bulkIn
                if (fd == null) {
                    out("USBMUX bulk IN 未打开，丢弃 ${bytes.size}B")
                    continue
                }
                try {
                    writeFully(fd, bytes)
                    out("USBMUX TX ${bytes.size}B ${hexPreview(bytes)}")
                } catch (error: ErrnoException) {
                    out("USBMUX bulk IN 写失败 errno=${error.errno}，丢弃 ${bytes.size}B")
                } catch (error: Throwable) {
                    out("USBMUX bulk IN 写失败：${error.javaClass.simpleName}: ${error.message}")
                }
            }
        }

        private fun readLoop() {
            val fd = bulkOut ?: return
            val buffer = ByteArray(16 * 1024)
            // 端点被 closeBulk 关闭或换成新的描述符后本线程退出，由主循环为新端点另起读线程。
            while (bulkOut === fd) {
                if (!poll(fd, POLL_MILLIS)) continue
                val count =
                    try {
                        Os.read(fd, buffer, 0, buffer.size)
                    } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                        throw error
                    }
                if (count <= 0) continue
                val bytes = buffer.copyOf(count)
                out("USBMUX RX ${count}B ${hexPreview(bytes)}")
                if (UsbMuxProtocol.isVersionMessage(bytes)) {
                    frameSequence.reset()
                    writeBulk(UsbMuxProtocol.versionReply())
                    out("USBMUX v2 握手已应答")
                    continue
                }
                decoder.append(bytes)
                drainFrames()
            }
        }

        private fun drainFrames() {
            while (true) {
                val frame = decoder.nextFrame() ?: return
                frameSequence.acceptInbound(frame.sequence)
                out("USBMUX frame protocol=${frame.protocol} mux_tx=${frame.sequence} mux_rx=${frame.acknowledgement}")
                when (frame.protocol) {
                    UsbMuxProtocol.PROTOCOL_SETUP -> out("USBMUX SETUP payload=${hexPreview(frame.payload)}")
                    UsbMuxProtocol.PROTOCOL_TCP -> {
                        val tcp = UsbMuxTcpSegment.parse(frame.payload)
                        out(
                            "USBMUX TCP ${tcp.sourcePort}->${tcp.destinationPort} " +
                                "flags=0x%02x seq=%d ack=%d payload=%dB".format(
                                    tcp.flags,
                                    tcp.sequence.toLong() and 0xFFFFFFFFL,
                                    tcp.acknowledgement.toLong() and 0xFFFFFFFFL,
                                    tcp.payload.size,
                                ),
                        )
                        val connection =
                            connections[tcp.sourcePort]
                                ?: if (tcp.flags and UsbMuxProtocol.TCP_SYN != 0) {
                                    UsbMuxTcpConnection(
                                        sourcePort = tcp.sourcePort,
                                        destinationPort = tcp.destinationPort,
                                        writeFrame = ::writeTcpFrame,
                                        log = ::out,
                                    ).also {
                                        connections[tcp.sourcePort] = it
                                        startTcpWorker(it)
                                    }
                                } else {
                                    out("USBMUX 忽略无 SYN 的 TCP 连接 ${tcp.sourcePort}->${tcp.destinationPort}")
                                    null
                                }
                        try {
                            connection?.accept(tcp)
                        } catch (error: Throwable) {
                            out("USBMUX TCP 处理失败：${error.javaClass.simpleName}: ${error.message}")
                            connection?.close()
                            connections.remove(tcp.sourcePort)
                        }
                    }
                    else -> out("USBMUX protocol=${frame.protocol} length=${frame.length}")
                }
            }
        }

        private fun startTcpWorker(connection: UsbMuxTcpConnection) {
            val serviceName = lockdown.serviceNameForPort(connection.destinationPort)
            if (connection.destinationPort != LOCKDOWN_PORT && serviceName == null) {
                out("USBMUX 未实现目标端口 ${connection.destinationPort}")
                return
            }
            Thread(
                {
                    try {
                        if (connection.destinationPort == LOCKDOWN_PORT) {
                            lockdown.serveLockdown(connection)
                        } else if (connection.destinationPort == LockdownUsbServer.CARKIT_PORT) {
                            val link =
                                Iap2LinkChannel(
                                    stream = UsbMuxTcpDuplexStream(connection),
                                    name = "wired-carkit-${connection.sourcePort}",
                                    log = ::out,
                                )
                            link.use {
                                val session =
                                    WiredIap2Session(
                                        channel = it,
                                        usbTransportIdentifier = usbTransportIdentifier,
                                        log = ::out,
                                    )
                                publishStart(session.run())
                                session.pumpIncoming()
                            }
                        } else {
                            lockdown.serveService(connection, serviceName!!)
                        }
                    } catch (error: Throwable) {
                        out(
                            "USBMUX 端口 ${connection.destinationPort} worker 退出：" +
                                "${error.javaClass.simpleName}: ${error.message}",
                        )
                    } finally {
                        connection.close()
                    }
                },
                "mcarplay-usbmux-tcp-${connection.destinationPort}-${connection.sourcePort}",
            ).apply {
                isDaemon = true
                start()
            }
        }

        /** 应用侧（UsbNcmAutoConnector）轮询该文件，以车机给出的地址直连 AirPlay。 */
        private fun publishStart(start: WiredIap2Session.CarPlayStart) {
            val file = startFile ?: return
            runCatching {
                val text =
                    WiredCarPlayStartFile.encode(start)
                val temp = File(file.path + ".tmp")
                temp.writeText(text)
                check(temp.renameTo(file)) { "rename 失败" }
                out("USBMUX CarPlayStartSession 已写入 ${file.path}")
            }.onFailure { out("USBMUX 写入 CarPlayStartSession 失败：${it.message}") }
        }

        private fun writeBulk(bytes: ByteArray) {
            synchronized(writeLock) {
                enqueueLocked(bytes)
            }
        }

        private fun writeTcpFrame(bytes: ByteArray) {
            synchronized(writeLock) {
                val stamped = frameSequence.stampOutbound(bytes)
                enqueueLocked(stamped)
                out(
                    "USBMUX frame TX mux_tx=${UsbMuxProtocol.readU16(stamped, 12)} " +
                        "mux_rx=${UsbMuxProtocol.readU16(stamped, 14)}",
                )
            }
        }

        private fun enqueueLocked(bytes: ByteArray) {
            check(bulkIn != null) { "USBMUX bulk IN 尚未打开" }
            outbound.put(bytes)
        }

        private fun closeBulk() {
            outbound.clear()
            connections.values.toList().forEach(UsbMuxTcpConnection::close)
            connections.clear()
            bulkOut?.let { runCatching { Os.close(it) } }
            bulkIn?.let { runCatching { Os.close(it) } }
            bulkOut = null
            bulkIn = null
        }

        private fun poll(
            fd: FileDescriptor,
            timeoutMillis: Int,
        ): Boolean {
            val pollfd =
                StructPollfd().apply {
                    this.fd = fd
                    events = OsConstants.POLLIN.toShort()
                }
            return try {
                Os.poll(arrayOf(pollfd), timeoutMillis) > 0
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) false else throw error
            }
        }

        private fun writeFully(
            fd: FileDescriptor,
            bytes: ByteArray,
        ) {
            var offset = 0
            while (offset < bytes.size) {
                offset += Os.write(fd, bytes, offset, bytes.size - offset)
            }
        }

        private fun hexPreview(bytes: ByteArray): String =
            bytes
                .take(48)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                .let { if (bytes.size > 48) "$it…" else it }
    }

    private fun option(
        args: Array<String>,
        name: String,
    ): String? = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }

    private fun out(line: String) {
        println("USBMUX: $line")
        System.out.flush()
    }

    private const val LOCKDOWN_PORT = 62_078
    private const val DEFAULT_TRANSPORT_ID = "mcarplay-usb"
    private const val REENUMERATION_COOLDOWN_MILLIS = 15_000L
    private const val REENUMERATION_REPLY_GRACE_MILLIS = 300L
    private const val REENUMERATION_GAP_MILLIS = 800L
    private val UDC_NAME = Regex("[A-Za-z0-9._:-]+")
}
