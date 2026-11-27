package com.mcarplay.app.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class HidNavigationTest {
    @Test
    fun joystickWheelUsesAxesAndOneStepPerWheelReport() {
        val descriptor =
            (
                "05010908a101050909011500250175019501810275019507810105010901a100093009311581257f750895028102c0" +
                    "09381581257f750895018106c0"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun keys(report: String) =
            HidNavigation.handle(
                "joystick-wheel-test",
                descriptor,
                report
                    .chunked(2)
                    .map {
                        it.toInt(16).toByte()
                    }.toByteArray(),
            )

        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_DOWN), keys("00007f00"))
        assertEquals(emptyList<Int>(), keys("00007f00"))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_UP), keys("00008100"))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_LEFT), keys("00810000"))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_RIGHT), keys("007f0000"))
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_CENTER), keys("01000000"))
        assertEquals(listOf(KeyEvent.KEYCODE_NAVIGATE_NEXT), keys("0000007f"))
        assertEquals(listOf(KeyEvent.KEYCODE_NAVIGATE_PREVIOUS), keys("00000081"))
        assertEquals(emptyList<Int>(), keys("00000000"))
    }
}
