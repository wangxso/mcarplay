package com.mcarplay.app.session

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.diagnostics.DiagnosticCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** J 层单元测试：[DualCheckAttemptScope] 的权威返回、日志契约与去重逻辑。 */
class DualCheckAttemptScopeTest {
    private val obs = ReceiverObservation(binding = "binding-dual", epoch = 1, session = 2)

    private class StubLegacyScope(
        override val id: String,
        override val observation: ReceiverObservation?,
        var currentValue: Boolean,
        var activeValue: Boolean,
    ) : AttemptScope {
        override val isCurrent: Boolean get() = currentValue
        override val isActive: Boolean get() = activeValue
        override val cancelReason: DiagnosticCode? = null

        override fun cancel(reason: DiagnosticCode) = Unit

        override fun checkActive() = Unit

        override fun <T : AutoCloseable> own(resource: T): T = resource

        override fun await(
            latch: CountDownLatch,
            timeoutMs: Long,
        ): Boolean = false

        override fun sleep(ms: Long): Boolean = false
    }

    @Test
    fun truthTableAlwaysReturnsLegacyAndOnlyLogsOnMismatch() {
        val realStates =
            listOf(
                Triple("active", true, true),
                Triple("cancelled", true, false),
                Triple("superseded", false, false),
            )
        for (legacyCurrent in listOf(false, true)) {
            for (legacyActive in listOf(false, true)) {
                for ((realState, expectedRealCurrent, expectedRealActive) in realStates) {
                    for (obsNull in listOf(false, true)) {
                        val observation = if (obsNull) null else obs
                        val legacy =
                            StubLegacyScope(
                                id = "a-$realState-$obsNull",
                                observation = observation,
                                currentValue = legacyCurrent,
                                activeValue = legacyActive,
                            )
                        val real = RealAttemptScope(id = legacy.id, observation = observation)
                        when (realState) {
                            "cancelled" -> real.cancel(DiagnosticCode.BT_LINK_CLOSED)
                            "superseded" -> real.supersede()
                        }
                        val logs = mutableListOf<String>()
                        val dual = DualCheckAttemptScope(legacy = legacy, real = real, onMismatch = logs::add)

                        val case =
                            "legacyCurrent=$legacyCurrent legacyActive=$legacyActive " +
                                "realState=$realState obsNull=$obsNull"
                        assertEquals(case, legacyCurrent, dual.isCurrent)
                        assertEquals(case, legacyActive, dual.isActive)

                        val expectedLogs =
                            (if (legacyCurrent != expectedRealCurrent) 1 else 0) +
                                (if (legacyActive != expectedRealActive) 1 else 0)
                        assertEquals(case, expectedLogs, logs.size)
                    }
                }
            }
        }
    }

    @Test
    fun mismatchLogFormatMatchesExactContract() {
        val legacy = StubLegacyScope(id = "attempt-42", observation = obs, currentValue = true, activeValue = true)
        val real = RealAttemptScope(id = "attempt-42", observation = obs)
        real.cancel(DiagnosticCode.BT_LINK_CLOSED)
        val logs = mutableListOf<String>()
        val dual = DualCheckAttemptScope(legacy = legacy, real = real, onMismatch = logs::add)

        assertTrue(dual.isActive)
        assertEquals(
            listOf(
                "SESSION: SCOPE_MISMATCH attempt=attempt-42 prop=isActive legacy=true real=false " +
                    "cancelReason=BT_LINK_CLOSED superseded=false obsNull=false",
            ),
            logs,
        )

        val nullLegacy = StubLegacyScope(id = "attempt-99", observation = null, currentValue = true, activeValue = false)
        val nullReal = RealAttemptScope(id = "attempt-99", observation = null)
        nullReal.supersede()
        val nullLogs = mutableListOf<String>()
        val nullDual = DualCheckAttemptScope(legacy = nullLegacy, real = nullReal, onMismatch = nullLogs::add)

        assertTrue(nullDual.isCurrent)
        assertEquals(
            listOf(
                "SESSION: SCOPE_MISMATCH attempt=attempt-99 prop=isCurrent legacy=true real=false " +
                    "cancelReason=none superseded=true obsNull=true",
            ),
            nullLogs,
        )
    }

    @Test
    fun repeatedIdenticalMismatchIsLoggedOnlyOnce() {
        val legacy = StubLegacyScope(id = "attempt-dedup", observation = obs, currentValue = true, activeValue = true)
        val real = RealAttemptScope(id = "attempt-dedup", observation = obs)
        real.cancel(DiagnosticCode.USER_STOP)
        val logs = mutableListOf<String>()
        val dual = DualCheckAttemptScope(legacy = legacy, real = real, onMismatch = logs::add)

        repeat(100) {
            assertTrue(dual.isActive)
        }
        assertEquals("同一轮相同分歧组合连续读取 100 次只能记 1 条", 1, logs.size)
    }

    @Test
    fun stateChangeProducesNewDedupKeyAndLogsAgain() {
        val legacy = StubLegacyScope(id = "attempt-rekey", observation = obs, currentValue = true, activeValue = true)
        val real = RealAttemptScope(id = "attempt-rekey", observation = obs)
        val logs = mutableListOf<String>()
        val dual = DualCheckAttemptScope(legacy = legacy, real = real, onMismatch = logs::add)

        real.cancel(DiagnosticCode.BT_LINK_CLOSED)
        repeat(10) { assertTrue(dual.isActive) }
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("superseded=false"))

