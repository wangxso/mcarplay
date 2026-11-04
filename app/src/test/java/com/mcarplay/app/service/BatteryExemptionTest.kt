package com.mcarplay.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 连接前的电池优化提示：只在明确未豁免时提示，每个进程一次。 */
class BatteryExemptionTest {
    @Test
    fun promptsOnlyWhenKnownNotExempt() {
        assertTrue(BatteryExemption.shouldPrompt(exempt = false, alreadyPrompted = false))
        assertFalse(BatteryExemption.shouldPrompt(exempt = true, alreadyPrompted = false))
        // 读不到状态的 ROM 不打扰
        assertFalse(BatteryExemption.shouldPrompt(exempt = null, alreadyPrompted = false))
    }

    @Test
    fun promptsOncePerProcess() {
        assertFalse(BatteryExemption.shouldPrompt(exempt = false, alreadyPrompted = true))
    }
}
