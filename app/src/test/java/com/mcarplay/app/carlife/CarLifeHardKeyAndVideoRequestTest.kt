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
}
