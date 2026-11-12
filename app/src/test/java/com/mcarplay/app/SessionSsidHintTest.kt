package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSsidHintTest {
    @Test
    fun placeholderLabelNeverBecomesAKnownCarSsid() {
        assertNull(usableKnownCarSsid(SessionController.INVITED_WIFI_LABEL, null))
        assertNull(usableKnownCarSsid(null, SessionController.INVITED_WIFI_LABEL))
        assertEquals("Car_AP", usableKnownCarSsid(SessionController.INVITED_WIFI_LABEL, "Car_AP"))
    }
}
