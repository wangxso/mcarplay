package com.mcarplay.app.bt

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale

/** 固定原因摘要不含异常原文；remote/debug 的原始异常由明文诊断策略另行记录。 */
internal object BluetoothFailure {
    private val readRet = Regex("(?i)read ret\\s*:\\s*(-?\\d{1,6})\\b")
    private val status = Regex("(?i)error status\\s*:\\s*(-?\\d{1,5})\\b")
    private val errno =
        Regex("\\b(EACCES|EPERM|ECONNREFUSED|ETIMEDOUT|ECONNRESET|ECONNABORTED|ENETDOWN|ENETUNREACH|EHOSTUNREACH|EBADF|EIO)\\b")

    fun describe(error: Throwable): String {
        val causes = causes(error)
        val messages = messages(causes)
        return buildString {
            append("error=${kind(error)} reason=${reason(error)}")
            readRet
                .find(messages)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.let { append(" readRet=$it") }
            status
                .find(messages)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.let { append(" status=$it") }
            errno.find(messages)?.value?.let { append(" errno=$it") }
            if (causes.size > 1) append(" causes=${causes.drop(1).joinToString(",", transform = ::kind)}")
        }
    }

    /** 固定原因（PERMISSION / READ_FAILED / …），供连接策略判断，不含异常原文。 */
    fun reason(error: Throwable): String {
        val causes = causes(error)
        val messages = messages(causes)
        val lower = messages.lowercase(Locale.ROOT)
        return when {
            causes.any { it is SecurityException } -> "PERMISSION"
            "service discovery failed" in lower || "unable to start service discovery" in lower -> "SDP_FAILED"
            // Android 的这句同时包含 closed / timeout，不能把它当作确定的超时或对端拒绝。
            "read failed" in lower -> "READ_FAILED"
            "connection refused" in lower || "ECONNREFUSED" in messages -> "CONNECTION_REFUSED"
            causes.any { it is SocketTimeoutException } || "connection timed out" in lower || "ETIMEDOUT" in messages -> "TIMED_OUT"
            "connection reset" in lower || "ECONNRESET" in messages -> "CONNECTION_RESET"
            "connection abort" in lower || "ECONNABORTED" in messages -> "CONNECTION_ABORTED"
            "socket closed" in lower || "socket is closed" in lower -> "SOCKET_CLOSED"
            "bluetooth is not enabled" in lower || "bluetooth is off" in lower -> "BLUETOOTH_OFF"
            else -> "OTHER"
        }
    }

    private fun causes(error: Throwable): List<Throwable> = generateSequence(error) { it.cause }.take(4).toList()

    private fun messages(causes: List<Throwable>): String = causes.joinToString(" ") { it.message.orEmpty().take(1024) }

    private fun kind(error: Throwable): String =
        when (error) {
            is SocketTimeoutException -> "SocketTimeoutException"
            is SocketException -> "SocketException"
            is InterruptedIOException -> "InterruptedIOException"
            is IOException -> "IOException"
            is SecurityException -> "SecurityException"
            is IllegalStateException -> "IllegalStateException"
            is IllegalArgumentException -> "IllegalArgumentException"
            else -> if (error.javaClass.name == "android.system.ErrnoException") "ErrnoException" else "Other"
        }
}
