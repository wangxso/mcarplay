package com.mcarplay.app.diagnostics.btsnoop

import com.mcarplay.app.diagnostics.btsnoop.BtSnoopAnalyzer.Initiator
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopAnalyzer.QueryStatus
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.CAR
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.be16
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.des
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.eir
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.serviceRecord
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.uint32
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.uuid128
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopFixtures.Companion.uuid16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.TimeZone

class BtSnoopAnalyzerTest {
    private val handle = 0x000b
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun analyze(capture: BtSnoopFixtures): BtSnoopFileAnalysis {
        val analyzer = BtSnoopAnalyzer()
        val stats = BtSnoopFile.read(ByteArrayInputStream(capture.bytes())) { analyzer.accept(it) }
        return BtSnoopFileAnalysis("btsnoop_hci.log", stats, analyzer.finish())
    }

    private fun phoneRecords(): ByteArray =
        des(
            serviceRecord(0x10000, uuid16(0x1101), 2, "SPP"),
            serviceRecord(0x10001, uuid128(BtUuids.IAP2_PHONE), 5, "iAP2"),
            serviceRecord(0x10002, uuid128(BtUuids.CARPLAY), 6, "CarPlay"),
        )

    /** 车机发起 ACL、配对，再在 SDP 通道上做 ServiceSearchAttribute（L2CAP 模式）。 */
    private fun carQueriesPhone(capture: BtSnoopFixtures) {
        capture.connectionRequest(CAR, 0x240420)
        capture.connectionComplete(handle, CAR)
        capture.pairingComplete(CAR)
        capture.connectionRequestL2cap(handle, incoming = true, id = 3, psm = 0x0001, sourceCid = 0x0040)
        capture.connectionResponseL2cap(handle, incoming = false, id = 3, destinationCid = 0x0041, sourceCid = 0x0040)
    }

    private fun searchAttributeRequest(continuation: ByteArray = byteArrayOf(0)): ByteArray =
        des(uuid16(0x0100)) + be16(0xffff) + des(uint32(0x0000ffff)) + continuation

    private fun attributeResponse(
        bytes: ByteArray,
        continuation: ByteArray,
    ): ByteArray = be16(bytes.size) + bytes + continuation

    @Test
    fun eirWithCarPlayDuringDiscoverableWindow() {
        val capture = BtSnoopFixtures()
        capture.command(0x0c24, BtSnoopFixtures.le24(0x5a020c))
        capture.command(0x0c13, "Secret Phone Name".toByteArray() + ByteArray(231))
        capture.command(
            0x0c52,
            byteArrayOf(0) + eir("Secret Phone Name", listOf(0x1101, 0x110a), listOf(BtUuids.IAP2_PHONE, BtUuids.CARPLAY)),
        )
        capture.command(0x0c1a, byteArrayOf(0x03))
        capture.advance(5_000)
        capture.command(0x0c1a, byteArrayOf(0x02))

        val analysis = analyze(capture)
        val write = analysis.result.eirWrites.single()
        assertEquals(17, write.eir.nameBytes)
        assertTrue(write.eir.nameComplete)
        assertEquals(2, write.eir.uuid16Count)
        assertEquals(listOf(BtUuids.IAP2_PHONE, BtUuids.CARPLAY), write.eir.uuid128)
        assertEquals(1, analysis.result.discoverableWindows.size)
        assertEquals(0x5a020c, analysis.result.localClassOfDevice)

        val text = BtSnoopReport.format(analysis, utc).joinToString("\n")
        assertTrue(text, text.contains("EIR含CarPlay=是"))
        assertTrue(text, text.contains("结论 EIR：最后一次写入含 CarPlay UUID"))
        assertFalse("设备名不得出现在报告里", text.contains("Secret"))
    }

    @Test
    fun incompleteUuid128ListIsReported() {
        val capture = BtSnoopFixtures()
        capture.command(0x0c52, byteArrayOf(0) + eir("P", listOf(0x1101), listOf(BtUuids.IAP2_PHONE), uuid128Complete = false))
        val text = BtSnoopReport.format(analyze(capture), utc).joinToString("\n")
        assertTrue(text, text.contains("128 位 UUID 列表标记为不完整"))
    }

