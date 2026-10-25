package com.mcarplay.app.carlife

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** CarLife 4.x command and video socket framing used by Xiaomi CarWith. */
object CarLifeProtocol {
    const val PORT_COMMAND = 7240
    const val PORT_VIDEO = 8240
    const val PORT_MEDIA = 9240
    const val PORT_TTS = 9241
    const val PORT_VOICE = 9242
    const val PORT_TOUCH = 9340
    const val PORT_DATA = 9440

    val ports: IntArray =
        intArrayOf(PORT_COMMAND, PORT_VIDEO, PORT_MEDIA, PORT_TTS, PORT_VOICE, PORT_TOUCH, PORT_DATA)

    const val HU_PROTOCOL_VERSION = 0x18001
    const val HU_INFO = 0x18003
    const val VIDEO_ENCODER_INIT = 0x18007
    const val VIDEO_ENCODER_START = 0x18009
    const val VIDEO_ENCODER_RESET = 0x1800B
    const val HU_GO_TO_FOREGROUND = 0x18025
    const val HU_STATISTICS = 0x18027
    const val HU_AUTH_REQUEST = 0x18048
    const val HU_AUTH_RESULT = 0x1804A
    const val HU_MD_AUTH_RESULT_ACK = 0x1804C
    const val HU_FEATURE_RESPONSE = 0x18052
    const val HU_RSA_PUBLIC_KEY = 0x1806B
    const val HU_AES_ACK = 0x1806D

    const val MD_PROTOCOL_MATCH = 0x10002
    const val MD_INFO = 0x10004
    const val MD_FOREGROUND = 0x1001B
    const val MD_AUTH_RESPONSE = 0x10049
    const val MD_AUTH_RESULT = 0x1004B
    const val MD_FEATURE_REQUEST = 0x10051
    const val MD_RSA_KEY_REQUEST = 0x1006A
    const val MD_AES_SEED = 0x1006C
    const val MD_ENCRYPT_READY = 0x1006E
    const val VIDEO_ENCODER_INIT_DONE = 0x10008
    const val TOUCH_DOWN = 425986
    const val TOUCH_UP = 425987
    const val TOUCH_MOVE = 425988
    const val VIDEO_FRAME = 0x20001
    const val VIDEO_HEARTBEAT = 0x20002
    const val MEDIA_INIT = 196609
    const val MEDIA_STOP = 196610
    const val MEDIA_PAUSE = 196611
    const val MEDIA_RESUME_PLAY = 196612
    const val MEDIA_DATA = 196614
    const val NAVI_TTS_INIT = 262145
    const val NAVI_TTS_END = 262146
    const val NAVI_TTS_DATA = 262147
    const val VR_AUDIO_INIT = 327682
    const val VR_AUDIO_DATA = 327683
    const val VR_AUDIO_STOP = 327684

    const val LEGACY_CHALLENGE = "CarLife_1.0.0"
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 720
    const val DEFAULT_FPS = 30

    private const val COMMAND_HEADER_SIZE = 8
    private const val VIDEO_HEADER_SIZE = 12
    private const val MAX_COMMAND_BODY = 65_535
    private const val MAX_VIDEO_BODY = 2_000_000
    private const val MAX_FEATURE_COUNT = 128

    data class CommandFrame(
        val kind: Int,
        val payload: ByteArray,
    )

    data class VideoFrame(
        val timestamp: Int,
        val kind: Int,
        val payload: ByteArray,
    )

    data class VideoFormat(
        val width: Int,
        val height: Int,
        val fps: Int,
    )

    data class DeviceInfo(
        val brand: String,
        val model: String,
        val sdk: String,
    )

