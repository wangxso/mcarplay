package com.mcarplay.app.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 帕萨特 2025（VW4211）2026-11-01 22:45–22:48 三种资源仲裁场景。 */
class ModeArbiterTest {
    private fun resource(
        id: Long,
        type: Long,
        priority: Long,
        constraint: Long,
    ) = mapOf(
        "resourceID" to id,
        "transferType" to type,
        "transferPriority" to priority,
        "takeConstraint" to constraint,
        "borrowConstraint" to 100L,
    )

    private fun entities(arbiter: ModeArbiter) = arbiter.resourcesPayload().associate { it["resourceID"] to it["entity"] }

    @Test
    fun niceToHaveInitialTakeLeavesScreenToPhone() {
        // 22:46 一轮：主屏 100/100，开场宣称持有生效（出画、触控 832 条）
        val arbiter = ModeArbiter()
        arbiter.applyInitial(mapOf("resources" to listOf(resource(1, 1, 100, 100), resource(2, 1, 100, 100))))
        assertTrue(arbiter.phoneOwnsScreen)
        assertEquals(mapOf(1L to 1L, 2L to 1L), entities(arbiter))
    }

    @Test
    fun userInitiatedInitialTakeIsReportedThenRequestUiTakesBack() {
        // 22:45 一轮：主屏 500/500 由车机持有；如实回 entity=2，用户点图标后拿回
        val arbiter = ModeArbiter()
        arbiter.applyInitial(mapOf("resources" to listOf(resource(1, 1, 500, 500), resource(2, 1, 100, 100))))
        assertFalse(arbiter.phoneOwnsScreen)
        assertEquals(mapOf(1L to 2L, 2L to 1L), entities(arbiter))
        assertFalse(arbiter.onUserRequestedUi().retake)
        assertTrue(arbiter.phoneOwnsScreen)
        assertEquals(1L, arbiter.resourcesPayload().first()["permanentEntity"])
    }

    @Test
    fun requestUiWhileAlreadyOwnedReleasesThenRetakes() {
        // 23:02:42 一轮：主屏 100/100 归手机，车机却停在原车界面持续要帧；点图标后重发同样的状态被无视
        val arbiter = ModeArbiter()
        arbiter.applyInitial(mapOf("resources" to listOf(resource(1, 1, 100, 100), resource(2, 1, 100, 100))))
        val request = arbiter.onUserRequestedUi()
        assertTrue(request.retake)
        // 让出那一条只改报文，主音频不动，记录的归属仍是手机
        assertEquals(
            mapOf(1L to 2L, 2L to 1L),
            arbiter.resourcesPayload(releaseScreen = true).associate { it["resourceID"] to it["entity"] },
        )
        assertEquals(2L, arbiter.resourcesPayload(releaseScreen = true).first()["permanentEntity"])
        assertTrue(arbiter.phoneOwnsScreen)
        assertEquals(mapOf(1L to 1L, 2L to 1L), entities(arbiter))
    }

    @Test
    fun carTakingScreenMidSessionIsAcknowledged() {
        // 22:47:45 用户回原车主界面：changeModes take 主屏 500/500 → 回 entity=2；再点图标 → entity=1
        val arbiter = ModeArbiter()
        arbiter.applyInitial(null)
        assertTrue(arbiter.phoneOwnsScreen)
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(1, 1, 500, 500))))
        assertFalse(arbiter.phoneOwnsScreen)
        assertEquals(1L, entities(arbiter)[2L])
        arbiter.onUserRequestedUi()
        assertTrue(arbiter.phoneOwnsScreen)
    }

    @Test
    fun neverConstraintIsNotOverriddenAndBorrowReturns() {
        val arbiter = ModeArbiter()
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(1, 1, 500, 1000))))
        assertFalse(arbiter.onUserRequestedUi().retake)
        assertFalse(arbiter.phoneOwnsScreen)
        // 借用（如倒车影像）结束后归还给原持有方
        val borrowed = ModeArbiter()
        borrowed.onChangeModes(mapOf("resources" to listOf(resource(1, 3, 500, 100))))
        assertFalse(borrowed.phoneOwnsScreen)
        borrowed.onChangeModes(mapOf("resources" to listOf(resource(1, 4, 500, 100))))
        assertTrue(borrowed.phoneOwnsScreen)
    }
}
