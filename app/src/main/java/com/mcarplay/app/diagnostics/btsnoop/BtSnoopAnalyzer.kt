package com.mcarplay.app.diagnostics.btsnoop

import java.util.UUID

/**
 * 从 HCI 包里还原与「车机能否识别 CarPlay 手机」相关的事实：
 * 本机 EIR 与可被发现窗口、各对端的连接/配对、L2CAP 通道，以及双向 SDP 查询（含续传与截断）。
 *
 * 只保留结构化事实：对端以出现顺序编号（peer=1…），地址、设备名、服务名与链路密钥一律不进结果。
 */
internal class BtSnoopAnalyzer {
    enum class Initiator { LOCAL, REMOTE, UNKNOWN }

    enum class QueryStatus {
        /** 最后一个回复不带续传状态。 */
        COMPLETE,

        /** 回复带续传状态，但请求方没有再续传就开始了新查询或关闭了通道——请求方主动截断。 */
        ABANDONED,

        /** 对端返回 SDP 错误。 */
        ERROR,

        /** 请求后没有回复。 */
        UNANSWERED,

        /** 抓包结束时仍在进行。 */
        OPEN,
    }

    class Peer(
        val index: Int,
    ) {
        var classOfDevice: Int? = null
        var connections = 0
        var localConnections = 0
        var remoteConnections = 0
        val connectedAt = ArrayList<Long>()
        val pairings = ArrayList<Pair<Long, Int>>()

        /** (发起方, PSM) → [请求数, 成功数]。 */
        val l2cap = LinkedHashMap<Pair<Initiator, Int>, IntArray>()
        val queries = ArrayList<SdpQuery>()
    }

    class SdpQuery(
        val peer: Int,
        val initiator: Initiator,
        val pduId: Int,
        val startedAt: Long,
        val pattern: List<UUID>,
        val maxBytes: Int?,
        val requestedHandle: Long?,
    ) {
        var status = QueryStatus.UNANSWERED
        var errorCode: Int? = null
        var responses = 0
        var continuationRequests = 0
        var continuationPending = false
        val handles = ArrayList<Long>()
        internal var buffer = java.io.ByteArrayOutputStream()
        var attributeBytes = 0
        var records: List<SdpRecordSummary> = emptyList()
    }

    class EirWrite(
        val at: Long,
        val eir: EirSummary,
    )

    class Result(
        val eirWrites: List<EirWrite>,
        /** 可被发现（inquiry scan 打开）的时间窗；结束为 null 表示抓包结束时仍打开。 */
        val discoverableWindows: List<Pair<Long, Long?>>,
        val localClassOfDevice: Int?,
        val localNameBytes: Int?,
        val peers: List<Peer>,
        val truncatedSdpPackets: Int,
    )

    private class Channel(
        val handle: Int,
        val initiator: Initiator,
        val keys: List<Long>,
    ) {
        var current: SdpQuery? = null
    }

    private class Reassembly(
        val cid: Int,
        val expected: Int,
    ) {
        val bytes = java.io.ByteArrayOutputStream()
    }

    private val peersByAddress = LinkedHashMap<String, Peer>()
    private val peersByHandle = HashMap<Int, Peer>()
    private val pendingOutgoing = HashSet<String>()
    private val pendingIncoming = HashSet<String>()
    private val pendingL2capRequests = HashMap<Long, Triple<Int, Int, Initiator>>()
    private val channels = HashMap<Long, Channel>()
    private val reassembly = HashMap<Int, Reassembly?>()
    private val eirWrites = ArrayList<EirWrite>()
    private val discoverable = ArrayList<Pair<Long, Long?>>()
    private var discoverableSince: Long? = null
    private var localClassOfDevice: Int? = null
    private var localNameBytes: Int? = null
    private var truncatedSdp = 0
    private var lastMicros = 0L

    fun accept(record: HciRecord) {
        lastMicros = record.unixMicros
        val payload = record.payload
        when (record.packetType) {
            BtSnoopFile.TYPE_COMMAND -> if (!record.incoming && payload.size >= 3) command(record.unixMicros, payload)
            BtSnoopFile.TYPE_EVENT -> if (payload.size >= 2) event(record.unixMicros, payload)
            BtSnoopFile.TYPE_ACL -> if (payload.size >= 4) acl(record, payload)
        }
    }

