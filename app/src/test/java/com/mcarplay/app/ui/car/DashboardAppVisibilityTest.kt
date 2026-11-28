package com.mcarplay.app.ui.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardAppVisibilityTest {
    @Test
    fun mapWithVisibleTaskStaysOnScreen() {
        val snapshot =
            """
            RootTask id=31071 bounds=[0,0][1200,2670] displayId=0 userId=0
              taskId=31071: com.waze/.MainActivity userId=0 visible=false
            RootTask id=32121 bounds=[0,0][1886,1074] displayId=234 userId=0
              taskId=32121: com.waze/.MainActivity userId=0 visible=true
            """.trimIndent()

        assertEquals(true, hasVisibleDashboardTask(snapshot, 234))
    }

    @Test
    fun hiddenMapTaskCountsAsExitedEvenWhenOtherDisplaysAreVisible() {
        val snapshot =
            """
            RootTask id=32119 bounds=[0,0][3020,1126] displayId=233 userId=0
              taskId=32119: com.mcarplay.app/.ui.car.CarDashboardActivity userId=0 visible=true
            RootTask id=32121 bounds=[0,0][1886,1074] displayId=234 userId=0
              taskId=32121: com.waze/.MainActivity userId=0 visible=false
            RootTask id=31071 bounds=[0,0][1200,2670] displayId=0 userId=0
              taskId=31071: com.waze/.MainActivity userId=0 visible=true
            """.trimIndent()

        assertEquals(false, hasVisibleDashboardTask(snapshot, 234))
    }

    @Test
    fun appDisplayWithNoListedTasksCountsAsExited() {
        val snapshot = "RootTask id=32119 bounds=[0,0][3020,1126] displayId=233 userId=0"

        assertEquals(false, hasVisibleDashboardTask(snapshot, 234))
    }

    @Test
    fun unavailableVisibilityDoesNotCloseTheMap() {
        assertNull(hasVisibleDashboardTask("Permission Denial", 234))
        val snapshot =
            """
            RootTask id=32121 bounds=[0,0][1886,1074] displayId=234 userId=0
              taskId=32121: com.waze/.MainActivity userId=0
            """.trimIndent()

        assertNull(hasVisibleDashboardTask(snapshot, 234))
    }

    @Test
    fun dockReusesOnlyTheSelectedMapVisibleOnItsOwnDisplay() {
        val snapshot =
            """
            RootTask id=32387 bounds=[0,0][1886,1074] displayId=305 userId=0
              taskId=32387: com.waze/com.waze.FreeMapAppActivity userId=0 visible=false
            RootTask id=32390 bounds=[0,0][1886,1074] displayId=305 userId=0
              taskId=32390: com.waze/.MainActivity userId=0 visible=true topActivity=ComponentInfo{com.google.android.apps.maps/.MapsActivity}
            RootTask id=32391 bounds=[0,0][1200,2670] displayId=0 userId=0
              taskId=32391: com.waze/.MainActivity userId=0 visible=true
            """.trimIndent()

        assertEquals(false, hasVisibleDashboardTask(snapshot, 305, "com.waze"))
        assertEquals(true, hasVisibleDashboardTask(snapshot, 305, "com.google.android.apps.maps"))
        assertEquals(true, hasVisibleDashboardTask(snapshot, 0, "com.waze"))
    }

    @Test
    fun unavailableMapIdentityDoesNotCauseAPotentiallyDisruptiveRelaunch() {
        val snapshot =
            """
            RootTask id=32387 bounds=[0,0][1886,1074] displayId=305 userId=0
              taskId=32387: null userId=0 visible=true
            """.trimIndent()

        assertNull(hasVisibleDashboardTask(snapshot, 305, "com.waze"))
    }
}
