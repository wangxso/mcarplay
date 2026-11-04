package com.mcarplay.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportTest {
    @Test
    fun rawReportRetainsNamesAddressesCredentialsPayloadsAndExitDetails() {
        val detail = "name=FamilyCar address=10.2.3.4 psk=SecretValue"
        val journal = JSONObject().put("event", "NOTE").put("detail", detail).toString()
        val log = "目标「FamilyCar」 AA:BB:CC:DD:EE:FF\nRAW tx: 01020304\n$detail"
        val report =
            SupportReport.render(
                "raw-example",
                mapOf("raw.target" to "AA:BB:CC:DD:EE:FF"),
                detail,
                journal,
                log,
                "exit description=$detail",
                "remote debug raw",
                "ok",
                knownNames = listOf("FamilyCar"),
                redact = false,
            )
        assertTrue(report.contains("privacyMode=UNREDACTED_REMOTE_DEBUG"))
        assertTrue(report.contains(log))
        assertTrue(report.contains("exit description=$detail"))
        assertTrue(report.contains("raw.target=AA:BB:CC:DD:EE:FF"))
        assertEquals(detail, JSONObject(report.lineSequence().single { it.startsWith('{') }).getString("detail"))
        assertFalse(report.contains("report-local aliases"))
        assertFalse(report.contains("<NAME-"))
    }

    @Test
    fun journalRemainsMachineReadableAfterRedaction() {
        val journal =
            JSONObject()
                .put("process", "p1")
                .put("attempt", "a1")
                .put("event", "END")
                .put("detail", "psk=SecretText address=10.2.3.4")
                .toString()
        val report = SupportReport.render("r1", emptyMap(), "", journal, "", "", "support", "ok")
        val event = JSONObject(report.lineSequence().single { it.startsWith('{') })
        org.junit.Assert.assertEquals("a1", event.getString("attempt"))
        assertFalse(event.getString("detail").contains("SecretText"))
        assertFalse(event.getString("detail").contains("10.2.3.4"))
    }

    @Test
    fun exportedEvidenceIsRedactedButTrustedApkFingerprintSurvives() {
        val hash = "abcdef0123456789".repeat(4)
        val report =
            SupportReport.render(
                "report-example",
                mapOf("app.apk.sha256" to hash, "app.version" to "0.1.0 (1000000)"),
                "attempt-1 PAIRING_FAILED",
                "previous process event\nssid=PrivateNetwork",
                "RAW tx: 70617373\npsk=WiFiSecret",
                "reason=3 description=single-cleaner",
                "support",
                "diagnosticWriteFailures=0",
            )
        assertTrue(report.contains("app.apk.sha256=$hash"))
        assertTrue(report.contains("PAIRING_FAILED"))
        assertTrue(report.contains("previous process event"))
        assertTrue(report.contains("single-cleaner"))
        assertTrue(report.contains("reportId=report-example"))
        listOf("PrivateNetwork", "WiFiSecret", "70617373").forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("No automatic upload"))
        assertTrue(report.contains("不等于故障发生时状态"))
    }
}
