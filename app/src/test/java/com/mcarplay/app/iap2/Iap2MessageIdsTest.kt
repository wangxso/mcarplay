package com.mcarplay.app.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2MessageIdsTest {
    @Test
    fun everyCataloguedIdHasAReadableName() {
        val declaredIds =
            Iap2MessageIds::class.java.declaredFields
                .filter { it.type == Int::class.javaPrimitiveType }
                .map { it.getInt(null) }
                .toSet()
        assertEquals(declaredIds, Iap2MessageIds.ALL_IDS.toSet())
        assertEquals(Iap2MessageIds.ALL_IDS.size, Iap2MessageIds.ALL_IDS.distinct().size)
        Iap2MessageIds.ALL_IDS.forEach { id ->
            val name = Iap2MessageIds.nameOf(id)
            assertFalse("0x${id.toString(16)} 仍退化为裸十六进制", name.matches(Regex("0x[0-9a-fA-F]+")))
            assertTrue("0x${id.toString(16)} 的名字不是可读标识符", name.matches(Regex("[A-Za-z][A-Za-z0-9]*")))
        }
    }

    @Test
    fun formerlyUnnamedDeviceStateAndMfiIdsAreCovered() {
        val formerlyMissing =
            setOf(
                Iap2MessageIds.DEVICE_INFORMATION_UPDATE,
                Iap2MessageIds.DEVICE_LANGUAGE_UPDATE,
                Iap2MessageIds.DEVICE_TIME_UPDATE,
                Iap2MessageIds.DEVICE_UUID_UPDATE,
                Iap2MessageIds.MFI_REQUEST_CERTIFICATE,
                Iap2MessageIds.MFI_CERTIFICATE,
                Iap2MessageIds.MFI_REQUEST_CHALLENGE,
                Iap2MessageIds.MFI_RESPONSE,
                Iap2MessageIds.MFI_AUTHENTICATION_FAILED,
                Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED,
            )
        assertTrue(formerlyMissing.all { it in Iap2MessageIds.ALL_IDS })
        assertTrue(formerlyMissing.all { Iap2MessageIds.nameOf(it) == it.expectedName() })
        assertTrue(formerlyMissing.all { !Iap2MessageIds.nameOf(it).contains("0x") })
    }

    private fun Int.expectedName(): String =
        when (this) {
            Iap2MessageIds.DEVICE_INFORMATION_UPDATE -> "DeviceInformationUpdate"
            Iap2MessageIds.DEVICE_LANGUAGE_UPDATE -> "DeviceLanguageUpdate"
            Iap2MessageIds.DEVICE_TIME_UPDATE -> "DeviceTimeUpdate"
            Iap2MessageIds.DEVICE_UUID_UPDATE -> "DeviceUuidUpdate"
            Iap2MessageIds.MFI_REQUEST_CERTIFICATE -> "MfiRequestCertificate"
            Iap2MessageIds.MFI_CERTIFICATE -> "MfiCertificate"
            Iap2MessageIds.MFI_REQUEST_CHALLENGE -> "MfiRequestChallenge"
            Iap2MessageIds.MFI_RESPONSE -> "MfiResponse"
            Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> "MfiAuthenticationFailed"
            Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED -> "MfiAuthenticationSucceeded"
            else -> error("unexpected message id")
        }
}
