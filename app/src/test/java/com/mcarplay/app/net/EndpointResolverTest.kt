package com.mcarplay.app.net

import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.iap2.Iap2SenderSession.CarPlayEndpoint
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.iap2.WifiCredentialSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [EndpointResolver] 的确定性 JVM 测试矩阵（Phase 2b 设计方案 §6.2–§6.3：J-M1..J-M7、J-T1..J-T2、
 * J-R1..J-R4、J-E1..J-E2、J-N1、J-W1..J-W13）。
 *
 * 全程使用可控单调 [FakeClock] 与事件记录端口，绝不调用真实 `Thread.sleep` 或依赖 Android NSD stub。
 */
class EndpointResolverTest {
    private sealed interface Event {
        data class ClockRead(
            val value: Long,
        ) : Event

        data class Sleep(
            val millis: Long,
            val clockAfter: Long,
        ) : Event

        data class CheckCancelled(
            val cancelled: Boolean,
        ) : Event

        data object CaptureInviteView : Event

        data class ReadInviteCount(
            val label: String,
            val value: Int,
        ) : Event

        data class ReadLastInviteAt(
            val label: String,
            val value: Long,
        ) : Event

        data class ReadAdvertisedAt(
            val label: String,
            val value: Long,
        ) : Event

        data class AwaitEndpoint(
            val timeoutMillis: Long,
        ) : Event

        data class DirectDiscoveryPolicy(
            val hostAddress: String?,
            val result: Boolean,
        ) : Event

        data class ForceAnnounce(
            val ssid: String,
            val advertiserLabel: String?,
            val networkTag: String?,
        ) : Event

        data object GatewayCandidates : Event

        data object ResolutionNeeded : Event

        data class ReadTxt(
            val key: String,
        ) : Event

        data class Log(
            val message: String,
        ) : Event
    }

    private class FakeClock(
        initialMillis: Long = 100_000L,
    ) {
        var now: Long = initialMillis
        private val scriptedReads = ArrayDeque<Long>()
        private val scriptedSleepTargets = ArrayDeque<Long>()

        fun queueReads(vararg timestamps: Long) {
            timestamps.forEach(scriptedReads::addLast)
        }

        fun queueSleepTargets(vararg timestamps: Long) {
            timestamps.forEach(scriptedSleepTargets::addLast)
        }

        fun read(): Long {
            if (scriptedReads.isNotEmpty()) {
                now = scriptedReads.removeFirst()
            }
            return now
        }

        fun advanceOnSleep(millis: Long): Long {
            now =
                if (scriptedSleepTargets.isNotEmpty()) {
                    scriptedSleepTargets.removeFirst()
                } else {
                    now + millis
                }
            return now
        }
    }

    private class FakeDiscoveredEndpoint(
        override val hostAddress: String? = null,
        override val port: Int = 0,
        private val attributes: Map<String, ByteArray?> = emptyMap(),
        private val onTxtRead: (String) -> Unit = {},
    ) : DiscoveredEndpoint {
        val accessedKeys = ArrayList<String>()

        override fun txtBytes(key: String): ByteArray? {
            accessedKeys += key
            onTxtRead(key)
            return attributes[key]
        }
    }

    private class FakeInviteView(
        val label: String = "A",
        inviteCount: Int = 0,
        lastInviteAtMillis: Long = 0L,
        advertisedAtMillis: Long = 0L,
        private val sink: (Event) -> Unit = {},
    ) : InviteView {
        var currentInviteCount: Int = inviteCount
        var currentLastInviteAtMillis: Long = lastInviteAtMillis
        var currentAdvertisedAtMillis: Long = advertisedAtMillis

        override val inviteCount: Int
            get() = currentInviteCount.also { sink(Event.ReadInviteCount(label, it)) }

        override val lastInviteAtMillis: Long
            get() = currentLastInviteAtMillis.also { sink(Event.ReadLastInviteAt(label, it)) }

        override val advertisedAtMillis: Long
            get() = currentAdvertisedAtMillis.also { sink(Event.ReadAdvertisedAt(label, it)) }
    }

    private class Harness(
        initialClockMillis: Long = 100_000L,
        val resolveWaitMillis: Long = 3_000L,
        private val maxSleeps: Int = 300,
    ) {
        val clock = FakeClock(initialClockMillis)
        val events = ArrayList<Event>()
        val logs = ArrayList<String>()
        var recordClockReads: Boolean = false

        var cancelled: Boolean = false
        var activeAdvertiser: FakeInviteView? =
            FakeInviteView(label = "A") { event -> events += event }
        var activeNetworkTag: String? = "net-1"
        var zeroWaitDiscovered: DiscoveredEndpoint? = null
        var blockingDiscovered: DiscoveredEndpoint? = null
        var gateways: List<String> = emptyList()
        var policyResult: Boolean = false

        var onIsCancelled: (() -> Boolean)? = null
        var onAwaitEndpoint: ((Long) -> DiscoveredEndpoint?)? = null
        var onDirectDiscoveryPolicy: ((CarPlayEndpoint, DiscoveredEndpoint) -> Boolean)? = null
        var onForceAnnounce: ((String) -> Unit)? = null
        var onSleep: ((Long, Int) -> Unit)? = null
        var onGatewayCandidates: (() -> List<String>)? = null

        var sleepCount: Int = 0
            private set

        val resolver =
            EndpointResolver(
                uptimeMillis = {
                    val value = clock.read()
                    if (recordClockReads) events += Event.ClockRead(value)
                    value
                },
                sleepMillis = { millis ->
                    sleepCount++
                    check(sleepCount <= maxSleeps) { "超过最大假睡眠次数 $maxSleeps，疑似死循环" }
                    val after = clock.advanceOnSleep(millis)
                    events += Event.Sleep(millis, after)
                    onSleep?.invoke(millis, sleepCount)
                },
                isConnectCancelled = {
                    val result = onIsCancelled?.invoke() ?: cancelled
                    events += Event.CheckCancelled(result)
                    result
                },
                captureInviteView = {
                    events += Event.CaptureInviteView
                    val captured = activeAdvertiser
                    captured?.let {
                        object : InviteView {
                            override val inviteCount: Int
                                get() = it.inviteCount
                            override val lastInviteAtMillis: Long
                                get() = it.lastInviteAtMillis
                            override val advertisedAtMillis: Long
                                get() = it.advertisedAtMillis
                        }
                    }
                },
                awaitEndpoint = { timeoutMs ->
                    events += Event.AwaitEndpoint(timeoutMs)
                    val handler = onAwaitEndpoint
                    if (handler != null) {
                        handler(timeoutMs)
                    } else if (timeoutMs == 0L) {
                        zeroWaitDiscovered
                    } else {
                        blockingDiscovered
                    }
                },
                forceAnnounce = { ssid ->
                    val currentAdv = activeAdvertiser
                    val net = if (currentAdv != null) activeNetworkTag else null
                    events += Event.ForceAnnounce(ssid, currentAdv?.label, net)
                    onForceAnnounce?.invoke(ssid)
                },
                gatewayCandidates = {
                    events += Event.GatewayCandidates
                    onGatewayCandidates?.invoke() ?: gateways
                },
                directDiscoveryPolicy = { ep, discovered ->
                    val decision = onDirectDiscoveryPolicy?.invoke(ep, discovered) ?: policyResult
                    events += Event.DirectDiscoveryPolicy(discovered.hostAddress, decision)
                    decision
                },
                log = { message ->
                    logs += message
                    events += Event.Log(message)
                },
                resolveWaitMillis = resolveWaitMillis,
            )
    }

    private fun endpoint(
        ssid: String = "CarPlay-AP",
        passphrase: String = "secret123",
        channel: Int = 36,
        securityType: Int = 2,
        airPlayPort: Int = 7000,
        deviceIdentifier: String? = null,
        publicKey: String? = null,
        sourceVersion: String? = null,
        ipAddresses: List<String> = listOf("10.174.189.1"),
        advertisedFeatures: Long? = null,
        receiverSignals: ReceiverProfile.Signals = ReceiverProfile.Signals(),
        observation: ReceiverObservation? = null,
        portIsFallback: Boolean = false,
        peerAddress: String? = null,
        wifiCredentialSource: WifiCredentialSource = WifiCredentialSource.ACCESSORY,
    ): CarPlayEndpoint =
        CarPlayEndpoint(
            ssid = ssid,
            passphrase = passphrase,
            channel = channel,
            securityType = securityType,
            airPlayPort = airPlayPort,
            deviceIdentifier = deviceIdentifier,
            publicKey = publicKey,
            sourceVersion = sourceVersion,
            ipAddresses = ipAddresses,
            advertisedFeatures = advertisedFeatures,
            receiverSignals = receiverSignals,
            observation = observation,
            portIsFallback = portIsFallback,
            peerAddress = peerAddress,
            wifiCredentialSource = wifiCredentialSource,
        )

