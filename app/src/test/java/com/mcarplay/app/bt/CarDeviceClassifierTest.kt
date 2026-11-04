package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarDeviceClassifierTest {
    private val addr = "AA:BB:CC:DD:EE:FF"

    private fun car(
        name: String?,
        deviceClass: Int?,
        target: String? = null,
        address: String = addr,
    ) = CarDeviceClassifier.isCarDevice(target, address, name, deviceClass)

    @Test
    fun targetDecidesByAddressOnly() {
        assertTrue(car("headset", 0x0404, target = "aa:bb:cc:dd:ee:ff"))
        assertFalse(car("Cloudrive-023564", 0x0420, target = "11:22:33:44:55:66"))
    }

    @Test
    fun carAudioAndHandsfreeClassesAreCarsWhateverTheName() {
        assertTrue(car("i8", 0x0420))
        assertTrue(car("Unknown head unit", 0x0408))
        assertTrue(car(null, 0x0420))
        // 带服务位的完整 CoD 也按设备类别判定
        assertTrue(car("i8", 0x240420))
    }

    @Test
    fun loggedHeadsetPhoneAndUncategorizedStayIgnored() {
        assertFalse(car("Redmi Buds 6", 0x0404))
        assertFalse(car("Xiaomi 15", 0x020C))
        assertFalse(car("Band 9", 0x1F00))
    }

    @Test
    fun headphonesAndWatchesAreExcludedEvenWithAKeyword() {
        assertFalse(car("Buds Mini", 0x0404))
        assertFalse(car("Audio MINI", 0x0418))
        assertFalse(car("Volvo watch", 0x0704))
    }

    @Test
    fun newKeywordsMatchRealHeadUnitNamesWithoutAClass() {
        assertTrue(car("Cloudrive-023564", null))
        assertTrue(car("VW BT 7094", null))
        assertTrue(car("cloudrive-1", 0x1F00))
        assertFalse(car("i8", null))
        assertFalse(car(null, null))
    }

    @Test
    fun benchReceiversOnPhonesOrComputersStillMatchByName() {
        assertTrue(car("DiPlay CarPlay", 0x020C))
        assertTrue(car("CarPlay Mac", 0x010C))
        assertTrue(car("BMW 12345", null))
    }

    @Test
    fun describeClassFormatsMaskedValue() {
        assertEquals("0x0420", CarDeviceClassifier.describeClass(0x240420))
        assertEquals("?", CarDeviceClassifier.describeClass(null))
    }
}
