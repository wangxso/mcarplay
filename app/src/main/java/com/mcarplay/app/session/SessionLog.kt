package com.mcarplay.app.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 进程级日志缓冲：负责 NUL 清洗、logcat/文件双写、时间戳格式化、有界内存缓冲与增量监听分发。
 *
 * 线程安全与锁边界：
 *  - [logcat] 与 [fileSink] 在锁外执行，避免 I/O 阻塞缓冲读写；
 *  - [SimpleDateFormat] 与 [StringBuilder] 统一由私有 [lock] 串行化；
 *  - [listener] 在释放 [lock] 后触发，避免回调内读 [snapshot] 或调 [clear] 时发生重入死锁。
 */
internal class SessionLog(
    private val logcat: (String) -> Unit,
    private val fileSink: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    timeZone: TimeZone? = null,
    private val maxBufferLength: Int = MAX_BUFFER_LENGTH,
    private val trimToLength: Int = TRIM_TO_LENGTH,
) {
    private val lock = Any()
    private val buffer = StringBuilder()
    private val timestamp =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
            if (timeZone != null) this.timeZone = timeZone
        }

    /** 当前挂屏 Activity 的渲染回调（收到**本条新增日志行**，含时间戳前缀）；Activity onDestroy 时清空。 */
    @Volatile
    var listener: ((String) -> Unit)? = null

    /** Controls UI buffering/rendering only; bounded private file diagnostics remain enabled. */
    @Volatile
    var enabled: Boolean = true

    /** 追加一行日志：logcat + 文件 + 进程级缓冲 + 挂屏渲染（增量行直送）。任意线程可调。 */
    fun append(message: String) {
        // NUL 会写进日志文件：文本编辑器/文件管理器打不开（Read 工具也会判为二进制），
        // 用户反馈日志时等于白导。0x4301 的定长地址字段曾原样打印出多个 NUL
        // （2026-10-18 实测 216 个），这里替换成可见占位符——顺带让「字符串里有脏字节」
        // 在日志上直接可见，而不是隐形成 InetSocketAddress 的解析失败。
        val clean = if (message.indexOf('\u0000') >= 0) message.replace("\u0000", "␀") else message
        logcat(clean)
        fileSink(clean)
        if (!enabled) return
        val line =
            synchronized(lock) {
                val stamped = '[' + timestamp.format(Date(nowMs())) + "] " + clean + '\n'
                buffer.append(stamped)
                if (buffer.length > maxBufferLength) buffer.delete(0, buffer.length - trimToLength)
                stamped
            }
        // 增量行直送 UI：此前每行 toString 整个缓冲再 setText 全文，
        // 高频日志（触摸/帧）下是 UI 线程与 GC 的双重放大器
        listener?.invoke(line)
    }

    fun snapshot(): String = synchronized(lock) { buffer.toString() }

    /**
     * 清空进程级日志缓冲（UI 刷新是调用方的事：显式 `renderConsole(logText())`）。
     * 历史上这里靠 `logListener?.invoke("")` 通知 UI——那是「每行全量 setText」时代的
     * 语义；改为增量 appendConsole 后空串等于追加空行，UI 原地不动（「清除按钮点了
     * 没反应」根因，2026-10-16）。不要恢复该调用：空串与真实日志行无法区分。
     */
    fun clear() {
        synchronized(lock) { buffer.setLength(0) }
    }

    companion object {
        internal const val MAX_BUFFER_LENGTH = 40_000
        internal const val TRIM_TO_LENGTH = 20_000
    }
}
