package com.mcarplay.app.usb

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * 运行在 USBMUX TCP 端口 62078 上的最小 Lockdown 服务。
 *
 * 支持 DiPlay/MacPlay 当前有线路径需要的 SetValue、GetValue、Pair、StartSession 与
 * StartService。配对和 TLS 材料只保留在当前 root 进程内；每次台架重启后都会重新配对。
 */
class LockdownUsbServer(
    private val log: (String) -> Unit = {},
    private val serialNumber: String = "mcarplay-usb",
) {
    private val identity = RsaLockdownIdentity.generate()
    private val uniqueChipId = serialNumber.fold(0L) { hash, char -> (hash * 131 + char.code) and UNIQUE_CHIP_ID_MASK }
    private val sessionIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var paired = false

    @Volatile
    private var tlsCertificateChain = arrayOf(identity.certificate)

    fun serveLockdown(connection: UsbMuxTcpConnection) {
        val input = UsbMuxConnectionInputStream(connection)
        val output = UsbMuxConnectionOutputStream(connection)
        while (true) {
            val request =
                try {
                    LockdownPlist.readFrame(input)
                } catch (error: EOFException) {
                    log("Lockdown 连接结束")
                    return
                } catch (error: SocketTimeoutException) {
                    log("Lockdown 空闲超时")
                    return
                } catch (error: Throwable) {
                    log("Lockdown 读取失败：${error.javaClass.simpleName}: ${error.message}")
                    return
                }
            val requestName = request.text("Request") ?: "unknown"
            log("Lockdown RX $requestName")
            val response =
                when (requestName) {
                    "QueryType" -> queryTypeResponse()
                    "SetValue" -> setValue(request)
                    "GetValue" -> getValue(request)
                    "Pair" -> pair(request)
                    "StartSession" -> startSession(request)
                    "StartService" -> startService(request, secure = false)
                    else -> errorResponse("InvalidRequest", requestName)
                }
            LockdownPlist.writeFrame(output, response)
            if (requestName == "StartSession" && paired) {
                serveSecureLockdown(connection)
                return
            }
        }
    }

    fun serviceNameForPort(port: Int): String? =
        when (port) {
            SYSLOG_PORT -> "com.apple.syslog_relay"
            CARKIT_PORT -> "com.apple.carkit.service"
            NOTIFICATION_PROXY_PORT -> "com.apple.mobile.insecure_notification_proxy"
            else -> null
        }

    fun serveService(
        connection: UsbMuxTcpConnection,
        serviceName: String,
    ) {
        log("Lockdown service opened $serviceName port=${connection.destinationPort}")
        var first = true
        while (true) {
            val bytes =
                try {
                    connection.receive(16 * 1024, 1_000)
                } catch (error: Throwable) {
                    log("Lockdown service $serviceName 结束：${error.javaClass.simpleName}: ${error.message}")
                    return
                } ?: continue
            if (bytes.isEmpty()) {
                log("Lockdown service $serviceName EOF")
                return
            }
            if (first) {
                first = false
                log("Lockdown service $serviceName 首包 ${bytes.size}B ${hexPreview(bytes)}")
            } else {
                log("Lockdown service $serviceName RX ${bytes.size}B")
            }
        }
    }

    private fun setValue(request: LockdownPlist.Value.Dictionary): LockdownPlist.Value.Dictionary {
        val key = request.text("Key")
        log("Lockdown SetValue key=${key ?: "missing"}")
        return dictionary(
            "Request" to LockdownPlist.Value.Text("SetValue"),
            "Key" to LockdownPlist.Value.Text(key.orEmpty()),
        )
    }

    private fun getValue(request: LockdownPlist.Value.Dictionary): LockdownPlist.Value.Dictionary {
        val key = request.text("Key") ?: return errorResponse("MissingKey", "GetValue")
        log("Lockdown GetValue key=$key")
        val value = identityValue(key) ?: return errorResponse("MissingValue", key)
        return dictionary(
            "Request" to LockdownPlist.Value.Text("GetValue"),
            "Key" to LockdownPlist.Value.Text(key),
            "Value" to value,
        )
    }

    internal fun identityValue(key: String): LockdownPlist.Value? =
        when (key) {
            "DevicePublicKey" -> LockdownPlist.Value.Data(identity.publicKeyPkcs1Pem)
            "WiFiAddress" -> LockdownPlist.Value.Text(FAKE_WIFI_ADDRESS)
            "BluetoothAddress" -> LockdownPlist.Value.Text(FAKE_BLUETOOTH_ADDRESS)
            "DeviceClass" -> LockdownPlist.Value.Text("iPhone")
            "ProductType" -> LockdownPlist.Value.Text("iPhone17,1")
            "ProductName" -> LockdownPlist.Value.Text("iPhone OS")
            "MarketingName" -> LockdownPlist.Value.Text("iPhone 16 Pro")
            "DeviceName" -> LockdownPlist.Value.Text("iPhone")
            "BuildVersion" -> LockdownPlist.Value.Text("23A000")
            "ProductVersion" -> LockdownPlist.Value.Text("26.0")
            "HardwareModel" -> LockdownPlist.Value.Text("D93AP")
            "HardwarePlatform" -> LockdownPlist.Value.Text("t8140")
            "BoardId" -> LockdownPlist.Value.Integer(0x0C)
            "ChipID" -> LockdownPlist.Value.Integer(0x8140)
            "UniqueChipID" -> LockdownPlist.Value.Integer(uniqueChipId)
            "UniqueDeviceID", "SerialNumber" -> LockdownPlist.Value.Text(serialNumber)
            "SecurityDomain" -> LockdownPlist.Value.Integer(1)
            "MobileDeviceMinimumVersion" -> LockdownPlist.Value.Text("1600")
            "DeviceColor" -> LockdownPlist.Value.Text("natural")
            "DeviceEnclosureColor" -> LockdownPlist.Value.Text("natural")
            "CPUArchitecture" -> LockdownPlist.Value.Text("arm64e")
            "PasswordProtected" -> LockdownPlist.Value.Boolean(false)
            else -> null
        }

    private fun pair(request: LockdownPlist.Value.Dictionary): LockdownPlist.Value.Dictionary {
        val record = request.entries["PairRecord"] as? LockdownPlist.Value.Dictionary
        val required =
            listOf(
                "DeviceCertificate",
                "HostCertificate",
                "HostID",
                "RootCertificate",
                "SystemBUID",
            )
        if (record == null || required.any { it !in record.entries }) {
            log("Lockdown Pair 缺少 PairRecord 字段")
            return errorResponse("PairingError", "Pair")
        }
        val deviceCertificate =
            parseCertificate(record.entries["DeviceCertificate"])
                ?: return errorResponse("PairingError", "DeviceCertificate")
        if (!deviceCertificate.publicKey.encoded.contentEquals(identity.keyPair.public.encoded)) {
            log("Lockdown Pair 的 DeviceCertificate 与 DevicePublicKey 不匹配")
            return errorResponse("PairingError", "DeviceCertificate")
        }
        val rootCertificate = parseCertificate(record.entries["RootCertificate"])
        tlsCertificateChain = listOfNotNull(deviceCertificate, rootCertificate).toTypedArray()
        paired = true
        log("Lockdown Pair 成功")
        return dictionary(
            "Request" to LockdownPlist.Value.Text("Pair"),
            "EscrowBag" to LockdownPlist.Value.Data(ByteArray(32).also(SecureRandom()::nextBytes)),
        )
    }

    private fun startSession(request: LockdownPlist.Value.Dictionary): LockdownPlist.Value.Dictionary {
        if (!paired) {
            log("Lockdown StartSession 拒绝旧配对记录")
            return errorResponse("InvalidPairRecord", "StartSession")
        }
        log("Lockdown StartSession 启用 TLS")
        val sessionId = UUID.randomUUID().toString().uppercase()
        sessionIds += sessionId
        return startSessionResponse(sessionId)
    }

    private fun serveSecureLockdown(connection: UsbMuxTcpConnection) {
        val tls =
            try {
                openTls(connection)
            } catch (error: Throwable) {
                log("Lockdown TLS 失败：${error.javaClass.simpleName}: ${error.message}")
                return
            }
        tls.use { socket ->
            log("Lockdown TLS 握手完成")
            while (true) {
                val request =
                    try {
                        LockdownPlist.readFrame(socket.inputStream)
                    } catch (error: Throwable) {
                        log("Secure Lockdown 结束：${error.javaClass.simpleName}: ${error.message}")
                        return
                    }
                val requestName = request.text("Request") ?: "unknown"
                log("Secure Lockdown RX $requestName")
                val response =
                    when (requestName) {
                        "QueryType" -> queryTypeResponse()
                        "SetValue" -> setValue(request)
                        "GetValue" -> getValue(request)
                        "StartSession" -> secureStartSession()
                        "StopSession" -> stopSession(request)
                        "StartService" -> startService(request, secure = true)
                        "Goodbye" -> goodbyeResponse()
                        else -> errorResponse("InvalidRequest", requestName)
                    }
                LockdownPlist.writeFrame(socket.outputStream, response)
                if (requestName == "Goodbye") return
            }
        }
    }

    private fun secureStartSession(): LockdownPlist.Value.Dictionary {
        val sessionId = UUID.randomUUID().toString().uppercase()
        sessionIds += sessionId
        log("Secure Lockdown StartSession 保持当前 TLS")
        return startSessionResponse(sessionId, enableSessionSsl = false)
    }

    private fun stopSession(request: LockdownPlist.Value.Dictionary): LockdownPlist.Value.Dictionary {
        val sessionId = request.text("SessionID")
        val known = sessionId != null && sessionIds.remove(sessionId)
        log("Secure Lockdown StopSession session=${if (known) "known" else "accepted"}")
        return stopSessionResponse()
    }

    private fun startService(
        request: LockdownPlist.Value.Dictionary,
        secure: Boolean,
    ): LockdownPlist.Value.Dictionary {
        val service = request.text("Service") ?: return errorResponse("MissingService", "StartService")
        val port =
            when (service) {
                "com.apple.syslog_relay" -> SYSLOG_PORT
                "com.apple.carkit.service" -> CARKIT_PORT
                "com.apple.mobile.insecure_notification_proxy" -> NOTIFICATION_PROXY_PORT
                else -> return errorResponse("InvalidService", service)
            }
        log("${if (secure) "Secure " else ""}Lockdown StartService $service -> $port")
        return dictionary(
            "Request" to LockdownPlist.Value.Text("StartService"),
            "Service" to LockdownPlist.Value.Text(service),
            "Port" to LockdownPlist.Value.Integer(port.toLong()),
            "EnableServiceSSL" to LockdownPlist.Value.Boolean(false),
        )
    }

    private fun openTls(connection: UsbMuxTcpConnection): SSLSocket {
        val password = TLS_PASSWORD
        val keyStore =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setKeyEntry(
                    TLS_KEY_ALIAS,
                    identity.keyPair.private,
                    password,
                    tlsCertificateChain,
                )
            }
        val keyManagers =
            KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, password) }
                .keyManagers
        val context =
            SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf(TrustAllManager), SecureRandom())
            }
        val socket = UsbMuxSocket(connection)
        val tls = context.socketFactory.createSocket(socket, "Device", 0, true) as SSLSocket
        tls.useClientMode = false
        tls.sslParameters =
            tls.sslParameters.apply {
                endpointIdentificationAlgorithm = null
                wantClientAuth = true
            }
        tls.startHandshake()
        return tls
    }

    private fun errorResponse(
        code: String,
        request: String,
    ): LockdownPlist.Value.Dictionary =
        dictionary(
            "Request" to LockdownPlist.Value.Text(request),
            "Error" to LockdownPlist.Value.Text(code),
        )

    private fun dictionary(vararg values: Pair<String, LockdownPlist.Value>): LockdownPlist.Value.Dictionary =
        LockdownPlist.Value.Dictionary(linkedMapOf(*values))

    private fun LockdownPlist.Value.Dictionary.text(key: String): String? = (entries[key] as? LockdownPlist.Value.Text)?.value

    private fun hexPreview(bytes: ByteArray): String =
        bytes
            .take(48)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            .let { if (bytes.size > 48) "$it…" else it }

    internal fun parseCertificate(value: LockdownPlist.Value?): X509Certificate? {
        val bytes = (value as? LockdownPlist.Value.Data)?.bytes ?: return null
        val pem =
            bytes
                .toString(StandardCharsets.US_ASCII)
                .takeIf { it.contains("-----BEGIN CERTIFICATE-----") }
        val der =
            if (pem == null) {
                bytes
            } else {
                val encoded =
                    pem
                        .substringAfter("-----BEGIN CERTIFICATE-----")
                        .substringBefore("-----END CERTIFICATE-----")
                        .filterNot(Char::isWhitespace)
                Base64.getDecoder().decode(encoded)
            }
        return CertificateFactory
            .getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private class UsbMuxConnectionInputStream(
        private val connection: UsbMuxTcpConnection,
    ) : InputStream() {
        @Volatile
        var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS

        override fun read(): Int {
            val byte = ByteArray(1)
            val count = read(byte, 0, 1)
            return if (count < 0) -1 else byte[0].toInt() and 0xFF
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            val bytes =
                connection.receive(length, timeoutMillis.coerceAtLeast(1))
                    ?: throw SocketTimeoutException("USBMUX TCP read timed out")
            if (bytes.isEmpty()) return -1
            bytes.copyInto(buffer, offset)
            return bytes.size
        }
    }

    private class UsbMuxConnectionOutputStream(
        private val connection: UsbMuxTcpConnection,
    ) : OutputStream() {
        override fun write(value: Int) {
            write(byteArrayOf(value.toByte()), 0, 1)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (length == 0) return
            connection.send(buffer.copyOfRange(offset, offset + length))
        }
    }

    private class UsbMuxSocket(
        private val connection: UsbMuxTcpConnection,
    ) : Socket() {
        private val input = UsbMuxConnectionInputStream(connection)
        private val output = UsbMuxConnectionOutputStream(connection)

        @Volatile
        private var closed = false

        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): OutputStream = output

        override fun isConnected(): Boolean = !closed

        override fun isBound(): Boolean = true

        override fun isClosed(): Boolean = closed

        override fun close() {
            if (closed) return
            closed = true
            connection.close()
        }

        override fun shutdownInput() = Unit

        override fun shutdownOutput() = Unit

        override fun setSoTimeout(timeout: Int) {
            input.timeoutMillis = if (timeout <= 0) DEFAULT_TIMEOUT_MILLIS else timeout.toLong()
        }

        override fun getSoTimeout(): Int = input.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        override fun getInetAddress(): InetAddress = InetAddress.getByName("127.0.0.1")

        override fun getPort(): Int = connection.sourcePort

        override fun getLocalPort(): Int = connection.destinationPort

        override fun getRemoteSocketAddress(): SocketAddress? = null

        override fun getLocalSocketAddress(): SocketAddress? = null
    }

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val SYSLOG_PORT = 62_079
        const val CARKIT_PORT = 62_080
        const val NOTIFICATION_PROXY_PORT = 62_081

        internal fun queryTypeResponse(): LockdownPlist.Value.Dictionary =
            LockdownPlist.Value.Dictionary(
                linkedMapOf(
                    "Request" to LockdownPlist.Value.Text("QueryType"),
                    "Type" to LockdownPlist.Value.Text("com.apple.mobile.lockdown"),
                ),
            )

        internal fun startSessionResponse(
            sessionId: String,
            enableSessionSsl: Boolean = true,
        ): LockdownPlist.Value.Dictionary =
            LockdownPlist.Value.Dictionary(
                linkedMapOf(
                    "Request" to LockdownPlist.Value.Text("StartSession"),
                    "SessionID" to LockdownPlist.Value.Text(sessionId),
                    "EnableSessionSSL" to LockdownPlist.Value.Boolean(enableSessionSsl),
                ),
            )

        internal fun stopSessionResponse(): LockdownPlist.Value.Dictionary =
            LockdownPlist.Value.Dictionary(
                linkedMapOf(
                    "Request" to LockdownPlist.Value.Text("StopSession"),
                ),
            )

        internal fun goodbyeResponse(): LockdownPlist.Value.Dictionary =
            LockdownPlist.Value.Dictionary(
                linkedMapOf(
                    "Request" to LockdownPlist.Value.Text("Goodbye"),
                ),
            )

        private const val FAKE_WIFI_ADDRESS = "02:00:00:00:00:01"
        private const val FAKE_BLUETOOTH_ADDRESS = "02:00:00:00:00:02"
        private const val UNIQUE_CHIP_ID_MASK = 0x00FF_FFFF_FFFF_FFFFL
        private const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        private const val TLS_KEY_ALIAS = "lockdown-device"
        private val TLS_PASSWORD = "mcarplay-lockdown".toCharArray()
    }
}
