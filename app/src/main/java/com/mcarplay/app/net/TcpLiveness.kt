package com.mcarplay.app.net

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.Socket

/**
 * CarPlay 长连接的 TCP 对端健康检查（对照 DiPlay `TcpLiveness`，2026-10-21 版本）。
 *
 * 为什么必须显式设置：Android 内核默认 keepalive 是 **两小时**才开始探测。车机熄火 /
 * 手机走出热点范围时链路常**不发 RST**（Wi-Fi 直接消失），于是控制通道的读线程会挂在
 * `read()` 上直到系统超时——画面冻结、看门狗不触发，用户只能手动断开重连。
 *
 * 取值与 DiPlay 一致：空闲 10s 起探测、每 3s 一次、连续 3 次无响应判定对端死亡
 * （合计 ~19s），再用 `TCP_USER_TIMEOUT` 给「已发出未确认的数据」设 20s 上限。
 * 19s 这个量级刻意大于车机的 15s AirPlay 看门狗，避免我们比车机更早判死正常会话。
 *
 * **API 差异（踩过）**：`ParcelFileDescriptor.fromSocket` 在 API 29 之前返回的是
 * socket **自身** fd 的包装，close/finalize 会把原 socket 一起关掉。所以 29+ 才用它
 * 拿副本；26~28 改为反射 `Socket.getFileDescriptor$()` 直接对原 fd 设置（该方法在
 * Android 9 属 light greylist，任意 targetSdk 可调用）；API 25 及以下没有公开的
 * `setsockoptInt`，只记一行日志。
 */
object TcpLiveness {
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6

    /** 与 DiPlay 相同的默认值（秒 / 次数 / 毫秒）。 */
    private const val KEEP_IDLE_SECONDS = 10
    private const val KEEP_INTERVAL_SECONDS = 3
    private const val KEEP_COUNT = 3
    private const val USER_TIMEOUT_MILLIS = 20_000

    /**
     * 为长连接 socket 打开并调优 keepalive。失败只记日志、不抛——车机可能不需要它，
     * 但绝不能因为设置失败而挡住连接。
     */
    fun configure(
        socket: Socket,
        label: String,
        log: (String) -> Unit,
    ) {
        runCatching { socket.keepAlive = true }
        if (Build.VERSION.SDK_INT < 26) {
            log("TCP: $label 无法调优 keepalive（API ${Build.VERSION.SDK_INT} < 26）")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ParcelFileDescriptor.fromSocket(socket).use { duplicate ->
                    tune(duplicate.fileDescriptor)
                }
            } else {
                val fd =
                    socket.javaClass
                        .getMethod("getFileDescriptor\$")
                        .invoke(socket) as FileDescriptor
                tune(fd)
            }
            log(
                "TCP: $label keepalive 已启用" +
                    "（idle=${KEEP_IDLE_SECONDS}s interval=${KEEP_INTERVAL_SECONDS}s count=$KEEP_COUNT" +
                    " userTimeout=${USER_TIMEOUT_MILLIS}ms）",
            )
        } catch (error: Exception) {
            log("TCP: $label keepalive 调优失败（${error.javaClass.simpleName}: ${error.message}）")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun tune(fd: FileDescriptor) {
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, KEEP_IDLE_SECONDS)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, KEEP_INTERVAL_SECONDS)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, KEEP_COUNT)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
    }
}
