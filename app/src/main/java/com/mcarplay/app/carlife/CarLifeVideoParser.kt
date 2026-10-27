package com.mcarplay.app.carlife

import com.mcarplay.app.video.ExternalVideoFrame
import java.io.ByteArrayOutputStream

/** A complete Annex B access unit delivered by the CarLife video socket. */
data class CarLifeVideoAccessUnit(
    val timestamp: Long,
    val data: ByteArray,
    val keyFrame: Boolean,
    val sps: ByteArray?,
    val pps: ByteArray?,
) {
    val isCodecConfig: Boolean
        get() = sps != null && pps != null

    fun toExternalVideoFrame(): ExternalVideoFrame =
        ExternalVideoFrame(
            data = data,
            keyFrame = keyFrame,
            timestampUs = timestamp * 1_000_000L,
        )

    fun dimensions(): Pair<Int, Int>? = sps?.let(::spsDimensions)
}

/**
 * Converts CarLife's per-packet Annex B data into complete access units.
 *
 * CarLife timestamps are Unix seconds and can be shared by many frames, so they
 * are not access-unit boundaries. H.264 semantics are used instead: a new AUD
 * or parameter set/SEI starts an access unit, and another VCL slice after a
 * complete first slice also starts one.
 */
class CarLifeVideoParser {
    private var pendingTimestamp: Long? = null
    private val pendingNals = ArrayList<ByteArray>()
    private var pendingHasVcl = false
    private var pendingHasIdr = false
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var annexBBuffer = ByteArray(0)
    private var annexBTimestamp: Long? = null
    private var latestSps: ByteArray? = null
    private var latestPps: ByteArray? = null

    fun push(
        timestamp: Long,
        payload: ByteArray,
    ): List<CarLifeVideoAccessUnit> {
        if (payload.isEmpty()) return emptyList()
        val complete = ArrayList<CarLifeVideoAccessUnit>(2)

        // Complete the previous trailing NAL only when the timestamp changes.
        // Within one second, H.264 start codes are the reliable frame boundary.
        if (annexBTimestamp != null && annexBTimestamp != timestamp && annexBBuffer.isNotEmpty()) {
            processAvailableNals(annexBTimestamp!!, final = true, target = complete)
        }
        if (annexBBuffer.isEmpty()) annexBTimestamp = timestamp
        annexBBuffer += payload
        processAvailableNals(timestamp, final = false, target = complete)
        return complete
    }

    fun flush(): List<CarLifeVideoAccessUnit> {
        val complete = ArrayList<CarLifeVideoAccessUnit>(1)
        annexBTimestamp?.let { timestamp ->
            processAvailableNals(timestamp, final = true, target = complete)
        }
        flushAccessUnit(complete)
        return complete
    }

    private fun processAvailableNals(
        timestamp: Long,
        final: Boolean,
        target: MutableList<CarLifeVideoAccessUnit>,
    ) {
        val starts = annexBStartCodes(annexBBuffer)
        var startsAccessUnitBeforeTrailing = false
        if (starts.isEmpty()) {
            if (final && annexBBuffer.isNotEmpty()) annexBBuffer = ByteArray(0)
            return
        }
        for (index in starts.indices) {
            val (start, prefixLength) = starts[index]
            if (index == starts.lastIndex && !final) break
            val bodyStart = start + prefixLength
            val bodyEnd = if (index + 1 < starts.size) starts[index + 1].first else annexBBuffer.size
            if (bodyEnd > bodyStart) {
                consumeNal(annexBBuffer.copyOfRange(bodyStart, bodyEnd), timestamp, target)
            }
        }
        if (!final) {
            val (start, prefixLength) = starts.last()
            if (start + prefixLength < annexBBuffer.size) {
                val trailing = annexBBuffer.copyOfRange(start + prefixLength, annexBBuffer.size)
                val type = trailing[0].toInt() and 0x1F
                val startsAccessUnit =
                    pendingHasVcl &&
                        (
                            type == 9 ||
                                type == 6 ||
                                type == 7 ||
                                type == 8 ||
                                ((type == 1 || type == 5) && firstMbInSlice(trailing) == 0)
                        )
                startsAccessUnitBeforeTrailing = startsAccessUnit
            }
        }
        val lastStart = starts.last().first
        annexBBuffer =
            if (final) {
                ByteArray(0)
            } else {
                annexBBuffer.copyOfRange(lastStart, annexBBuffer.size)
            }
        if (annexBBuffer.isEmpty()) annexBTimestamp = null
        // Flushing in consumeNal would drop a trailing NAL whose start code has
        // already arrived but whose body is still waiting for the next packet.
        if (startsAccessUnitBeforeTrailing) flushAccessUnit(target)
    }

