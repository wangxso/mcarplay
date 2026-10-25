package com.mcarplay.app.carlife

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Discovery half of the CarWith wireless HU handshake.
 *
 * This object contains no sockets. It defines the wire format and readiness
 * rules so they can be tested without Bluetooth, Wi-Fi Direct, or Android.
 */
object ExternalHuDiscoveryProtocol {
    const val SERVICE_TYPE = "_EasyConn._tcp.local."
    const val SERVICE_INSTANCE_PREFIX = "EasyConn"
    const val PORT = 10_930

    const val REQUEST_COMMAND = 0x70000010
    const val RESPONSE_COMMAND = 0x70000011

    private const val HEADER_SIZE = 16
    private const val MAX_BODY_SIZE = 64 * 1024
    private const val INSTANCE_PREFIX = "easyconn"
    private const val UNAVAILABLE_MAC = "02:00:00:00:00:00"

    data class Identity(
        val deviceName: String,
        val macAddress: String,
        val interfaceName: String,
        val ipv4Address: Inet4Address,
    ) {
        /** Non-blank values are required, and the MAC must be a real unicast address. */
        fun isUsable(): Boolean = unavailableReason() == null

        fun unavailableReason(): String? {
            val normalizedMac = macAddress.replace("-", ":").lowercase()
            if (normalizedMac == UNAVAILABLE_MAC) return "mac-redacted"
            val bytes = macAddressToBytes(normalizedMac) ?: return "invalid-mac"
            val multicast = (bytes.first().toInt() and 1) != 0
            val allZero = bytes.all { it == 0.toByte() }
            return when {
                multicast || allZero -> "invalid-mac"
                deviceName.isBlank() -> "device-name-pending"
                interfaceName.isBlank() -> "local-interface-pending"
                ipv4Address.isAnyLocalAddress || ipv4Address.isLoopbackAddress -> "invalid-ipv4"
                else -> null
            }
        }
    }

    enum class GroupState {
        FORMING,
        READY,
        NOT_OWNER,
    }

    data class GroupEvaluation(
        val state: GroupState,
        val reason: String,
    )

    data class ServiceIdentity(
        val instanceName: String,
        val deviceName: String,
        val macAddress: String,
        /** Null means the service is not ready to publish. */
        val ipv4Address: Inet4Address?,
        val packageName: String = "net.easyconn.carman.neutral",
        val channel: String = "default",
        val flavor: String = "1",
    )

    fun isMatchingInstanceName(instanceName: String): Boolean {
        val prefix = instanceName.substringBefore('.')
        return prefix.startsWith(INSTANCE_PREFIX, ignoreCase = true)
    }

    fun buildInstanceName(
        deviceName: String,
        fallback: String,
    ): String {
        val suffix = deviceName.trim().ifBlank { fallback }.replace('.', '-')
        return "$SERVICE_INSTANCE_PREFIX-$suffix"
    }

    fun serviceIdentity(
        identity: Identity,
        instanceName: String = buildInstanceName(identity.deviceName, "mcarplay"),
    ): ServiceIdentity =
        ServiceIdentity(
            instanceName = instanceName,
            deviceName = identity.deviceName,
            macAddress = identity.macAddress,
            ipv4Address = identity.ipv4Address.takeIf { identity.isUsable() },
        )

    fun txtRecord(identity: ServiceIdentity): Map<String, String> =
        linkedMapOf(
            "packagename" to identity.packageName,
            "ec_name" to identity.deviceName,
            "huid" to identity.macAddress,
            "channel" to identity.channel,
            "flavor" to identity.flavor,
            "huname" to identity.deviceName,
        )

    fun evaluateGroup(
        groupFormed: Boolean,
        isGroupOwner: Boolean,
        hasUsableIdentity: Boolean,
        networkName: String = "",
    ): GroupEvaluation =
        when {
            !groupFormed -> GroupEvaluation(GroupState.FORMING, "group-not-formed")
            !isGroupOwner -> GroupEvaluation(GroupState.NOT_OWNER, "not-group-owner")
            !hasUsableIdentity -> GroupEvaluation(GroupState.FORMING, "identity-pending")
            networkName.isBlank() -> GroupEvaluation(GroupState.FORMING, "network-name-pending")
            else -> GroupEvaluation(GroupState.READY, "ready")
        }