    private fun txtMap(vararg pairs: Pair<String, String>): Map<String, ByteArray?> =
        pairs.associate { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }

    // ---- §6.2 J：合并、地址、TXT 与刷新 ----

    @Test
    fun jM1_preferEndpointWifiCredentialSourceRankAndBlankSsid() {
        val rankedSources =
            listOf(
                WifiCredentialSource.NONE to 0,
                WifiCredentialSource.KNOWN_SSID to 1,
                WifiCredentialSource.CURRENT_NETWORK to 2,
                WifiCredentialSource.ACCESSORY to 3,
            )
        for ((lateSource, lateRank) in rankedSources) {
            for ((fallbackSource, fallbackRank) in rankedSources) {
                val late =
                    endpoint(
                        ssid = "Late-SSID",
                        passphrase = "late-password",
                        channel = 6,
                        securityType = 1,
                        wifiCredentialSource = lateSource,
                    )
                val fallback =
                    endpoint(
                        ssid = "Fallback-SSID",
                        passphrase = "fallback-password",
                        channel = 149,
                        securityType = 3,
                        wifiCredentialSource = fallbackSource,
                    )
                val merged = EndpointResolver.preferEndpoint(late, fallback)
                if (fallbackRank > lateRank) {
                    assertEquals("Fallback-SSID", merged.ssid)
                    assertEquals("fallback-password", merged.passphrase)
                    assertEquals(149, merged.channel)
                    assertEquals(3, merged.securityType)
                    assertEquals(fallbackSource, merged.wifiCredentialSource)
                } else {
                    assertEquals("Late-SSID", merged.ssid)
                    assertEquals("late-password", merged.passphrase)
                    assertEquals(6, merged.channel)
                    assertEquals(1, merged.securityType)
                    assertEquals(lateSource, merged.wifiCredentialSource)
                }
            }
        }

        // fallback 虽有更高 rank，但 SSID 为空或纯空白时，绝不覆盖 late 的 Wi-Fi 字段
        for (blankSsid in listOf("", "   ")) {
            val late =
                endpoint(
                    ssid = "Known-Only",
                    passphrase = "",
                    channel = 11,
                    securityType = 0,
                    wifiCredentialSource = WifiCredentialSource.KNOWN_SSID,
                )
            val blankFallback =
                endpoint(
                    ssid = blankSsid,
                    passphrase = "should-not-leak",
                    channel = 44,
                    securityType = 2,
                    wifiCredentialSource = WifiCredentialSource.ACCESSORY,
                )
            val merged = EndpointResolver.preferEndpoint(late, blankFallback)
            assertEquals("Known-Only", merged.ssid)
            assertEquals("", merged.passphrase)
            assertEquals(11, merged.channel)
            assertEquals(0, merged.securityType)
            assertEquals(WifiCredentialSource.KNOWN_SSID, merged.wifiCredentialSource)
        }
    }

    @Test
    fun jM2_preferEndpointPortAndFallbackFlag() {
        val latePorts = listOf(7000, 5000, 0, -1)
        val fallbackPorts = listOf(6000, 0, -2)
        val booleans = listOf(false, true)

        for (latePort in latePorts) {
            for (lateFallbackFlag in booleans) {
                for (fallbackPort in fallbackPorts) {
                    for (fallbackFlag in booleans) {
                        val late = endpoint(airPlayPort = latePort, portIsFallback = lateFallbackFlag)
                        val fallback = endpoint(airPlayPort = fallbackPort, portIsFallback = fallbackFlag)
                        val merged = EndpointResolver.preferEndpoint(late, fallback)

                        val expectedPort = if (latePort > 0) latePort else fallbackPort
                        val expectedFallback = latePort <= 0 && fallbackFlag
                        assertEquals(expectedPort, merged.airPlayPort)
                        assertEquals(expectedFallback, merged.portIsFallback)
                    }
                }
            }
        }

        // 显式钉住现状：late 带正端口且自身 portIsFallback=true 时，仍按表达式清为 false
        val positiveLateWithFallbackTrue =
            EndpointResolver.preferEndpoint(
                endpoint(airPlayPort = 7000, portIsFallback = true),
                endpoint(airPlayPort = 7000, portIsFallback = true),
            )
        assertEquals(7000, positiveLateWithFallbackTrue.airPlayPort)
        assertFalse(positiveLateWithFallbackTrue.portIsFallback)
    }

    @Test
    fun jM3_preferEndpointIpUnionPreservesRawOrderAndDistinctWithoutScopeNormalization() {
        val lateIps = listOf("fe80::1%wlan0", "FE80::1%wlan0", "", "  ", "10.0.0.1\u0000", "192.168.1.1")
        val fallbackIps = listOf("fe80::1", "fe80::1%wlan0", "10.0.0.2", "192.168.1.1")
        val late = endpoint(ipAddresses = lateIps)
        val fallback = endpoint(ipAddresses = fallbackIps)

        val merged = EndpointResolver.preferEndpoint(late, fallback)

        // prefer 按原字符串 distinct 保序，不做去 scope、小写化、过滤或 IPv4 重排
        assertEquals(
            listOf("fe80::1%wlan0", "FE80::1%wlan0", "", "  ", "10.0.0.1\u0000", "192.168.1.1", "fe80::1", "10.0.0.2"),
            merged.ipAddresses,
        )
        // 原输入未被修改
        assertEquals(lateIps, late.ipAddresses)
        assertEquals(fallbackIps, fallback.ipAddresses)

        // 对比 EndpointHosts.candidates：按去 scope 小写去重且 IPv4 优先
        assertEquals(
            listOf("10.0.0.1\u0000", "192.168.1.1", "10.0.0.2", "fe80::1%wlan0"),
            EndpointHosts.candidates(merged.ipAddresses),
        )
    }

    @Test
    fun jM4_preferEndpointNullableMetadataSignalsAndObservationOwnership() {
        val obsLate = ReceiverObservation(binding = "bt-1", epoch = 1L, session = 1L)
        val obsFallback = ReceiverObservation(binding = "bt-2", epoch = 1L, session = 2L)
        val fallbackSignals =
            ReceiverProfile.Signals(
                manufacturer = "FallbackMfg",
                model = "FallbackModel",
                sendIds = setOf(0x5703),
            )

        // 1) late 元数据为 null、signals 全空：全部回退到 fallback；observation/peerAddress 仍取自 late
        val allNullLate =
            endpoint(
                publicKey = null,
                deviceIdentifier = null,
                sourceVersion = null,
                advertisedFeatures = null,
                receiverSignals = ReceiverProfile.Signals(),
                observation = obsLate,
                peerAddress = "AA:BB:CC:DD:EE:01",
            )
        val richFallback =
            endpoint(
                publicKey = "pk-fallback",
                deviceIdentifier = "dev-fallback",
                sourceVersion = "365.10.2",
                advertisedFeatures = 0x61_5653aee2L,
                receiverSignals = fallbackSignals,
                observation = obsFallback,
                peerAddress = "AA:BB:CC:DD:EE:02",
            )
        val mergedFromFallback = EndpointResolver.preferEndpoint(allNullLate, richFallback)
        assertEquals("pk-fallback", mergedFromFallback.publicKey)
        assertEquals("dev-fallback", mergedFromFallback.deviceIdentifier)
        assertEquals("365.10.2", mergedFromFallback.sourceVersion)
        assertEquals(0x61_5653aee2L, mergedFromFallback.advertisedFeatures)
        assertEquals(fallbackSignals, mergedFromFallback.receiverSignals)
        assertSame(obsLate, mergedFromFallback.observation)
        assertEquals("AA:BB:CC:DD:EE:01", mergedFromFallback.peerAddress)

        // 2) late 已有空串或 0L features，以及部分非空 signals：不回退、不逐字段拼接
        val partialSignals = ReceiverProfile.Signals(manufacturer = "LateOnlyMfg")
        val nonNullEmptyLate =
            endpoint(
                publicKey = "",
                deviceIdentifier = "",
                sourceVersion = "",
                advertisedFeatures = 0L,
                receiverSignals = partialSignals,
                observation = obsLate,
                peerAddress = null,
            )
        val mergedKeepLate = EndpointResolver.preferEndpoint(nonNullEmptyLate, richFallback)
        assertEquals("", mergedKeepLate.publicKey)
        assertEquals("", mergedKeepLate.deviceIdentifier)
        assertEquals("", mergedKeepLate.sourceVersion)
        assertEquals(0L, mergedKeepLate.advertisedFeatures)
        assertEquals(partialSignals, mergedKeepLate.receiverSignals)
        assertSame(obsLate, mergedKeepLate.observation)
        assertNull(mergedKeepLate.peerAddress)
    }

