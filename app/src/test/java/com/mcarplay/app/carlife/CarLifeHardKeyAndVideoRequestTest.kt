package com.mcarplay.app.carlife

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarLifeHardKeyAndVideoRequestTest {
    @Test
    fun onlyEvidencedMediaKeysMapToCarLifeHardKeys() {
        assertEquals(16, CarLifeProtocol.hardKeyFor(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(15, CarLifeProtocol.hardKeyFor(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertNull(CarLifeProtocol.hardKeyFor(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertNull(CarLifeProtocol.hardKeyFor(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test
    fun directionsAndConfirmMapToCarLifeSelectorKeys() {
        assertEquals(6, CarLifeProtocol.navigationHardKeyFor(KeyEvent.KEYCODE_NAVIGATE_NEXT))
        assertEquals(7, CarLifeProtocol.navigationHardKeyFor(KeyEvent.KEYCODE_NAVIGATE_PREVIOUS))
        assertEquals(20, CarLifeProtocol.navigationHardKeyFor(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(21, CarLifeProtocol.navigationHardKeyFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(22, CarLifeProtocol.navigationHardKeyFor(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(23, CarLifeProtocol.navigationHardKeyFor(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(24, CarLifeProtocol.navigationHardKeyFor(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(14, CarLifeProtocol.navigationHardKeyFor(KeyEvent.KEYCODE_BACK))
        assertArrayEquals(byteArrayOf(0x08, 0x17), CarLifeProtocol.hardKeyPayload(23))
    }

    @Test
    fun hardKeyIsProtobufFieldOne() {
        assertArrayEquals(byteArrayOf(0x08, 0x10), CarLifeProtocol.hardKeyPayload(16))
    }

    @Test
    fun videoRequestKeepsReceiverAspectWithinLongSideCap() {
        assertEquals(1920 to 1260, CarLifeProtocol.videoRequestFor(2800, 1840))
        assertEquals(1160 to 540, CarLifeProtocol.videoRequestFor(1160, 540))
        assertEquals(1920 to 558, CarLifeProtocol.videoRequestFor(2560, 744))
    }

    @Test
    fun unknownDisplayFallsBackToProtocolDefault() {
        assertEquals(1280 to 720, CarLifeProtocol.videoRequestFor(0, 0))
    }

    /**
     * 实验（issue #62，PR #78 评审 P2）：默认长边上限会把面板尺寸先压小，
     * 使实验连「请求正确尺寸」都做不到。放开上限后才应得到面板原尺寸。
     */
    @Test
    fun videoRequestCanAskForThePanelSizeWhenTheCapIsRaised() {
        // 2288×1728 面板：默认请求被压到长边 1920。
        val capped = CarLifeProtocol.videoRequestFor(2288, 1728)
        assertEquals(CarLifeProtocol.VIDEO_REQUEST_MAX_LONG_SIDE, capped.first)
        // 实验放开到面板长边后，请求就是面板尺寸本身（2288/1728 均为偶数）。
        assertEquals(
            2288 to 1728,
            CarLifeProtocol.videoRequestFor(
                2288,
                1728,
                maxOf(CarLifeProtocol.VIDEO_REQUEST_MAX_LONG_SIDE, 2288, 1728),
            ),
        )
        // 小于上限的面板不受影响。
        assertEquals(1280 to 720, CarLifeProtocol.videoRequestFor(1280, 720, 1920))
    }
}
