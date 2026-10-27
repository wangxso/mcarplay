package com.mcarplay.app

import com.mcarplay.app.airplay.pairingRetryBackoffMs
import com.mcarplay.app.compat.ReceiverCompatibility
import com.mcarplay.app.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对失败兜底路径决策测试：场景全部来自真机 sysdiagnose 对照
 * （2026-10-21/17，docs/PROTOCOL-NOTES.md §13）。
 */
class PairingFallbackTest {
    @Test
    fun `配对成功则无兜底`() {
        val f =
            pairingFallback(
                true,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `奥迪首连 403 - verify 段被拒 - 禁断 BT 且禁明文 - 原地退避等重邀`() {
        // 真机抓包：pair-verify 通过后认证段 403（对客户端表现为 verify/认证 IOException）
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse("403=车机未就绪，断 BT 会错过 18~20s 后的自动重邀", f.useBtRetireHack)
        assertFalse("403=信任已建立，明文必被拒", f.usePlainSession)
    }

    @Test
    fun `pair-setup 403 - 走快路径后仍失败 - 同样禁断 BT`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = true,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `DiPlay 断链形态 - 无 403 - 保留二段 hack`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
                policy =
                    ReceiverCompatibility.resolve(
                        ReceiverProfile.Signals(
                            name = "DiPlay",
                            firmwareVersion = "0.1.0",
                            externalAccessoryProtocols = setOf("com.shilapi.xcertplay"),
                        ),
                    ),
            )
        assertTrue(f.useBtRetireHack)
        assertFalse("断蓝牙重试与明文降级是两项独立决策", f.usePlainSession)
    }

    @Test
    fun `pre-HomeKit 老车机 - 不支持配对 - 明文兜底`() {
        val f =
            pairingFallback(
                false,
                noPairing = true,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = true,
            )
        assertFalse(f.useBtRetireHack)
        assertTrue(f.usePlainSession)
    }

    @Test
    fun `端点未解析 - RTSP 未连上 - 两条兜底都不启用`() {
        val f =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = false,
            )
        assertFalse(f.useBtRetireHack)
        assertFalse(f.usePlainSession)
    }

    @Test
    fun `明文兜底开关 - 默认关时普通失败不降级`() {
        val off =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
            )
        assertFalse(off.usePlainSession)
    }

    @Test
    fun `明文兜底开关 - 打开后已连上 RTSP 的普通失败降级明文`() {
        val on =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = true,
                pairingUnsupported = false,
                allowPlainAfterFailure = true,
            )
        assertTrue(on.usePlainSession)
        assertFalse("开关不影响断蓝牙重试", on.useBtRetireHack)
    }

    @Test
    fun `明文兜底开关 - 打开后 403 与未连上 RTSP 仍不降级`() {
        val forbidden =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = true,
                attemptedPairing = true,
                pairingUnsupported = false,
                allowPlainAfterFailure = true,
            )
        assertFalse(forbidden.usePlainSession)
        val unreached =
            pairingFallback(
                false,
                noPairing = false,
                pairSetupRejected = false,
                pairingForbidden = false,
                attemptedPairing = false,
                pairingUnsupported = false,
                allowPlainAfterFailure = true,
            )
        assertFalse(unreached.usePlainSession)
    }

    @Test
    fun `重试退避 - 403 长 - 其余短`() {
        assertEquals(5_000L, pairingRetryBackoffMs("RTSP/1.0 403 Forbidden"))
        assertEquals(5_000L, pairingRetryBackoffMs("HTTP/1.1 403"))
        assertEquals(500L, pairingRetryBackoffMs("RTSP/1.0 500 Internal"))
        assertEquals(500L, pairingRetryBackoffMs("Connection reset"))
    }
}