        real.supersede()
        repeat(10) { assertTrue(dual.isActive) }
        assertEquals("superseded 翻转后产生新去重键，应再记 1 条", 2, logs.size)
        assertTrue(logs[1].contains("superseded=true"))
    }

    @Test
    fun matchingLifecycleTransitionsProduceZeroMismatchLogs() {
        var cancelled = false
        var owns = true
        val legacy = LegacyAttemptScope("attempt-match", obs, cancelled = { cancelled }, owns = { owns })
        val real = RealAttemptScope("attempt-match", obs)
        val logs = mutableListOf<String>()
        val dual = DualCheckAttemptScope(legacy = legacy, real = real, onMismatch = logs::add)

        assertTrue(dual.isCurrent)
        assertTrue(dual.isActive)

        cancelled = true
        real.cancel(DiagnosticCode.USER_STOP)
        assertTrue(dual.isCurrent)
        assertFalse(dual.isActive)

        owns = false
        real.supersede()
        assertFalse(dual.isCurrent)
        assertFalse(dual.isActive)
        assertTrue("新旧一致时不得产生任何分歧日志", logs.isEmpty())
    }

    @Test
    fun activityGuardMembersReportBothDivergenceDirections() {
        data class Case(
            val name: String,
            val legacyActive: Boolean,
            val realActive: Boolean,
        )

        val cases =
            listOf(
                Case("legacyActive-realInactive", legacyActive = true, realActive = false),
                Case("legacyInactive-realActive", legacyActive = false, realActive = true),
            )
        for (case in cases) {
            for (member in listOf("checkActive", "own", "await", "sleep")) {
                val legacy =
                    StubLegacyScope(
                        id = "attempt-guard-${case.name}-$member",
                        observation = obs,
                        currentValue = true,
                        activeValue = case.legacyActive,
                    )
                val real = RealAttemptScope(id = legacy.id, observation = obs)
                if (!case.realActive) {
                    real.cancel(DiagnosticCode.BT_LINK_CLOSED)
                }
                val logs = mutableListOf<String>()
                val dual = DualCheckAttemptScope(legacy = legacy, real = real, onMismatch = logs::add)

                try {
                    when (member) {
                        "checkActive" -> dual.checkActive()
                        "own" -> dual.own(AutoCloseable {})
                        "await" -> dual.await(CountDownLatch(0), 10L)
                        "sleep" -> dual.sleep(0L)
                    }
                } catch (_: AttemptCancelled) {
                    // 执行侧仍是 real：real 不活跃时 checkActive 抛异常，但分歧必须先被记录。
                }

                val label = "${case.name} / $member"
                assertEquals("$label 必须记录 1 条分歧日志", 1, logs.size)
                assertTrue("$label 日志前缀错误：${logs[0]}", logs[0].startsWith("SESSION: SCOPE_MISMATCH "))
                assertTrue("$label 必须带成员名：${logs[0]}", logs[0].contains("prop=$member "))
                assertTrue(
                    "$label 两侧取值错误：${logs[0]}",
                    logs[0].contains("legacy=${case.legacyActive} real=${case.realActive}"),
                )
            }
        }
    }

    @Test
    fun activityGuardMembersStaySilentWhenBothSidesAgree() {
        val legacy = StubLegacyScope(id = "attempt-guard-match", observation = obs, currentValue = true, activeValue = true)
        val real = RealAttemptScope(id = "attempt-guard-match", observation = obs)
        val logs = mutableListOf<String>()
        val dual = DualCheckAttemptScope(legacy = legacy, real = real, onMismatch = logs::add)

        dual.checkActive()
        dual.own(AutoCloseable {})
        assertTrue(dual.await(CountDownLatch(0), 10L))
        assertTrue(dual.sleep(0L))
        assertTrue("新旧一致时四个守卫入口都不得记日志", logs.isEmpty())
    }

    @Test
    fun delegatesCancelOwnCheckActiveSleepAwaitAndCancelReasonToReal() {
        val legacy = StubLegacyScope(id = "attempt-del", observation = obs, currentValue = true, activeValue = true)
        val real = RealAttemptScope(id = "attempt-del", observation = obs)
        val dual = DualCheckAttemptScope(legacy = legacy, real = real, onMismatch = {})

        assertEquals("attempt-del", dual.id)
        assertSame(obs, dual.observation)
        assertNull(dual.cancelReason)
        assertTrue(dual.sleep(0L))
        assertTrue(dual.await(CountDownLatch(0), 10L))

        val closed = AtomicInteger(0)
        val resource = AutoCloseable { closed.incrementAndGet() }
        assertSame(resource, dual.own(resource))

        dual.cancel(DiagnosticCode.USER_STOP)
        assertEquals(DiagnosticCode.USER_STOP, dual.cancelReason)
        assertEquals(1, closed.get())
        assertFalse(dual.sleep(100L))
        assertFalse(dual.await(CountDownLatch(0), 10L))
        try {
            dual.checkActive()
            fail("checkActive 应委托 real 抛出 AttemptCancelled")
        } catch (ex: AttemptCancelled) {
            assertEquals(DiagnosticCode.USER_STOP, ex.reason)
        }
    }
}
