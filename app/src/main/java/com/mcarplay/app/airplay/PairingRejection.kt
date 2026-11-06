package com.mcarplay.app.airplay

import java.io.IOException

/** HomeKit 配对 TLV `Error`（0x07）取值；规范外的私有码归入 [OTHER]。 */
internal enum class PairingErrorCode(
    val raw: Int,
    val label: String,
) {
    UNKNOWN(0x01, "Unknown"),
    AUTHENTICATION(0x02, "Authentication"),
    BACKOFF(0x03, "Backoff"),
    MAX_PEERS(0x04, "MaxPeers"),
    MAX_TRIES(0x05, "MaxTries"),
    UNAVAILABLE(0x06, "Unavailable"),
    BUSY(0x07, "Busy"),
    OTHER(-1, "Other"),
    ;

    companion object {
        fun of(raw: Int): PairingErrorCode = entries.firstOrNull { it.raw == raw } ?: OTHER
    }
}

internal val PairingRejectedException.code: PairingErrorCode get() = PairingErrorCode.of(errorCode)

/** 配对被拒后的处置。 */
internal enum class PairingRejectionAction {
    /** 窗口内照常重试（暂时状态、未知码）。 */
    RETRY,

    /** 确定性拒绝：重试只会拿到同一个码，立即结束配对窗口。 */
    STOP,
}

/**
 * 按 TLV 错误码决定是否继续在配对窗口（默认 120 s）里重试。
 *
 * - MaxPeers / MaxTries：车机配对表已满或尝试次数用尽，只有车机侧操作或其计时器能解除；
 * - 本轮已保存的信任被 pair-verify 以 Authentication 拒绝（[trustInvalidated]），重新注册的
 *   pair-setup 又被 Authentication 拒绝：车机既不认这台手机，也不接受重新配对；
 * - 其余（Backoff / Busy / Unavailable / 单独的 Authentication / 私有码）：宁可多试，
 *   不把可能连得上的车机判死。
 */
internal fun pairingRejectionAction(
    rejection: PairingRejectedException,
    trustInvalidated: Boolean = false,
): PairingRejectionAction =
    when (rejection.code) {
        PairingErrorCode.MAX_PEERS, PairingErrorCode.MAX_TRIES -> PairingRejectionAction.STOP
        PairingErrorCode.AUTHENTICATION ->
            if (trustInvalidated && rejection.path == "/pair-setup") PairingRejectionAction.STOP else PairingRejectionAction.RETRY

        else -> PairingRejectionAction.RETRY
    }

/** 配对失败后的重试间隔：403 与 TLV Backoff 都表示车机要求放慢（5 s），其余快速轮询。 */
internal fun pairingRetryDelayMs(error: IOException): Long =
    if (error is PairingRejectedException && error.code == PairingErrorCode.BACKOFF) {
        BACKOFF_RETRY_DELAY_MS
    } else {
        pairingRetryBackoffMs(error.message.orEmpty())
    }

/** 车机确定性拒绝配对时给用户的处置提示。 */
internal fun pairingRejectionHint(code: PairingErrorCode): String =
    when (code) {
        PairingErrorCode.MAX_PEERS -> "车机已保存的配对设备已满：请在车机上删除不用的手机后重连"
        PairingErrorCode.MAX_TRIES -> "车机配对尝试次数已用尽：请重启车机侧 CarPlay（或熄火重启车机）后重连"
        else -> "车机不认本机的配对身份且拒绝重新配对：请在车机上删除本机的 CarPlay 记录后重连"
    }

private const val BACKOFF_RETRY_DELAY_MS = 5_000L
