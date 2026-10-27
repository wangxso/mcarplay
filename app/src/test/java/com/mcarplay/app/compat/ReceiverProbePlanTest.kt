package com.mcarplay.app.compat

import com.mcarplay.app.audio.AudioProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverProbePlanTest {
    @Test
    fun onlyExplicitMethodRejectionEnablesPostProbe() {
        for (code in listOf(403, 455, 408, 500, 200)) {
            assertNull(ReceiverProbePlan.alternativeInfo(InfoRequest.GET_EMPTY_PLIST, "RTSP/1.0 $code status", true))
        }
        assertNull(ReceiverProbePlan.alternativeInfo(InfoRequest.GET_EMPTY_PLIST, "timeout", true))
        for (code in listOf(405, 501)) {
            assertEquals(
                InfoRequest.POST_EMPTY_PLIST,
                ReceiverProbePlan.alternativeInfo(InfoRequest.GET_EMPTY_PLIST, "RTSP/1.0 $code status", true),
            )
            assertNull(ReceiverProbePlan.alternativeInfo(InfoRequest.GET_EMPTY_PLIST, "RTSP/1.0 $code status", false))
        }
    }

    @Test
    fun cachedPostIsACandidateAndCannotTurnForbiddenIntoAnotherProbe() {
        assertEquals(
            InfoRequest.GET_EMPTY_PLIST,
            ReceiverProbePlan.alternativeInfo(InfoRequest.POST_EMPTY_PLIST, "RTSP/1.0 405 status", false),
        )
        assertNull(ReceiverProbePlan.alternativeInfo(InfoRequest.POST_EMPTY_PLIST, "RTSP/1.0 403 forbidden", true))
    }

    @Test
    fun portTrialsAreRestrictedToExplicitDetectionAndGatewayFallback() {
        assertEquals(listOf(7000, 7001, 7100), ReceiverProbePlan.ports(7000, true, true))
        assertEquals(listOf(7001, 7100), ReceiverProbePlan.ports(7001, true, true))
        assertEquals(listOf(17000), ReceiverProbePlan.ports(17000, false, true))
        assertEquals(listOf(7000), ReceiverProbePlan.ports(7000, true, false))
        assertEquals(emptyList<Int>(), ReceiverProbePlan.ports(0, true, true))
    }

    @Test
    fun rememberedAudioCanOnlyReorderCurrentlySupportedCandidates() {
        val candidates = listOf(AudioProfile("media", 1L shl 15, 48_000, 100), AudioProfile("media", 1L shl 11, 44_100, 100))
        assertEquals(candidates, ReceiverProbePlan.audio(candidates, "unsupported-format"))
        assertEquals(candidates.reversed(), ReceiverProbePlan.audio(candidates, candidates.last().key))
        assertEquals(emptyList<AudioProfile>(), ReceiverProbePlan.audio(emptyList(), candidates.first().key))
    }
}
