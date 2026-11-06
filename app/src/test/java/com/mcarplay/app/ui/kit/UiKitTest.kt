package com.mcarplay.app.ui.kit

import com.mcarplay.app.R
import com.mcarplay.app.diagnostics.ConnectionProgress.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiKitTest {
    @Test
    fun stepperColorsFollowTheTokenTable() {
        assertEquals(R.color.ui_accent, StepperView.colorFor(StepState.DONE))
        assertEquals(R.color.ui_accent_soft, StepperView.colorFor(StepState.ACTIVE))
        assertEquals(R.color.ui_error, StepperView.colorFor(StepState.FAILED))
        assertEquals(R.color.ui_track, StepperView.colorFor(StepState.PENDING))
    }

    @Test
    fun everyStepStateHasAColorAndPendingDiffersFromDone() {
        StepState.entries.forEach { assertNotEquals("$it", 0, StepperView.colorFor(it)) }
        assertNotEquals(StepperView.colorFor(StepState.PENDING), StepperView.colorFor(StepState.DONE))
    }

    @Test
    fun dialogActionsStackVerticallyFromThree() {
        assertFalse(UiDialog.isVertical(1))
        assertFalse(UiDialog.isVertical(2))
        assertTrue(UiDialog.isVertical(3))
    }

    @Test
    fun statusToneColorsAreTheTextVariants() {
        // 状态文字必须用 *_text 令牌（原色对比度不足 AA），见 UiTokenContrastTest
        assertEquals(R.color.ui_ok_text, StatusCard.Tone.OK.color)
        assertEquals(R.color.ui_error_text, StatusCard.Tone.ERROR.color)
    }
}
