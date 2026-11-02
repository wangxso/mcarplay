package com.mcarplay.app.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

class BluetoothFailureTest {
    @Test
    fun ambiguousAndroidReadFailureIsNotReportedAsDefiniteTimeout() {
        val failure = BluetoothFailure.describe(IOException("read failed, socket might closed or timeout, read ret: -1"))
        assertEquals("error=IOException reason=READ_FAILED readRet=-1", failure)
    }

    @Test
    fun nestedSystemReasonSurvivesWithoutItsAddress() {
        val failure =
            BluetoothFailure.describe(IOException("connect to AA:BB:CC:DD:EE:FF", SocketException("Connection refused (ECONNREFUSED)")))
        assertEquals("error=IOException reason=CONNECTION_REFUSED errno=ECONNREFUSED causes=SocketException", failure)
    }

    @Test
    fun discoveryFailureDoesNotClaimUnsupportedReceiver() {
        assertEquals("error=IOException reason=SDP_FAILED", BluetoothFailure.describe(IOException("Service discovery failed")))
    }

    @Test
    fun numericSocketStatusIsPreservedWithoutInventingCause() {
        assertEquals(
            "error=IOException reason=OTHER status=133",
            BluetoothFailure.describe(IOException("Connect failed. Error Status: 133")),
        )
    }

    @Test
    fun arbitraryExceptionTextAndSecretsNeverReachLog() {
        val failure =
            BluetoothFailure.describe(
                IOException("Family Car AA:BB:CC:DD:EE:FF ssid=Private Network psk=SecretText\nprivate free text 10.2.3.4"),
            )
        assertEquals("error=IOException reason=OTHER", failure)
    }

    @Test
    fun explicitTimeoutAndPermissionHaveDistinctReasons() {
        assertTrue(BluetoothFailure.describe(SocketTimeoutException("private text")).contains("reason=TIMED_OUT"))
        assertTrue(BluetoothFailure.describe(SecurityException("private permission details")).contains("reason=PERMISSION"))
    }

    @Test
    fun cyclicCausesAreBounded() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        val failure = BluetoothFailure.describe(first)
        assertEquals("error=IOException reason=OTHER causes=IOException,IOException,IOException", failure)
        assertFalse(failure.contains("first"))
    }
}
