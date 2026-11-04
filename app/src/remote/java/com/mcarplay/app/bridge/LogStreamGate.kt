package com.mcarplay.app.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * 日志流闸门：回补与实时冲刷的唯一出口（泛型 [C] = 连接，测试可替身）。
 *
 * - 发送锁内完成「查账→发送→记账」，同一进程内一个 seq 至多成功发出一次；
 *   [begin] 在锁内先回补再登记连接，实时行不会插到回补之前。
 * - [end] **不取发送锁**（PR #30 评审）：冲刷线程可能正持锁阻塞在死连接的
 *   socket 写入上，断线回调若等这把锁，读线程就无法返回去关闭 socket，写入
 *   永远解不开、重连循环也回不来。撤销只是原子地清空登记，持锁中的写入由
 *   socket 关闭/写超时（[WsClient]）解除。
 */
class LogStreamGate<C : Any>(
    private val ledger: LogSeqLedger,
    private val sendText: (C, String) -> Boolean,
    private val chunkSize: Int = 200,
) {
    private val sendLock = Any()
    private val stream = AtomicReference<C?>(null)

    /** 当前承载日志流的连接（null = 未配对/已断开）。 */
    val current: C? get() = stream.get()

    /** welcome 通过：回补 [snapshot] 中未发过的行，然后把 [client] 登记为日志流。 */
    fun begin(
        client: C,
        snapshot: () -> List<Pair<Long, String>>,
    ) {
        synchronized(sendLock) {
            replayLocked(client, snapshot())
            stream.set(client)
        }
    }

    /** 撤销 [client] 的日志流登记（只撤自己的；无锁、不阻塞）。 */
    fun end(client: C) {
        stream.compareAndSet(client, null)
    }

    /** 按服务端请求回补（只发未发过的行）。 */
    fun replay(
        client: C,
        snapshot: () -> List<Pair<Long, String>>,
    ) {
        synchronized(sendLock) { replayLocked(client, snapshot()) }
    }

    /**
     * 冲刷实时行。日志流未就绪时直接丢弃：这些行仍在环形缓冲里，下次 welcome
     * 回补会补发未发过的部分。返回成功发出的行数。
     */
    fun flush(entries: List<Pair<Long, String>>): Int {
        if (entries.isEmpty()) return 0
        synchronized(sendLock) {
            val client = stream.get() ?: return 0
            return sendLocked(client, ledger.unsent(entries) { it.first })
        }
    }

    /** 无可发内容时仍发一个空 batch（服务端据此确认回补结束）。 */
    private fun replayLocked(
        client: C,
        entries: List<Pair<Long, String>>,
    ) {
        if (sendLocked(client, ledger.unsent(entries) { it.first }) == 0) {
            sendText(client, batchJson(emptyList()))
        }
    }

    /**
     * 分片发送（单条 JSON 控制在 ~256KB 内，服务端 ws 消息上限友好），每片发送成功
     * 才记账；某片失败即停——剩下的留给下次 welcome 回补。
     */
    private fun sendLocked(
        client: C,
        entries: List<Pair<Long, String>>,
    ): Int {
        var sent = 0
        for (chunk in entries.chunked(chunkSize)) {
            if (!sendText(client, batchJson(chunk))) break
            chunk.forEach { ledger.markSent(it.first) }
            sent += chunk.size
        }
        return sent
    }

    private fun batchJson(chunk: List<Pair<Long, String>>): String {
        val lines = JSONArray()
        chunk.forEach { (seq, line) -> lines.put(JSONObject().put("seq", seq).put("text", line)) }
        return JSONObject().put("t", "log.batch").put("lines", lines).toString()
    }
}