    fun finish(): Result {
        for (channel in channels.values.distinct()) closeQuery(channel, atEnd = true)
        discoverableSince?.let { discoverable += it to null }
        discoverableSince = null
        return Result(eirWrites, discoverable, localClassOfDevice, localNameBytes, peersByAddress.values.toList(), truncatedSdp)
    }

    // ---------------------------------------------------------------- HCI 命令 / 事件

    private fun command(
        at: Long,
        payload: ByteArray,
    ) {
        val opcode = u16le(payload, 0)
        val length = payload[2].toInt() and 0xff
        val params = 3
        if (payload.size < params + length) return
        when (opcode) {
            OP_WRITE_EIR -> if (length >= 2) eirWrites += EirWrite(at, EirSummary.parse(payload, params + 1, length - 1))
            OP_WRITE_SCAN_ENABLE ->
                if (length >= 1) {
                    val inquiryScan = payload[params].toInt() and 0x01 != 0
                    val since = discoverableSince
                    if (inquiryScan && since == null) {
                        discoverableSince = at
                    } else if (!inquiryScan && since != null) {
                        discoverable += since to at
                        discoverableSince = null
                    }
                }
            OP_WRITE_CLASS_OF_DEVICE -> if (length >= 3) localClassOfDevice = u24le(payload, params)
            OP_WRITE_LOCAL_NAME -> {
                var end = params
                while (end < params + length && payload[end] != 0.toByte()) end++
                localNameBytes = end - params
            }
            OP_CREATE_CONNECTION -> if (length >= 6) pendingOutgoing += address(payload, params)
        }
    }

    private fun event(
        at: Long,
        payload: ByteArray,
    ) {
        val code = payload[0].toInt() and 0xff
        val length = payload[1].toInt() and 0xff
        val params = 2
        if (payload.size < params + length) return
        when (code) {
            EVT_CONNECTION_REQUEST ->
                if (length >= 10 && payload[params + 9].toInt() == LINK_ACL) {
                    val address = address(payload, params)
                    pendingIncoming += address
                    peer(address).classOfDevice = u24le(payload, params + 6)
                }
            EVT_CONNECTION_COMPLETE ->
                if (length >= 11 && payload[params].toInt() == 0 && payload[params + 9].toInt() == LINK_ACL) {
                    val handle = u16le(payload, params + 1) and 0x0fff
                    val address = address(payload, params + 3)
                    val peer = peer(address)
                    peersByHandle[handle] = peer
                    peer.connections++
                    peer.connectedAt += at
                    when {
                        pendingOutgoing.remove(address) -> peer.localConnections++
                        pendingIncoming.remove(address) -> peer.remoteConnections++
                    }
                }
            EVT_DISCONNECTION_COMPLETE ->
                if (length >= 4 && payload[params].toInt() == 0) {
                    val handle = u16le(payload, params + 1) and 0x0fff
                    disconnect(handle)
                }
            EVT_SIMPLE_PAIRING_COMPLETE ->
                if (length >= 7) peer(address(payload, params + 1)).pairings += at to (payload[params].toInt() and 0xff)
        }
    }

    private fun disconnect(handle: Int) {
        val gone = channels.values.filter { it.handle == handle }.distinct()
        for (channel in gone) {
            closeQuery(channel, atEnd = false)
            channel.keys.forEach { channels.remove(it) }
        }
        reassembly.remove(handle shl 1)
        reassembly.remove((handle shl 1) or 1)
        pendingL2capRequests.keys.removeAll { (it ushr 24).toInt() == handle }
        peersByHandle.remove(handle)
    }

    // ---------------------------------------------------------------- ACL / L2CAP

