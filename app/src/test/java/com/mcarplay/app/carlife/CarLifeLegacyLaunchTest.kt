package com.mcarplay.app.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeLegacyLaunchTest {
    private fun result(
        uid: String,
        exit: Int = 0,
    ) = CarLifeLauncher.CommandResult(exit, uid)

    @Test
    fun ordinaryUidCannotClaimSuccessfulServiceLaunch() {
        val suCalls = mutableListOf<List<String>>()
        val shellCalls = mutableListOf<String>()
        val launch =
            CarLifeLauncher.runLegacyServiceCommand(
                command = "start-service",
                runSu = {
                    suCalls += it
                    result("2000")
                },
                runShell = { cmd, _ ->
                    shellCalls += cmd
                    result("2000")
                },
            )
        assertFalse(launch.ok)
        assertEquals(listOf(listOf("su", "-c", "id -u")), suCalls)
        assertEquals(listOf("id -u"), shellCalls)
    }

    @Test
    fun verifiedSuExecutesServiceAndReturnsItsActualResult() {
        val calls = mutableListOf<List<String>>()
        val launch =
            CarLifeLauncher.runLegacyServiceCommand(
                command = "start-service",
                runSu = {
                    calls += it
                    if (it.last() == "id -u") result("0") else result("Error: Permission denied")
                },
                runShell = { _, _ -> error("su already verified") },
            )
        assertFalse(launch.ok)
        assertEquals(listOf(listOf("su", "-c", "id -u"), listOf("su", "-c", "start-service")), calls)
    }

    @Test
    fun rootShizukuCanLaunchAfterSuIsUnavailable() {
        val calls = mutableListOf<String>()
        val launch =
            CarLifeLauncher.runLegacyServiceCommand(
                command = "start-service",
                runSu = { result("unavailable", -1) },
                runShell = { cmd, _ ->
                    calls += cmd
                    if (cmd == "id -u") result("0") else result("Starting service")
                },
            )
        assertTrue(launch.ok)
        assertEquals(listOf("id -u", "start-service"), calls)
    }

    @Test
    fun failedUidProbeCannotAuthorizeServiceEvenIfOutputSaysRoot() {
        val launch =
            CarLifeLauncher.runLegacyServiceCommand(
                command = "start-service",
                runSu = { result("0", -1) },
                runShell = { cmd, _ ->
                    assertEquals("id -u", cmd)
                    result("0", -1)
                },
            )
        assertFalse(launch.ok)
    }
}
