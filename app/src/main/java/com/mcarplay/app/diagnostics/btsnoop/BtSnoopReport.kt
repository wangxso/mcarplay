package com.mcarplay.app.diagnostics.btsnoop

import com.mcarplay.app.diagnostics.btsnoop.BtSnoopAnalyzer.Initiator
import com.mcarplay.app.diagnostics.btsnoop.BtSnoopAnalyzer.QueryStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 一个 btsnoop 文件的分析结果。 */
internal class BtSnoopFileAnalysis(
    /** 文件名（不含目录）。 */
    val name: String,
    val stats: BtSnoopFile.Stats,
    val result: BtSnoopAnalyzer.Result,
)

/**
 * 把分析结果写成脱敏文本行（每行前由调用方加 `BTSNOOP:`）：
 * 只有时间、计数、服务 UUID、CoD 与结论——不含地址、设备名、服务名或任何包内容。
 */
internal object BtSnoopReport {
    private const val MAX_QUERIES = 40
    private const val MAX_WINDOWS = 10

    fun format(
        analysis: BtSnoopFileAnalysis,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<String> {
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { this.timeZone = timeZone }

        fun time(micros: Long?): String = micros?.let { clock.format(Date(it / 1000)) } ?: "?"

        val stats = analysis.stats
        val result = analysis.result
        val lines = ArrayList<String>()
        lines +=
            "文件=${analysis.name} 记录=${stats.records} 时间=${time(stats.firstMicros)}–${time(stats.lastMicros)} " +
            "截断包=${stats.truncated} 文件完整=${yes(stats.complete)}" +
            if (result.truncatedSdpPackets > 0) " SDP截断包=${result.truncatedSdpPackets}" else ""
        val local =
            buildList {
                result.localClassOfDevice?.let { add("CoD=${cod(it)}") }
                result.localNameBytes?.let { add("名称=$it 字节") }
            }
        if (local.isNotEmpty()) lines += "本机 " + local.joinToString(" ")

        val last = result.eirWrites.lastOrNull()
        if (last == null) {
            lines += "本机 EIR：抓包期间没有写入（EIR 只在服务变化或蓝牙启动时写；先开抓包再关开蓝牙才能看到）"
        } else {
            val withCarPlay = result.eirWrites.count { write -> write.eir.uuid128.any(BtUuids::isCarPlay) }
            lines += "本机 EIR 写入 ${result.eirWrites.size} 次，含 CarPlay 的 $withCarPlay 次；最后一次 ${time(last.at)}：${eir(last.eir)}"
        }
        if (result.discoverableWindows.isNotEmpty()) {
            val windows =
                result.discoverableWindows.take(MAX_WINDOWS).joinToString("，") { (start, end) ->
                    val carPlay = eirDuring(result, start, end)
                    "${time(start)}–${end?.let { time(it) } ?: "结束仍开"}(EIR含CarPlay=${carPlay?.let { yes(it) } ?: "未知"})"
                }
            lines += "本机可被发现 ${result.discoverableWindows.size} 次：$windows"
        } else {
            lines += "本机可被发现：抓包期间没有打开"
        }

        var printed = 0
        for (peer in result.peers) {
            if (peer.connections == 0 && peer.queries.isEmpty() && peer.pairings.isEmpty()) continue
            val pairings =
                if (peer.pairings.isEmpty()) {
                    "无"
                } else {
                    peer.pairings.joinToString(",") { (at, status) -> "${time(at)}${if (status == 0) "成功" else "失败0x%02X".format(status)}" }
                }
            lines +=
                "peer=${peer.index}" + (peer.classOfDevice?.let { " CoD=${cod(it)}" } ?: "") +
                " ACL连接=${peer.connections}(对端发起${peer.remoteConnections}/本机发起${peer.localConnections}) 配对=$pairings"
            if (peer.l2cap.isNotEmpty()) {
                lines +=
                    "peer=${peer.index} L2CAP " +
                    peer.l2cap.entries.joinToString(" ") { (key, counts) ->
                        "${if (key.first == Initiator.REMOTE) "对端发起" else "本机发起"}:${psm(key.second)}=${counts[1]}/${counts[0]}"
                    }
            }
            for ((index, query) in peer.queries.withIndex()) {
                if (printed >= MAX_QUERIES) break
                printed++
                lines += "peer=${peer.index} ${direction(query.initiator)}SDP#${index + 1} ${time(query.startedAt)} ${query(query)}"
            }
        }
        val totalQueries = result.peers.sumOf { it.queries.size }
        if (totalQueries > printed) lines += "SDP 查询共 $totalQueries 条，只列出前 $printed 条"
        lines += conclusions(result)
        return lines
    }

    /** 只陈述抓包里能看到的事实，不推断车型或手机兼容性。 */
    fun conclusions(result: BtSnoopAnalyzer.Result): List<String> {
        val lines = ArrayList<String>()
        val last = result.eirWrites.lastOrNull()
        if (last != null) {
            val carPlay = last.eir.uuid128.any(BtUuids::isCarPlay)
            lines +=
                if (carPlay) {
                    "结论 EIR：最后一次写入含 CarPlay UUID"
                } else if (!last.eir.uuid128Complete) {
                    "结论 EIR：最后一次写入不含 CarPlay，且 128 位 UUID 列表标记为不完整（空间或槽位不足）"
                } else {
                    "结论 EIR：最后一次写入不含 CarPlay（当时 mcarplay 可能未在连接中）"
                }
        }
        for (peer in result.peers) {
            val remote = peer.queries.filter { it.initiator == Initiator.REMOTE }
            if (remote.isEmpty()) {
                if (peer.connections > 0) lines += "结论 peer=${peer.index}：抓包期间对端没有向本机发起 SDP 查询"
                continue
            }
            val sawCarPlay = remote.filter { q -> q.records.any { r -> r.allUuids.any(BtUuids::isCarPlay) } }
            val abandoned = remote.filter { it.status == QueryStatus.ABANDONED }
            when {
                sawCarPlay.isNotEmpty() ->
                    lines += "结论 peer=${peer.index}：对端收到过含 CarPlay 记录的 SDP 回复（${sawCarPlay.size} 次）——手机侧发布已送达"
                abandoned.isNotEmpty() -> {
                    val worst = abandoned.maxByOrNull { it.attributeBytes }!!
                    lines +=
                        "结论 peer=${peer.index}：对端在续传未完成时停止（收到 ${worst.attributeBytes} 字节、${worst.records.size} 条记录），" +
                        "CarPlay 记录没有送达——符合对端缓冲截断"
                }
                remote.any { it.status == QueryStatus.COMPLETE && it.pduId == BtSnoopAnalyzer.SDP_SERVICE_SEARCH_ATTRIBUTE_REQUEST } ->
                    lines += "结论 peer=${peer.index}：对端拿到了完整回复，但里面没有 CarPlay 记录（查询时 mcarplay 可能未注册）"
                else ->
                    lines += "结论 peer=${peer.index}：对端查询了 ${remote.size} 次，回复中没有 CarPlay 记录"
            }
            val patterns =
                remote
                    .map { q -> q.pattern }
                    .filter { it.isNotEmpty() }
                    .distinct()
            if (patterns.isNotEmpty()) {
                lines +=
                    "结论 peer=${peer.index}：对端查询模式 " +
                    patterns.joinToString(" ") { p -> "[" + p.joinToString(",") { BtUuids.label(it) } + "]" }
            }
        }
        return lines
    }

    private fun query(query: BtSnoopAnalyzer.SdpQuery): String {
        val parts = ArrayList<String>()
        parts +=
            when (query.pduId) {
                BtSnoopAnalyzer.SDP_SERVICE_SEARCH_REQUEST -> "ServiceSearch"
                BtSnoopAnalyzer.SDP_SERVICE_ATTRIBUTE_REQUEST -> "ServiceAttribute"
                else -> "ServiceSearchAttribute"
            }
        if (query.pattern.isNotEmpty()) parts += "模式=[" + query.pattern.joinToString(",") { BtUuids.label(it) } + "]"
        query.requestedHandle?.let { parts += "句柄=0x%X".format(it) }
        query.maxBytes?.let { parts += "上限=$it" }
        parts += "回复=${query.responses} 续传=${query.continuationRequests}"
        parts += "状态=" + status(query)
        if (query.pduId == BtSnoopAnalyzer.SDP_SERVICE_SEARCH_REQUEST) {
            parts += "句柄数=${query.handles.size}"
        } else {
            parts += "属性字节=${query.attributeBytes} 记录=${query.records.size}" +
                if (query.records.isNotEmpty()) "：" + records(query.records) else ""
        }
        return parts.joinToString(" ")
    }

    private fun records(records: List<SdpRecordSummary>): String {
        var offset = 0
        return records
            .mapIndexed { index, record ->
                val start = offset
                offset += record.bytes
                val classes = record.serviceClasses.ifEmpty { record.allUuids.filter { BtUuids.isCarPlay(it) } }
                val name = classes.firstOrNull()?.let { BtUuids.label(it) } ?: "?"
                val marks =
                    buildList {
                        if (record.allUuids.any(BtUuids::isCarPlay) ||
                            record.allUuids.contains(BtUuids.IAP2_PHONE) ||
                            record.allUuids.contains(BtUuids.IAP2_ACCESSORY)
                        ) {
                            add("#${index + 1}")
                            add("偏移$start")
                            record.rfcommChannel?.let { add("RFCOMM$it") }
                        }
                        if (!record.complete) add("不完整")
                    }
                if (marks.isEmpty()) name else "$name(${marks.joinToString(" ")})"
            }.joinToString(",")
    }

    private fun status(query: BtSnoopAnalyzer.SdpQuery): String =
        when (query.status) {
            QueryStatus.COMPLETE -> "完成"
            QueryStatus.ABANDONED -> "请求方中止续传"
            QueryStatus.ERROR -> "错误" + (query.errorCode?.let { "0x%04X".format(it) } ?: "")
            QueryStatus.UNANSWERED -> "无回复"
            QueryStatus.OPEN -> "抓包结束时未完成"
        }

    private fun eir(eir: EirSummary): String {
        val uuid128 = eir.uuid128.joinToString(",") { BtUuids.label(it) }
        return "已用 ${eir.usedBytes}/240 字节 名称=" +
            (eir.nameBytes?.let { "$it 字节(${if (eir.nameComplete) "完整" else "缩短"})" } ?: "无") +
            " 16位UUID=${eir.uuid16Count}(${complete(eir.uuid16Complete)})" +
            " 128位UUID=${eir.uuid128.size}(${complete(eir.uuid128Complete)})[$uuid128]" +
            " CarPlay=${yes(eir.uuid128.any(BtUuids::isCarPlay))} iAP2(手机)=${yes(eir.uuid128.contains(BtUuids.IAP2_PHONE))}"
    }

    /** 可被发现窗口内是否有过含 CarPlay 的 EIR；窗口开始前没有任何 EIR 写入时为 null。 */
    private fun eirDuring(
        result: BtSnoopAnalyzer.Result,
        start: Long,
        end: Long?,
    ): Boolean? {
        val before = result.eirWrites.lastOrNull { it.at <= start }
        val within = result.eirWrites.filter { it.at > start && (end == null || it.at <= end) }
        val candidates = listOfNotNull(before) + within
        if (candidates.isEmpty()) return null
        return candidates.any { write -> write.eir.uuid128.any(BtUuids::isCarPlay) }
    }

    private fun direction(initiator: Initiator): String =
        when (initiator) {
            Initiator.REMOTE -> "对端查本机"
            Initiator.LOCAL -> "本机查对端"
            Initiator.UNKNOWN -> "未知方向"
        }

    private fun psm(value: Int): String =
        when (value) {
            BtSnoopAnalyzer.PSM_SDP -> "SDP"
            BtSnoopAnalyzer.PSM_RFCOMM -> "RFCOMM"
            0x0017 -> "AVCTP"
            0x0019 -> "AVDTP"
            0x001b -> "AVCTP浏览"
            else -> "PSM0x%04X".format(value)
        }

    private fun cod(value: Int): String {
        val label =
            when {
                value and 0x1ffc == 0x0420 -> "车载"
                (value ushr 8) and 0x1f == 0x02 -> "手机"
                (value ushr 8) and 0x1f == 0x04 -> "音视频"
                (value ushr 8) and 0x1f == 0x01 -> "电脑"
                else -> null
            }
        return "0x%06X".format(value) + (label?.let { "($it)" } ?: "")
    }

    private fun yes(value: Boolean): String = if (value) "是" else "否"

    private fun complete(value: Boolean): String = if (value) "完整" else "不完整"
}
