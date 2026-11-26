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

    /**
     * issue #127：声明 `DISABLE_RESOLUTION_OPTION=1` 时按车机 displays[0] 精确请求，
     * 不再被 1920 长边上限压缩；宽高按 H.264 偶数对齐。
     */
    @Test
    fun exactVehicleRequestKeepsTheCarScreenGeometryBeyondThe1920Cap() {
        assertEquals(2560 to 1200, CarLifeProtocol.vehicleResolutionRequestFor(2560, 1200))
        assertEquals(2560 to 1440, CarLifeProtocol.vehicleResolutionRequestFor(2560, 1440))
        assertEquals(1920 to 1080, CarLifeProtocol.vehicleResolutionRequestFor(1920, 1080))
        assertEquals(2560 to 1200, CarLifeProtocol.vehicleResolutionRequestFor(2561, 1201))
    }

    @Test
    fun exactVehicleRequestRejectsInvalidOrOversizedGeometry() {
        listOf(0 to 1200, 2560 to 0, -2560 to 1200, 4097 to 1200, 2560 to 4097).forEach { (width, height) ->
            assertNull("$width x $height 应被拒绝", CarLifeProtocol.vehicleResolutionRequestFor(width, height))
        }
        // 单边在 4K 内，但像素总量超过预算（4096×2160 = 8,847,360 > 8,294,400）。
        assertNull(CarLifeProtocol.vehicleResolutionRequestFor(4096, 2160))
        // 预算边界本身可用（3840×2160 = 8,294,400）。
        assertEquals(3840 to 2160, CarLifeProtocol.vehicleResolutionRequestFor(3840, 2160))
    }
}
