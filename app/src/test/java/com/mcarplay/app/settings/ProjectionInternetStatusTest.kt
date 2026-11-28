package com.mcarplay.app.settings

import com.mcarplay.app.R
import com.mcarplay.app.settings.ProjectionInternetStatus.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectionInternetStatusTest {
    @Test
    fun phoneModeDistinguishesCarHotspotFromUnavailableCellular() {
        fun hint(
            transport: Transport,
            validated: Boolean,
            carIsDefault: Boolean?,
        ) = ProjectionInternetStatus(InternetNetwork.PHONE, transport, validated, carIsDefault).hint
        assertEquals(R.string.main_internet_car_default_offline, hint(Transport.WIFI, false, true))
        assertEquals(R.string.main_internet_car_default, hint(Transport.WIFI, true, true))
        assertEquals(R.string.main_internet_cellular_offline, hint(Transport.CELLULAR, false, false))
        assertEquals(R.string.main_internet_default_offline, hint(Transport.NONE, false, null))
        assertNull(hint(Transport.CELLULAR, true, false))
        assertNull(hint(Transport.WIFI, true, null))
    }

    @Test
    fun carModeOnlyReportsMismatchWhenKnownAndHealthyRouteIsQuiet() {
        fun hint(
            transport: Transport,
            validated: Boolean,
            carIsDefault: Boolean?,
        ) = ProjectionInternetStatus(InternetNetwork.CAR, transport, validated, carIsDefault).hint
        assertNull(hint(Transport.WIFI, true, true))
        assertNull(hint(Transport.WIFI, true, null))
        assertEquals(R.string.main_internet_car_default_offline, hint(Transport.WIFI, false, true))
        assertEquals(R.string.main_internet_car_using_cellular, hint(Transport.CELLULAR, true, false))
        assertEquals(R.string.main_internet_car_other_route, hint(Transport.WIFI, true, false))
    }

    @Test
    fun summaryOnlyExposesEnumsAndBooleanFlags() {
        assertEquals(
            "NETWORK: default-route transport=WIFI validated=true preference=PHONE matches=unknown",
            ProjectionInternetStatus(InternetNetwork.PHONE, Transport.WIFI, true, null).logLine(),
        )
        assertEquals(
            "NETWORK: default-route transport=CELLULAR validated=true preference=PHONE matches=true",
            ProjectionInternetStatus(InternetNetwork.PHONE, Transport.CELLULAR, true, false).logLine(),
        )
    }
}
