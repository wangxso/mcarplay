package com.mcarplay.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun sourceStartupDiagnosticsRetainFixedPortAndPermissionEvidence() {
        val text =
            "CARLIFE-DIAG event=ENTRY result=REQUESTED background=false\n" +
                "CARLIFE_SELF_TEST_DIAGNOSTICS schema=1 state=CONNECTED entry=REQUESTED " +
                "lastFailedPort=7240 lastFailure=REFUSED openedThisAttempt=none allConnectedOnce=false timedOut=true\n" +
                "carwith.application=enabled=true systemApp=false updatedSystemApp=false targetSdk=33\n" +
                "carwith.permission.CAPTURE_VIDEO_OUTPUT=declared=true granted=false"
        assertEquals(text, DiagnosticRedactor().redact(text))
    }

    @Test
    fun bluetoothDiagnosticsRetainFixedEvidenceWithoutRevealingPeer() {
        val safe =
            DiagnosticRedactor().redact(
                "BT-DIAG schema=1 run=2 event=CONNECT_END number=3 peer=1 phase=CONNECT outcome=FAILED " +
                    "elapsedMs=120 error=IOException reason=READ_FAILED readRet=-1\n" +
                    "BT-DIAG event=SDP_REGISTER service=CARPLAY uuid=2d8d2466-e14d-451c-88bc-7301abea291a " +
                    "outcome=OK evidence=LOCAL_REGISTRATION_ONLY\n" +
                    "目标「Family Car」 address=AA:BB:CC:DD:EE:FF",
            )
        assertTrue(safe.contains("error=IOException reason=READ_FAILED readRet=-1"))
        assertTrue(safe.contains("uuid=2d8d2466-e14d-451c-88bc-7301abea291a"))
        assertTrue(safe.contains("evidence=LOCAL_REGISTRATION_ONLY"))
        assertFalse(safe.contains("Family Car"))
        assertFalse(safe.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun detectionPrivateIndexesAreOmittedWhileProgressCountersRemainUseful() {
        val safe =
            DiagnosticRedactor().redact(
                "binding=private-index capabilityDigest=private-capabilities environmentDigest=private-environment " +
                    "DETECT state=COLLECTING videoFrames=42 audioPackets=21 touches=5",
            )
        assertFalse(safe.contains("private-index"))
        assertFalse(safe.contains("private-capabilities"))
        assertFalse(safe.contains("private-environment"))
        assertTrue(safe.contains("state=COLLECTING videoFrames=42 audioPackets=21 touches=5"))
    }

    @Test
    fun videoDiagnosticsSurviveRedaction() {
        // 帕萨特排查依赖这些行进报告：参数集字节、码流统计、链路环境都不含标识，不得被当成线上报文删掉
        val lines =
            listOf(
                "  ↳ avcC=01 42 80 28 ff e1 00 0c 67 42 80 28 da 01 40 16 e9 a8 28 30 01 00 04 68 ce 06 e2",
                "VIDEO: CarWith 参数集 profile=66 constraint=0x80 level=31 chroma=1 poc=2 refs=1 | cabac=0 sliceGroups=1",
                "VIDEO: 车机经屏幕数据口回传 4B（累计 4B）前 32 字节=00 01 02 03",
                "ENV: Wi-Fi rssi=-48dBm 5745MHz 标准=5 速率=433Mbps tx=433 rx=390；温控=正常 亮屏=true",
                "EVENT: 本轮车机命令统计 forceKeyFrame=60 requestUI=20 hidSendReport=0",
            )
        val safe = DiagnosticRedactor().redact(lines.joinToString("\n"))
        assertEquals(lines.joinToString("\n"), safe)
    }

    @Test
    fun hidesCredentialsAndShortSessionKeys() {
        val safe =
            DiagnosticRedactor().redact(
                "psk=VerySecret123 password=\"two words\" readKey=0123456789abcdef writeKey=fedcba9876543210 nonce=challenge",
            )
        listOf("VerySecret123", "two words", "0123456789abcdef", "fedcba9876543210", "challenge").forEach {
            assertFalse(safe.contains(it))
        }
        assertTrue(safe.contains("psk=<redacted>"))
    }

    @Test
    fun removesBearerCredentialsAndPemBlocks() {
        val pem = "-----BEGIN " + "PRIVATE KEY-----\nprivate-base64-material\n-----END PRIVATE KEY-----"
        val safe = DiagnosticRedactor().redact("Authorization: Bearer special-access-token\n$pem")
        assertFalse(safe.contains("special-access-token"))
        assertFalse(safe.contains("private-base64-material"))
    }

    @Test
    fun omitsWireDumpsThatCouldContainHexEncodedCredentials() {
        val safe = DiagnosticRedactor().redact("[12:34:56.000] RAW tx: 70617373776f7264\nbodyHex=deadbeef\n0x4301 原始体=secret")
        assertFalse(safe.contains("70617373776f7264"))
        assertFalse(safe.contains("deadbeef"))
        assertTrue(safe.contains("[12:34:56.000] [wire payload omitted]"))
    }

    @Test
    fun preservesTimesVersionsPortsErrorCodesAndStackFrames() {
        val safe =
            DiagnosticRedactor().redact(
                "[12:34:56.789] version=0.1.0 port=7240 stage=CARWITH error=ECONNREFUSED\n" +
                    " at com.mcarplay.app.carlife.CarLifeSession.start(CarLifeSession.kt:42)",
            )
        assertTrue(safe.contains("12:34:56.789"))
        assertTrue(safe.contains("version=0.1.0 port=7240"))
        assertTrue(safe.contains("ECONNREFUSED"))
        assertTrue(safe.contains("CarLifeSession.kt:42"))
    }

    @Test
    fun correlatesAddressesWithoutRevealingThem() {
        val safe =
            DiagnosticRedactor().redact(
                "10.2.3.4:7240 10.2.3.4:8240 AA:BB:CC:DD:EE:FF aa:bb:cc:dd:ee:ff [fe80::12ab%wlan0]:7000",
            )
        assertFalse(safe.contains("10.2.3.4"))
        assertFalse(safe.contains("fe80::12ab"))
        assertFalse(safe.contains("AA:BB"))
        assertEquals(2, Regex("<IP-1>").findAll(safe).count())
        assertEquals(2, Regex("<MAC-1>").findAll(safe).count())
    }

    @Test
    fun hidesNamesEvenWhenLaterMentionedWithoutLabels() {
        val safe = DiagnosticRedactor().redact("ssid=PrivateHome\n正在加入 PrivateHome…\n目标「Family Pad」\nFamily Pad disconnected")
        assertFalse(safe.contains("PrivateHome"))
        assertFalse(safe.contains("Family Pad"))
        assertEquals(2, Regex("<NAME-1>").findAll(safe).count())
    }

    @Test
    fun invitedWifiLogLineHidesLiveSsid() {
        val safe =
            DiagnosticRedactor().redact(
                ">>> [主动连车机] 车机已在 WiFi 上发 ctrl 邀请（无 0x5703/0x4301）——按手机当前 WiFi \"MIB3 Car AP\" 绑定后继续",
            )
        assertFalse(safe, safe.contains("MIB3 Car AP"))
        assertTrue(safe, safe.contains("0x5703/0x4301"))
    }

    @Test
    fun ssidWithSpacesIsHiddenWithoutRemovingFollowingTechnicalFields() {
        val safe = DiagnosticRedactor().redact("ssid=Private Family WiFi channel=48 security=2\njoining Private Family WiFi")
        assertFalse(safe.contains("Private Family WiFi"))
        assertFalse(safe.contains("Family"))
        assertTrue(safe.contains("channel=48 security=2"))
    }

    @Test
    fun usesKnownNamesWhenRollingLogNoLongerContainsTheirDeclaration() {
        assertFalse(DiagnosticRedactor(listOf("FamilyCar")).redact("连接 FamilyCar 失败").contains("FamilyCar"))
    }

    @Test
    fun hidesContactInformationLocationsUrlsAndFullDigestsInUntrustedLogs() {
        val safe =
            DiagnosticRedactor().redact(
                "mail=example@example.test phone=13912345678 latitude=30.123 longitude=120.456 " +
                    "https://example.test/?token=hidden 0123456789abcdef0123456789abcdef",
            )
        listOf("example@example.test", "13912345678", "30.123", "120.456", "example.test", "0123456789abcdef").forEach {
            assertFalse(safe.contains(it))
        }
    }

    @Test
    fun knownNameIsReplacedOnlyAsWholeToken() {
        val safe = DiagnosticRedactor(listOf("CarPlay")).redact("CarPlayService started\nconnecting CarPlay now")
        assertTrue(safe.contains("CarPlayService started"))
        assertTrue(safe.contains("connecting <NAME-1> now"))
    }

    @Test
    fun nameLabelInsideIdentifierAndAppConstantsAreNotAliased() {
        val safe =
            DiagnosticRedactor().redact(
                "广播 _carplay-ctrl._tcp name=sender id=x\nVideoSender: sender ready\nserviceName=Lobby lobby",
            )
        assertTrue(safe.contains("name=sender"))
        assertTrue(safe.contains("VideoSender: sender ready"))
        assertTrue(safe.contains("serviceName=Lobby"))
        assertFalse(safe.contains("<NAME-"))
    }

    @Test
    fun sourceScrubUsesGenericPlaceholders() {
        assertEquals("", DiagnosticRedactor.scrubAtSource(""))
        val safe = DiagnosticRedactor.scrubAtSource("peer 10.0.0.1 ssid=Home")
        assertEquals("peer <redacted-at-source> ssid=<redacted-at-source>", safe)
    }
}