    /** >HHI: body length, reserved, service type. */
    fun encodeCommand(
        kind: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        require(payload.size <= MAX_COMMAND_BODY) { "CarLife command body is too large" }
        return ByteBuffer
            .allocate(COMMAND_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(payload.size.toShort())
            .putShort(0)
            .putInt(kind)
            .put(payload)
            .array()
    }

    /** >III: body length, timestamp, service type. */
    fun encodeVideo(
        kind: Int,
        timestamp: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        require(payload.size <= MAX_VIDEO_BODY) { "CarLife video body is too large" }
        return ByteBuffer
            .allocate(VIDEO_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(payload.size)
            .putInt(timestamp)
            .putInt(kind)
            .put(payload)
            .array()
    }

    fun decodeCommandHeader(header: ByteArray): Pair<Int, Int> {
        require(header.size == COMMAND_HEADER_SIZE) { "Invalid command header size" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val length = buffer.short.toInt() and 0xFFFF
        buffer.short
        val kind = buffer.int
        require(length <= MAX_COMMAND_BODY) { "Invalid command body length $length" }
        return length to kind
    }

    fun decodeVideoHeader(header: ByteArray): Triple<Int, Int, Int> {
        require(header.size == VIDEO_HEADER_SIZE) { "Invalid video header size" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val length = buffer.int
        val timestamp = buffer.int
        val kind = buffer.int
        require(length in 0..MAX_VIDEO_BODY) { "Invalid video body length $length" }
        return Triple(length, timestamp, kind)
    }

    fun videoHeaderSize(port: Int): Int =
        when (port) {
            PORT_VIDEO, PORT_MEDIA, PORT_TTS, PORT_VOICE -> VIDEO_HEADER_SIZE
            else -> COMMAND_HEADER_SIZE
        }

    fun protocolVersionPayload(): ByteArray = pbInt(1, 4) + pbInt(2, 1)

    fun authRequestPayload(challenge: String = LEGACY_CHALLENGE): ByteArray = pbString(1, challenge)

    fun authResultPayload(success: Boolean): ByteArray = pbInt(1, if (success) 1 else 0)

    /**
     * Reads the fields used by CarLife's handshake binding. Missing, blank,
     * oversized, or malformed values are rejected so an incomplete MD_INFO
     * can never be treated as a valid authentication input.
     */
    fun parseDeviceInfo(payload: ByteArray): DeviceInfo? =
        runCatching {
            val brand = requiredStringField(payload, 4)
            val model = requiredStringField(payload, 14)
            val sdk = requiredStringField(payload, 20)
            DeviceInfo(brand = brand, model = model, sdk = sdk)
        }.getOrNull()

    /**
     * CarLife feature map: count plus repeated {key, value} entries.
     *
     * CarWith requires CONTENT_ENCRYPTION=1 for media. Advertising it commits
     * both sides to the RSA/AES exchange before video initialization.
     */
    fun featureResponsePayload(features: Map<String, Int> = linkedMapOf("CONTENT_ENCRYPTION" to 1)): ByteArray {
        require(features.size <= MAX_FEATURE_COUNT) { "Too many CarLife features" }
        val out = ByteArrayOutputStream()
        out.write(pbInt(1, features.size))
        features.forEach { (key, value) ->
            val entry = pbString(1, key) + pbInt(2, value)
            out.write(pbBytes(2, entry))
        }
        return out.toByteArray()
    }

    /** Parses the feature map so tests and diagnostics inspect actual wire bytes. */
    fun parseFeatures(payload: ByteArray): Map<String, Int> {
        val features = linkedMapOf<String, Int>()
        var offset = 0
        while (offset < payload.size) {
            val (tag, tagSize) = readVarint(payload, offset)
            offset += tagSize
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            when (wireType) {
                0 -> {
                    val (_, valueSize) = readVarint(payload, offset)
                    offset += valueSize
                }

                2 -> {
                    val (length, lengthSize) = readVarint(payload, offset)
                    offset += lengthSize
                    require(length <= (payload.size - offset).toLong()) {
                        "Invalid feature length"
                    }
                    if (field == 2) {
                        val entry = payload.copyOfRange(offset, offset + length.toInt())
                        val key = firstStringField(entry, 1)
                        val value = firstIntField(entry, 2)
                        if (key != null && value != null) features[key] = value
                    }
                    offset += length.toInt()
                }

                else -> return emptyMap()
            }
        }
        return features
    }

    fun videoInitPayload(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        fps: Int = DEFAULT_FPS,
    ): ByteArray = pbInt(1, width) + pbInt(2, height) + pbInt(3, fps)

    fun touchPayload(
        x: Int,
        y: Int,
        pointerX: Int = 0,
        pointerY: Int = 0,
    ): ByteArray {
        val out = pbInt(1, x) + pbInt(2, y)
        return if (pointerX != 0 || pointerY != 0) {
            out + pbInt(3, pointerX) + pbInt(4, pointerY)
        } else {
            out
        }
    }

    fun huInfoPayload(): ByteArray =
        pbString(1, "Android") +
            pbString(4, "mcarplay") +
            pbString(14, "Xiaomi") +
            pbString(20, "CarWith")

    fun statisticsPayload(): ByteArray =
        pbString(1, "mcarplay-local") +
            pbString(2, "0.1.0") +
            pbInt(3, 1) +
            pbString(4, "29999999") +
            pbInt(5, 1) +
            pbInt(6, 0) +
            pbInt(7, 0)

    /** Reads the first length-delimited protobuf field, used for encryptValue. */
    fun firstStringField(
        payload: ByteArray,
        fieldNumber: Int,
    ): String? {
        var offset = 0
        while (offset < payload.size) {
            val (tag, tagSize) = readVarint(payload, offset)
            offset += tagSize
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            when (wireType) {
                0 -> {
                    val (_, valueSize) = readVarint(payload, offset)
                    offset += valueSize
                }

                1 -> offset += 8
                2 -> {
                    val (length, lengthSize) = readVarint(payload, offset)
                    offset += lengthSize
                    require(length <= (payload.size - offset).toLong()) { "Invalid protobuf length" }
                    if (field == fieldNumber) {
                        return payload.copyOfRange(offset, offset + length.toInt()).toString(Charsets.UTF_8)
                    }
                    offset += length.toInt()
                }

                5 -> offset += 4
                else -> error("Unsupported protobuf wire type $wireType")
            }
            require(offset <= payload.size) { "Truncated protobuf field" }
        }
        return null
    }

    private fun requiredStringField(
        payload: ByteArray,
        fieldNumber: Int,
    ): String {
        val value = firstStringField(payload, fieldNumber)?.trim().orEmpty()
        require(value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= MAX_DEVICE_FIELD_BYTES) {
            "Invalid CarLife device info field $fieldNumber"
        }
        return value
    }

    fun firstIntField(
        payload: ByteArray,
        fieldNumber: Int,
    ): Int? {
        var offset = 0
        while (offset < payload.size) {
            val (tag, tagSize) = readVarint(payload, offset)
            offset += tagSize
            val field = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            when (wireType) {
                0 -> {
                    val (value, valueSize) = readVarint(payload, offset)
                    offset += valueSize
                    if (field == fieldNumber) return value.toInt()
                }

                1 -> offset += 8
                2 -> {
                    val (length, lengthSize) = readVarint(payload, offset)
                    offset += lengthSize
                    require(length <= (payload.size - offset).toLong()) { "Invalid protobuf length" }
                    offset += length.toInt()
                }

                5 -> offset += 4
                else -> error("Unsupported protobuf wire type $wireType")
            }
            require(offset <= payload.size) { "Truncated protobuf field" }
        }
        return null
    }

    fun parseVideoFormat(payload: ByteArray): VideoFormat? {
        val width = firstIntField(payload, 1) ?: return null
        val height = firstIntField(payload, 2) ?: return null
        val fps = firstIntField(payload, 3) ?: return null
        if (width !in 1..8192 || height !in 1..8192 || fps !in 1..240) return null
        return VideoFormat(width, height, fps)
    }

    fun pbInt(
        fieldNumber: Int,
        value: Int,
    ): ByteArray = varint((fieldNumber shl 3).toLong()) + varint(value.toLong())

    fun pbString(
        fieldNumber: Int,
        value: String,
    ): ByteArray {
        val encoded = value.toByteArray(Charsets.UTF_8)
        return varint(((fieldNumber shl 3) or 2).toLong()) + varint(encoded.size.toLong()) + encoded
    }

    fun pbBytes(
        fieldNumber: Int,
        value: ByteArray,
    ): ByteArray = varint(((fieldNumber shl 3) or 2).toLong()) + varint(value.size.toLong()) + value

    fun varint(value: Long): ByteArray {
        var remaining = value
        val out = ByteArrayOutputStream()
        while (remaining and 0x7FL.inv() != 0L) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
        return out.toByteArray()
    }

    internal fun readVarint(
        bytes: ByteArray,
        start: Int,
    ): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var offset = start
        while (offset < bytes.size && shift < 64) {
            val byte = bytes[offset].toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            offset++
            if (byte and 0x80 == 0) return value to (offset - start)
            shift += 7
        }
        error("Invalid protobuf varint")
    }

    private const val MAX_DEVICE_FIELD_BYTES = 128
}

/**
 * Encrypts and decrypts control and media bodies after CarLife enables
 * content encryption. Empty bodies remain empty on the wire.
 *
 * CarLife 8.8.4 mandates AES-ECB with PKCS#5 padding on the wire. The mode is
 * therefore a protocol compatibility requirement, not an independent choice.
 * Each session uses a fresh random AES key from the RSA exchange.
 */
class CarLifePayloadCipher(
    key: ByteArray,
) {
    private val key = key.copyOf()

    init {
        require(this.key.size in intArrayOf(16, 24, 32)) { "Invalid CarLife AES key length" }
    }

    fun encrypt(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return ByteArray(0)
        @Suppress("GetInstance")
        return Cipher
            .getInstance("AES/ECB/PKCS5Padding")
            .run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                doFinal(payload)
            }
    }

    fun decrypt(payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return ByteArray(0)
        @Suppress("GetInstance")
        return Cipher
            .getInstance("AES/ECB/PKCS5Padding")
            .run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                doFinal(payload)
            }
    }
}

/**
 * Verifies the signature returned by CarLife for the legacy challenge.
 *
 * CarLife uses its bundled private key to sign the challenge with
 * PKCS#1 v1.5 padding. That is raw RSA over a private exponent, not a JCA
 * "RSA/ECB/PKCS1Padding" decrypt, so verify with modPow and strict structure.
 * The matching private key must never be included in this application.
 */
class CarLifeAuthVerifier(
    private val publicKeyBase64: String,
) {
    fun verify(
        challenge: String,
        payload: ByteArray,
        deviceInfo: CarLifeProtocol.DeviceInfo? = null,
    ): Boolean {
        val encrypted = CarLifeProtocol.firstStringField(payload, 1) ?: return false
        if (CarLifeAuthentication.isVersion2(challenge)) {
            return CarLifeAuthentication.verifyVersion2(challenge, deviceInfo, encrypted)
        }
        return runCatching {
            val signatureBytes = Base64.getDecoder().decode(encrypted)
            val keyBytes = Base64.getDecoder().decode(publicKeyBase64)
            val key =
                KeyFactory
                    .getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(keyBytes)) as RSAPublicKey
            verifyRawSignature(key, signatureBytes, challenge.toByteArray(Charsets.UTF_8))
        }.getOrDefault(false)
    }

