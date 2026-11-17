package com.mcarplay.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportReportTest {
    @Test
    fun summaryTextKeepsSectionOrderPrefixesSeparatorsAndExactValues() {
        val observedCalls = mutableListOf<String>()
        val connectionSummary = {
            observedCalls += "connection"
            "CarPlay 连接：\nEXPORT_SNAPSHOT\n多行 value 保留"
        }
        val selfTestSummary = {
            observedCalls += "self-test"
            "CarWith 自检：line1\nline2"
        }
        val receiverDetectionSummary = {
            observedCalls += "receiver"
            "receiver value"
        }
        val bluetoothSummary = {
            observedCalls += "bluetooth"
            "bluetooth value"
        }
        val notificationProbeSummary = {
            observedCalls += "notification"
            "notification value"
        }
        val carLifeSelfTestSummary = {
            observedCalls += "carlife"
            "carlife value"
        }

        val summary =
            SupportReport.summaryText(
                connectionSummary = connectionSummary,
                selfTestSummary = selfTestSummary,
                receiverDetectionSummary = receiverDetectionSummary,
                bluetoothSummary = bluetoothSummary,
                notificationProbeSummary = notificationProbeSummary,
                carLifeSelfTestSummary = carLifeSelfTestSummary,
            )

        assertEquals(
            listOf(
                "CarPlay 连接：\nEXPORT_SNAPSHOT\n多行 value 保留",
                "CarWith 自检：line1\nline2",
                "RECEIVER_DETECTION receiver value",
                "BLUETOOTH_DIAGNOSTICS bluetooth value",
                "CARWITH_NOTIFICATION_PROBE notification value",
                "CARLIFE_SELF_TEST_DIAGNOSTICS carlife value",
            ).joinToString("\n\n"),
            summary,
        )
        assertEquals(
            listOf("connection", "self-test", "receiver", "bluetooth", "notification", "carlife"),
            observedCalls,
        )
    }

    @Test
    fun summaryTextSkipsNullSnapshotsAndKeepsBluetoothBetweenReceiverAndNotification() {
        val observedCalls = mutableListOf<String>()
        val bluetoothSummary = {
            observedCalls += "bluetooth"
            "after null snapshots"
        }

        val summary =
            SupportReport.summaryText(
                connectionSummary = { null },
                selfTestSummary = { null },
                receiverDetectionSummary = {
                    observedCalls += "receiver"
                    "receiver value"
                },
                bluetoothSummary = bluetoothSummary,
                notificationProbeSummary = {
                    observedCalls += "notification"
                    "notification value"
                },
                carLifeSelfTestSummary = {
                    observedCalls += "carlife"
                    "carlife value"
                },
            )

        assertEquals(
            listOf(
                "RECEIVER_DETECTION receiver value",
                "BLUETOOTH_DIAGNOSTICS after null snapshots",
                "CARWITH_NOTIFICATION_PROBE notification value",
                "CARLIFE_SELF_TEST_DIAGNOSTICS carlife value",
            ).joinToString("\n\n"),
            summary,
        )
        assertEquals(listOf("receiver", "bluetooth", "notification", "carlife"), observedCalls)
    }

    @Test
    fun summaryTextKeepsEmptyStringAndMultilineSectionValues() {
        val summary =
            SupportReport.summaryText(
                connectionSummary = { null },
                selfTestSummary = { "CarWith 自检：" },
                receiverDetectionSummary = { "" },
                bluetoothSummary = { "line1\nline2" },
                notificationProbeSummary = { "notification value" },
                carLifeSelfTestSummary = { "" },
            )

        assertEquals(
            listOf(
                "CarWith 自检：",
                "RECEIVER_DETECTION ",
                "BLUETOOTH_DIAGNOSTICS line1\nline2",
                "CARWITH_NOTIFICATION_PROBE notification value",
                "CARLIFE_SELF_TEST_DIAGNOSTICS ",
            ).joinToString("\n\n"),
            summary,
        )
    }

    @Test
    fun summaryTextDoesNotInvokeBluetoothProviderAtConstructionAndInvokesItOnceOnSameThread() {
        val executionThreadNames = mutableListOf<String>()
        var invocationCount = 0
        val bluetoothSummary: () -> String = {
            invocationCount += 1
            executionThreadNames += Thread.currentThread().name
            "bluetooth value"
        }

        assertEquals(0, invocationCount)

        val summary =
            SupportReport.summaryText(
                connectionSummary = { null },
                selfTestSummary = { null },
                receiverDetectionSummary = { "receiver value" },
                bluetoothSummary = bluetoothSummary,
                notificationProbeSummary = { "notification value" },
                carLifeSelfTestSummary = { "carlife value" },
            )

        assertEquals(1, invocationCount)
        assertEquals(listOf(Thread.currentThread().name), executionThreadNames)
        assertEquals(
            listOf(
                "RECEIVER_DETECTION receiver value",
                "BLUETOOTH_DIAGNOSTICS bluetooth value",
                "CARWITH_NOTIFICATION_PROBE notification value",
                "CARLIFE_SELF_TEST_DIAGNOSTICS carlife value",
            ).joinToString("\n\n"),
            summary,
        )
    }

    @Test
    fun summaryTextPropagatesBluetoothExceptionAndSkipsLaterSections() {
        val failure = IllegalStateException("bluetooth summary failed")
        val observedCalls = mutableListOf<String>()

        val thrown =
            runCatching {
                SupportReport.summaryText(
                    connectionSummary = { null },
                    selfTestSummary = { null },
                    receiverDetectionSummary = {
                        observedCalls += "receiver"
                        "receiver value"
                    },
                    bluetoothSummary = {
                        observedCalls += "bluetooth"
                        throw failure
                    },
                    notificationProbeSummary = {
                        observedCalls += "notification"
                        "notification value"
                    },
                    carLifeSelfTestSummary = {
                        observedCalls += "carlife"
                        "carlife value"
                    },
                )
            }

        assertEquals(
            listOf("receiver", "bluetooth"),
            observedCalls,
        )
        assertSame(failure, thrown.exceptionOrNull())
    }

    @Test
    fun summaryTextUsesEmptyFallbackWhenAllSectionsAreNull() {
        var invocationCount = 0
        val summary =
            SupportReport.summaryText(
                connectionSummary = { null },
                selfTestSummary = { null },
                receiverDetectionSummary = { "" },
                bluetoothSummary = {
                    invocationCount += 1
                    ""
                },
                notificationProbeSummary = { "" },
                carLifeSelfTestSummary = { "" },
            )

        assertEquals(
            listOf(
                "RECEIVER_DETECTION ",
                "BLUETOOTH_DIAGNOSTICS ",
                "CARWITH_NOTIFICATION_PROBE ",
                "CARLIFE_SELF_TEST_DIAGNOSTICS ",
            ).joinToString("\n\n"),
            summary,
        )
        assertEquals(1, invocationCount)
    }

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