    private fun consumeNal(
        nal: ByteArray,
        timestamp: Long,
        target: MutableList<CarLifeVideoAccessUnit>,
    ) {
        if (nal.isEmpty()) return
        val type = nal[0].toInt() and 0x1F
        val isVcl = type == 1 || type == 5
        val startsAccessUnit =
            type == 9 ||
                (pendingHasVcl && (type == 6 || type == 7 || type == 8)) ||
                (pendingHasVcl && isVcl && firstMbInSlice(nal) == 0)
        if (startsAccessUnit) flushAccessUnit(target)
        if (pendingTimestamp == null) pendingTimestamp = timestamp
        pendingNals += nal
        pendingHasVcl = pendingHasVcl || isVcl
        when (type) {
            5 -> pendingHasIdr = true
            7 -> {
                pendingSps = nal
                latestSps = nal
            }

            8 -> {
                pendingPps = nal
                latestPps = nal
            }
        }
        val total =
            pendingNals.sumOf {
                it.size + ANNEX_B_START_CODE.size
            }
        if (total > MAX_ACCESS_UNIT_BYTES) {
            pendingNals.clear()
            pendingTimestamp = null
            pendingHasVcl = false
            pendingHasIdr = false
            pendingSps = null
            pendingPps = null
        }
    }

    private fun flushAccessUnit(target: MutableList<CarLifeVideoAccessUnit>) {
        val timestamp = pendingTimestamp
        if (timestamp == null || pendingNals.isEmpty()) {
            resetAccessUnit()
            return
        }
        val bytes = ByteArrayOutputStream()
        pendingNals.forEach { nal ->
            bytes.write(ANNEX_B_START_CODE)
            bytes.write(nal)
        }
        target +=
            CarLifeVideoAccessUnit(
                timestamp = timestamp,
                data = bytes.toByteArray(),
                keyFrame = pendingHasIdr,
                // Repeated parameter sets are not required in every frame, but
                // keeping the latest pair lets VideoSender build avcC reliably.
                sps = pendingSps ?: latestSps,
                pps = pendingPps ?: latestPps,
            )
        resetAccessUnit()
    }

    private fun resetAccessUnit() {
        pendingTimestamp = null
        pendingNals.clear()
        pendingHasVcl = false
        pendingHasIdr = false
        pendingSps = null
        pendingPps = null
    }

    private companion object {
        val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)
        const val MAX_ACCESS_UNIT_BYTES = 4 * 1024 * 1024
    }
}

internal fun splitAnnexB(data: ByteArray): List<ByteArray> {
    val starts = annexBStartCodes(data)
    if (starts.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
    return starts.mapIndexed { unit, (start, prefix) ->
        val bodyStart = start + prefix
        val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1].first else data.size
        data.copyOfRange(bodyStart, bodyEnd)
    }
}

internal fun annexBStartCodes(data: ByteArray): List<Pair<Int, Int>> {
    val starts = ArrayList<Pair<Int, Int>>()
    var index = 0
    while (index + 3 <= data.size) {
        when {
            index + 4 <= data.size &&
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() &&
                data[index + 3] == 1.toByte() -> {
                starts += index to 4
                index += 4
            }

            data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte() -> {
                starts += index to 3
                index += 3
            }

            else -> index++
        }
    }
    return starts
}

/**
 * Reads the unsigned Exp-Golomb value immediately after the one-byte NAL
 * header. Emulation-prevention bytes are removed from the small prefix only.
 */
