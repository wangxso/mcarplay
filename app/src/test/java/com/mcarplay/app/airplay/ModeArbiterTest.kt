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

    @Test
    fun homeReturnKeepsRebuildPendingUntilStreamIsRestored() {
        val arbiter = ModeArbiter()
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(1, 1, 500, 500))))
        val first = arbiter.onUserRequestedUi()
        assertTrue(first.allowed)
        assertTrue(first.rebuildStream)
        assertFalse(first.retake)
        assertTrue(arbiter.onUserRequestedUi().rebuildStream)
        arbiter.onScreenStreamRestored()
        assertFalse(arbiter.onUserRequestedUi().rebuildStream)
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(1, 1, 500, 500))))
        assertTrue(arbiter.onUserRequestedUi().rebuildStream)
    }

    @Test
    fun initialUiRequestDoesNotRebuildStream() {
        val arbiter = ModeArbiter()
        arbiter.applyInitial(mapOf("resources" to listOf(resource(1, 1, 500, 500))))
        assertFalse(arbiter.onUserRequestedUi().rebuildStream)
    }

    @Test
    fun audioOwnershipChangesDoNotRebuildScreenStream() {
        val arbiter = ModeArbiter()
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(2, 1, 500, 500))))
        assertFalse(arbiter.onUserRequestedUi().rebuildStream)
    }

    @Test
    fun audioStreamStartTakesMainAudioBackWithoutTakingTheScreen() {
        // VW4131 报告：主屏归车机、主音频被车机借用后，音频 SETUP 三档全部 400。
        // 真 iPhone 在 Main audio setting up 前先报 mainAudio controller。
        val arbiter = ModeArbiter()
        arbiter.applyInitial(
            mapOf(
                "resources" to
                    listOf(
                        resource(1, 1, 500, 500),
                        resource(2, 3, 500, 100),
                    ),
            ),
        )
        assertEquals(mapOf(1L to 2L, 2L to 2L), entities(arbiter))

        val claim = arbiter.onAudioStreamStarting()

        assertTrue(claim.claimed)
        assertTrue(claim.note.contains("接管主音频"))
        assertEquals(mapOf(1L to 2L, 2L to 1L), entities(arbiter))
        assertEquals(1L, arbiter.resourcesPayload().first { it["resourceID"] == 2L }["permanentEntity"])
        assertFalse(arbiter.phoneOwnsScreen)
    }

    @Test
    fun audioStreamStartRespectsUnborrowAndTakeConstraints() {
        // 车机借用主音频且 unborrowConstraint=1000（如倒车提示音）时不抢占，保持车机归属。
        val borrowed = ModeArbiter()
        borrowed.applyInitial(
            mapOf(
                "resources" to
                    listOf(
                        resource(1, 1, 500, 500),
                        resource(2, 3, 500, 100) + ("unborrowConstraint" to 1000L),
                    ),
            ),
        )
        val refused = borrowed.onAudioStreamStarting()
        assertFalse(refused.claimed)
        assertEquals(mapOf(1L to 2L, 2L to 2L), entities(borrowed))

        // 车机以 takeConstraint=1000 持有主音频时同样不抢占。
        val taken = ModeArbiter()
        taken.applyInitial(mapOf("resources" to listOf(resource(2, 1, 500, 1000))))
        assertFalse(taken.onAudioStreamStarting().claimed)
        assertEquals(2L, entities(taken)[2L])
    }

    @Test
    fun refusedAudioClaimRetriesOnlyAfterTheHeadUnitReturnsTheResource() {
        // 借用高约束被拒：归还（unborrow）前不得重试，归还后只消费一次。
        val borrowed = ModeArbiter()
        borrowed.applyInitial(
            mapOf("resources" to listOf(resource(2, 3, 500, 100) + ("unborrowConstraint" to 1000L))),
        )
        assertFalse(borrowed.onAudioStreamStarting().claimed)
        assertFalse(borrowed.consumeAudioClaimRetry())
        borrowed.onChangeModes(mapOf("resources" to listOf(resource(2, 4, 500, 100))))
        assertTrue(borrowed.consumeAudioClaimRetry())
        assertFalse(borrowed.consumeAudioClaimRetry())
        assertTrue(borrowed.onAudioStreamStarting().claimed)

        // take 高约束同理：untake 后允许一次重试。
        val taken = ModeArbiter()
        taken.applyInitial(mapOf("resources" to listOf(resource(2, 1, 500, 1000))))
        assertFalse(taken.onAudioStreamStarting().claimed)
        taken.onChangeModes(mapOf("resources" to listOf(resource(2, 2, 500, 100))))
        assertTrue(taken.consumeAudioClaimRetry())

        // 正常接管成功时不留下待重试状态。
        val ready = ModeArbiter()
        assertTrue(ready.onAudioStreamStarting().claimed)
        assertFalse(ready.consumeAudioClaimRetry())
    }

    @Test
    fun userRequestRespectsBorrowAndHigherTakeConstraints() {
        for (constraint in listOf(501L, 1000L)) {
            val taken = ModeArbiter()
            taken.onChangeModes(mapOf("resources" to listOf(resource(1, 1, 500, constraint))))
            assertFalse(taken.onUserRequestedUi().allowed)
            assertFalse(taken.phoneOwnsScreen)
            val borrowed = ModeArbiter()
            val request = resource(1, 3, 500, 100) + ("borrowConstraint" to constraint)
            borrowed.onChangeModes(mapOf("resources" to listOf(request)))
            assertFalse(borrowed.onUserRequestedUi().allowed)
            assertFalse(borrowed.phoneOwnsScreen)
        }
    }

    @Test
    fun requestUiRespectsUnborrowWhenAccessoryAlreadyOwnsScreen() {
        // 车机永久持有主屏后再 BORROW（倒车影像）：约束必须看 unborrowConstraint，
        // 不能回退到旧的 takeConstraint=100 把倒车画面抢走。
        val arbiter = ModeArbiter()
        arbiter.applyInitial(mapOf("resources" to listOf(resource(1, 1, 500, 100))))
        assertFalse(arbiter.phoneOwnsScreen)
        arbiter.onChangeModes(
            mapOf("resources" to listOf(resource(1, 3, 500, 100) + ("unborrowConstraint" to 1000L))),
        )
        assertFalse(arbiter.onUserRequestedUi().allowed)
        assertFalse(arbiter.phoneOwnsScreen)
    }

    @Test
    fun audioClaimRespectsUnborrowWhenAccessoryAlreadyOwnsMainAudio() {
        val arbiter = ModeArbiter()
        arbiter.applyInitial(mapOf("resources" to listOf(resource(2, 1, 500, 100))))
        arbiter.onChangeModes(
            mapOf("resources" to listOf(resource(2, 3, 500, 100) + ("unborrowConstraint" to 1000L))),
        )
        assertFalse(arbiter.onAudioStreamStarting().claimed)
        assertTrue(arbiter.audioClaimAwaitingReturn)
        assertFalse(arbiter.consumeAudioClaimRetry())

        // UNBORROW 后主音频仍永久归车机，但 takeConstraint=100 已允许手机接管。
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(2, 4, 500, 100))))
        assertTrue(arbiter.consumeAudioClaimRetry())
        assertFalse(arbiter.audioClaimAwaitingReturn)
        assertTrue(arbiter.onAudioStreamStarting().claimed)
        assertTrue(arbiter.phoneOwnsAudio)
    }

    @Test
    fun describeReportsBorrowStateAndUnborrowConstraint() {
        val arbiter = ModeArbiter()
        arbiter.onChangeModes(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to 1,
                            "transferType" to 3,
                            "borrowConstraint" to 100,
                            "unborrowConstraint" to 1000,
                        ),
                    ),
            ),
        )
        assertTrue(arbiter.describe(), arbiter.describe().contains("主屏=车机借用(约束 1000)"))
        assertFalse(arbiter.describe(), arbiter.describe().contains("主屏=车机(约束 100)"))
    }

    @Test
    fun vw4131ReverseBorrowCannotBeRetakenUntilUnborrowed() {
        val arbiter = ModeArbiter()
        arbiter.onChangeModes(
            mapOf(
                "reasonStr" to "0",
                "resources" to
                    listOf(
                        mapOf(
                            "borrowID" to "4",
                            "transferPriority" to 500,
                            "resourceID" to 1,
                            "transferType" to 3,
                            "unborrowConstraint" to 1000,
                        ),
                    ),
            ),
        )
        assertFalse(arbiter.phoneOwnsScreen)
        assertFalse("A requestUI must not steal the reversing-camera screen", arbiter.onUserRequestedUi().allowed)

        arbiter.onChangeModes(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to 1,
                            "transferType" to 4,
                        ),
                    ),
            ),
        )
        assertTrue(arbiter.phoneOwnsScreen)
    }

    @Test
    fun borrowConstraintAppliesEvenWhenAccessoryAlreadyOwnsScreenPermanently() {
        val arbiter = ModeArbiter()
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(1, 1, 500, 500))))
        assertFalse(arbiter.phoneOwnsScreen)

        arbiter.onChangeModes(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "borrowID" to "4",
                            "transferPriority" to 500,
                            "resourceID" to 1,
                            "transferType" to 3,
                            "unborrowConstraint" to 1000,
                        ),
                    ),
            ),
        )
        assertFalse("A permanent TAKE must not bypass a later BORROW constraint", arbiter.onUserRequestedUi().allowed)
        assertFalse(arbiter.phoneOwnsScreen)

        arbiter.onChangeModes(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to 1,
                            "transferType" to 4,
                        ),
                    ),
            ),
        )
        assertFalse("UNBORROW restores the prior accessory ownership", arbiter.phoneOwnsScreen)
    }

    @Test
    fun vw4131AudioBorrowIsNotTreatedAsPhoneAudioOwnership() {
        val arbiter = ModeArbiter()
        arbiter.applyInitial(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "transferPriority" to 100,
                            "takeConstraint" to 100,
                            "resourceID" to 1,
                            "transferType" to 1,
                            "borrowConstraint" to 100,
                        ),
                        mapOf(
                            "borrowID" to null,
                            "transferPriority" to 500,
                            "resourceID" to 2,
                            "transferType" to 3,
                            "unborrowConstraint" to 1000,
                        ),
                    ),
            ),
        )
        assertTrue(arbiter.phoneOwnsScreen)
        assertFalse(arbiter.phoneOwnsAudio)

        arbiter.onChangeModes(
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to 2,
                            "transferType" to 4,
                        ),
                    ),
            ),
        )
        assertTrue(arbiter.phoneOwnsAudio)
    }

    @Test
    fun returningHomeDuringRecoveryKeepsNextRebuildPending() {
        val arbiter = ModeArbiter()
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(1, 1, 500, 500))))
        assertTrue(arbiter.onUserRequestedUi().rebuildStream)
        arbiter.onChangeModes(mapOf("resources" to listOf(resource(1, 1, 500, 500))))
        arbiter.onScreenStreamRestored()
        assertTrue(arbiter.onUserRequestedUi().rebuildStream)
    }

    @Test
    fun returnToCarHandsBackScreenAndAudioAndAllowsRetake() {
        val arbiter = ModeArbiter()
        assertTrue(arbiter.phoneOwnsScreen)

        arbiter.onUserReturnedToCar()

        assertFalse(arbiter.phoneOwnsScreen)
        assertTrue(arbiter.resourcesPayload().all { it["entity"] == ModeArbiter.Entity.ACCESSORY.code })
        val retake = arbiter.onUserRequestedUi()
        assertTrue(retake.allowed)
        assertTrue(retake.rebuildStream)
        assertTrue(arbiter.phoneOwnsScreen)
    }
}