    private fun acl(
        record: HciRecord,
        payload: ByteArray,
    ) {
        val header = u16le(payload, 0)
        val handle = header and 0x0fff
        val boundary = (header ushr 12) and 0x3
        val length = minOf(u16le(payload, 2), payload.size - 4)
        val key = (handle shl 1) or if (record.incoming) 1 else 0
        if (boundary == PB_CONTINUATION) {
            val pending = reassembly[key] ?: return
            pending.bytes.write(payload, 4, length)
            if (record.truncated) markTruncated(pending.cid, handle, record.incoming)
            if (pending.bytes.size() >= pending.expected) {
                reassembly.remove(key)
                l2cap(record.unixMicros, handle, record.incoming, pending.cid, pending.bytes.toByteArray())
            }
            return
        }
        if (length < 4) return
        val l2capLength = u16le(payload, 4)
        val cid = u16le(payload, 6)
        if (cid != CID_SIGNALING && !channels.containsKey(channelKey(handle, record.incoming, cid))) {
            reassembly.remove(key)
            return
        }
        if (record.truncated) markTruncated(cid, handle, record.incoming)
        if (length - 4 >= l2capLength) {
            reassembly.remove(key)
            l2cap(record.unixMicros, handle, record.incoming, cid, payload.copyOfRange(8, 8 + l2capLength))
        } else {
            val pending = Reassembly(cid, l2capLength)
            pending.bytes.write(payload, 8, length - 4)
            reassembly[key] = pending
        }
    }

    private fun markTruncated(
        cid: Int,
        handle: Int,
        incoming: Boolean,
    ) {
        if (channels.containsKey(channelKey(handle, incoming, cid))) truncatedSdp++
    }

    private fun l2cap(
        at: Long,
        handle: Int,
        incoming: Boolean,
        cid: Int,
        data: ByteArray,
    ) {
        if (cid == CID_SIGNALING) {
            signaling(handle, incoming, data)
        } else {
            channels[channelKey(handle, incoming, cid)]?.let { sdp(at, it, incoming, data) }
        }
    }

    private fun signaling(
        handle: Int,
        incoming: Boolean,
        data: ByteArray,
    ) {
        var cursor = 0
        while (cursor + 4 <= data.size) {
            val code = data[cursor].toInt() and 0xff
            val id = data[cursor + 1].toInt() and 0xff
            val length = u16le(data, cursor + 2)
            val body = cursor + 4
            if (body + length > data.size) return
            when (code) {
                SIG_CONNECTION_REQUEST ->
                    if (length >= 4) {
                        val psm = u16le(data, body)
                        val sourceCid = u16le(data, body + 2)
                        val initiator = if (incoming) Initiator.REMOTE else Initiator.LOCAL
                        pendingL2capRequests[requestKey(handle, incoming, id)] = Triple(psm, sourceCid, initiator)
                        peerOf(handle).let { peer -> peer.l2cap.getOrPut(initiator to psm) { IntArray(2) }[0]++ }
                    }
                SIG_CONNECTION_RESPONSE ->
                    if (length >= 8) {
                        val requestKey = requestKey(handle, !incoming, id)
                        val request = pendingL2capRequests[requestKey]
                        val destinationCid = u16le(data, body)
                        val result = u16le(data, body + 4)
                        if (request != null && result != L2CAP_PENDING) {
                            pendingL2capRequests.remove(requestKey)
                            val (psm, requesterCid, initiator) = request
                            if (result == 0) {
                                peerOf(handle).let { peer -> peer.l2cap.getOrPut(initiator to psm) { IntArray(2) }[1]++ }
                                if (psm == PSM_SDP) openSdpChannel(handle, initiator, requesterCid, destinationCid)
                            }
                        }
                    }
                SIG_DISCONNECTION_REQUEST ->
                    if (length >= 4) {
                        val channel = channels[channelKey(handle, incoming, u16le(data, body))]
                        if (channel != null) {
                            closeQuery(channel, atEnd = false)
                            channel.keys.forEach { channels.remove(it) }
                        }
                    }
            }
            cursor = body + length
        }
    }

    /**
     * 请求方的 CID 写在它发出的包里是「对端 CID」：本机发起时，收到的包带本机 CID（请求里的源 CID），
     * 发出的包带对端 CID（回复里的目的 CID）；对端发起时正好相反。
     */
    private fun openSdpChannel(
        handle: Int,
        initiator: Initiator,
        requesterCid: Int,
        responderCid: Int,
    ) {
        val localCid = if (initiator == Initiator.LOCAL) requesterCid else responderCid
        val remoteCid = if (initiator == Initiator.LOCAL) responderCid else requesterCid
        val keys = listOf(channelKey(handle, true, localCid), channelKey(handle, false, remoteCid))
        keys.forEach { key -> channels.remove(key)?.let { old -> closeQuery(old, atEnd = false) } }
        val channel = Channel(handle, initiator, keys)
        keys.forEach { channels[it] = channel }
    }

