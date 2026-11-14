package com.mcarplay.app.compat

import com.mcarplay.app.iap2.ReceiverProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverCapabilitiesTest {
    private val signals = ReceiverProfile.Signals(model = "receiver", manufacturer = "maker", firmwareVersion = "1")
    private val display = mapOf("widthPixels" to 1920, "heightPixels" to 1080, "maxFPS" to 30)
    private val aac = mapOf("type" to 102, "audioType" to "media", "audioOutputFormats" to (1L shl 23))
    private val pcm = mapOf("type" to 100, "audioType" to "media", "audioOutputFormats" to (1L shl 15))
    private val hid = mapOf("hidDescriptor" to byteArrayOf(5, 13, 9, 1))
    private val info = mapOf("displays" to listOf(display), "audioFormats" to listOf(aac, pcm), "hidDevices" to listOf(hid))

    @Test
    fun firmwareMediaGeometryAndHidChangesInvalidateCapabilities() {
        val previous = ReceiverCapabilities.digest(signals, info, "instance")
        assertNotEquals(previous, ReceiverCapabilities.digest(signals.copy(firmwareVersion = "2"), info, "instance"))
        for (next in listOf(
            info + ("audioFormats" to listOf(pcm)),
            info + ("displays" to listOf(display + ("heightPixels" to 720))),
            info + ("hidDevices" to listOf(hid + ("hidDescriptor" to byteArrayOf(5, 1, 9, 1)))),
        )) {
            assertNotEquals(previous, ReceiverCapabilities.digest(signals, next, "instance"))
        }
        assertNotEquals(previous, ReceiverCapabilities.digest(signals, info, "other-instance"))
    }

    @Test
    fun orderDynamicNetworkFieldsAndDeviceRenameDoNotChangeCapabilities() {
        val next = info + ("audioFormats" to listOf(pcm, aac)) + ("ipAddress" to "192.0.2.5") + ("port" to 7001)
        assertEquals(
            ReceiverCapabilities.digest(signals, info, "instance"),
            ReceiverCapabilities.digest(signals.copy(name = "renamed"), next, "instance"),
        )
    }

    @Test
    fun missingInfoIsNotAValidCapabilityFingerprint() {
        assertNull(ReceiverCapabilities.digest(signals, null, "instance"))
        assertNull(ReceiverCapabilities.digest(signals, emptyMap<Any, Any>(), "instance"))
    }

    @Test
    fun videoPanelGeometryChangesInvalidateCapabilities() {
        // issue #62：displayPanels 的物理面板可能与 displays[0] 高度不同；面板分辨率一变，
        // 我们按 displays 编出来的流就不再匹配，缓存的兼容能力必须失效。
        val panel = mapOf("widthPixels" to 2288, "heightPixels" to 1728)
        val withPanel = info + ("displayPanels" to listOf(panel))
        val previous = ReceiverCapabilities.digest(signals, withPanel, "instance")
        assertNotEquals(
            previous,
            ReceiverCapabilities.digest(
                signals,
                withPanel + ("displayPanels" to listOf(panel + ("heightPixels" to 1278))),
                "instance",
            ),
        )
        assertNotEquals(previous, ReceiverCapabilities.digest(signals, info, "instance"))
    }

    @Test
    fun handshakeMemoryUsesInstanceFirmwareAndCapabilitiesAndSkipsUnknown() {
        val first = ReceiverFingerprint.handshake("instance-a", signals)
        assertNotEquals(first, ReceiverFingerprint.handshake("instance-b", signals))
        assertNotEquals(first, ReceiverFingerprint.handshake("instance-a", signals.copy(firmwareVersion = "2")))
        assertNotEquals(first, ReceiverFingerprint.handshake("instance-a", signals.copy(receiveIds = setOf(0x4300))))
        assertEquals(first, ReceiverFingerprint.handshake("instance-a", signals.copy(name = "renamed")))
        assertNull(ReceiverFingerprint.handshake(null, signals))
        assertNull(ReceiverFingerprint.handshake("", signals))
    }
}
