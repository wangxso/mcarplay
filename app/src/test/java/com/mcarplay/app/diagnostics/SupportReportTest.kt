package com.mcarplay.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportTest {
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
