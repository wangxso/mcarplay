package com.mcarplay.app.airplay

import android.app.Service
import android.content.Intent
import com.mcarplay.app.SessionController
import com.mcarplay.app.SessionHandoffOwnership
import com.mcarplay.app.audio.AudioCodec
import com.mcarplay.app.compat.ReceiverCompatibility
import com.mcarplay.app.compat.ReceiverObservation
import com.mcarplay.app.compat.VehiclePreset
import com.mcarplay.app.compat.VehiclePresetRuntime
import com.mcarplay.app.iap2.Iap2SenderSession
import com.mcarplay.app.iap2.ReceiverProfile
import com.mcarplay.app.net.AirPlayFeatures
import com.mcarplay.app.service.CarPlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ReceiverCompatibilityIntegrationTest {
    @Test
    fun gs3ExplicitStartClaimsA500ScreenAndAnnouncesItWithoutTakingAudio() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val previousPreset = VehiclePresetRuntime.current
            VehiclePresetRuntime.current = VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW
            val intent = ExplicitStartScreenClaim().apply { manualStart() }
            val claim = requireNotNull(intent.lease())
            intent.adopt(claim)
            val logs = mutableListOf<String>()
            val client =
                AirPlayClient(
                    endpoint().copy(
                        airPlayPort = peer.port,
                        receiverSignals = ReceiverProfile.Signals(),
                    ),
                    log = logs::add,
                    explicitStartScreenClaim = claim,
                )
            try {
                peer.reply = { request ->
                    when (request.path) {
                        "/setup" ->
                            request.respond(
                                200,
                                BinaryPlist.encode(mapOf("eventPort" to 0L, "timingPort" to 0L)),
                            )

                        "/info" ->
                            request.respond(
                                200,
                                BinaryPlist.encode(
                                    mapOf(
                                        "displays" to listOf(mapOf("uuid" to "display-test")),
                                        "modes" to
                                            mapOf(
                                                "resources" to
                                                    listOf(
                                                        modeResource(
                                                            ModeArbiter.SCREEN,
                                                            ModeArbiter.TRANSFER_TAKE,
                                                            ModeArbiter.PRIORITY_USER_INITIATED,
                                                            500,
                                                        ),
                                                        modeResource(
                                                            ModeArbiter.MAIN_AUDIO,
                                                            ModeArbiter.TRANSFER_TAKE,
                                                            ModeArbiter.PRIORITY_USER_INITIATED,
                                                            ModeArbiter.CONSTRAINT_NEVER,
                                                        ),
                                                    ),
                                            ),
                                    ),
                                ),
                            )

                        "/command" -> request.respond(200)
                        else -> request.respond(200)
                    }
                }
                assertTrue(client.reconnect())
                val setup = client.runSetup()
                assertNotNull(setup)
                assertTrue(client.modes.phoneOwnsScreen)
                assertFalse("Taking the screen for GS3 must leave main audio with the head unit", client.modes.phoneOwnsAudio)
                assertTrue(logs.any { it.contains("MODE: GS3 用户手动启动且主屏约束≤userInitiated") })
                assertTrue("The intent must survive until modesChanged is acknowledged", claim.isPending())
                assertTrue(client.assertModes(acknowledgeInitialScreenClaim = true))
                assertFalse(claim.isPending())

                assertEquals("SETUP", peer.next()!!.method)
                assertEquals("/info", peer.next()!!.path)
                val modesRequest = peer.next()!!
                assertEquals("/command", modesRequest.path)
                val body = BinaryPlist.parse(modesRequest.body) as Map<*, *>
                assertEquals("modesChanged", body["type"])
                val resources = ((body["params"] as Map<*, *>)["resources"] as List<*>).map { it as Map<*, *> }
                assertEquals(ModeArbiter.Entity.CONTROLLER.code, resources.first { it["resourceID"] == ModeArbiter.SCREEN }["entity"])
                assertEquals(
                    "The initial non-user-initiated modesChanged must not steal main audio",
                    ModeArbiter.Entity.ACCESSORY.code,
                    resources.first { it["resourceID"] == ModeArbiter.MAIN_AUDIO }["entity"],
                )
                assertFalse(claim.consume())
            } finally {
                client.close()
                VehiclePresetRuntime.current = previousPreset
            }
        }
    }

    @Test
    fun gs3ExplicitClaimWaitsWhenInitialAnnouncementNoLongerOwnsTheScreen() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val previousPreset = VehiclePresetRuntime.current
            VehiclePresetRuntime.current = VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW
            val intent = ExplicitStartScreenClaim().apply { manualStart() }
            val claim = requireNotNull(intent.lease())
            intent.adopt(claim)
            val client =
                AirPlayClient(
                    endpoint().copy(
                        airPlayPort = peer.port,
                        receiverSignals = ReceiverProfile.Signals(),
                    ),
                    log = {},
                    explicitStartScreenClaim = claim,
                )
            try {
                peer.reply = { request ->
                    when (request.path) {
                        "/setup" -> request.respond(200, BinaryPlist.encode(mapOf("eventPort" to 0L)))
                        "/info" ->
                            request.respond(
                                200,
                                BinaryPlist.encode(
                                    mapOf(
                                        "displays" to listOf(mapOf("uuid" to "display-test")),
                                        "modes" to
                                            mapOf(
                                                "resources" to
                                                    listOf(
                                                        modeResource(
                                                            ModeArbiter.SCREEN,
                                                            ModeArbiter.TRANSFER_TAKE,
                                                            ModeArbiter.PRIORITY_USER_INITIATED,
                                                            500,
                                                        ),
                                                    ),
                                            ),
                                    ),
                                ),
                            )

                        else -> request.respond(200)
                    }
                }
                assertTrue(client.reconnect())
                assertNotNull(client.runSetup())
                assertTrue(client.modes.phoneOwnsScreen)

                client.modes.onChangeModes(
                    mapOf(
                        "resources" to
                            listOf(
                                modeResource(
                                    ModeArbiter.SCREEN,
                                    ModeArbiter.TRANSFER_TAKE,
                                    ModeArbiter.PRIORITY_USER_INITIATED,
                                    500,
                                ),
                            ),
                    ),
                )
                assertFalse(client.modes.phoneOwnsScreen)
                assertTrue(client.assertModes(acknowledgeInitialScreenClaim = true))
                assertTrue("A receiver-owned announcement must not consume the retry intent", claim.isPending())

                assertEquals("SETUP", peer.next()!!.method)
                assertEquals("/info", peer.next()!!.path)
                val modesRequest = peer.next()!!
                val body = BinaryPlist.parse(modesRequest.body) as Map<*, *>
                val resources = ((body["params"] as Map<*, *>)["resources"] as List<*>).map { it as Map<*, *> }
                assertEquals(ModeArbiter.Entity.ACCESSORY.code, resources.first { it["resourceID"] == ModeArbiter.SCREEN }["entity"])
            } finally {
                client.close()
                VehiclePresetRuntime.current = previousPreset
            }
        }
    }

    @Test
    fun gs3ExplicitStartLeavesNeverConstraintsAndAutomaticStartsUntouched() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val previousPreset = VehiclePresetRuntime.current
            VehiclePresetRuntime.current = VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW
            val intent = ExplicitStartScreenClaim().apply { manualStart() }
            val claim = requireNotNull(intent.lease())
            intent.adopt(claim)
            val client =
                AirPlayClient(
                    endpoint().copy(
                        airPlayPort = peer.port,
                        receiverSignals = ReceiverProfile.Signals(),
                    ),
                    log = {},
                    explicitStartScreenClaim = claim,
                )
            try {
                peer.reply = { request ->
                    when (request.path) {
                        "/setup" -> request.respond(200, BinaryPlist.encode(mapOf("eventPort" to 0L)))
                        "/info" ->
                            request.respond(
                                200,
                                BinaryPlist.encode(
                                    mapOf(
                                        "displays" to listOf(mapOf("uuid" to "display-test")),
                                        "modes" to
                                            mapOf(
                                                "resources" to
                                                    listOf(
                                                        modeResource(
                                                            ModeArbiter.SCREEN,
                                                            ModeArbiter.TRANSFER_TAKE,
                                                            ModeArbiter.PRIORITY_USER_INITIATED,
                                                            ModeArbiter.CONSTRAINT_NEVER,
                                                        ),
                                                    ),
                                            ),
                                    ),
                                ),
                            )

                        else -> request.respond(200)
                    }
                }
                assertTrue(client.reconnect())
                assertNotNull(client.runSetup())
                assertFalse("A 1000 constraint must remain with the head unit", client.modes.phoneOwnsScreen)

                val automatic =
                    AirPlayClient(
                        endpoint().copy(
                            airPlayPort = peer.port,
                            receiverSignals = ReceiverProfile.Signals(),
                        ),
                        log = {},
                        explicitStartScreenClaim = ExplicitStartScreenClaim().lease(),
                    )
                try {
                    assertTrue(automatic.reconnect())
                    assertNotNull(automatic.runSetup())
                    assertFalse("An automatic start must not claim the screen", automatic.modes.phoneOwnsScreen)
                } finally {
                    automatic.close()
                }
            } finally {
                client.close()
                VehiclePresetRuntime.current = previousPreset
            }
        }
    }

    @Test
    fun mdnsRefreshRetainsPrivateBindingAndUpdatesCapabilityFingerprint() {
        val observation = ReceiverObservation("local-private-binding", 1, 1)
        val endpoint = endpoint().copy(observation = observation)
        val client = AirPlayClient(endpoint, log = {})
        setInfo(client, listOf(format(102, "media", 1L shl 23)))
        val digest = client.capabilityDigest
        assertTrue(client.updateEndpoint(endpoint.copy(observation = null, advertisedFeatures = 0), "mDNS refresh"))
        assertEquals(observation, client.currentEndpoint.observation)
        assertNotEquals(digest, client.capabilityDigest)
        client.close()
    }

    private val signals =
        ReceiverProfile.Signals(
            model = "WinPlay",
            manufacturer = "Roylyl",
            firmwareVersion = "1.2.4",
            externalAccessoryProtocols = setOf("io.github.roylyl.winplay"),
        )

    @Test
    fun metadataOnlyUpdateChangesPolicyWithoutClosingControlConnection() {
        val endpoint = endpoint()
        val client = AirPlayClient(endpoint, log = {})
        Socket().use { socket ->
            field("socket").set(client, socket)
            assertTrue(client.updateEndpoint(endpoint.copy(advertisedFeatures = 0), "capabilities arrived"))
            assertEquals(ReceiverCompatibility.PairingMode.ADVERTISED_PLAIN, client.receiverCompatibility().pairingMode)
            assertSame(socket, field("socket").get(client))
            assertFalse(socket.isClosed)
            assertTrue(
                client.updateEndpoint(endpoint.copy(advertisedFeatures = AirPlayFeatures.HK_PAIRING_AND_ENCRYPT), "pairing declared"),
            )
            assertEquals(ReceiverCompatibility.PairingMode.PAIR_AND_ENCRYPT, client.receiverCompatibility().pairingMode)
            assertEquals(signals, client.currentEndpoint.receiverSignals)
            val withoutBluetoothFacts = client.currentEndpoint.copy(receiverSignals = ReceiverProfile.Signals(), sourceVersion = "951.1")
            assertTrue(client.updateEndpoint(withoutBluetoothFacts, "mDNS-only refresh"))
            assertEquals(signals, client.currentEndpoint.receiverSignals)
            assertSame(socket, field("socket").get(client))
            client.close()
        }
    }

    @Test
    fun winPlayRejectsPlaceholderIdentityBeforeOpeningSetupResources() {
        val logs = mutableListOf<String>()
        val client = AirPlayClient(endpoint(), log = logs::add)
        try {
            assertNull(client.runSetup())
            assertTrue(logs.any { it.contains("要求真实蓝牙身份") })
            assertFalse(logs.any { it.contains("初始 SETUP body=") })
        } finally {
            client.close()
        }
    }

    @Test
    fun handoffNeedsRecordAndNetworkAuthenticationAndIsClearedOnClose() {
        val client = AirPlayClient(endpoint(), log = {})
        field("recordAccepted").setBoolean(client, true)
        assertFalse(client.networkHandoffReady)
        field("mib3ControlSessionDone").setBoolean(client, true)
        assertFalse("MIB3 资源突发完成不能替代网络认证证据", client.networkHandoffReady)
        field("networkIapAuthenticated").setBoolean(client, true)
        assertTrue(client.networkHandoffReady)
        client.close()
        assertFalse(client.networkHandoffReady)
    }

    @Test
    fun bothBluetoothClosePathsHonorConfirmedNetworkHandoff() {
        val client = AirPlayClient(endpoint(), log = {})
        field("recordAccepted").setBoolean(client, true)
        field("networkIapAuthenticated").setBoolean(client, true)
        val starting =
            SessionController::class.java
                .getDeclaredField("sessionStarting")
                .apply { isAccessible = true }
                .get(SessionController) as AtomicBoolean
        val previousStarting = starting.getAndSet(true)
        val previousClient = SessionController.currentClient
        val previousCancelled = SessionController.connectCancelled
        SessionController.currentClient = client
        SessionController.connectCancelled = false
        try {
            val intent =
                object : Intent() {
                    override fun getAction(): String = CarPlayService.ACTION_AUTO_STOP_SESSION
                }
            assertEquals(Service.START_STICKY, CarPlayService().onStartCommand(intent, 0, 1))
            SessionController.onSenderLinkClosed()
            assertTrue(starting.get())
            assertFalse(SessionController.connectCancelled)
            client.close()
            SessionController.onSenderLinkClosed()
            assertFalse(starting.get())
            assertTrue(SessionController.connectCancelled)
        } finally {
            client.close()
            SessionController.currentClient = previousClient
            SessionController.connectCancelled = previousCancelled
            starting.set(previousStarting)
        }
    }

    @Test
    fun gs3SetupHandoffKeepsNetworkSessionAcrossBothBluetoothClosePaths() {
        val client = setupClient(endpoint().copy(receiverSignals = ReceiverProfile.Signals()))
        Socket().use { socket ->
            field("socket").set(client, socket)
            setInitialSetupWritten(client, true)
            val starting =
                SessionController::class.java
                    .getDeclaredField("sessionStarting")
                    .apply { isAccessible = true }
                    .get(SessionController) as AtomicBoolean
            val previousStarting = starting.getAndSet(true)
            val previousClient = SessionController.currentClient
            val previousCancelled = SessionController.connectCancelled
            val previousPreset = com.mcarplay.app.compat.VehiclePresetRuntime.current
            SessionController.currentClient = client
            SessionController.connectCancelled = false
            com.mcarplay.app.compat.VehiclePresetRuntime.current =
                com.mcarplay.app.compat.VehiclePreset.TRUMPCHI_GS3_IAP2_WINDOW
            try {
                val intent =
                    object : Intent() {
                        override fun getAction(): String = CarPlayService.ACTION_AUTO_STOP_SESSION
                    }
                assertEquals(Service.START_STICKY, CarPlayService().onStartCommand(intent, 0, 1))
                SessionController.onSenderLinkClosed()
                assertFalse(SessionController.connectCancelled)
                assertTrue("GS3 交接期间仍须占住会话互斥，避免新端点拆掉待 RECORD 的 AirPlay 会话", starting.get())
                assertTrue("只处理 ACL 广播时也不能放行并发端点", handoffOwnsObservation(client))

                // 随后到达的 RFCOMM 关闭也必须幂等，不能把仍未完成的交接所有权释放掉。
                SessionController.onSenderLinkClosed(client.currentEndpoint.observation)
                assertTrue(starting.get())
                assertTrue(handoffOwnsObservation(client))
            } finally {
                com.mcarplay.app.compat.VehiclePresetRuntime.current = previousPreset
                SessionController.currentClient = previousClient
                SessionController.connectCancelled = previousCancelled
                starting.set(previousStarting)
                client.close()
            }
        }
    }

    private fun handoffOwnsObservation(client: AirPlayClient): Boolean {
        val ownership =
            SessionController::class.java
                .getDeclaredField("handoffOwnership")
                .apply { isAccessible = true }
                .get(SessionController) as SessionHandoffOwnership
        return ownership.owns(client.currentEndpoint.observation)
    }

    @Test
    fun setupHandoffStartsOnlyAfterInitialSetupWriteAndSurvivesDelayedResponse() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val setupResult = AtomicReference<AirPlayClient.SetupInfo?>(null)
            val failure = AtomicReference<Throwable?>(null)
            peer.reply = {}
            val client =
                AirPlayClient(
                    endpoint().copy(
                        airPlayPort = peer.port,
                        receiverSignals = ReceiverProfile.Signals(),
                    ),
                    log = {},
                )
            assertTrue(client.reconnect())
            assertFalse(client.setupHandoffStarted)
            val runner =
                Thread({
                    try {
                        setupResult.set(client.runSetup())
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }, "gs3-setup-runner").apply {
                    isDaemon = true
                    start()
                }
            try {
                val setupRequest = peer.next()!!
                assertEquals("SETUP", setupRequest.method)
                assertEquals("/setup", setupRequest.path)
                val waitForWriteState = System.currentTimeMillis() + 2_000
                while (!client.setupHandoffStarted && System.currentTimeMillis() < waitForWriteState) Thread.sleep(5)
                assertTrue("SETUP 写出后、200 响应前必须开启交接窗口", client.setupHandoffStarted)
                setupRequest.respond(200, BinaryPlist.encode(mapOf("eventPort" to 0L)))

                exchangeSetupInfo(peer)
                runner.join(2_000)
                assertFalse(runner.isAlive)
                assertNull(failure.get())
                assertNotNull(setupResult.get())
                assertTrue("控制 TCP 仍存活时，交接窗口应保持开启", client.setupHandoffStarted)

                val recording =
                    Thread(
                        { setupResult.set(if (client.record()) setupResult.get() else null) },
                        "gs3-record-runner",
                    ).apply {
                        isDaemon = true
                        start()
                    }
                val recordRequest = peer.next()!!
                assertEquals("RECORD", recordRequest.method)
                recordRequest.respond(200)
                recording.join(2_000)
                assertFalse(recording.isAlive)
                assertNotNull(setupResult.get())
                assertTrue(client.sessionActive)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun setupHandoffIsClearedAfterRejectedSetupIoFailureAndClose() {
        AirPlayRecordLifecycleTest.Peer().use { peer ->
            peer.reply = {}
            val client =
                AirPlayClient(
                    endpoint().copy(
                        airPlayPort = peer.port,
                        receiverSignals = ReceiverProfile.Signals(),
                    ),
                    log = {},
                )
            try {
                assertTrue(client.reconnect())
                val setupResult = AtomicReference<AirPlayClient.SetupInfo?>(null)
                val setupFailure = AtomicReference<Throwable?>(null)
                val runner =
                    Thread({
                        try {
                            setupResult.set(client.runSetup())
                        } catch (error: Throwable) {
                            setupFailure.set(error)
                        }
                    }, "gs3-rejected-setup").apply {
                        isDaemon = true
                        start()
                    }
                peer.next()!!.respond(403)
                runner.join(2_000)
                assertFalse(runner.isAlive)
                assertNull(setupFailure.get())
                assertNull(setupResult.get())
                assertFalse(client.setupHandoffStarted)
            } finally {
                client.close()
            }
        }

        AirPlayRecordLifecycleTest.Peer().use { peer ->
            peer.reply = {}
            val client =
                AirPlayClient(
                    endpoint().copy(
                        airPlayPort = peer.port,
                        receiverSignals = ReceiverProfile.Signals(),
                    ),
                    log = {},
                )
            try {
                assertTrue(client.reconnect())
                val setupResult = AtomicReference<AirPlayClient.SetupInfo?>(null)
                val setupFailure = AtomicReference<Throwable?>(null)
                val runner =
                    Thread({
                        try {
                            setupResult.set(client.runSetup())
                        } catch (error: Throwable) {
                            setupFailure.set(error)
                        }
                    }, "gs3-io-failed-setup").apply {
                        isDaemon = true
                        start()
                    }
                peer
                    .next()!!
                    .connection.socket
                    .close()
                runner.join(2_000)
                assertFalse(runner.isAlive)
                assertNull(setupFailure.get())
                assertNull(setupResult.get())
                assertFalse(client.setupHandoffStarted)
            } finally {
                client.close()
            }
        }

        AirPlayRecordLifecycleTest.Peer().use { peer ->
            val client =
                AirPlayClient(
                    endpoint().copy(
                        airPlayPort = peer.port,
                        receiverSignals = ReceiverProfile.Signals(),
                    ),
                    log = {},
                )
            try {
                assertTrue(client.reconnect())
                var pending: AirPlayRecordLifecycleTest.Request? = null
                peer.reply = { pending = it }
                val setupResult = AtomicReference<AirPlayClient.SetupInfo?>(null)
                val runner =
                    Thread(
                        {
                            setupResult.set(client.runSetup())
                        },
                        "gs3-close-setup",
                    ).apply {
                        isDaemon = true
                        start()
                    }
                val waitForRequest = System.currentTimeMillis() + 2_000
                while (pending == null && System.currentTimeMillis() < waitForRequest) Thread.sleep(5)
                while (!client.setupHandoffStarted && System.currentTimeMillis() < waitForRequest) Thread.sleep(5)
                assertTrue("关闭前应已进入 SETUP 交接窗口", client.setupHandoffStarted)
                client.close()
                runner.join(2_000)
                assertFalse(runner.isAlive)
                assertNull(setupResult.get())
                assertFalse(client.setupHandoffStarted)
            } finally {
                client.close()
            }
        }

        val closed = setupClient(endpoint())
        setInitialSetupWritten(closed, true)
        assertFalse("没有控制 TCP 时不能进入交接窗口", closed.setupHandoffStarted)
        Socket().use { socket ->
            field("socket").set(closed, socket)
            assertTrue(closed.setupHandoffStarted)
            closed.close()
            assertFalse(closed.setupHandoffStarted)
        }
    }

    private fun exchangeSetupInfo(peer: AirPlayRecordLifecycleTest.Peer) {
        val infoRequest = peer.next()!!
        val info =
            when {
                infoRequest.path == "/info" -> mapOf("displays" to listOf(mapOf("uuid" to "display-test")))
                else -> error("expected /info after SETUP, got ${infoRequest.method} ${infoRequest.path}")
            }
        infoRequest.respond(200, BinaryPlist.encode(info))
    }

    @Test
    fun declaredMediaFormatsDriveReceiverSpecificAudioSelection() {
        val client = AirPlayClient(endpoint(), log = {})
        setInfo(client, listOf(format(102, "media", 1L shl 23)))
        assertEquals(listOf(AudioCodec.AAC_LC), client.audioCandidates().map { it.codec })
        assertEquals(48_000, client.audioCandidates().single().sampleRate)
        val windowsKey = client.receiverIdentity()

        setInfo(client, listOf(format(100, "media", 1L shl 11), format(102, "media", 1L shl 22)))
        assertEquals(AudioCodec.AAC_LC, client.audioCandidates().first().codec)
        assertEquals(44_100, client.audioCandidates().first().sampleRate)
        assertNotEquals(windowsKey, client.receiverIdentity())

        setInfo(client, listOf(format(100, "media", 1L shl 15)))
        assertEquals(AudioCodec.PCM, client.audioCandidates().single().codec)
        assertEquals(48_000, client.audioCandidates().single().sampleRate)
    }

    @Test
    fun audioBaselineSwitchRestoresLegacyFallback() {
        val client = AirPlayClient(endpoint(), log = {})
        setInfo(client, emptyList())
        assertEquals(listOf(AudioCodec.PCM), client.audioCandidates(allowBaseline = true).map { it.codec })
        assertEquals(48_000, client.audioCandidates(allowBaseline = true).single().sampleRate)

        // 只有非媒体档：开关打开后与旧行为一致，可用该档的 PCM
        setInfo(client, listOf(format(100, "telephony", 1L shl 15)))
        assertEquals("telephony", client.audioCandidates(allowBaseline = true).single().audioType)

        // 只有不支持的编码：回退协议基线
        setInfo(client, listOf(format(100, "media", 1L shl 2)))
        assertEquals(AudioCodec.PCM, client.audioCandidates(allowBaseline = true).single().codec)

        // 有可用声明时开关不改变选择
        setInfo(client, listOf(format(102, "media", 1L shl 23)))
        assertEquals(client.audioCandidates(), client.audioCandidates(allowBaseline = true))
    }

    @Test
    fun unsupportedAndTelephonyOnlyCapabilitiesNeverBecomeMediaBaseline() {
        val client = AirPlayClient(endpoint(), log = {})
        assertEquals(1, client.audioCandidates().size)
        setInfo(client, emptyList())
        assertTrue(client.audioCandidates().isEmpty())
        setInfo(client, listOf(format(100, "telephony", 1L shl 15)))
        assertTrue(client.audioCandidates().isEmpty())
        setInfo(client, listOf(format(102, "media", 1L shl 30)))
        assertTrue(client.audioCandidates().isEmpty())
    }

    @Test
    fun oldNetworkLinkCallbacksCannotAuthenticateReplacementConnection() {
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val client = AirPlayClient(endpoint().copy(airPlayPort = server.localPort), log = {})
            try {
                assertTrue(client.reconnect())
                server.accept().use {
                    val obtain = AirPlayClient::class.java.getDeclaredMethod("obtainIapLink").apply { isAccessible = true }
                    val oldLink = obtain.invoke(client) as VirtualIap2Link
                    client.close()
                    assertTrue(client.reconnect())
                    server.accept().use {
                        val newLink = obtain.invoke(client) as VirtualIap2Link
                        field("recordAccepted").setBoolean(client, true)
                        oldLink.onAuthenticationComplete!!.invoke()
                        oldLink.onControlSessionComplete!!.invoke()
                        assertFalse(client.networkHandoffReady)
                        assertFalse(client.mib3ControlSessionDone)
                        newLink.onAuthenticationComplete!!.invoke()
                        assertTrue(client.networkHandoffReady)
                    }
                }
            } finally {
                client.close()
            }
        }
    }

    private fun setInfo(
        client: AirPlayClient,
        formats: List<Map<String, Any>>,
    ) {
        field("negotiatedInfo").set(client, mapOf("audioFormats" to formats))
    }

    private fun format(
        type: Int,
        audioType: String,
        output: Long,
    ): Map<String, Any> = mapOf("type" to type, "audioType" to audioType, "audioOutputFormats" to output)

    private fun modeResource(
        resourceId: Long,
        transferType: Int,
        priority: Int,
        constraint: Int,
    ): Map<String, Long> =
        mapOf(
            "resourceID" to resourceId,
            "transferType" to transferType.toLong(),
            "transferPriority" to priority.toLong(),
            "takeConstraint" to constraint.toLong(),
            "borrowConstraint" to 100L,
        )

    private fun endpoint() =
        Iap2SenderSession.CarPlayEndpoint(
            ssid = "test-lan",
            passphrase = "",
            channel = 0,
            securityType = 0,
            airPlayPort = 17000,
            deviceIdentifier = "test-receiver-instance",
            publicKey = null,
            sourceVersion = "950.7.1",
            ipAddresses = listOf("127.0.0.1"),
            receiverSignals = signals,
        )

    private fun setupClient(
        endpoint: Iap2SenderSession.CarPlayEndpoint,
        log: (String) -> Unit = {},
    ): AirPlayClient =
        AirPlayClient(
            endpoint.copy(
                receiverSignals = endpoint.receiverSignals,
            ),
            log = log,
        )

    @Suppress("UNCHECKED_CAST")
    private fun setInitialSetupWritten(
        client: AirPlayClient,
        value: Boolean,
    ) {
        (field("initialSetupWritten").get(client) as AtomicBoolean).set(value)
    }

    private fun field(name: String) = AirPlayClient::class.java.getDeclaredField(name).apply { isAccessible = true }
}
