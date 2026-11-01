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
            sps = sps,
            pps = pps,
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
internal fun firstMbInSlice(nal: ByteArray): Int? = sliceHeaderUe(nal, index = 0)

/** slice_type（0–9；%5：0=P 1=B 2=I 3=SP 4=SI），只解析切片头前缀。 */
internal fun sliceType(nal: ByteArray): Int? = sliceHeaderUe(nal, index = 1)

private fun sliceHeaderUe(
    nal: ByteArray,
    index: Int,
): Int? {
    if (nal.size < 2) return null
    val type = nal[0].toInt() and 0x1F
    if (type != 1 && type != 5) return null
    val bits = BitReader(unescapeRbsp(nal, limit = 32))
    return runCatching {
        repeat(index) { bits.readUe() }
        bits.readUe()
    }.getOrNull()
}

/** 去掉 NAL 头与 emulation prevention 字节（[limit] 为最多读取的原始字节数）。 */
private fun unescapeRbsp(
    nal: ByteArray,
    limit: Int = nal.size,
): ByteArray {
    val rbsp = ByteArrayOutputStream(nal.size)
    var zeros = 0
    for (index in 1 until minOf(nal.size, limit)) {
        val value = nal[index].toInt() and 0xFF
        if (zeros >= 2 && value == 3) {
            zeros = 0
            continue
        }
        rbsp.write(value)
        zeros = if (value == 0) zeros + 1 else 0
    }
    return rbsp.toByteArray()
}

/**
 * SPS 中与车机解码兼容性相关的字段（诊断用）。VUI 解析失败时 [vuiParsed] 为 false，
 * VUI 字段保持 null，尺寸等前段字段仍有效。
 */
internal data class SpsInfo(
    val profileIdc: Int,
    val constraintFlags: Int,
    val levelIdc: Int,
    val chromaFormatIdc: Int,
    val picOrderCntType: Int,
    val maxNumRefFrames: Int,
    val frameMbsOnly: Boolean,
    val width: Int,
    val height: Int,
    val vuiPresent: Boolean,
    val vuiParsed: Boolean = false,
    val fullRange: Boolean? = null,
    val timingFps: Double? = null,
    val fixedFrameRate: Boolean? = null,
    val hrdPresent: Boolean? = null,
    val bitstreamRestriction: Boolean? = null,
    val maxNumReorderFrames: Int? = null,
    val maxDecFrameBuffering: Int? = null,
) {
    fun describe(): String =
        buildString {
            append("profile=$profileIdc constraint=0x%02x level=$levelIdc".format(constraintFlags))
            append(" chroma=$chromaFormatIdc poc=$picOrderCntType refs=$maxNumRefFrames")
            append(" frameMbsOnly=${if (frameMbsOnly) 1 else 0} ${width}x$height vui=${if (vuiPresent) 1 else 0}")
            if (vuiPresent && !vuiParsed) append("(解析失败)")
            fullRange?.let { append(" fullRange=${if (it) 1 else 0}") }
            timingFps?.let { append(" timingFps=%.2f".format(it)) }
            fixedFrameRate?.let { append(" fixedRate=${if (it) 1 else 0}") }
            hrdPresent?.let { append(" hrd=${if (it) 1 else 0}") }
            if (bitstreamRestriction == true) append(" reorder=$maxNumReorderFrames dpb=$maxDecFrameBuffering")
            if (bitstreamRestriction == false) append(" reorder=未声明")
        }
}

/** PPS 中与解码兼容性相关的字段（诊断用）；[transform8x8] 为 null 表示 PPS 无扩展段。 */
internal data class PpsInfo(
    val cabac: Boolean,
    val sliceGroups: Int,
    val numRefIdxL0: Int,
    val numRefIdxL1: Int,
    val weightedPred: Boolean,
    val weightedBipredIdc: Int,
    val transform8x8: Boolean?,
) {
    fun describe(): String =
        "cabac=${if (cabac) 1 else 0} sliceGroups=$sliceGroups refIdx=$numRefIdxL0/$numRefIdxL1 " +
            "weighted=${if (weightedPred) 1 else 0}/$weightedBipredIdc " +
            "8x8=${transform8x8?.let { if (it) 1 else 0 } ?: "无"}"
}

/** Parse pic_width_in_mbs/pic_height_in_map_units from a baseline/high SPS. */
internal fun spsDimensions(sps: ByteArray): Pair<Int, Int>? = parseSps(sps)?.let { it.width to it.height }

internal fun parseSps(sps: ByteArray): SpsInfo? {
    if (sps.size < 4) return null
    return runCatching {
        val bits = BitReader(unescapeRbsp(sps))
        val profile = bits.readBits(8)
        val constraints = bits.readBits(8)
        val level = bits.readBits(8)
        bits.readUe() // seq_parameter_set_id
        var chromaFormatIdc = 1
        var separateColourPlane = 0
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
        val maxNumRefFrames = bits.readUe()
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
        val base =
            SpsInfo(
                profileIdc = profile,
                constraintFlags = constraints,
                levelIdc = level,
                chromaFormatIdc = chromaFormatIdc,
                picOrderCntType = picOrderCntType,
                maxNumRefFrames = maxNumRefFrames,
                frameMbsOnly = frameMbsOnly == 1,
                width = width,
                height = height,
                vuiPresent = bits.hasMore() && bits.readBit() == 1,
            )
        if (base.vuiPresent) runCatching { parseVui(bits, base) }.getOrDefault(base) else base
    }.getOrNull()
}

