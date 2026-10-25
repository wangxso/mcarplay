package com.mcarplay.app.carlife

import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Bluetooth SDP link used by CarWith before it opens its Wi-Fi Direct group.
 *
 * The service UUID and command values come from the shipped CarWith client
 * (`SdpBluetoothUtil`). The emulated endpoint accepts discovery requests and
 * advertises its Wi-Fi Direct identity. Runtime peer roles still need device validation.
 */
object ExternalHuSdpProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("9f03b326-5d75-46f1-9a39-b71f144d1d97")

    const val CONNECT_TYPE = 0x00080000
    const val CLIENT_INFO = 0x00080010
    const val REQUEST_BUILD_NET = 0x00080020
    const val BUILD_NET_FINISH = 0x00080030
    const val NOTIFY_AP_INFO = 0x00080040

    const val STATUS_AUTH_PENDING = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_NEED_PHONE_AP = 1
    const val STATUS_USE_PHONE_AP = 2

    const val WIFI_MODE_P2P = 8

    private const val HEADER_SIZE = 16
    const val MAX_BODY_SIZE = 64 * 1024

    data class Frame(
        val kind: Int,
        val body: ByteArray,
    )

    data class NetworkAddress(
        val name: String,
        val address: String,
        val mask: String,
    )

    data class CarNetwork(
        val name: String,
        val mac: String,
        val ssid: String,
        val password: String,
        val mode: Int,
        val frequency: Int = 0,
    )

    data class HuAdvertisement(
        val network: CarNetwork,
        val packageName: String = "net.easyconn.carman.neutral",
        val channel: String = "default",
        val flavor: Int = 1,
        val huName: String = network.name,
        val huId: String = network.mac,
    )

    enum class Decision {
        WAIT,
        P2P_STARTED,
        SUCCESS,
        INVALID,
    }

    data class BuildNetResult(
        val decision: Decision,
        val network: CarNetwork? = null,
        val error: String? = null,
    )

    fun encodeFrame(
        kind: Int,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        require(body.size <= MAX_BODY_SIZE) { "SDP body is too large" }
        val length = HEADER_SIZE + body.size
        return ByteBuffer
            .allocate(length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(kind)
            .putInt(length)
            .putInt(length xor kind)
            .putInt(0)
            .put(body)
            .array()
    }

    /** Returns null until all 16 header bytes are available. */
    fun decodeFrame(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE) return null
        val header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val kind = header.int
        val length = header.int
        val checksum = header.int
        if (length < HEADER_SIZE || length > HEADER_SIZE + MAX_BODY_SIZE) {
            throw IllegalArgumentException("Invalid SDP frame length $length")
        }
        if (checksum != (length xor kind)) {
            throw IllegalArgumentException("Invalid SDP frame checksum")
        }
        if (bytes.size < length) return null
        return Frame(kind = kind, body = bytes.copyOfRange(HEADER_SIZE, length))
    }

    fun parseJson(
        body: ByteArray,
        keys: Set<String>,
    ): Map<String, Any?>? =
        runCatching {
            val json = org.json.JSONObject(String(body, StandardCharsets.UTF_8))
            keys.associateWith { key ->
                when {
                    !json.has(key) -> null
                    json.isNull(key) -> null
                    else -> json.get(key)
                }
            }
        }.getOrNull()

    fun statusOf(body: ByteArray): Int? =
        runCatching {
            org.json.JSONObject(String(body, StandardCharsets.UTF_8)).optInt("status", Int.MIN_VALUE)
        }.getOrNull()

    fun parseCarNetwork(body: ByteArray): CarNetwork? =
        runCatching {
            val root = org.json.JSONObject(String(body, StandardCharsets.UTF_8))
            val info = root.optJSONObject("carNetDeviceInfo") ?: return null
            CarNetwork(
                name = info.optString("name"),
                mac = info.optString("mac"),
                ssid = info.optString("ssid"),
                password = info.optString("pwd"),
                mode = info.optInt("mode", Int.MIN_VALUE),
                frequency = root.optInt("phoneApFrequency", 0),
            ).takeIf { it.name.isNotBlank() && it.mode != Int.MIN_VALUE }
        }.getOrNull()

    fun classifyBuildNet(body: ByteArray): BuildNetResult {
        val status = statusOf(body) ?: return BuildNetResult(Decision.INVALID, error = "malformed JSON")
        return when (status) {
            STATUS_AUTH_PENDING -> BuildNetResult(Decision.WAIT)
            STATUS_NEED_PHONE_AP -> {
                val network = parseCarNetwork(body)
                if (network == null) {
                    BuildNetResult(Decision.INVALID, error = "missing car network")
                } else if (network.mode == WIFI_MODE_P2P) {
                    BuildNetResult(Decision.P2P_STARTED, network = network)
                } else {
                    BuildNetResult(Decision.WAIT, network = network)
                }
            }
            STATUS_USE_PHONE_AP -> BuildNetResult(Decision.WAIT)
            STATUS_SUCCESS -> BuildNetResult(Decision.SUCCESS, network = parseCarNetwork(body))
            else -> BuildNetResult(Decision.INVALID, error = "unknown status $status")
        }
    }

    fun clientInfoBody(
        phoneId: String,
        phoneName: String,
        packageName: String,
        addresses: List<NetworkAddress>,
    ): ByteArray {
        val nets = org.json.JSONArray()
        addresses.forEach { item ->
            nets.put(
                org.json
                    .JSONObject()
                    .put("name", item.name)
                    .put("addr", item.address)
                    .put("mask", item.mask),
            )
        }
        return org
            .json
            .JSONObject()
            .put("phoneType", 0)
            .put("phoneID", phoneId)
            .put("phoneName", phoneName)
            .put("packageName", packageName)
            .put("netInterface", nets)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun carInfoBody(advertisement: HuAdvertisement): ByteArray =
        org
            .json
            .JSONObject()
            .put("status", STATUS_NEED_PHONE_AP)
            .put("packagename", advertisement.packageName)
            .put("channel", advertisement.channel)
            .put("flavor", advertisement.flavor)
            .put("HUName", advertisement.huName)
            .put("HUID", advertisement.huId)
            .put(
                "carNetDeviceInfo",
                org
                    .json
                    .JSONObject()
                    .put("name", advertisement.network.name)
                    .put("mac", advertisement.network.mac)
                    .put("ssid", advertisement.network.ssid)
                    .put("mode", advertisement.network.mode),
            ).toString()
            .toByteArray(StandardCharsets.UTF_8)

    fun localAddresses(): List<NetworkAddress> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filterNot { excludedInterface(it.name) }
                .flatMap { networkInterface ->
                    networkInterface.interfaceAddresses.mapNotNull { address ->
                        val inet = address.address as? Inet4Address ?: return@mapNotNull null
                        if (inet.isLoopbackAddress || inet.isAnyLocalAddress) return@mapNotNull null
                        NetworkAddress(
                            name = networkInterface.name,
                            address = inet.hostAddress ?: return@mapNotNull null,
                            mask = prefixToMask(address.networkPrefixLength.toInt()),
                        )
                    }
                }.distinct()
        }.getOrDefault(emptyList())

    private fun excludedInterface(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(
            "oem",
            "nm_",
            "tun",
            "qcom_",
            "dummy",
            "lo",
            "ifb",
            "sit",
            "usb",
            "tunl",
            "bond",
            "ccmni",
            "rmnet",
        ).any(lower::startsWith)
    }

    internal fun prefixToMask(prefix: Int): String {
        val bits = prefix.coerceIn(0, 32)
        val mask =
            when (bits) {
                0 -> 0
                32 -> -1
                else -> -1 shl (32 - bits)
            }
        return ByteBuffer
            .allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(mask)
            .array()
            .joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    fun joinJsonStatus(
        status: Int,
        extra: Map<String, Any?> = emptyMap(),
    ): ByteArray {
        val json = org.json.JSONObject().put("status", status)
        extra.forEach { (key, value) ->
            if (value != null) json.put(key, value)
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun joinJsonStatus(status: String): ByteArray =
        org
            .json
            .JSONObject()
            .put("status", status)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
}
