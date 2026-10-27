package com.mcarplay.app.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CarLifeJoinGateTest {
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())
    private val slice = byteArrayOf(0x41, 0x9A.toByte())
    private val aud = byteArrayOf(0x09, 0xF0.toByte())

    @Test
    fun dropsFramesUntilFirstIdr() {
        val gate = CarLifeJoinGate()
        assertNull(gate.admit(unit(annexB(slice), keyFrame = false)))
        val key = unit(annexB(sps, pps, idr), keyFrame = true)
        assertSame(key, gate.admit(key))
        val next = unit(annexB(slice), keyFrame = false)
        assertSame(next, gate.admit(next))
    }

    @Test
    fun prependsCachedParameterSetsWhenIdrHasNoInlineSps() {
        val gate = CarLifeJoinGate()
        val admitted = gate.admit(unit(annexB(idr), keyFrame = true))
        assertArrayEquals(annexB(sps, pps, idr), admitted!!.data)
    }

    @Test
    fun idrWithInlineSpsOnlyGetsCachedPps() {
        // 审查复现顺序：配置 AU [7,8]（非关键帧被丢弃）→ IDR [9,7,5] → P 帧 [1]
        val gate = CarLifeJoinGate()
        assertNull(gate.admit(unit(annexB(sps, pps), keyFrame = false)))
        val admitted = gate.admit(unit(annexB(aud, sps, idr), keyFrame = true))
        assertArrayEquals(annexB(pps, aud, sps, idr), admitted!!.data)
        val next = unit(annexB(slice), keyFrame = false)
        assertSame(next, gate.admit(next))
    }

    @Test
    fun idrWithInlinePpsOnlyGetsCachedSps() {
        val gate = CarLifeJoinGate()
        val admitted = gate.admit(unit(annexB(pps, idr), keyFrame = true))
        assertArrayEquals(annexB(sps, pps, idr), admitted!!.data)
    }

    @Test
    fun idrMissingPpsWithoutCacheKeepsWaiting() {
        val gate = CarLifeJoinGate()
        assertNull(gate.admit(CarLifeVideoAccessUnit(1, annexB(sps, idr), keyFrame = true, sps = sps, pps = null)))
    }

    @Test
    fun idrWithoutAnyKnownParameterSetsKeepsWaiting() {
        val gate = CarLifeJoinGate()
        assertNull(gate.admit(CarLifeVideoAccessUnit(1, annexB(idr), keyFrame = true, sps = null, pps = null)))
        assertNull(gate.admit(unit(annexB(slice), keyFrame = false)))
    }

    @Test
    fun resetWaitsForNextIdrAfterDroppedSubmission() {
        val gate = CarLifeJoinGate()
        gate.admit(unit(annexB(sps, pps, idr), keyFrame = true))
        gate.reset()
        assertNull(gate.admit(unit(annexB(slice), keyFrame = false)))
    }

    private fun unit(
        data: ByteArray,
        keyFrame: Boolean,
    ) = CarLifeVideoAccessUnit(timestamp = 1, data = data, keyFrame = keyFrame, sps = sps, pps = pps)

    private fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { acc, nal -> acc + byteArrayOf(0, 0, 0, 1) + nal }
}