    @Test
    fun jM5_mergeBrowserEndpointPrependsNewAddressAndLogsOnce() {
        val harness = Harness()
        val initial = endpoint(ipAddresses = listOf("fe80::3ca8:64ff:fe7c:293e%wlan0"), airPlayPort = 7000)

        // 1) mDNS 解析出 IPv4：前插到列表最前并记录一次 L10
        val ipv4Info = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        val withIpv4 = harness.resolver.mergeBrowserEndpoint(initial, ipv4Info, "BT", needPort = false)
        assertEquals(listOf("10.174.189.1", "fe80::3ca8:64ff:fe7c:293e%wlan0"), withIpv4.ipAddresses)
        assertEquals(listOf(">>> [BT] mDNS 解析到车机地址 10.174.189.1:7000"), harness.logs)
        assertEquals(
            listOf("10.174.189.1", "fe80::3ca8:64ff:fe7c:293e%wlan0"),
            EndpointHosts.candidates(withIpv4.ipAddresses),
        )

        // 2) 完全相同地址：不重复前插，不写 L10
        harness.logs.clear()
        val duplicate = harness.resolver.mergeBrowserEndpoint(withIpv4, ipv4Info, "BT", needPort = false)
        assertEquals(withIpv4.ipAddresses, duplicate.ipAddresses)
        assertTrue(harness.logs.isEmpty())

        // 3) 仅 scope 后缀不同：stripScope 相等，不前插、不写 L10
        val differentScopeInfo = FakeDiscoveredEndpoint(hostAddress = "fe80::3ca8:64ff:fe7c:293e%3", port = 7000)
        val sameBase = harness.resolver.mergeBrowserEndpoint(withIpv4, differentScopeInfo, "BT", needPort = false)
        assertEquals(withIpv4.ipAddresses, sameBase.ipAddresses)
        assertTrue(harness.logs.isEmpty())

        // 4) 大小写不同：stripScope 区分大小写，前插并写一次 L10，但 EndpointHosts.candidates 仍合并去重
        val upperCaseInfo = FakeDiscoveredEndpoint(hostAddress = "FE80::3CA8:64FF:FE7C:293E%wlan0", port = 7000)
        val withUpper = harness.resolver.mergeBrowserEndpoint(withIpv4, upperCaseInfo, "BT", needPort = false)
        assertEquals(
            listOf("FE80::3CA8:64FF:FE7C:293E%wlan0", "10.174.189.1", "fe80::3ca8:64ff:fe7c:293e%wlan0"),
            withUpper.ipAddresses,
        )
        assertEquals(
            listOf(">>> [BT] mDNS 解析到车机地址 FE80::3CA8:64FF:FE7C:293E%wlan0:7000"),
            harness.logs,
        )
        assertEquals(
            listOf("10.174.189.1", "FE80::3CA8:64FF:FE7C:293E%wlan0"),
            EndpointHosts.candidates(withUpper.ipAddresses),
        )

        // 5) 非法或空地址：candidates 为空，忽略且无 L10
        harness.logs.clear()
        for (invalidHost in listOf(null, "", "   ", "not-a-host")) {
            val res =
                harness.resolver.mergeBrowserEndpoint(
                    withIpv4,
                    FakeDiscoveredEndpoint(hostAddress = invalidHost, port = 7000),
                    "BT",
                    needPort = false,
                )
            assertEquals(withIpv4.ipAddresses, res.ipAddresses)
        }
        assertTrue(harness.logs.isEmpty())
    }