    // ---------------------------------------------------------------- SDP

    private fun sdp(
        at: Long,
        channel: Channel,
        incoming: Boolean,
        data: ByteArray,
    ) {
        if (data.size < 5) return
        val pduId = data[0].toInt() and 0xff
        val parameterLength = minOf(SdpElements.u16be(data, 3), data.size - 5)
        val params = 5
        val end = params + parameterLength
        val fromInitiator = (channel.initiator == Initiator.REMOTE) == incoming
        if (fromInitiator) request(at, channel, pduId, data, params, end) else response(channel, pduId, data, params, end)
    }

    private fun request(
        at: Long,
        channel: Channel,
        pduId: Int,
        data: ByteArray,
        params: Int,
        end: Int,
    ) {
        var cursor = params
        var pattern: List<UUID> = emptyList()
        var maxBytes: Int? = null
        var handle: Long? = null
        when (pduId) {
            SDP_SERVICE_SEARCH_REQUEST, SDP_SERVICE_SEARCH_ATTRIBUTE_REQUEST -> {
                val element = SdpElements.parse(data, cursor, end) ?: return
                pattern = SdpElements.uuids(element)
                cursor += element.size
                if (cursor + 2 > end) return
                maxBytes = SdpElements.u16be(data, cursor)
                cursor += 2
                if (pduId == SDP_SERVICE_SEARCH_ATTRIBUTE_REQUEST) {
                    cursor += SdpElements.parse(data, cursor, end)?.size ?: return
                }
            }
            SDP_SERVICE_ATTRIBUTE_REQUEST -> {
                if (cursor + 6 > end) return
                handle = SdpElements.u32be(data, cursor)
                maxBytes = SdpElements.u16be(data, cursor + 4)
                cursor += 6
                cursor += SdpElements.parse(data, cursor, end)?.size ?: return
            }
            else -> return
        }
        val continuation = if (cursor < end) data[cursor].toInt() and 0xff else 0
        val current = channel.current
        if (continuation > 0 && current != null && current.pduId == pduId) {
            current.continuationRequests++
            current.continuationPending = false
            return
        }
        closeQuery(channel, atEnd = false)
        val peer = peerOf(channel.handle)
        val query =
            SdpQuery(
                peer.index,
                channel.initiator,
                pduId,
                at,
                pattern,
                if (pduId == SDP_SERVICE_SEARCH_REQUEST) null else maxBytes,
                handle,
            )
        peer.queries += query
        channel.current = query
    }

    private fun response(
        channel: Channel,
        pduId: Int,
        data: ByteArray,
        params: Int,
        end: Int,
    ) {
        val query = channel.current ?: return
        query.responses++
        when (pduId) {
            SDP_ERROR_RESPONSE -> {
                query.errorCode = if (params + 2 <= end) SdpElements.u16be(data, params) else null
                query.status = QueryStatus.ERROR
                channel.current = null
            }
            SDP_SERVICE_SEARCH_RESPONSE -> {
                if (params + 4 > end) return
                val count = SdpElements.u16be(data, params + 2)
                var cursor = params + 4
                repeat(count) {
                    if (cursor + 4 <= end) query.handles += SdpElements.u32be(data, cursor)
                    cursor += 4
                }
                finishFragment(channel, query, data, cursor, end)
            }
            SDP_SERVICE_ATTRIBUTE_RESPONSE, SDP_SERVICE_SEARCH_ATTRIBUTE_RESPONSE -> {
                if (params + 2 > end) return
                val count = minOf(SdpElements.u16be(data, params), end - params - 2)
                if (query.buffer.size() < MAX_QUERY_BYTES) query.buffer.write(data, params + 2, count)
                query.attributeBytes += count
                finishFragment(channel, query, data, params + 2 + count, end)
            }
        }
    }

    private fun finishFragment(
        channel: Channel,
        query: SdpQuery,
        data: ByteArray,
        continuationAt: Int,
        end: Int,
    ) {
        val continuation = if (continuationAt < end) data[continuationAt].toInt() and 0xff else 0
        if (continuation == 0) {
            query.continuationPending = false
            complete(query, QueryStatus.COMPLETE)
            channel.current = null
        } else {
            query.continuationPending = true
            query.status = QueryStatus.OPEN
        }
    }