    private fun verifyRawSignature(
        key: RSAPublicKey,
        signatureBytes: ByteArray,
        expected: ByteArray,
    ): Boolean {
        val keySize = (key.modulus.bitLength() + 7) / 8
        if (expected.isEmpty() || expected.size > keySize - 11) return false

        val encoded =
            BigInteger(1, signatureBytes)
                .modPow(key.publicExponent, key.modulus)
                .toByteArray()
                .let { value ->
                    when {
                        value.size > keySize -> value.copyOfRange(value.size - keySize, value.size)
                        value.size < keySize -> ByteArray(keySize - value.size) + value
                        else -> value
                    }
                }
        val delimiter = keySize - expected.size - 1
        if (encoded.size != keySize ||
            encoded[0] != 0.toByte() ||
            encoded[1] != 1.toByte() ||
            encoded[delimiter] != 0.toByte() ||
            (2 until delimiter).any { encoded[it] != 0xFF.toByte() }
        ) {
            encoded.fill(0)
            return false
        }
        val matches = java.security.MessageDigest.isEqual(expected, encoded.copyOfRange(delimiter + 1, encoded.size))
        encoded.fill(0)
        return matches
    }

    companion object {
        /**
         * Public half of the key bundled with the Xiaomi CarLife component.
         * This is a verification key only; the matching private key is not
         * present in this repository or application.
         */
        const val XIAOMI_CARLIFE_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC86AUdyKOcMUB/iDMtHpFBOdLPWThDziP8t20sF0mRKlNBIAxlUZxQWIa/DEhJL9kx05+RyrFDN4B3d66UIURtia3GhWPoiBCbNornl73ynfFKk1BUDma3O8Qs9Z6YOCkXrGOFQBDfltfXs/w7CogkuS2sZsBPOOMVom04I7KWPQIDAQAB"

        fun xiaomi(): CarLifeAuthVerifier = CarLifeAuthVerifier(XIAOMI_CARLIFE_PUBLIC_KEY)
    }
}

internal fun ByteArray.isAnnexB(): Boolean {
    if (size < 4) return false
    return (this[0].toInt() == 0 && this[1].toInt() == 0 && this[2].toInt() == 1) ||
        (
            this[0].toInt() == 0 &&
                this[1].toInt() == 0 &&
                this[2].toInt() == 0 &&
                this[3].toInt() == 1
        )
}

internal fun ByteArray.nalType(): Int? {
    val start =
        when {
            size >= 4 && this[0].toInt() == 0 && this[1].toInt() == 0 && this[2].toInt() == 0 && this[3].toInt() == 1 -> 4
            size >= 3 && this[0].toInt() == 0 && this[1].toInt() == 0 && this[2].toInt() == 1 -> 3
            else -> return null
        }
    return if (start < size) this[start].toInt() and 0x1F else null
}