    @Test
    fun jM6_mergeBrowserEndpointPortOverwriteRulesAndNoAddressLogOnPortChange() {
        val harness = Harness()
        val infoPorts = listOf(7100, 0, -5)
        val booleans = listOf(false, true)

        for (needPort in booleans) {
            for (isFallback in booleans) {
                for (infoPort in infoPorts) {
                    val basePort = if (needPort) 0 else 5000
                    val ep =
                        endpoint(
                            ipAddresses = listOf("10.174.189.1"),
                            airPlayPort = basePort,
                            portIsFallback = isFallback,
                        )
                    val info = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = infoPort)
                    val merged = harness.resolver.mergeBrowserEndpoint(ep, info, "BT", needPort = needPort)

                    if ((needPort || isFallback) && infoPort > 0) {
                        assertEquals(infoPort, merged.airPlayPort)
                        assertFalse(merged.portIsFallback)
                    } else {
                        assertEquals(basePort, merged.airPlayPort)
                        assertEquals(isFallback, merged.portIsFallback)
                    }
                }
            }
        }
        // 地址已存在时，单纯端口变化不产生 L10
        assertTrue(harness.logs.isEmpty())
    }

    @Test
    fun jM7_mergeBrowserEndpointTxtFieldsFillOnlyNullAndPreserveAuthoritative() {
        val harness = Harness()
        val emptyMeta =
            endpoint(
                publicKey = null,
                deviceIdentifier = null,
                sourceVersion = null,
                advertisedFeatures = null,
            )
        val validInfo =
            FakeDiscoveredEndpoint(
                hostAddress = "10.174.189.1",
                port = 7000,
                attributes =
                    txtMap(
                        "pk" to "pk-from-mdns\u0000",
                        "deviceid" to "AA:BB:CC:DD:EE:FF",
                        "srcvers" to "366.0",
                        "features" to "0x5653aee2,0x61",
                    ),
            )
        val filled = harness.resolver.mergeBrowserEndpoint(emptyMeta, validInfo, "BT", needPort = false)
        assertEquals("pk-from-mdns", filled.publicKey)
        assertEquals("AA:BB:CC:DD:EE:FF", filled.deviceIdentifier)
        assertEquals("366.0", filled.sourceVersion)
        assertEquals((0x61L shl 32) or 0x5653aee2L, filled.advertisedFeatures)
        assertEquals(listOf("pk", "deviceid", "srcvers", "features"), validInfo.accessedKeys)
        assertTrue(harness.logs.isEmpty())

        // 坏 features 保持 null，不降级成 0L
        val badFeaturesInfo =
            FakeDiscoveredEndpoint(
                hostAddress = "10.174.189.1",
                port = 7000,
                attributes = txtMap("features" to "invalid-hex"),
            )
        val badFeaturesResult = harness.resolver.mergeBrowserEndpoint(emptyMeta, badFeaturesInfo, "BT", needPort = false)
        assertNull(badFeaturesResult.advertisedFeatures)

        // 端点已有非 null 值（包括空串与 0L features）时不覆盖，也不读取对应 TXT 键
        val authoritative =
            endpoint(
                publicKey = "auth-pk",
                deviceIdentifier = "",
                sourceVersion = "210.81",
                advertisedFeatures = 0L,
            )
        val probeInfo =
            FakeDiscoveredEndpoint(
                hostAddress = "10.174.189.1",
                port = 7000,
                attributes =
                    txtMap(
                        "pk" to "mdns-pk",
                        "deviceid" to "mdns-id",
                        "srcvers" to "mdns-ver",
                        "features" to "0x5653aee2,0x61",
                    ),
            )
        val preserved = harness.resolver.mergeBrowserEndpoint(authoritative, probeInfo, "BT", needPort = false)
        assertEquals("auth-pk", preserved.publicKey)
        assertEquals("", preserved.deviceIdentifier)
        assertEquals("210.81", preserved.sourceVersion)
        assertEquals(0L, preserved.advertisedFeatures)
        assertTrue(probeInfo.accessedKeys.isEmpty())
        assertTrue(harness.logs.isEmpty())
    }

    @Test
    fun jT1_txtValueKeySensitivityEmptyAndTrailingNuls() {
        val info =
            FakeDiscoveredEndpoint(
                attributes =
                    mapOf(
                        "pk" to "value\u0000".toByteArray(Charsets.UTF_8),
                        "multiNul" to "value\u0000\u0000\u0000".toByteArray(Charsets.UTF_8),
                        "onlyNul" to "\u0000\u0000".toByteArray(Charsets.UTF_8),
                        "empty" to ByteArray(0),
                        "nullBytes" to null,
                    ),
            )
        assertNull(EndpointResolver.txtValue(info, "missing"))
        assertNull(EndpointResolver.txtValue(info, "PK"))
        assertNull(EndpointResolver.txtValue(info, "empty"))
        assertNull(EndpointResolver.txtValue(info, "nullBytes"))
        assertNull(EndpointResolver.txtValue(info, "onlyNul"))
        assertEquals("value", EndpointResolver.txtValue(info, "pk"))
        assertEquals("value", EndpointResolver.txtValue(info, "multiNul"))
    }

    @Test
    fun jT2_txtValueUtf8InvalidBytesLeadingInternalNulAndWhitespace() {
        val rawInvalid = byteArrayOf(0xFF.toByte(), 'A'.code.toByte(), 0)
        val originalCopy = rawInvalid.copyOf()
        val info =
            FakeDiscoveredEndpoint(
                attributes =
                    mapOf(
                        "chinese" to "帕萨特 ORA 03\u0000".toByteArray(Charsets.UTF_8),
                        "invalid" to rawInvalid,
                        "internalNul" to "\u0000a\u0000b\u0000\u0000".toByteArray(Charsets.UTF_8),
                        "spaces" to "  AbC  \u0000".toByteArray(Charsets.UTF_8),
                        "onlySpaces" to "   ".toByteArray(Charsets.UTF_8),
                    ),
            )
        assertEquals("帕萨特 ORA 03", EndpointResolver.txtValue(info, "chinese"))
        assertEquals("\uFFFDA", EndpointResolver.txtValue(info, "invalid"))
        assertTrue(rawInvalid.contentEquals(originalCopy))
        assertEquals("\u0000a\u0000b", EndpointResolver.txtValue(info, "internalNul"))
        assertEquals("  AbC  ", EndpointResolver.txtValue(info, "spaces"))
        assertEquals("   ", EndpointResolver.txtValue(info, "onlySpaces"))
    }

    @Test
    fun jR1_resolveEndpointHostSelfContainedIpv4OrIpv6AlwaysChecksZeroWaitCacheFirst() {
        // 1) IPv6-only + 合法端口，零等待缓存未命中：hasUsable=true，直接返回，不触发 UI/L7/长等待/网关
        val harness1 = Harness()
        val ipv6Only = endpoint(ipAddresses = listOf("fe80::3ca8:64ff:fe7c:293e%wlan0"), airPlayPort = 7000)
        var uiCalled1 = false
        val resolved1 =
            harness1.resolver.resolveEndpointHost(ipv6Only, "BT") {
                uiCalled1 = true
                harness1.events += Event.ResolutionNeeded
            }
        assertEquals(ipv6Only, resolved1)
        assertFalse(uiCalled1)
        assertEquals(listOf(Event.AwaitEndpoint(0L)), harness1.events)
        assertTrue(harness1.logs.isEmpty())

        // 2) IPv6-only + 合法端口，零等待缓存命中 IPv4：前插 IPv4、记录 L10，不触发 UI/L7/长等待
        val harness2 = Harness()
        harness2.zeroWaitDiscovered =
            FakeDiscoveredEndpoint(
                hostAddress = "10.174.189.1",
                port = 7000,
                attributes = txtMap("pk" to "cached-pk"),
            )
        var uiCalled2 = false
        val resolved2 =
            harness2.resolver.resolveEndpointHost(ipv6Only, "BT") {
                uiCalled2 = true
                harness2.events += Event.ResolutionNeeded
            }
        assertFalse(uiCalled2)
        assertEquals(listOf("10.174.189.1", "fe80::3ca8:64ff:fe7c:293e%wlan0"), resolved2.ipAddresses)
        assertEquals("cached-pk", resolved2.publicKey)
        assertEquals(
            listOf(
                Event.AwaitEndpoint(0L),
                Event.Log(">>> [BT] mDNS 解析到车机地址 10.174.189.1:7000"),
            ),
            harness2.events,
        )

        // 3) 输入缺端口，但零等待缓存已补齐端口：合并后不再阻塞等待
        val harness3 = Harness()
        harness3.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        val missingPort = endpoint(ipAddresses = listOf("10.174.189.1"), airPlayPort = 0)
        val resolved3 =
            harness3.resolver.resolveEndpointHost(missingPort, "BT") {
                fail("零等待缓存已补齐端口，不应触发 UI")
            }
        assertEquals(7000, resolved3.airPlayPort)
        assertEquals(listOf(Event.AwaitEndpoint(0L)), harness3.events)
    }

    @Test
    fun jR2_resolveEndpointHostIncompleteCacheMissThenBlockingHitOrder() {
        val harness = Harness()
        val obs = ReceiverObservation(binding = "bt-1", epoch = 1L, session = 1L)
        val incomplete =
            endpoint(
                ipAddresses = emptyList(),
                airPlayPort = 0,
                publicKey = null,
                observation = obs,
            )
        harness.zeroWaitDiscovered = null
        harness.blockingDiscovered =
            FakeDiscoveredEndpoint(
                hostAddress = "192.168.43.1",
                port = 7000,
                attributes = txtMap("pk" to "resolved-pk"),
            )

        val resolved =
            harness.resolver.resolveEndpointHost(incomplete, "0x4301") {
                harness.events += Event.ResolutionNeeded
            }

        assertEquals(listOf("192.168.43.1"), resolved.ipAddresses)
        assertEquals(7000, resolved.airPlayPort)
        assertFalse(resolved.portIsFallback)
        assertEquals("resolved-pk", resolved.publicKey)
        assertSame(obs, resolved.observation)

        // 严格顺序：await(0) -> UI 回调 -> L7 -> await(3_000) -> merge(L10)
        assertEquals(
            listOf(
                Event.AwaitEndpoint(0L),
                Event.ResolutionNeeded,
                Event.Log(">>> [0x4301] 0x4301 端点不完整（port=0 ips=[]），取 mDNS 车机 _airplay._tcp…"),
                Event.AwaitEndpoint(3_000L),
                Event.Log(">>> [0x4301] mDNS 解析到车机地址 192.168.43.1:7000"),
            ),
            harness.events,
        )
    }

    @Test
    fun jR3_resolveEndpointHostGatewayFallbackAndNoServiceLogs() {
        // 1) 等待未命中且无网关：L7 -> L9，返回原 effective
        val harnessEmpty = Harness()
        val incomplete = endpoint(ipAddresses = emptyList(), airPlayPort = 0)
        val resultEmpty =
            harnessEmpty.resolver.resolveEndpointHost(incomplete, "BT") {
                harnessEmpty.events += Event.ResolutionNeeded
            }
        assertEquals(incomplete, resultEmpty)
        assertEquals(
            listOf(
                ">>> [BT] 0x4301 端点不完整（port=0 ips=[]），取 mDNS 车机 _airplay._tcp…",
                ">>> [BT] mDNS 未发现车机 AirPlay 服务（车机可能尚未启动无线会话）",
            ),
            harnessEmpty.logs,
        )

        // 2) 单条网关且缺端口：L8 无 others，前插网关，端口置 7000 且 portIsFallback=true
        val harnessSingle = Harness()
        harnessSingle.gateways = listOf("192.168.43.1")
        val resultSingle =
            harnessSingle.resolver.resolveEndpointHost(incomplete, "BT") {
                harnessSingle.events += Event.ResolutionNeeded
            }
        assertEquals(listOf("192.168.43.1"), resultSingle.ipAddresses)
        assertEquals(7000, resultSingle.airPlayPort)
        assertTrue(resultSingle.portIsFallback)
        assertEquals(
            listOf(
                ">>> [BT] 0x4301 端点不完整（port=0 ips=[]），取 mDNS 车机 _airplay._tcp…",
                ">>> [BT] mDNS 未发现，兜底使用网关地址 192.168.43.1:7000（车机热点网关=车机）",
            ),
            harnessSingle.logs,
        )

        // 3) 多条网关且已有非默认端口（needHost=true, needPort=false）：
        // 只前插第一条网关，保留原端口 5000，portIsFallback=needPort(false)，日志仍固定写 :7000 与其余网关
        val harnessMulti = Harness()
        harnessMulti.gateways = listOf("10.174.189.1", "fe80::1%wlan0", "10.0.0.1")
        val hasPortMissingHost = endpoint(ipAddresses = emptyList(), airPlayPort = 5000, portIsFallback = true)
        val resultMulti =
            harnessMulti.resolver.resolveEndpointHost(hasPortMissingHost, "BT") {
                harnessMulti.events += Event.ResolutionNeeded
            }
        assertEquals(listOf("10.174.189.1"), resultMulti.ipAddresses)
        assertEquals(5000, resultMulti.airPlayPort)
        assertFalse(resultMulti.portIsFallback)
        assertEquals(
            listOf(
                ">>> [BT] 0x4301 端点不完整（port=5000 ips=[]），取 mDNS 车机 _airplay._tcp…",
                ">>> [BT] mDNS 未发现，兜底使用网关地址 10.174.189.1:7000（车机热点网关=车机；其余默认路由网关 fe80::1%wlan0、10.0.0.1）",
            ),
            harnessMulti.logs,
        )
    }

    @Test
    fun jR4_resolveEndpointHostDoesNotCheckCancelInternallyOnWaitOrInterrupt() {
        // 1) 进入时已 cancelled，阻塞等待被中断但返回了当前缓存：内部不查 cancel，照常合并
        val harnessCached = Harness()
        harnessCached.cancelled = true
        harnessCached.onAwaitEndpoint = { timeoutMs ->
            if (timeoutMs == 0L) {
                null
            } else {
                FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
            }
        }
        val incomplete = endpoint(ipAddresses = emptyList(), airPlayPort = 0)
        val resolvedCached = harnessCached.resolver.resolveEndpointHost(incomplete, "BT")
        assertEquals(listOf("10.174.189.1"), resolvedCached.ipAddresses)
        assertEquals(7000, resolvedCached.airPlayPort)
        assertFalse(harnessCached.events.any { it is Event.CheckCancelled })

        // 2) 阻塞等待期间变更为 cancelled 且中断返回 null：内部仍走网关兜底，留给外层 scope 检查收口
        val harnessGateway = Harness()
        harnessGateway.gateways = listOf("10.174.189.1")
        harnessGateway.onAwaitEndpoint = { timeoutMs ->
            if (timeoutMs > 0L) harnessGateway.cancelled = true
            null
        }
        val resolvedGateway = harnessGateway.resolver.resolveEndpointHost(incomplete, "BT")
        assertEquals(listOf("10.174.189.1"), resolvedGateway.ipAddresses)
        assertEquals(7000, resolvedGateway.airPlayPort)
        assertTrue(resolvedGateway.portIsFallback)
        assertFalse(harnessGateway.events.any { it is Event.CheckCancelled })
    }

    @Test
    fun jE1_enrichEndpointFromBrowserCacheMissUnchangedAndSingleFieldRefresh() {
        val base =
            endpoint(
                ipAddresses = listOf("fe80::1%wlan0"),
                airPlayPort = 7000,
                publicKey = "pk-1",
                deviceIdentifier = "dev-1",
                sourceVersion = "365.10.2",
                advertisedFeatures = 0x80L,
            )

        // 1) 缓存未命中：返回 null，无日志，只调用 awaitEndpoint(0)
        val harnessMiss = Harness()
        assertNull(harnessMiss.resolver.enrichEndpointFromBrowser(base, "BT"))
        assertTrue(harnessMiss.logs.isEmpty())
        assertEquals(listOf(Event.AwaitEndpoint(0L)), harnessMiss.events)

        // 2) 缓存内容与当前端点等价：返回 null，静默
        val harnessSame = Harness()
        harnessSame.zeroWaitDiscovered =
            FakeDiscoveredEndpoint(
                hostAddress = "fe80::1%3",
                port = 7000,
                attributes =
                    txtMap(
                        "pk" to "pk-other",
                        "deviceid" to "dev-other",
                        "srcvers" to "999.0",
                        "features" to "0x5653aee2,0x61",
                    ),
            )
        assertNull(harnessSame.resolver.enrichEndpointFromBrowser(base, "BT"))
        assertTrue(harnessSame.logs.isEmpty())

        // 3) 新增 IPv4 地址：只打一条 L11，不打 L10
        val harnessAddr = Harness()
        harnessAddr.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        val refreshedAddr = harnessAddr.resolver.enrichEndpointFromBrowser(base, "BT")
        assertNotNull(refreshedAddr)
        assertEquals(listOf("10.174.189.1", "fe80::1%wlan0"), refreshedAddr!!.ipAddresses)
        assertEquals(
            listOf(">>> [BT] 配对期间 mDNS 刷新端点 → 10.174.189.1:7000"),
            harnessAddr.logs,
        )

        // 4) 仅补全一个缺失 TXT 字段（例如 publicKey）：同样只打一条 L11
        val harnessPk = Harness()
        harnessPk.zeroWaitDiscovered =
            FakeDiscoveredEndpoint(
                hostAddress = "fe80::1%wlan0",
                port = 7000,
                attributes = txtMap("pk" to "new-pk"),
            )
        val refreshedPk = harnessPk.resolver.enrichEndpointFromBrowser(base.copy(publicKey = null), "BT")
        assertNotNull(refreshedPk)
        assertEquals("new-pk", refreshedPk!!.publicKey)
        assertEquals(
            listOf(">>> [BT] 配对期间 mDNS 刷新端点 → fe80::1%wlan0:7000"),
            harnessPk.logs,
        )
    }

    @Test
    fun jE2_enrichEndpointFromBrowserReentrancyAndFallbackPortReplacement() {
        val harness = Harness()
        harness.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 5000)
        val withFallbackPort =
            endpoint(
                ipAddresses = listOf("10.174.189.1"),
                airPlayPort = 7000,
                portIsFallback = true,
            )

        // 第一次：fallback 7000 被真实端口 5000 替换，portIsFallback 清为 false，输出 L11
        val first = harness.resolver.enrichEndpointFromBrowser(withFallbackPort, "BT")
        assertNotNull(first)
        assertEquals(5000, first!!.airPlayPort)
        assertFalse(first.portIsFallback)
        assertEquals(listOf(">>> [BT] 配对期间 mDNS 刷新端点 → 10.174.189.1:5000"), harness.logs)

        // 第二次用刷新后的结果再调：无变化返回 null 且无新日志
        harness.logs.clear()
        assertNull(harness.resolver.enrichEndpointFromBrowser(first, "BT"))
        assertTrue(harness.logs.isEmpty())

        // 再次用原输入调用：resolver 不缓存上轮状态，仍可重入返回新结果并打 L11
        val repeated = harness.resolver.enrichEndpointFromBrowser(withFallbackPort, "BT")
        assertEquals(first, repeated)
        assertEquals(listOf(">>> [BT] 配对期间 mDNS 刷新端点 → 10.174.189.1:5000"), harness.logs)
    }

    @Test
    fun jN1_cleanAndInjectedDirtyNulAddressesFollowExistingBoundaries() {
        val harness = Harness()

        // 1) 正常已由 CSM 剥离尾部 NUL 的干净 IP：resolver 全程不引入任何 NUL
        val clean = endpoint(ipAddresses = listOf("10.174.189.1"), airPlayPort = 7000)
        harness.zeroWaitDiscovered =
            FakeDiscoveredEndpoint(
                hostAddress = "10.174.189.1",
                port = 7000,
                attributes = txtMap("srcvers" to "365.10.2\u0000\u0000"),
            )
        val mergedClean = harness.resolver.mergeBrowserEndpoint(clean, harness.zeroWaitDiscovered!!, "BT", needPort = false)
        assertEquals(listOf("10.174.189.1"), mergedClean.ipAddresses)
        assertEquals("365.10.2", mergedClean.sourceVersion)
        assertTrue(harness.logs.isEmpty())

        // 2) 人为注入带尾/内部 NUL 的脏 IP：resolver 不做隐式清洗，按原字符串比较与保留
        val dirtyLate = endpoint(ipAddresses = listOf("10.174.189.1\u0000", "10.0.\u0000.1"))
        val cleanFallback = endpoint(ipAddresses = listOf("10.174.189.1"))
        val preferred = EndpointResolver.preferEndpoint(dirtyLate, cleanFallback)
        assertEquals(listOf("10.174.189.1\u0000", "10.0.\u0000.1", "10.174.189.1"), preferred.ipAddresses)

        val mergedOnDirty =
            harness.resolver.mergeBrowserEndpoint(
                dirtyLate,
                FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000),
                "BT",
                needPort = false,
            )
        assertEquals(listOf("10.174.189.1", "10.174.189.1\u0000", "10.0.\u0000.1"), mergedOnDirty.ipAddresses)
        assertEquals(listOf(">>> [BT] mDNS 解析到车机地址 10.174.189.1:7000"), harness.logs)
    }

    // ---- §6.3 J：awaitCtrlInvite 循环与 #93 ----

    @Test
    fun jW1_awaitCtrlInviteFreshInviteAtEntryAndMidGraceWinsImmediately() {
        // 1) 入口已有新鲜邀请：仍先读 inviteCount -> awaitEndpoint(0) -> directDiscoveryPolicy -> Gate -> L1
        val harness1 = Harness(initialClockMillis = 100_000L)
        harness1.activeAdvertiser!!.apply {
            currentInviteCount = 1
            currentLastInviteAtMillis = 93_000L
            currentAdvertisedAtMillis = 99_500L
        }
        harness1.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        harness1.policyResult = true

        assertTrue(harness1.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(0, harness1.sleepCount)
        assertEquals(
            listOf("CTRL: ✓ 收到车机邀请（0ms，第 1 次）——立即连接 AirPlay"),
            harness1.logs,
        )
        assertEquals(
            listOf(
                Event.CaptureInviteView,
                Event.CheckCancelled(false),
                Event.ReadInviteCount("A", 1),
                Event.AwaitEndpoint(0L),
                Event.DirectDiscoveryPolicy("10.174.189.1", true),
                Event.ReadLastInviteAt("A", 93_000L),
                Event.ReadAdvertisedAt("A", 99_500L),
                Event.Log("CTRL: ✓ 收到车机邀请（0ms，第 1 次）——立即连接 AirPlay"),
            ),
            harness1.events,
        )

        // 2) 宽限期中途（600ms）到达新鲜邀请：当轮立刻 L1+true，不再继续 sleep 或重公告
        val harness2 = Harness(initialClockMillis = 100_000L)
        harness2.activeAdvertiser!!.currentAdvertisedAtMillis = 100_000L
        harness2.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        harness2.onSleep = { _, _ ->
            if (harness2.clock.now == 100_600L) {
                harness2.activeAdvertiser!!.currentInviteCount = 2
                harness2.activeAdvertiser!!.currentLastInviteAtMillis = 100_580L
            }
        }
        assertTrue(harness2.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(12, harness2.sleepCount)
        assertEquals(
            listOf("CTRL: ✓ 收到车机邀请（600ms，第 2 次）——立即连接 AirPlay"),
            harness2.logs,
        )
        assertFalse(harness2.events.any { it is Event.ForceAnnounce })
    }

    @Test
    fun jW2_awaitCtrlInviteStaleZeroAndBoundaryInviteTimestamps() {
        // 1) inviteCount > 0 但 lastInviteAt = 0 或 42s 前：count 不触发 INVITED；后续 L2 显示旧邀请次数
        val harnessStale = Harness(initialClockMillis = 100_000L)
        harnessStale.activeAdvertiser!!.apply {
            currentInviteCount = 3
            currentLastInviteAtMillis = 100_000L - 42_000L
            currentAdvertisedAtMillis = 0L
        }
        harnessStale.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        harnessStale.clock.queueSleepTargets(102_000L, 105_000L)
        assertTrue(harnessStale.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(
            "CTRL: 已发现 _airplay._tcp，广播可见/重公告后 3000ms 内无新鲜邀请（旧邀请 3 次不算，共等 5000ms）——按旧机型直接连接 AirPlay",
            harnessStale.logs.last(),
        )

        // 2) 精确 20_000ms 边界（含等号）：视为新鲜邀请
        val harnessBoundary = Harness(initialClockMillis = 100_000L)
        harnessBoundary.activeAdvertiser!!.apply {
            currentInviteCount = 1
            currentLastInviteAtMillis = 80_000L
        }
        assertTrue(harnessBoundary.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(listOf("CTRL: ✓ 收到车机邀请（0ms，第 1 次）——立即连接 AirPlay"), harnessBoundary.logs)

        // 3) 20_001ms（超 1ms）或入门 19_000ms 在等待 2_000ms 后过期：不触发 INVITED
        val harnessExpiring = Harness(initialClockMillis = 100_000L)
        harnessExpiring.activeAdvertiser!!.apply {
            currentInviteCount = 1
            currentLastInviteAtMillis = 100_000L - 20_001L
        }
        harnessExpiring.clock.queueSleepTargets(109_000L)
        assertFalse(harnessExpiring.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(
            listOf("CTRL: 9000ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）"),
            harnessExpiring.logs,
        )

        // 4) 未来时间戳（lastInviteAt > now）：保持 Gate 现状判定为新鲜
        val harnessFuture = Harness(initialClockMillis = 100_000L)
        harnessFuture.activeAdvertiser!!.apply {
            currentInviteCount = 4
            currentLastInviteAtMillis = 100_500L
        }
        assertTrue(harnessFuture.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(listOf("CTRL: ✓ 收到车机邀请（0ms，第 4 次）——立即连接 AirPlay"), harnessFuture.logs)
    }

    @Test
    fun jW3_awaitCtrlInviteUnseenAdvertReannouncesOnceAtTwoSeconds() {
        val harness = Harness(initialClockMillis = 100_000L)
        harness.activeAdvertiser!!.currentAdvertisedAtMillis = 0L
        harness.zeroWaitDiscovered = null
        harness.clock.queueSleepTargets(101_999L, 102_000L, 109_000L)

        assertFalse(harness.resolver.awaitCtrlInvite(endpoint(ssid = "Car-Hotspot"), 9_000L))

        // 1_999ms 时不重公告；2_000ms 时先写 L4（未确认广播可见分支）再执行 forceAnnounce；全程只重公告一次
        val forceEvents = harness.events.filterIsInstance<Event.ForceAnnounce>()
        assertEquals(1, forceEvents.size)
        assertEquals("Car-Hotspot", forceEvents.single().ssid)

        val l4Log = Event.Log("CTRL: 2000ms 未确认广播可见——强制重新公告一次（catplay 同款 force_announce）")
        val l4Index = harness.events.indexOf(l4Log)
        val forceIndex = harness.events.indexOf(forceEvents.single())
        assertTrue("L4 必须出现在 forceAnnounce 之前", l4Index in 0 until forceIndex)
        assertEquals("CTRL: 9000ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）", harness.logs.last())
    }

    @Test
    fun jW4_awaitCtrlInviteVisibleAfterStartWaitsFullGraceBeforeReannounce() {
        val harness = Harness(initialClockMillis = 100_000L)
        // 广播在等待开始后 800ms 才确认可见
        harness.activeAdvertiser!!.currentAdvertisedAtMillis = 100_800L
        harness.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        harness.policyResult = false
        // 103_799（可见 2_999ms）仍 WAIT；103_800（可见 3_000ms）先 REANNOUNCE；随后 109_000 退出
        harness.clock.queueSleepTargets(103_799L, 103_800L)
        harness.onForceAnnounce = {
            harness.cancelled = true
        }

        assertFalse(harness.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertTrue(
            harness.logs.contains("CTRL: 广播可见 3000ms 仍无邀请——强制重新公告一次（catplay 同款 force_announce）"),
        )
        assertFalse(harness.logs.any { it.contains("按旧机型直接连接 AirPlay") })
    }

    @Test
    fun jW5_awaitCtrlInviteAfterReannounceFallbackGraceFromLaterOfReannounceOrVisible() {
        // 1) 重公告后广播先置 0，随后在 103_000 重新可见：从 103_000 起再满 3_000ms（106_000）才 MDNS_FALLBACK
        val harnessRevisible = Harness(initialClockMillis = 100_000L)
        harnessRevisible.activeAdvertiser!!.currentAdvertisedAtMillis = 0L
        harnessRevisible.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        harnessRevisible.onForceAnnounce = {
            harnessRevisible.activeAdvertiser!!.currentAdvertisedAtMillis = 103_000L
        }
        harnessRevisible.clock.queueSleepTargets(102_000L, 105_999L, 106_000L)

        assertTrue(harnessRevisible.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(
            "CTRL: 已发现 _airplay._tcp，广播可见/重公告后 3000ms 内无新鲜邀请（旧邀请 0 次不算，共等 6000ms）——按旧机型直接连接 AirPlay",
            harnessRevisible.logs.last(),
        )

        // 2) 重公告后 advertisedAt 始终为 0，但 mDNS 已发现：通用末分支不要求 advertisedAt > 0，从 reannouncedAt 满 3_000ms 放行
        val harnessUnseen = Harness(initialClockMillis = 100_000L)
        harnessUnseen.activeAdvertiser!!.currentAdvertisedAtMillis = 0L
        harnessUnseen.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
        harnessUnseen.clock.queueSleepTargets(102_000L, 104_999L, 105_000L)

        assertTrue(harnessUnseen.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(
            "CTRL: 已发现 _airplay._tcp，广播可见/重公告后 3000ms 内无新鲜邀请（旧邀请 0 次不算，共等 5000ms）——按旧机型直接连接 AirPlay",
            harnessUnseen.logs.last(),
        )
    }

    @Test
    fun jW6_awaitCtrlInviteDirectDiscoveryPolicyConnectsAfterThreeSecondVisibleGraceWithoutReannounce() {
        // 1) policy=true、mDNS 已解析、advertisedAt > 0：2_999ms 仍等待，3_000ms 走 MDNS_DIRECT（L3），零重公告
        val harnessDirect = Harness(initialClockMillis = 100_000L)
        harnessDirect.activeAdvertiser!!.currentAdvertisedAtMillis = 100_500L
        harnessDirect.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "192.168.43.1", port = 7000)
        harnessDirect.policyResult = true
        harnessDirect.clock.queueSleepTargets(103_499L, 103_500L)

        assertTrue(harnessDirect.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertFalse(harnessDirect.events.any { it is Event.ForceAnnounce })
        assertEquals(
            "CTRL: ORA 03 N220346.2 已解析且等待邀请超时（3500ms）——直接连接 AirPlay",
            harnessDirect.logs.last(),
        )

        // 2) 同等条件下若在 3_000ms 前/当轮有新鲜邀请：INVITED（L1）优先于 MDNS_DIRECT
        val harnessInvitedWins = Harness(initialClockMillis = 100_000L)
        harnessInvitedWins.activeAdvertiser!!.apply {
            currentInviteCount = 1
            currentAdvertisedAtMillis = 100_000L
        }
        harnessInvitedWins.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "192.168.43.1", port = 7000)
        harnessInvitedWins.policyResult = true
        harnessInvitedWins.onSleep = { _, _ ->
            harnessInvitedWins.activeAdvertiser!!.currentLastInviteAtMillis = 102_900L
        }
        harnessInvitedWins.clock.queueSleepTargets(103_000L)

        assertTrue(harnessInvitedWins.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(
            "CTRL: ✓ 收到车机邀请（3000ms，第 1 次）——立即连接 AirPlay",
            harnessInvitedWins.logs.last(),
        )
    }

    @Test
    fun jW7_awaitCtrlInviteDirectPolicyWithMissingMdnsOrInvisibleAdvertFallsBackToGeneric() {
        // 1) discovered == null 时短路，不调用 directDiscoveryPolicy，按通用路径重公告
        val harnessNoMdns = Harness(initialClockMillis = 100_000L)
        harnessNoMdns.activeAdvertiser!!.currentAdvertisedAtMillis = 100_000L
        harnessNoMdns.zeroWaitDiscovered = null
        harnessNoMdns.policyResult = true
        harnessNoMdns.clock.queueSleepTargets(103_000L, 109_000L)

        assertFalse(harnessNoMdns.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertFalse(harnessNoMdns.events.any { it is Event.DirectDiscoveryPolicy })
        assertEquals(1, harnessNoMdns.events.count { it is Event.ForceAnnounce })

        // 2) policy=true 且有 mDNS，但 advertisedAtMillis == 0：不触发 MDNS_DIRECT，2s 重公告后 5s 走 MDNS_FALLBACK（L2）
        val harnessInvisible = Harness(initialClockMillis = 100_000L)
        harnessInvisible.activeAdvertiser!!.currentAdvertisedAtMillis = 0L
        harnessInvisible.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "192.168.43.1", port = 7000)
        harnessInvisible.policyResult = true
        harnessInvisible.clock.queueSleepTargets(102_000L, 105_000L)

        assertTrue(harnessInvisible.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(1, harnessInvisible.events.count { it is Event.ForceAnnounce })
        assertEquals(
            "CTRL: 已发现 _airplay._tcp，广播可见/重公告后 3000ms 内无新鲜邀请（旧邀请 0 次不算，共等 5000ms）——按旧机型直接连接 AirPlay",
            harnessInvisible.logs.last(),
        )

        // 3) policy=false：即使有 mDNS 且广播可见，3s 时仍走 REANNOUNCE 而非 MDNS_DIRECT
        val harnessPolicyFalse = Harness(initialClockMillis = 100_000L)
        harnessPolicyFalse.activeAdvertiser!!.currentAdvertisedAtMillis = 100_000L
        harnessPolicyFalse.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "192.168.43.1", port = 7000)
        harnessPolicyFalse.policyResult = false
        harnessPolicyFalse.clock.queueSleepTargets(103_000L, 106_000L)

        assertTrue(harnessPolicyFalse.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(1, harnessPolicyFalse.events.count { it is Event.ForceAnnounce })
        assertFalse(harnessPolicyFalse.logs.any { it.contains("ORA 03") })
    }

    @Test
    fun jW8_awaitCtrlInviteEvaluatesDirectDiscoveryPolicyDynamicallyEveryIteration() {
        // 模拟 preset 或 TXT 在等待中途变化：前两轮返回 false，满 3s 当轮变为 true -> 触发 MDNS_DIRECT
        val harnessFlipTrue = Harness(initialClockMillis = 100_000L)
        harnessFlipTrue.activeAdvertiser!!.currentAdvertisedAtMillis = 100_000L
        harnessFlipTrue.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "192.168.43.1", port = 7000)
        val answers = ArrayDeque(listOf(false, false, true))
        harnessFlipTrue.onDirectDiscoveryPolicy = { _, _ -> answers.removeFirst() }
        harnessFlipTrue.clock.queueSleepTargets(101_000L, 103_000L)

        assertTrue(harnessFlipTrue.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(
            listOf(
                Event.DirectDiscoveryPolicy("192.168.43.1", false),
                Event.DirectDiscoveryPolicy("192.168.43.1", false),
                Event.DirectDiscoveryPolicy("192.168.43.1", true),
            ),
            harnessFlipTrue.events.filterIsInstance<Event.DirectDiscoveryPolicy>(),
        )
        assertEquals(
            "CTRL: ORA 03 N220346.2 已解析且等待邀请超时（3000ms）——直接连接 AirPlay",
            harnessFlipTrue.logs.last(),
        )

        // 反向：前两轮为 true，到 3s 切为 false -> 3s 当轮按 false 走 REANNOUNCE
        val harnessFlipFalse = Harness(initialClockMillis = 100_000L)
        harnessFlipFalse.activeAdvertiser!!.currentAdvertisedAtMillis = 100_000L
        harnessFlipFalse.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "192.168.43.1", port = 7000)
        val answersReverse = ArrayDeque(listOf(true, true, false))
        harnessFlipFalse.onDirectDiscoveryPolicy = { _, _ -> answersReverse.removeFirst() }
        harnessFlipFalse.clock.queueSleepTargets(101_000L, 103_000L)
        harnessFlipFalse.onForceAnnounce = { harnessFlipFalse.cancelled = true }

        assertFalse(harnessFlipFalse.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(1, harnessFlipFalse.events.count { it is Event.ForceAnnounce })
        assertFalse(harnessFlipFalse.logs.any { it.contains("ORA 03") })
    }

    @Test
    fun jW9_awaitCtrlInviteTimeoutBoundariesZeroNegativeAndInviteAtDeadline() {
        // 1) 正常 9_000ms 耗尽；若邀请恰好在 deadline（109_000）时刻才出现，while (< deadline) 为 false，不再进入下一轮采样
        val harnessTimeout = Harness(initialClockMillis = 100_000L)
        harnessTimeout.onSleep = { _, _ ->
            if (harnessTimeout.clock.now >= 109_000L) {
                harnessTimeout.activeAdvertiser!!.apply {
                    currentInviteCount = 1
                    currentLastInviteAtMillis = 109_000L
                }
            }
        }
        assertFalse(harnessTimeout.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(180, harnessTimeout.sleepCount)
        assertEquals(
            "CTRL: 9000ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）",
            harnessTimeout.logs.last(),
        )

        // 2) timeout = 0 或负数：captureInviteView 仍调用，但不进入循环，直接输出 L6 并返回 false
        for (nonPositive in listOf(0L, -50L)) {
            val harnessNonPositive = Harness(initialClockMillis = 100_000L)
            harnessNonPositive.activeAdvertiser!!.apply {
                currentInviteCount = 1
                currentLastInviteAtMillis = 100_000L
            }
            assertFalse(harnessNonPositive.resolver.awaitCtrlInvite(endpoint(), nonPositive))
            assertEquals(0, harnessNonPositive.sleepCount)
            assertEquals(
                listOf(
                    Event.CaptureInviteView,
                    Event.Log("CTRL: ${nonPositive}ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）"),
                ),
                harnessNonPositive.events,
            )
        }
    }

    @Test
    fun jW10_awaitCtrlInviteCancelAtEntryMidLoopOrSleepInterrupted() {
        // 1) 入口已取消：首轮开头即返回 false，不读 inviteCount/browser/policy，不输出 L6
        val harnessEntry = Harness(initialClockMillis = 100_000L)
        harnessEntry.cancelled = true
        assertFalse(harnessEntry.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertTrue(harnessEntry.logs.isEmpty())
        assertEquals(
            listOf(
                Event.CaptureInviteView,
                Event.CheckCancelled(true),
            ),
            harnessEntry.events,
        )

        // 2) 第 3 轮取消：保留前两轮事件，第 3 轮开头返回 false 且无 L6
        val harnessMid = Harness(initialClockMillis = 100_000L)
        harnessMid.onSleep = { _, count ->
            if (count == 2) harnessMid.cancelled = true
        }
        assertFalse(harnessMid.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(2, harnessMid.sleepCount)
        assertTrue(harnessMid.logs.isEmpty())

        // 3) sleepMillis 抛 InterruptedException：返回 false，无 L6，不修改当前线程中断标志位
        Thread.interrupted()
        val harnessInterrupted = Harness(initialClockMillis = 100_000L)
        harnessInterrupted.onSleep = { _, _ ->
            throw InterruptedException("simulated sleep interrupt")
        }
        assertFalse(harnessInterrupted.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertFalse(Thread.currentThread().isInterrupted)
        assertTrue(harnessInterrupted.logs.isEmpty())
    }

    @Test
    fun jW11_awaitCtrlInviteHeartbeatThresholdBranchesAndSlowForceAnnounce() {
        // 1) 心跳严格 `> 1_200`：1_200ms 不打，1_201ms 打未可见分支并更新 lastLogAt；
        //    再过 1_200ms（2_401ms）不打，2_402ms 打已可见分支
        val harnessHeartbeat = Harness(initialClockMillis = 100_000L)
        harnessHeartbeat.activeAdvertiser!!.currentAdvertisedAtMillis = 0L
        harnessHeartbeat.clock.queueSleepTargets(101_200L, 101_201L, 102_401L, 102_402L)
        harnessHeartbeat.onSleep = { _, count ->
            when (count) {
                3 -> harnessHeartbeat.activeAdvertiser!!.currentAdvertisedAtMillis = 100_402L
                5 -> harnessHeartbeat.cancelled = true
            }
        }
        assertFalse(harnessHeartbeat.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(
            listOf(
                "CTRL: 等待车机 ctrl 邀请…（已等 1201ms，广播尚未确认可见）",
                "CTRL: 等待车机 ctrl 邀请…（已等 2402ms，广播已可见 2000ms）",
            ),
            harnessHeartbeat.logs,
        )

        // 2) deadline 与 startedAt 分两次采样；forceAnnounce 耗时越过 deadline 时，当轮仍用原 now 完成心跳与 sleep，下一轮 while 退出
        val harnessSlowForce = Harness(initialClockMillis = 100_000L)
        harnessSlowForce.recordClockReads = true
        // 入口第 1 次读 100_000（deadline=109_000），第 2 次读 100_005（startedAt=100_005），
        // 首轮 while 读 102_005，首轮 now 读 102_005
        harnessSlowForce.clock.queueReads(100_000L, 100_005L, 102_005L, 102_005L)
        harnessSlowForce.onForceAnnounce = {
            // forceAnnounce 内部耗时使时钟直接超过 deadline
            harnessSlowForce.clock.now = 110_000L
        }
        assertFalse(harnessSlowForce.resolver.awaitCtrlInvite(endpoint(), 9_000L))
        assertEquals(
            listOf(
                "CTRL: 2000ms 未确认广播可见——强制重新公告一次（catplay 同款 force_announce）",
                "CTRL: 等待车机 ctrl 邀请…（已等 2000ms，广播尚未确认可见）",
                "CTRL: 9000ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）",
            ),
            harnessSlowForce.logs,
        )
        val clockReads = harnessSlowForce.events.filterIsInstance<Event.ClockRead>().map { it.value }
        // 入口两次独立读取（100_000, 100_005）、首轮 while（102_005）、首轮 now（102_005）、次轮 while（110_050）
        assertEquals(listOf(100_000L, 100_005L, 102_005L, 102_005L, 110_050L), clockReads)
    }

    @Test
    fun jW12_awaitCtrlInviteCapturedAdvertiserVsDynamicForceBrowserAndNullFallbacks() {
        // 1) 入口捕获 advertiser A；途中 activeAdvertiser 换成 B、网络换成 net-2、browser 返回新结果：
        //    邀请指标仍从 A 实时读取，但 forceAnnounce 使用动态 B 与 net-2
        val harnessSwap = Harness(initialClockMillis = 100_000L)
        val advertiserA = harnessSwap.activeAdvertiser!!
        val advertiserB =
            FakeInviteView(
                label = "B",
                inviteCount = 99,
                lastInviteAtMillis = 100_100L,
                advertisedAtMillis = 100_100L,
            ) { event -> harnessSwap.events += event }

        harnessSwap.onSleep = { _, count ->
            when (count) {
                1 -> {
                    harnessSwap.activeAdvertiser = advertiserB
                    harnessSwap.activeNetworkTag = "net-2"
                    harnessSwap.zeroWaitDiscovered = FakeDiscoveredEndpoint(hostAddress = "10.174.189.1", port = 7000)
                }

                2 -> {
                    // 更新已捕获的 A：下一轮仍能读到 A 的新邀请
                    advertiserA.currentInviteCount = 2
                    advertiserA.currentLastInviteAtMillis = 102_010L
                }
            }
        }
        harnessSwap.clock.queueSleepTargets(102_000L, 102_050L)

        assertTrue(harnessSwap.resolver.awaitCtrlInvite(endpoint(ssid = "Car-AP"), 9_000L))
        // 所有指标读取均来自 A，从未读 B 的 99 次邀请
        assertTrue(harnessSwap.events.filterIsInstance<Event.ReadInviteCount>().all { it.label == "A" })
        // 但 2_000ms 重公告落到了当时动态活跃的 B 与 net-2
        assertEquals(
            listOf(Event.ForceAnnounce("Car-AP", "B", "net-2")),
            harnessSwap.events.filterIsInstance<Event.ForceAnnounce>(),
        )
        assertEquals(
            "CTRL: ✓ 收到车机邀请（2050ms，第 2 次）——立即连接 AirPlay",
            harnessSwap.logs.last(),
        )

        // 2) advertiser 与 browser 均为 null 时：使用 0/null 安全兜底，2_000ms 仍正常走到重公告分支
        val harnessNull = Harness(initialClockMillis = 100_000L)
        harnessNull.activeAdvertiser = null
        harnessNull.zeroWaitDiscovered = null
        harnessNull.clock.queueSleepTargets(102_000L, 109_000L)

        assertFalse(harnessNull.resolver.awaitCtrlInvite(endpoint(ssid = "Null-AP"), 9_000L))
        assertEquals(
            listOf(Event.ForceAnnounce("Null-AP", null, null)),
            harnessNull.events.filterIsInstance<Event.ForceAnnounce>(),
        )
        assertEquals(
            listOf(
                "CTRL: 2000ms 未确认广播可见——强制重新公告一次（catplay 同款 force_announce）",
                "CTRL: 等待车机 ctrl 邀请…（已等 2000ms，广播尚未确认可见）",
                "CTRL: 9000ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）",
            ),
            harnessNull.logs,
        )
    }

    @Test
    fun jW13_awaitCtrlInvitePropagatesNonInterruptedExceptionAndResetsPerCallState() {
        // 1) 非 InterruptedException 原样向外抛出，不被吞成 false 或超时日志
        val harnessBoom = Harness(initialClockMillis = 100_000L)
        harnessBoom.onAwaitEndpoint = { throw IllegalStateException("browser failure") }
        try {
            harnessBoom.resolver.awaitCtrlInvite(endpoint(), 9_000L)
            fail("非中断异常必须向外传播")
        } catch (expected: IllegalStateException) {
            assertEquals("browser failure", expected.message)
        }
        assertTrue(harnessBoom.logs.isEmpty())

        // 2) 同一 resolver 连续两次调用 awaitCtrlInvite：reannouncedAt 与 lastLogAt 每次重置，互不污染
        val harnessReuse = Harness(initialClockMillis = 100_000L)
        harnessReuse.clock.queueSleepTargets(102_000L, 109_000L)
        assertFalse(harnessReuse.resolver.awaitCtrlInvite(endpoint(ssid = "First"), 9_000L))
        assertEquals(1, harnessReuse.events.count { it is Event.ForceAnnounce })

        harnessReuse.events.clear()
        harnessReuse.logs.clear()
        harnessReuse.clock.now = 200_000L
        harnessReuse.clock.queueSleepTargets(202_000L, 209_000L)
        assertFalse(harnessReuse.resolver.awaitCtrlInvite(endpoint(ssid = "Second"), 9_000L))
        assertEquals(
            listOf(Event.ForceAnnounce("Second", "A", "net-1")),
            harnessReuse.events.filterIsInstance<Event.ForceAnnounce>(),
        )
        assertEquals(
            listOf(
                "CTRL: 2000ms 未确认广播可见——强制重新公告一次（catplay 同款 force_announce）",
                "CTRL: 等待车机 ctrl 邀请…（已等 2000ms，广播尚未确认可见）",
                "CTRL: 9000ms 未收到邀请——按超时继续连接（可能车机走旧发现路径）",
            ),
            harnessReuse.logs,
        )
    }
}