    @Test
    fun carSdpQueryWithContinuationAndFragmentationReachesCarPlayRecord() {
        val capture = BtSnoopFixtures()
        carQueriesPhone(capture)
        val lists = phoneRecords()
        val half = lists.size / 2
        capture.sdp(handle, incoming = true, cid = 0x0041, pduId = 0x06, params = searchAttributeRequest())
        capture.sdp(
            handle,
            incoming = false,
            cid = 0x0040,
            pduId = 0x07,
            params = attributeResponse(lists.copyOfRange(0, half), byteArrayOf(2, 0x12, 0x34)),
            fragment = 27,
        )
        capture.sdp(handle, incoming = true, cid = 0x0041, pduId = 0x06, params = searchAttributeRequest(byteArrayOf(2, 0x12, 0x34)))
        capture.sdp(
            handle,
            incoming = false,
            cid = 0x0040,
            pduId = 0x07,
            params = attributeResponse(lists.copyOfRange(half, lists.size), byteArrayOf(0)),
            fragment = 27,
        )

        val analysis = analyze(capture)
        val peer = analysis.result.peers.single()
        assertEquals(0x240420, peer.classOfDevice)
        assertEquals(1, peer.remoteConnections)
        assertEquals(1, peer.pairings.size)
        val query = peer.queries.single()
        assertEquals(Initiator.REMOTE, query.initiator)
        assertEquals(QueryStatus.COMPLETE, query.status)
        assertEquals(2, query.responses)
        assertEquals(1, query.continuationRequests)
        assertEquals(lists.size, query.attributeBytes)
        assertEquals(3, query.records.size)
        assertEquals(BtUuids.CARPLAY, query.records[2].serviceClasses.single())
        assertEquals(6, query.records[2].rfcommChannel)
        assertEquals(0x10002L, query.records[2].handle)

        val text = BtSnoopReport.format(analysis, utc).joinToString("\n")
        assertTrue(text, text.contains("CoD=0x240420(车载)"))
        assertTrue(text, text.contains("对端查本机SDP#1"))
        assertTrue(text, text.contains("模式=[L2CAP]"))
        assertTrue(text, text.contains("CarPlay(#3"))
        assertTrue(text, text.contains("手机侧发布已送达"))
        assertFalse("服务名不得出现在报告里", text.contains("\"CarPlay\""))
        assertFalse("地址不得出现在报告里", text.lowercase().contains("11:22:33"))
    }

    @Test
    fun carStoppingBeforeLastContinuationIsReportedAsAbandoned() {
        val capture = BtSnoopFixtures()
        carQueriesPhone(capture)
        val lists = phoneRecords()
        val firstRecordEnd = 2 + serviceRecord(0x10000, uuid16(0x1101), 2, "SPP").size
        capture.sdp(handle, incoming = true, cid = 0x0041, pduId = 0x06, params = searchAttributeRequest())
        capture.sdp(
            handle,
            incoming = false,
            cid = 0x0040,
            pduId = 0x07,
            params = attributeResponse(lists.copyOfRange(0, firstRecordEnd + 5), byteArrayOf(2, 0, 1)),
        )
        // 车机不续传，直接断开 ACL。
        capture.disconnectionComplete(handle)

        val query =
            analyze(capture)
                .result.peers
                .single()
                .queries
                .single()
        assertEquals(QueryStatus.ABANDONED, query.status)
        assertEquals(1, query.records.count { it.complete })
        assertFalse(query.records.any { r -> r.allUuids.contains(BtUuids.CARPLAY) })

        val analysis = analyze(capture)
        val text = BtSnoopReport.format(analysis, utc).joinToString("\n")
        assertTrue(text, text.contains("请求方中止续传"))
        assertTrue(text, text.contains("符合对端缓冲截断"))
    }

    @Test
    fun phoneQueryingCarIsLocalAndListsCarServices() {
        val capture = BtSnoopFixtures()
        capture.command(0x0405, CAR + ByteArray(7))
        capture.connectionComplete(handle, CAR)
        capture.connectionRequestL2cap(handle, incoming = false, id = 7, psm = 0x0001, sourceCid = 0x0050)
        capture.connectionResponseL2cap(handle, incoming = true, id = 7, destinationCid = 0x0070, sourceCid = 0x0050)
        capture.sdp(handle, incoming = false, cid = 0x0070, pduId = 0x06, params = searchAttributeRequest())
        val carRecords = des(serviceRecord(0x10010, uuid16(0x111e), 3, "Car HFP"))
        capture.sdp(handle, incoming = true, cid = 0x0050, pduId = 0x07, params = attributeResponse(carRecords, byteArrayOf(0)))

        val analysis = analyze(capture)
        val peer = analysis.result.peers.single()
        assertEquals(1, peer.localConnections)
        val query = peer.queries.single()
        assertEquals(Initiator.LOCAL, query.initiator)
        assertEquals(QueryStatus.COMPLETE, query.status)
        assertEquals(1, peer.l2cap[Initiator.LOCAL to 0x0001]!![1])

        val text = BtSnoopReport.format(analysis, utc).joinToString("\n")
        assertTrue(text, text.contains("本机查对端SDP#1"))
        assertTrue(text, text.contains("HFP"))
        assertTrue(text, text.contains("抓包期间对端没有向本机发起 SDP 查询"))
        assertFalse(text.contains("Car HFP"))
    }