private fun parseVui(
    bits: BitReader,
    base: SpsInfo,
): SpsInfo {
    if (bits.readBit() == 1) { // aspect_ratio_info_present_flag
        if (bits.readBits(8) == 255) bits.skip(32) // Extended_SAR: sar_width, sar_height
    }
    if (bits.readBit() == 1) bits.skip(1) // overscan_info_present → overscan_appropriate
    var fullRange: Boolean? = null
    if (bits.readBit() == 1) { // video_signal_type_present_flag
        bits.skip(3) // video_format
        fullRange = bits.readBit() == 1
        if (bits.readBit() == 1) bits.skip(24) // colour primaries / transfer / matrix
    }
    if (bits.readBit() == 1) { // chroma_loc_info_present_flag
        bits.readUe()
        bits.readUe()
    }
    var timingFps: Double? = null
    var fixedFrameRate: Boolean? = null
    if (bits.readBit() == 1) { // timing_info_present_flag
        val unitsInTick = bits.readBits(32).toLong() and 0xFFFFFFFFL
        val timeScale = bits.readBits(32).toLong() and 0xFFFFFFFFL
        fixedFrameRate = bits.readBit() == 1
        if (unitsInTick > 0) timingFps = timeScale.toDouble() / (2.0 * unitsInTick)
    }
    val nalHrd = bits.readBit() == 1
    if (nalHrd) skipHrd(bits)
    val vclHrd = bits.readBit() == 1
    if (vclHrd) skipHrd(bits)
    if (nalHrd || vclHrd) bits.skip(1) // low_delay_hrd_flag
    bits.skip(1) // pic_struct_present_flag
    val restriction = bits.readBit() == 1
    var reorder: Int? = null
    var dpb: Int? = null
    if (restriction) {
        bits.skip(1) // motion_vectors_over_pic_boundaries_flag
        repeat(4) { bits.readUe() } // max_bytes_per_pic / max_bits_per_mb / log2_max_mv_length h/v
        reorder = bits.readUe()
        dpb = bits.readUe()
    }
    return base.copy(
        vuiParsed = true,
        fullRange = fullRange,
        timingFps = timingFps,
        fixedFrameRate = fixedFrameRate,
        hrdPresent = nalHrd || vclHrd,
        bitstreamRestriction = restriction,
        maxNumReorderFrames = reorder,
        maxDecFrameBuffering = dpb,
    )
}

private fun skipHrd(bits: BitReader) {
    val cpbCount = bits.readUe() + 1
    bits.skip(8) // bit_rate_scale, cpb_size_scale
    repeat(cpbCount) {
        bits.readUe()
        bits.readUe()
        bits.skip(1)
    }
    bits.skip(20) // four 5-bit delay/offset lengths
}

internal fun parsePps(pps: ByteArray): PpsInfo? {
    if (pps.size < 2 || (pps[0].toInt() and 0x1F) != 8) return null
    return runCatching {
        val bits = BitReader(unescapeRbsp(pps))
        bits.readUe() // pic_parameter_set_id
        bits.readUe() // seq_parameter_set_id
        val cabac = bits.readBit() == 1
        bits.skip(1) // bottom_field_pic_order_in_frame_present_flag
        val sliceGroups = bits.readUe() + 1
        // 多 slice group（FMO）只出现在 Baseline 扩展场景，后续字段布局复杂：只报组数
        if (sliceGroups > 1) return@runCatching PpsInfo(cabac, sliceGroups, 0, 0, false, 0, null)
        val refL0 = bits.readUe() + 1
        val refL1 = bits.readUe() + 1
        val weighted = bits.readBit() == 1
        val bipred = bits.readBits(2)
        bits.readSe() // pic_init_qp_minus26
        bits.readSe() // pic_init_qs_minus26
        bits.readSe() // chroma_qp_index_offset
        bits.skip(3) // deblocking_filter_control / constrained_intra_pred / redundant_pic_cnt
        val transform8x8 = if (bits.hasMoreRbspData()) bits.readBit() == 1 else null
        PpsInfo(cabac, sliceGroups, refL0, refL1, weighted, bipred, transform8x8)
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

    fun readBits(count: Int): Int {
        var value = 0
        repeat(count) { value = (value shl 1) or readBit() }
        return value
    }

    fun hasMore(): Boolean = bit < data.size * 8

    /** H.264 more_rbsp_data()：当前位置之后除 rbsp_stop_one_bit 与补零外是否还有数据。 */
    fun hasMoreRbspData(): Boolean {
        var last = data.size * 8 - 1
        while (last >= bit && (data[last / 8].toInt() ushr (7 - last % 8)) and 1 == 0) last--
        return last > bit
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