internal fun firstMbInSlice(nal: ByteArray): Int? {
    if (nal.size < 2) return null
    val type = nal[0].toInt() and 0x1F
    if (type != 1 && type != 5) return null
    val rbsp = ByteArrayOutputStream()
    var zeros = 0
    for (index in 1 until minOf(nal.size, 32)) {
        val value = nal[index].toInt() and 0xFF
        if (zeros >= 2 && value == 3) {
            zeros = 0
            continue
        }
        rbsp.write(value)
        zeros = if (value == 0) zeros + 1 else 0
    }
    val bytes = rbsp.toByteArray()
    var bit = 0

    fun readBit(): Int? {
        if (bit >= bytes.size * 8) return null
        val value = (bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1
        bit++
        return value
    }
    var leadingZeros = 0
    while (true) {
        when (readBit()) {
            null -> return null
            1 -> break
            else -> {
                leadingZeros++
                if (leadingZeros >= 32) return null
            }
        }
    }
    var value = 1
    repeat(leadingZeros) {
        val next = readBit() ?: return null
        value = (value shl 1) or next
    }
    return value - 1
}

/** Parse pic_width_in_mbs/pic_height_in_map_units from a baseline/high SPS. */
internal fun spsDimensions(sps: ByteArray): Pair<Int, Int>? {
    if (sps.size < 4) return null
    val rbsp = ArrayList<Byte>(sps.size)
    var zeros = 0
    for (index in 1 until sps.size) {
        val value = sps[index].toInt() and 0xFF
        if (zeros >= 2 && value == 3) {
            zeros = 0
            continue
        }
        rbsp += value.toByte()
        zeros = if (value == 0) zeros + 1 else 0
    }
    return runCatching {
        val bits = BitReader(rbsp.toByteArray())
        bits.skip(24) // profile_idc, constraint flags, level_idc
        bits.readUe() // seq_parameter_set_id
        var chromaFormatIdc = 1
        var separateColourPlane = 0
        val profile = sps[1].toInt() and 0xFF
        if (profile in HIGH_PROFILES) {
            chromaFormatIdc = bits.readUe()
            if (chromaFormatIdc == 3) separateColourPlane = bits.readBit()
            bits.readUe() // bit_depth_luma_minus8
            bits.readUe() // bit_depth_chroma_minus8
            bits.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (bits.readBit() == 1) {
                val scalingLists = if (chromaFormatIdc != 3) 8 else 12
                repeat(scalingLists) {
                    if (bits.readBit() == 1) {
                        val size = if (it < 6) 16 else 64
                        var last = 8
                        var next = 8
                        for (unused in 0 until size) {
                            if (next != 0) next = (last + bits.readSe() + 256) % 256
                            last = if (next == 0) last else next
                        }
                    }
                }
            }
        }
        bits.readUe() // log2_max_frame_num_minus4
        val picOrderCntType = bits.readUe()
        if (picOrderCntType == 0) {
            bits.readUe()
        } else if (picOrderCntType == 1) {
            bits.skip(1)
            bits.readSe()
            bits.readSe()
            repeat(bits.readUe()) { bits.readSe() }
        }
        bits.readUe() // max_num_ref_frames
        bits.skip(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = bits.readUe() + 1
        val heightMapUnits = bits.readUe() + 1
        val frameMbsOnly = bits.readBit()
        if (frameMbsOnly == 0) bits.skip(1)
        bits.skip(1) // direct_8x8_inference_flag
        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0
        if (bits.readBit() == 1) {
            cropLeft = bits.readUe()
            cropRight = bits.readUe()
            cropTop = bits.readUe()
            cropBottom = bits.readUe()
        }
        val chromaArrayType = if (separateColourPlane == 1) 0 else chromaFormatIdc
        val subWidthC = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
        val subHeightC = if (chromaArrayType == 1) 2 else 1
        val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
        val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnly) else subHeightC * (2 - frameMbsOnly)
        val width = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropTop + cropBottom) * cropUnitY
        width to height
    }.getOrNull()
}

private class BitReader(
    private val data: ByteArray,
) {
    private var bit = 0

    fun readBit(): Int {
        require(bit < data.size * 8) { "SPS bitstream ended" }
        val value = (data[bit / 8].toInt() ushr (7 - bit % 8)) and 1
        bit++
        return value
    }

    fun skip(count: Int) {
        require(bit + count <= data.size * 8) { "SPS bitstream ended" }
        bit += count
    }

    fun readUe(): Int {
        var zeros = 0
        while (readBit() == 0) {
            zeros++
            require(zeros < 32) { "Invalid Exp-Golomb code" }
        }
        var value = 1
        repeat(zeros) { value = (value shl 1) or readBit() }
        return value - 1
    }

    fun readSe(): Int {
        val code = readUe()
        return if (code and 1 == 0) -(code / 2) else (code + 1) / 2
    }
}

private val HIGH_PROFILES =
    setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
