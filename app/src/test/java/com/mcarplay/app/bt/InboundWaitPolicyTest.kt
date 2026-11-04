package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class InboundWaitPolicyTest {
    private var now = 10_000L
    private val policy = InboundWaitPolicy { now }

    private fun short(id: Int): UUID = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", id))

    /** 2026-11-02 艾瑞泽 8 雅 SDP（实时查询完成）：无 …caff，带厂商 UUID ec884348。 */
    private val arrizo =
        listOf(
            short(0x1101),
            short(0x110B),
            short(0x110E),
            short(0x111E),
            short(0x1133),
            UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66"),
            InboundWaitPolicy.INBOUND_ONLY_SIGNATURE,
        )
    private val headUnitWithoutSignature = listOf(short(0x110B), short(0x110E), short(0x111E), short(0x1133))

    @Test
    fun arrizoSignatureWaitsAfterFirstFastReadFailure() {
        assertFalse(policy.skipOutbound(ADDRESS))
        assertTrue(policy.failed(ADDRESS, arrizo, "READ_FAILED", 43))
        assertTrue(policy.isWaiting(ADDRESS))
        assertTrue(policy.skipOutbound(ADDRESS))
        // 已在等待：再次失败不重复提示
        assertFalse(policy.failed(ADDRESS, arrizo, "READ_FAILED", 50))
    }

    @Test
    fun otherHeadUnitsNeedThreeConsecutiveFastFailures() {
        assertFalse(policy.failed(ADDRESS, headUnitWithoutSignature, "READ_FAILED", 60))
        assertFalse(policy.failed(ADDRESS, headUnitWithoutSignature, "READ_FAILED", 70))
        assertFalse(policy.skipOutbound(ADDRESS))
        assertTrue(policy.failed(ADDRESS, headUnitWithoutSignature, "READ_FAILED", 80))
        assertTrue(policy.skipOutbound(ADDRESS))
    }

    @Test
    fun slowOrOtherFailuresResetTheCount() {
        policy.failed(ADDRESS, headUnitWithoutSignature, "READ_FAILED", 60)
        policy.failed(ADDRESS, headUnitWithoutSignature, "READ_FAILED", 60)
        assertFalse(policy.failed(ADDRESS, headUnitWithoutSignature, "READ_FAILED", 5_000))
        assertFalse(policy.failed(ADDRESS, headUnitWithoutSignature, "READ_FAILED", 60))
        assertFalse(policy.failed(ADDRESS, headUnitWithoutSignature, "CONNECTION_REFUSED", 60))
        assertFalse(policy.failed(ADDRESS, headUnitWithoutSignature, "READ_FAILED", 60))
        assertFalse(policy.isWaiting(ADDRESS))
    }

    /** 声明了 …caff 的车机（奥迪等）失败只是暂时现象，永不放弃主动连入。 */
    @Test
    fun receiversAdvertisingIap2NeverSwitchToWaiting() {
        val audi = arrizo + Iap2BluetoothClient.ACCESSORY_IAP2_UUID
        repeat(5) { assertFalse(policy.failed(ADDRESS, audi, "READ_FAILED", 40)) }
        assertFalse(policy.skipOutbound(ADDRESS))
    }

    @Test
    fun waitingStillProbesOncePerInterval() {
        policy.failed(ADDRESS, arrizo, "READ_FAILED", 43)
        now += InboundWaitPolicy.PROBE_INTERVAL_MILLIS - 1
        assertTrue(policy.skipOutbound(ADDRESS))
        now += 1
        assertFalse(policy.skipOutbound(ADDRESS))
        assertTrue(policy.skipOutbound(ADDRESS))
    }

    @Test
    fun successOrNewRoundClearsWaiting() {
        policy.failed(ADDRESS, arrizo, "READ_FAILED", 43)
        policy.connected(ADDRESS)
        assertFalse(policy.skipOutbound(ADDRESS))

        policy.failed(ADDRESS, arrizo, "READ_FAILED", 43)
        policy.reset()
        assertFalse(policy.isWaiting(ADDRESS))
    }

    @Test
    fun waitingIsPerDevice() {
        policy.failed(ADDRESS, arrizo, "READ_FAILED", 43)
        assertFalse(policy.skipOutbound("11:22:33:44:55:66"))
    }

    @Test
    fun androidReadFailureFeedsThePolicyReason() {
        val reason = BluetoothFailure.reason(IOException("read failed, socket might closed or timeout, read ret: -1"))
        assertEquals("READ_FAILED", reason)
        assertTrue(InboundWaitPolicy.isFastReadFailure(reason, 127))
        assertFalse(InboundWaitPolicy.isFastReadFailure(reason, InboundWaitPolicy.FAST_FAILURE_MILLIS))
        assertFalse(InboundWaitPolicy.isFastReadFailure(null, 10))
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
