package com.mcarplay.app.iap2

import com.mcarplay.app.iap2.EaSessionStop.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** 断开前是否发 0xEA01 的判据表。 */
class EaSessionStopTest {
    private fun decide(
        started: Int = 2,
        stopped: Boolean = false,
        declares: Boolean? = true,
        alive: Boolean = true,
    ) = EaSessionStop.decide(started, stopped, declares, alive)

    @Test
    fun sendsWhenSessionsStartedAndLinkAlive() {
        assertEquals(Decision.SEND, decide())
    }

    @Test
    fun missingReceiveListStillSends() {
        // 奥迪 9310 形态不发可收清单：无清单不能反证不支持
        assertEquals(Decision.SEND, decide(declares = null))
    }

    @Test
    fun nothingStartedMeansNothingToStop() {
        assertEquals(Decision.NOT_STARTED, decide(started = 0))
        assertEquals(Decision.NOT_STARTED, decide(started = 0, alive = false, declares = false))
    }

    @Test
    fun sendsAtMostOncePerSession() {
        assertEquals(Decision.ALREADY_SENT, decide(stopped = true))
    }

    @Test
    fun explicitListWithoutEa01Skips() {
        assertEquals(Decision.UNDECLARED, decide(declares = false))
    }

    @Test
    fun closedLinkSkips() {
        assertEquals(Decision.LINK_CLOSED, decide(alive = false))
    }
}
