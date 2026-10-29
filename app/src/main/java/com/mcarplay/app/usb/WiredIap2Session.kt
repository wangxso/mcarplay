package com.mcarplay.app.usb

import com.mcarplay.app.iap2.Iap2Csm
import com.mcarplay.app.iap2.Iap2MessageIds
import com.mcarplay.app.iap2.asString
import com.mcarplay.app.transport.Iap2LinkChannel
import java.io.IOException
import java.security.SecureRandom

/**
 * MacPlay / DiPlay 有线模式所需的 iPhone 侧最小 iAP2 会话。
 *
 * MacPlay 在打开 `com.apple.carkit.service` 后作为配件发起识别、MFi 与 CarPlay
 * 传输协商。这里完成识别和认证，再通过 `0x4300` 声明有线 CarPlay 可用；配件返回的
 * `0x4301` 提供 AirPlay 端口和 USB 网卡地址。音视频本身仍由现有 AirPlay 发送端承载。
 */
class WiredIap2Session(
    private val channel: Iap2LinkChannel,
    private val usbTransportIdentifier: String,
    private val deviceName: String = "mcarplay iPhone",
    private val log: (String) -> Unit = {},
) {
    data class CarPlayStart(
        val addresses: List<String>,
        val port: Int,
        val deviceIdentifier: String?,
        val publicKeyLength: Int,
        val sourceVersion: String?,
    )

    private val parser = Iap2Csm.Parser()
    private val pendingFrames = ArrayDeque<Iap2Csm.Frame>()
    private val random = SecureRandom()

    fun run(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): CarPlayStart {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        if (!channel.awaitReady(remainingMillis(deadline))) {
            throw IOException("有线 iAP2 链路协商超时")
        }
        log("有线 iAP2 链路已就绪")

        send(Iap2MessageIds.START_IDENTIFICATION)
        val identification = expect(Iap2MessageIds.IDENTIFICATION_INFORMATION, deadline)
        val identity =
            Iap2Csm
                .parseParams(identification.body)
                .associateBy(Iap2Csm.Param::id)
        log(
            "有线 iAP2 配件 name=${identity[0]?.asString().orEmpty()} " +
                "model=${identity[1]?.asString().orEmpty()} " +
                "manufacturer=${identity[2]?.asString().orEmpty()}",
        )
        send(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
        val certificate = expect(Iap2MessageIds.MFI_CERTIFICATE, deadline)
        log("有线 iAP2 配件证书 ${certificate.body.size}B（本台架跳过证书校验）")

        authenticateAccessory(deadline)
        send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

        sendDeviceStateGreetings()
        send(
            Iap2MessageIds.CARPLAY_AVAILABILITY,
            Iap2Csm.group(
                0,
                Iap2Csm.u8(0, 1),
                Iap2Csm.string(1, usbTransportIdentifier),
            ),
        )
        log("有线 iAP2 已声明 wired CarPlay available")

        val start = parseCarPlayStart(expect(Iap2MessageIds.CARPLAY_START_SESSION, deadline))
        log(
            "有线 iAP2 收到 CarPlayStartSession port=${start.port} " +
                "addresses=${start.addresses.joinToString()} device=${start.deviceIdentifier}",
        )
        return start
    }

    /** 保持控制链路，直到 MacPlay 断开或 USB gadget 回滚。 */
    fun pumpIncoming() {
        var lastMessageId = -1
        var repeats = 0
        while (channel.isAlive) {
            val frame = recvFrame(PUMP_TIMEOUT_MILLIS) ?: continue
            if (frame.messageId == lastMessageId) {
                repeats++
                if (repeats % REPEATED_MESSAGE_LOG_INTERVAL != 0) continue
            } else {
                lastMessageId = frame.messageId
                repeats = 0
            }
            log(
                "有线 iAP2 RX 0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) bytes=${frame.body.size}",
            )
        }
    }

    private fun sendDeviceStateGreetings() {
        send(Iap2MessageIds.DEVICE_INFORMATION_UPDATE, Iap2Csm.string(0, deviceName))
        send(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE, Iap2Csm.string(0, "zh-CN"))
        send(
            Iap2MessageIds.DEVICE_TIME_UPDATE,
            Iap2Csm.param(0, u64Bytes(System.currentTimeMillis() / 1_000L)) +
                Iap2Csm.u16(
                    1,
                    (
                        java.util.TimeZone
                            .getDefault()
                            .getOffset(System.currentTimeMillis()) / 60_000
                    ) and 0xFFFF,
                ) +
                Iap2Csm.u8(2, 0),
        )
        send(Iap2MessageIds.DEVICE_UUID_UPDATE, Iap2Csm.string(0, usbTransportIdentifier))
    }

    private fun authenticateAccessory(deadline: Long) {
        for ((index, size) in MFI_CHALLENGE_BYTES.withIndex()) {
            val challenge = ByteArray(size).also(random::nextBytes)
            send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
            val window =
                minOf(
                    deadline,
                    System.nanoTime() + MFI_ATTEMPT_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND,
                )
            val response = expectOrNull(Iap2MessageIds.MFI_RESPONSE, window)
            if (response != null) {
                log("有线 iAP2 MFi ${size}B 应答 ${response.body.size}B（本台架跳过签名校验）")
                return
            }
            if (!channel.isAlive) throw IOException("有线 iAP2 MFi 挑战期间链路断开")
            if (index < MFI_CHALLENGE_BYTES.lastIndex) {
                log("有线 iAP2 MFi ${size}B 无应答，改用 ${MFI_CHALLENGE_BYTES[index + 1]}B")
            }
        }
        log("有线 iAP2 MFi 各挑战长度均无应答，继续发送认证成功（与无线台架策略一致）")
    }

    private fun send(
        messageId: Int,
        vararg body: ByteArray,
    ) {
        val joined =
            when (body.size) {
                0 -> ByteArray(0)
                1 -> body[0]
                else -> body.reduce { left, right -> left + right }
            }
        val frame = Iap2Csm.encode(messageId, joined)
        val limit = channel.peerMaxControlPayload() ?: (frame.size - 1).coerceAtLeast(1)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + limit, frame.size)
            if (!channel.sendControl(frame.copyOfRange(offset, end))) {
                throw IOException("有线 iAP2 链路在发送 0x${messageId.toString(16)} 时关闭")
            }
            offset = end
        }
        log("有线 iAP2 TX 0x${messageId.toString(16).padStart(4, '0')} (${Iap2MessageIds.nameOf(messageId)})")
    }

    private fun expect(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame =
        expectOrNull(messageId, deadline)
            ?: throw IOException("等待有线 iAP2 0x${messageId.toString(16)} 超时")

    private fun expectOrNull(
        messageId: Int,
        deadline: Long,
    ): Iap2Csm.Frame? {
        while (remainingMillis(deadline) > 0 && channel.isAlive) {
            pendingFrames.firstOrNull { it.messageId == messageId }?.let {
                pendingFrames.remove(it)
                return it
            }
            if (pendingFrames.isNotEmpty()) {
                val skipped = pendingFrames.removeFirst()
                log(
                    "有线 iAP2 跳过非预期 0x${skipped.messageId.toString(16).padStart(4, '0')} " +
                        "(${Iap2MessageIds.nameOf(skipped.messageId)}) bytes=${skipped.body.size}",
                )
                continue
            }
            val frame = recvNewFrame(minOf(POLL_TIMEOUT_MILLIS, remainingMillis(deadline))) ?: continue
            if (frame.messageId == messageId) return frame
            log(
                "有线 iAP2 跳过非预期 0x${frame.messageId.toString(16).padStart(4, '0')} " +
                    "(${Iap2MessageIds.nameOf(frame.messageId)}) bytes=${frame.body.size}",
            )
        }
        return null
    }

    private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        pendingFrames.firstOrNull()?.let {
            pendingFrames.removeFirst()
            return it
        }
        return recvNewFrame(timeoutMillis)
    }

    private fun recvNewFrame(timeoutMillis: Long): Iap2Csm.Frame? {
        if (timeoutMillis <= 0 || !channel.isAlive) return null
        val bytes = channel.recvControl(timeoutMillis) ?: return null
        val frames = parser.offer(bytes)
        if (frames.isEmpty()) return null
        frames.drop(1).forEach(pendingFrames::addLast)
        while (pendingFrames.size > MAX_PENDING_FRAMES) pendingFrames.removeFirst()
        return frames.first()
    }

    private fun parseCarPlayStart(frame: Iap2Csm.Frame): CarPlayStart {
        val top = Iap2Csm.parseParams(frame.body).associateBy(Iap2Csm.Param::id)
        val wired = top[0]?.value?.let(Iap2Csm::parseParams).orEmpty()
        return CarPlayStart(
            addresses =
                wired
                    .filter { it.id == 0 }
                    .mapNotNull { it.asString()?.takeIf(String::isNotEmpty) },
            port = top[2]?.value?.asU32() ?: 0,
            deviceIdentifier = top[3]?.asString(),
            publicKeyLength = top[4]?.value?.size ?: 0,
            sourceVersion = top[5]?.asString(),
        )
    }

    private fun ByteArray.asU32(): Int =
        if (size == 4) {
            ((this[0].toInt() and 0xFF) shl 24) or
                ((this[1].toInt() and 0xFF) shl 16) or
                ((this[2].toInt() and 0xFF) shl 8) or
                (this[3].toInt() and 0xFF)
        } else {
            0
        }

    private fun u64Bytes(value: Long): ByteArray =
        ByteArray(8) {
            (value ushr ((7 - it) * 8)).toByte()
        }

    private fun remainingMillis(deadline: Long): Long = ((deadline - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(0)

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val POLL_TIMEOUT_MILLIS = 1_000L
        const val PUMP_TIMEOUT_MILLIS = 1_000L
        val MFI_CHALLENGE_BYTES = intArrayOf(32, 20, 16)
        const val MFI_ATTEMPT_TIMEOUT_MILLIS = 3_000L
        const val MAX_PENDING_FRAMES = 64
        const val REPEATED_MESSAGE_LOG_INTERVAL = 100
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
