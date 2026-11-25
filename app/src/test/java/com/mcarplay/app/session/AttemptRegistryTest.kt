package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** J 层单元测试：[AttemptRegistry] 覆盖附录 B-5 / B-7、空端点摘除、推流邀请取代与锁外清理。 */
class AttemptRegistryTest {
    private val obsA = ReceiverObservation(binding = "binding-a", epoch = 1, session = 1)
    private val obsB = ReceiverObservation(binding = "binding-a", epoch = 1, session = 2)
    private val obsOther = ReceiverObservation(binding = "binding-other", epoch = 1, session = 9)

    @Test
    fun b5LinkCloseCancelsMatchingObservationAndNextEndpointNeverRevivesOldScope() {
        val registry = AttemptRegistry()
        val scopeA = RealAttemptScope("attempt-a", obsA)
        registry.activate(scopeA)

        assertTrue(registry.cancelIfObservation(obsA, DiagnosticCode.BT_LINK_CLOSED))
        assertFalse("B-5：链路断开后旧轮次必须立刻失效", scopeA.isActive)
        assertTrue("B-5：取消后旧轮次仍保留 isCurrent 以释放交接", scopeA.isCurrent)
        assertEquals(DiagnosticCode.BT_LINK_CLOSED, scopeA.cancelReason)

        val scopeB = RealAttemptScope("attempt-b", obsB)
        registry.activate(scopeB)
        assertFalse("B-5：新一轮激活后旧轮次永不复活", scopeA.isActive)
        assertFalse("B-5：新一轮激活后旧轮次被取代", scopeA.isCurrent)
        assertTrue(scopeB.isActive)
        assertTrue(scopeB.isCurrent)
    }

    @Test
    fun b5NonMatchingObservationDoesNotCancelActiveScope() {
        val registry = AttemptRegistry()
        val scopeA = RealAttemptScope("attempt-a", obsA)
        registry.activate(scopeA)

        assertFalse(registry.cancelIfObservation(obsOther, DiagnosticCode.BT_LINK_CLOSED))
        assertFalse(registry.cancelIfObservation(null, DiagnosticCode.BT_LINK_CLOSED))
        assertTrue(scopeA.isActive)
        assertTrue(scopeA.isCurrent)
        assertNull(scopeA.cancelReason)
    }

    @Test
    fun b7UncancelledScopeAllowsLateRedeliveryWhileCancelActiveBlocksItBeforeSupersedeAll() {
        val registry = AttemptRegistry()
        val scopeA = RealAttemptScope("attempt-b7", obsA)
        registry.activate(scopeA)

        // 未取消：允许 finally 补投晚到 0x4301
        assertTrue(scopeA.isActive)
        assertTrue(scopeA.isCurrent)

        // 用户断开或链路断开先 cancelActive：禁止补投，但保持 isCurrent 供 abandon 收尾
        registry.cancelActive(DiagnosticCode.USER_STOP)
        assertFalse(scopeA.isActive)
        assertTrue(scopeA.isCurrent)
        assertEquals(DiagnosticCode.USER_STOP, scopeA.cancelReason)

        // 随后与 ReceiverDetectionRuntime 同点 supersedeAll：isCurrent 同步翻转
        registry.supersedeAll()
        assertFalse(scopeA.isCurrent)
        assertFalse(scopeA.isActive)
    }

    @Test
    fun emptyEndpointDetachLeavesScopeUncancelledAndAllowsNextActivation() {
        val registry = AttemptRegistry()
        val scopeEmpty = RealAttemptScope("attempt-empty", obsA)
        registry.activate(scopeEmpty)
        registry.detachIfActive(scopeEmpty)

        assertFalse(registry.cancelIfObservation(obsA, DiagnosticCode.BT_LINK_CLOSED))
        registry.cancelActive(DiagnosticCode.USER_STOP)
        assertTrue("空端点摘除后不得被后续取消误伤", scopeEmpty.isActive)
        assertTrue(scopeEmpty.isCurrent)

        val scopeNext = RealAttemptScope("attempt-next", obsB)
        registry.activate(scopeNext)
        assertTrue(scopeEmpty.isCurrent)
        assertTrue(scopeNext.isActive)
        assertTrue(scopeNext.isCurrent)
    }