    private fun closeQuery(
        channel: Channel,
        atEnd: Boolean,
    ) {
        val query = channel.current ?: return
        channel.current = null
        complete(
            query,
            when {
                atEnd -> QueryStatus.OPEN
                query.continuationPending -> QueryStatus.ABANDONED
                else -> QueryStatus.UNANSWERED
            },
        )
    }

    private fun complete(
        query: SdpQuery,
        status: QueryStatus,
    ) {
        query.status = status
        val bytes = query.buffer.toByteArray()
        query.records =
            when (query.pduId) {
                SDP_SERVICE_SEARCH_ATTRIBUTE_REQUEST -> SdpRecords.summarizeLists(bytes, bytes.size)
                SDP_SERVICE_ATTRIBUTE_REQUEST ->
                    (SdpElements.parse(bytes, 0, bytes.size, lenient = true) as? SdpSequence)
                        ?.let { listOf(SdpRecords.summarize(it)) }
                        .orEmpty()
                else -> emptyList()
            }
        query.buffer = java.io.ByteArrayOutputStream(0)
    }

    // ---------------------------------------------------------------- 工具

    private fun peer(address: String): Peer = peersByAddress.getOrPut(address) { Peer(peersByAddress.size + 1) }

    /** 抓包开始前就已建立的连接没有 Connection Complete：按句柄建一个匿名对端。 */
    private fun peerOf(handle: Int): Peer = peersByHandle.getOrPut(handle) { peer("handle:$handle") }

    /** 地址只作内部键，从不输出。 */
    private fun address(
        bytes: ByteArray,
        offset: Int,
    ): String = (5 downTo 0).joinToString(":") { "%02x".format(bytes[offset + it]) }

    private fun channelKey(
        handle: Int,
        incoming: Boolean,
        cid: Int,
    ): Long = (handle.toLong() shl 17) or ((if (incoming) 1L else 0L) shl 16) or cid.toLong()

    private fun requestKey(
        handle: Int,
        incoming: Boolean,
        id: Int,
    ): Long = (handle.toLong() shl 24) or ((if (incoming) 1L else 0L) shl 8) or id.toLong()

    private fun u16le(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u24le(
        bytes: ByteArray,
        offset: Int,
    ): Int = u16le(bytes, offset) or ((bytes[offset + 2].toInt() and 0xff) shl 16)

    companion object {
        const val SDP_ERROR_RESPONSE = 0x01
        const val SDP_SERVICE_SEARCH_REQUEST = 0x02
        const val SDP_SERVICE_SEARCH_RESPONSE = 0x03
        const val SDP_SERVICE_ATTRIBUTE_REQUEST = 0x04
        const val SDP_SERVICE_ATTRIBUTE_RESPONSE = 0x05
        const val SDP_SERVICE_SEARCH_ATTRIBUTE_REQUEST = 0x06
        const val SDP_SERVICE_SEARCH_ATTRIBUTE_RESPONSE = 0x07

        const val PSM_SDP = 0x0001
        const val PSM_RFCOMM = 0x0003

        private const val OP_CREATE_CONNECTION = 0x0405
        private const val OP_WRITE_LOCAL_NAME = 0x0c13
        private const val OP_WRITE_SCAN_ENABLE = 0x0c1a
        private const val OP_WRITE_CLASS_OF_DEVICE = 0x0c24
        private const val OP_WRITE_EIR = 0x0c52

        private const val EVT_CONNECTION_COMPLETE = 0x03
        private const val EVT_CONNECTION_REQUEST = 0x04
        private const val EVT_DISCONNECTION_COMPLETE = 0x05
        private const val EVT_SIMPLE_PAIRING_COMPLETE = 0x36
        private const val LINK_ACL = 0x01

        private const val PB_CONTINUATION = 0x1
        private const val CID_SIGNALING = 0x0001
        private const val SIG_CONNECTION_REQUEST = 0x02
        private const val SIG_CONNECTION_RESPONSE = 0x03
        private const val SIG_DISCONNECTION_REQUEST = 0x06
        private const val L2CAP_PENDING = 0x0001

        /** 单次查询最多保留的属性字节（远大于常见手机的全部 SDP 记录）。 */
        private const val MAX_QUERY_BYTES = 256 * 1024
    }
}
