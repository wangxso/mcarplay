package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WiredAccessoryInterfacesTest {
    private val bulkPair = listOf(UsbEndpointSummary(0x81, 2), UsbEndpointSummary(0x02, 2))

    @Test
    fun matchesIapAndNcmPair() {
        val result =
            WiredAccessoryInterfaces.match(
                listOf(
                    UsbInterfaceSummary(0, 0xFF, 0xF0, 0, bulkPair),
                    UsbInterfaceSummary(1, 0x02, 0x0D, 0, listOf(UsbEndpointSummary(0x83, 3))),
                    UsbInterfaceSummary(2, 0x0A, 0x00, 1, bulkPair),
                ),
            )

        assertEquals(0, result.iap2?.id)
        assertEquals(1, result.ncmControl?.id)
        assertEquals(2, result.ncmData?.id)
    }

    @Test
    fun fallsBackToAnyVendorBulkPairAndToleratesMissingNcm() {
        val result =
            WiredAccessoryInterfaces.match(
                listOf(
                    UsbInterfaceSummary(0, 0xFF, 0x42, 0, listOf(UsbEndpointSummary(0x81, 3))),
                    UsbInterfaceSummary(1, 0xFF, 0x42, 0, bulkPair),
                ),
            )

        assertEquals(1, result.iap2?.id)
        assertNull(result.ncmControl)
        assertNull(result.ncmData)
    }

    @Test
    fun ncmDataMustFollowControlInterface() {
        val result =
            WiredAccessoryInterfaces.match(
                listOf(
                    UsbInterfaceSummary(0, 0x0A, 0x00, 1, bulkPair),
                    UsbInterfaceSummary(1, 0x02, 0x0D, 0, emptyList()),
                ),
            )
        assertNull(result.ncmData)
    }
}