    @Test
    fun sdpErrorAndServiceSearchHandlesAreRecorded() {
        val capture = BtSnoopFixtures()
        carQueriesPhone(capture)
        val search = des(uuid128(BtUuids.CARPLAY_REVERSED)) + be16(10) + byteArrayOf(0)
        capture.sdp(handle, incoming = true, cid = 0x0041, pduId = 0x02, params = search)
        capture.sdp(handle, incoming = false, cid = 0x0040, pduId = 0x03, params = be16(0) + be16(0) + byteArrayOf(0))
        capture.sdp(
            handle,
            incoming = true,
            cid = 0x0041,
            pduId = 0x04,
            params =
                BtSnoopFixtures.be32(0x10009) + be16(100) + des(uint32(0xffff)) + byteArrayOf(0),
        )
        capture.sdp(handle, incoming = false, cid = 0x0040, pduId = 0x01, params = be16(0x0002))

        val analysis = analyze(capture)
        val queries =
            analysis.result.peers
                .single()
                .queries
        assertEquals(2, queries.size)
        assertEquals(QueryStatus.COMPLETE, queries[0].status)
        assertEquals(listOf(BtUuids.CARPLAY_REVERSED), queries[0].pattern)
        assertEquals(QueryStatus.ERROR, queries[1].status)
        assertEquals(0x0002, queries[1].errorCode)
        assertEquals(0x10009L, queries[1].requestedHandle)
        val text = BtSnoopReport.format(analysis, utc).joinToString("\n")
        assertTrue(text, text.contains("CarPlay(反序)"))
        assertTrue(text, text.contains("错误0x0002"))
    }

    @Test
    fun captureStartedAfterConnectionUsesAnonymousPeer() {
        val capture = BtSnoopFixtures()
        capture.connectionRequestL2cap(handle, incoming = true, id = 1, psm = 0x0001, sourceCid = 0x0040)
        capture.connectionResponseL2cap(handle, incoming = false, id = 1, destinationCid = 0x0041, sourceCid = 0x0040)
        capture.sdp(handle, incoming = true, cid = 0x0041, pduId = 0x06, params = searchAttributeRequest())

        val result = analyze(capture).result
        val query =
            result.peers
                .single()
                .queries
                .single()
        assertEquals(QueryStatus.OPEN, query.status)
        assertNull(result.peers.single().classOfDevice)
    }

    @Test
    fun unencapsulatedHciDatalinkIsSupported() {
        val capture = BtSnoopFixtures(BtSnoopFile.DATALINK_HCI)
        capture.command(0x0c52, byteArrayOf(0) + eir("P", emptyList(), listOf(BtUuids.CARPLAY)))
        val analysis = analyze(capture)
        assertEquals(BtSnoopFile.DATALINK_HCI, analysis.stats.datalink)
        assertTrue(
            analysis.result.eirWrites
                .single()
                .eir.uuid128
                .contains(BtUuids.CARPLAY),
        )
    }

    @Test
    fun truncatedFileIsMarkedIncomplete() {
        val capture = BtSnoopFixtures()
        capture.command(0x0c1a, byteArrayOf(0x03))
        val bytes = capture.bytes()
        val analyzer = BtSnoopAnalyzer()
        val stats = BtSnoopFile.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 2))) { analyzer.accept(it) }
        assertFalse(stats.complete)
        assertEquals(0L, stats.records)
    }

    @Test
    fun lenientParserStopsAtTruncatedRecord() {
        val lists = phoneRecords()
        val partial = lists.copyOf(lists.size - 4)
        assertNull(SdpElements.parse(partial, 0, partial.size))
        val outer = SdpElements.parse(partial, 0, partial.size, lenient = true) as SdpSequence
        assertFalse(outer.complete)
        val records = SdpRecords.summarizeLists(partial, partial.size)
        assertEquals(3, records.size)
        assertTrue(records[0].complete)
        assertFalse(records[2].complete)
        assertNotNull(records[2].handle)
    }
}