    /**
     * Locate IPv4 by interface name first. If the vendor renamed the P2P
     * interface, fall back to the Wi-Fi Direct address range.
     */
    fun findP2pIPv4(
        interfaceName: String,
        interfaces: List<NetworkInterface> =
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
                .getOrDefault(emptyList()),
    ): Inet4Address? {
        if (interfaceName.isNotBlank()) {
            interfaces
                .firstOrNull { it.name == interfaceName }
                ?.let { networkInterface -> firstIpv4(networkInterface) }
                ?.let { return it }
        }
        return interfaces
            .filter { it.name.startsWith("p2p", ignoreCase = true) }
            .mapNotNull(::firstIpv4)
            .firstOrNull { it.hostAddress?.startsWith("192.168.49.") == true }
            ?: interfaces
                .filter { it.name.startsWith("p2p", ignoreCase = true) }
                .mapNotNull(::firstIpv4)
                .firstOrNull()
    }

    fun findP2pInterfaceName(
        address: InetAddress,
        interfaces: List<NetworkInterface> =
            runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
                .getOrDefault(emptyList()),
    ): String? =
        interfaces
            .firstOrNull { networkInterface ->
                networkInterface.inetAddresses.toList().any { candidate ->
                    candidate.hostAddress?.substringBefore('%') == address.hostAddress?.substringBefore('%')
                }
            }?.name

    fun encodeRequest(): ByteArray = encodeFrame(REQUEST_COMMAND)

    fun encodeResponse(): ByteArray = encodeFrame(RESPONSE_COMMAND, ExternalHuSdpProtocol.joinJsonStatus("true"))

    fun encodeFrame(
        command: Int,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        require(body.size <= MAX_BODY_SIZE) { "discovery body is too large" }
        val totalLength = HEADER_SIZE + body.size
        return ByteBuffer
            .allocate(totalLength)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command)
            .putInt(totalLength)
            .putInt(totalLength xor command)
            .putInt(0)
            .put(body)
            .array()
    }

    fun decodeFrame(bytes: ByteArray): ExternalHuSdpProtocol.Frame? {
        if (bytes.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val totalLength = buffer.int
        val checksum = buffer.int
        buffer.int
        require(totalLength in HEADER_SIZE..HEADER_SIZE + MAX_BODY_SIZE) {
            "invalid discovery frame length $totalLength"
        }
        require(checksum == (totalLength xor command)) { "invalid discovery frame checksum" }
        if (bytes.size < totalLength) return null
        return ExternalHuSdpProtocol.Frame(
            kind = command,
            body = bytes.copyOfRange(HEADER_SIZE, totalLength),
        )
    }

    /** Returns null until a complete request frame is available. */
    fun decodeRequest(bytes: ByteArray): ExternalHuSdpProtocol.Frame? {
        val frame = decodeFrame(bytes) ?: return null
        return frame.takeIf { it.kind == REQUEST_COMMAND }
    }

    fun isValidRequest(frame: ExternalHuSdpProtocol.Frame): Boolean = frame.kind == REQUEST_COMMAND

    fun frameLength(bytes: ByteArray): Int {
        if (bytes.size < HEADER_SIZE) return 0
        return ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    internal fun macAddressToBytes(mac: String): ByteArray? {
        val parts = mac.replace("-", ":").split(':')
        if (parts.size != 6) return null
        return runCatching {
            parts
                .map { part ->
                    require(part.length == 2)
                    part.toInt(16).toByte()
                }.toByteArray()
        }.getOrNull()
    }

    private fun firstIpv4(networkInterface: NetworkInterface): Inet4Address? =
        networkInterface.inetAddresses
            .toList()
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
}