    @Test
    fun detachIfActiveIgnoresNonActiveScope() {
        val registry = AttemptRegistry()
        val scopeA = RealAttemptScope("attempt-a", obsA)
        val scopeB = RealAttemptScope("attempt-b", obsB)
        registry.activate(scopeA)
        registry.activate(scopeB)

        registry.detachIfActive(scopeA)
        assertTrue(registry.cancelIfObservation(obsB, DiagnosticCode.BT_LINK_CLOSED))
        assertFalse(scopeB.isActive)
    }

    @Test
    fun supersedeClientCancelsAndSupersedesBoundScopeAndClearsMapping() {
        val registry = AttemptRegistry()
        val scopeA = RealAttemptScope("attempt-stream", obsA)
        val clientA = Any()
        registry.activate(scopeA)
        registry.bindClient(clientA, scopeA)
        assertSame(scopeA, registry.scopeForClient(clientA))

        registry.supersedeClient(clientA, DiagnosticCode.SUPERSEDED)
        assertEquals(DiagnosticCode.SUPERSEDED, scopeA.cancelReason)
        assertFalse(scopeA.isActive)
        assertFalse(scopeA.isCurrent)
        assertNull(registry.scopeForClient(clientA))
    }

    @Test
    fun bindClientUsesObjectIdentityAndSupportsTwoClientsInSameRound() {
        data class EqualKey(
            val value: String,
        )

        val registry = AttemptRegistry()
        val scopeA = RealAttemptScope("attempt-dual-client", obsA)
        val client1 = EqualKey("same")
        val client2 = EqualKey("same")
        registry.activate(scopeA)
        registry.bindClient(client1, scopeA)
        assertSame(scopeA, registry.scopeForClient(client1))
        assertNull("client 映射必须按对象身份（===）索引，不能按 equals 冲突", registry.scopeForClient(client2))

        // 同轮 BT 退役 hack 重建第二个 client
        registry.bindClient(client2, scopeA)
        assertSame(scopeA, registry.scopeForClient(client2))

        registry.supersedeClient(client2, DiagnosticCode.SUPERSEDED)
        assertNull(registry.scopeForClient(client1))
        assertNull(registry.scopeForClient(client2))
        assertFalse(scopeA.isCurrent)
    }

    @Test
    fun activatingNewScopeSupersedesPreviousWithoutCancellingNewRound() {
        val registry = AttemptRegistry()
        val scopeA = RealAttemptScope("attempt-prev", obsA)
        val clientA = Any()
        registry.activate(scopeA)
        registry.bindClient(clientA, scopeA)

        val scopeB = RealAttemptScope("attempt-curr", obsB)
        registry.activate(scopeB)
        assertFalse(scopeA.isCurrent)
        assertFalse(scopeA.isActive)
        assertNull(registry.scopeForClient(clientA))
        assertTrue("激活新一轮不得误取消新 scope（防 retireSession 自杀）", scopeB.isCurrent)
        assertTrue(scopeB.isActive)
    }

    @Test
    fun reentrantRegistryAccessInsideOwnedCloseDoesNotDeadlock() {
        val registry = AttemptRegistry()
        val scopeA = RealAttemptScope("attempt-reentrant", obsA)
        val clientA = Any()
        val observedClientScope = AtomicReference<RealAttemptScope?>(scopeA)
        val closeRan = AtomicBoolean(false)

        registry.activate(scopeA)
        registry.bindClient(clientA, scopeA)
        scopeA.own(
            AutoCloseable {
                // 若 registry 持锁调用 supersede()，此处跨线程或重入读锁将暴露锁序问题。
                observedClientScope.set(registry.scopeForClient(clientA))
                registry.cancelActive(DiagnosticCode.USER_STOP)
                closeRan.set(true)
            },
        )

        registry.supersedeAll()
        assertTrue(closeRan.get())
        assertNull("锁外执行 supersede() 时映射必须已清空", observedClientScope.get())
    }
}
