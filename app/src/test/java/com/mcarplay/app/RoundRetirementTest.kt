package com.mcarplay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundRetirementTest {
    @Test
    fun defaultRetirementCapturesAndStopsResources() {
        val fixture = Fixture()
        val client = Token("client")
        val sender = Token("sender")
        val audio = Token("audio")
        val bridge = Token("bridge")
        val context = Token("context")
        fixture.slots.seed(client, sender, audio, bridge, context)

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            listOf(
                "client.read",
                "client.begin",
                "recovery.clear",
                "autoplay.stop",
                "sender.read",
                "sender.clear",
                "sender.stop",
                "wifi.release",
                "context.read",
                "monitor.stop",
                "dimmer.inactive",
                "screen.restore",
                "audio.read",
                "audio.stop",
                "audio.clear",
                "bridge.read",
                "bridge.stop",
                "bridge.clear",
                "secret.clear",
                "host.clear",
                "carlife.stop",
                "client.read",
                "client.cancel",
                "client.read",
                "client.close",
                "client.clear",
                "session.inactive",
            ),
            fixture.events,
        )
        assertSame(client, fixture.cancelledClients.single())
        assertSame(client, fixture.closedClients.single())
        assertSame(sender, fixture.stoppedSenders.single())
        assertSame(audio, fixture.stoppedAudio.single())
        assertSame(bridge, fixture.stoppedBridges.single())
        assertSame(context, fixture.stoppedMonitors.single())
        assertTrue(fixture.slots.clientReads == 3)
        assertTrue(fixture.slots.senderReads == 1)
        assertTrue(fixture.slots.audioReads == 1)
        assertTrue(fixture.slots.bridgeReads == 1)
        assertTrue(fixture.slots.contextReads == 1)
        assertNullSlots(fixture.slots)
    }

    @Test
    fun disconnectRetirementQuiescesCapturedSenderWithoutClosingClient() {
        val fixture = Fixture()
        val client = Token("client")
        val sender = Token("sender")
        val audio = Token("audio")
        val bridge = Token("bridge")
        val context = Token("context")
        fixture.slots.seed(client, sender, audio, bridge, context)

        fixture.retirement.retire(closeClient = false, preserveScreenSocket = true)

        assertEquals(
            listOf(
                "client.read",
                "client.begin",
                "recovery.clear",
                "autoplay.stop",
                "sender.read",
                "sender.clear",
                "sender.quiesce",
                "wifi.release",
                "context.read",
                "monitor.stop",
                "dimmer.inactive",
                "screen.restore",
                "audio.read",
                "audio.stop",
                "audio.clear",
                "bridge.read",
                "bridge.stop",
                "bridge.clear",
                "secret.clear",
                "host.clear",
                "carlife.stop",
                "client.read",
                "client.cancel",
                "client.clear",
                "session.inactive",
            ),
            fixture.events,
        )
        assertSame(client, fixture.cancelledClients.single())
        assertTrue(fixture.closedClients.isEmpty())
        assertSame(sender, fixture.quiescedSenders.single())
        assertSame(audio, fixture.stoppedAudio.single())
        assertSame(bridge, fixture.stoppedBridges.single())
        assertSame(context, fixture.stoppedMonitors.single())
        assertEquals(2, fixture.slots.clientReads)
        assertEquals(1, fixture.slots.senderReads)
        assertEquals(1, fixture.slots.audioReads)
        assertEquals(1, fixture.slots.bridgeReads)
        assertEquals(1, fixture.slots.contextReads)
        assertNullSlots(fixture.slots)
    }

    private fun assertNullSlots(slots: Fixture.RecordingSlots) {
        assertNull(slots.clientValue)
        assertNull(slots.senderValue)
        assertNull(slots.audioValue)
        assertNull(slots.bridgeValue)
    }

    @Test
    fun allNullRetireKeepsOnlyUnconditionalOperations() {
        val fixture = Fixture()

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            listOf(
                "client.read",
                "recovery.clear",
                "autoplay.stop",
                "sender.read",
                "sender.clear",
                "wifi.release",
                "context.read",
                "dimmer.inactive",
                "screen.restore",
                "audio.read",
                "audio.clear",
                "bridge.read",
                "bridge.clear",
                "secret.clear",
                "host.clear",
                "carlife.stop",
                "client.read",
                "client.read",
                "client.clear",
                "session.inactive",
            ),
            fixture.events,
        )
        assertEquals(
            listOf(
                "client.read",
                "client.read",
                "client.read",
                "client.clear",
            ),
            fixture.events.filter { it == "client.read" || it == "client.clear" },
        )
        assertEquals(3, fixture.slots.clientReads)
        assertEquals(1, fixture.slots.senderReads)
        assertEquals(1, fixture.slots.audioReads)
        assertEquals(1, fixture.slots.bridgeReads)
        assertEquals(1, fixture.slots.contextReads)
    }

    @Test
    fun nullClientShortCircuitsOnlyClientLeafs() {
        val fixture = Fixture()
        fixture.slots.seed(
            sender = Token("sender"),
            audio = Token("audio"),
            bridge = Token("bridge"),
            context = Token("context"),
        )

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            listOf(
                "client.read",
                "recovery.clear",
                "autoplay.stop",
                "sender.read",
                "sender.clear",
                "sender.stop",
                "wifi.release",
                "context.read",
                "monitor.stop",
                "dimmer.inactive",
                "screen.restore",
                "audio.read",
                "audio.stop",
                "audio.clear",
                "bridge.read",
                "bridge.stop",
                "bridge.clear",
                "secret.clear",
                "host.clear",
                "carlife.stop",
                "client.read",
                "client.read",
                "client.clear",
                "session.inactive",
            ),
            fixture.events,
        )
        assertEquals(3, fixture.slots.clientReads)
    }

    @Test
    fun nullSenderShortCircuitsOnlySenderLeafs() {
        val fixture = Fixture()
        fixture.slots.seed(
            client = Token("client"),
            audio = Token("audio"),
            bridge = Token("bridge"),
            context = Token("context"),
        )

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            baseEvents(closeClient = true, preserveScreenSocket = false)
                .filterNot { it == "sender.stop" },
            fixture.events,
        )
        assertEquals(1, fixture.slots.senderReads)
        assertNull(fixture.slots.senderValue)
        assertTrue(fixture.stoppedSenders.isEmpty())
        assertTrue(fixture.quiescedSenders.isEmpty())
    }

    @Test
    fun nullAudioShortCircuitsOnlyAudioLeafs() {
        val fixture = Fixture()
        fixture.slots.seed(
            client = Token("client"),
            sender = Token("sender"),
            bridge = Token("bridge"),
            context = Token("context"),
        )

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            baseEvents(closeClient = true, preserveScreenSocket = false)
                .filterNot { it == "audio.stop" },
            fixture.events,
        )
        assertEquals(1, fixture.slots.audioReads)
        assertNull(fixture.slots.audioValue)
        assertTrue(fixture.stoppedAudio.isEmpty())
    }

    @Test
    fun nullBridgeShortCircuitsOnlyBridgeLeafs() {
        val fixture = Fixture()
        fixture.slots.seed(
            client = Token("client"),
            sender = Token("sender"),
            audio = Token("audio"),
            context = Token("context"),
        )

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            baseEvents(closeClient = true, preserveScreenSocket = false)
                .filterNot { it == "bridge.stop" },
            fixture.events,
        )
        assertEquals(1, fixture.slots.bridgeReads)
        assertNull(fixture.slots.bridgeValue)
        assertTrue(fixture.stoppedBridges.isEmpty())
    }

    @Test
    fun nullContextShortCircuitsOnlyMonitorLeafs() {
        val fixture = Fixture()
        fixture.slots.seed(
            client = Token("client"),
            sender = Token("sender"),
            audio = Token("audio"),
            bridge = Token("bridge"),
        )

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            baseEvents(closeClient = true, preserveScreenSocket = false)
                .filterNot { it == "monitor.stop" },
            fixture.events,
        )
        assertEquals(1, fixture.slots.contextReads)
        assertTrue(fixture.stoppedMonitors.isEmpty())
    }

    @Test
    fun constructorDoesNotReadSlotsOrRunLeaves() {
        val fixture = Fixture()

        assertTrue(fixture.events.isEmpty())
        assertEquals(0, fixture.slots.clientReads)
        assertEquals(0, fixture.slots.senderReads)
        assertEquals(0, fixture.slots.audioReads)
        assertEquals(0, fixture.slots.bridgeReads)
        assertEquals(0, fixture.slots.contextReads)

        fixture.slots.seed(
            client = Token("client"),
            sender = Token("sender"),
            audio = Token("audio"),
            bridge = Token("bridge"),
            context = Token("context"),
        )

        assertTrue(fixture.events.isEmpty())
        assertEquals(0, fixture.slots.clientReads)

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(baseEvents(closeClient = true, preserveScreenSocket = false), fixture.events)
    }

    @Test
    fun contextIsReadLateAfterWifiRelease() {
        val fixture = Fixture()
        val lateContext = Token("late-context")
        fixture.slots.seed(
            client = Token("client"),
            sender = Token("sender"),
            audio = Token("audio"),
            bridge = Token("bridge"),
        )
        fixture.actions.hook = { event ->
            if (event == "wifi.release") fixture.slots.contextValue = lateContext
        }

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(baseEvents(closeClient = true, preserveScreenSocket = false), fixture.events)
        assertEquals(1, fixture.slots.contextReads)
        assertSame(lateContext, fixture.stoppedMonitors.single())
    }

    @Test
    fun eachActionLeafPropagatesSentinelAndStopsTrace() {
        listOf(
            "client.begin",
            "autoplay.stop",
            "sender.stop",
            "wifi.release",
            "monitor.stop",
            "dimmer.inactive",
            "screen.restore",
            "audio.stop",
            "bridge.stop",
            "carlife.stop",
            "client.close",
        ).forEach { leaf ->
            assertLeafThrows(
                leaf = leaf,
                closeClient = true,
                preserveScreenSocket = false,
            )
        }
    }

    @Test
    fun quiesceLeafPropagatesSentinelAndStopsTrace() {
        assertLeafThrows(
            leaf = "sender.quiesce",
            closeClient = true,
            preserveScreenSocket = true,
        )
    }

    @Test
    fun retireRunsOnCallingThreadWithAndWithoutMonitor() {
        val monitor = Any()
        val unheldFixture = Fixture().apply { monitorLock = monitor }
        val heldFixture = Fixture().apply { monitorLock = monitor }

        unheldFixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertTrue(unheldFixture.threadIds.values.all { it == Thread.currentThread().id })
        assertTrue(unheldFixture.holdsLocks.values.none { it })

        synchronized(monitor) {
            heldFixture.retirement.retire(closeClient = true, preserveScreenSocket = false)
        }

        assertTrue(heldFixture.threadIds.values.all { it == Thread.currentThread().id })
        assertTrue(heldFixture.holdsLocks.values.all { it })
    }

    @Test
    fun clientDynamicReadsUseDistinctAAndBAndC() {
        val fixture = Fixture()
        val clientA = Token("A")
        val clientB = Token("B")
        val clientC = Token("C")
        fixture.slots.seed(client = clientA)
        fixture.actions.hook = { event ->
            if (event == "client.begin") fixture.slots.clientValue = clientB
        }
        fixture.slots.slotHook = { event ->
            if (event == "client.cancel") fixture.slots.clientValue = clientC
        }

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            baseEvents(closeClient = true, preserveScreenSocket = false)
                .filterNot { it == "sender.stop" || it == "monitor.stop" || it == "audio.stop" || it == "bridge.stop" },
            fixture.events,
        )
        assertEquals(3, fixture.slots.clientReads)
        assertSame(clientA, fixture.begunClients.single())
        assertSame(clientB, fixture.cancelledClients.single())
        assertSame(clientC, fixture.closedClients.single())
        assertNull(fixture.slots.clientValue)
    }

    @Test
    fun clientDynamicReadsWithoutCloseUsesOnlyAAndB() {
        val fixture = Fixture()
        val clientA = Token("A")
        val clientB = Token("B")
        fixture.slots.seed(client = clientA)
        fixture.actions.hook = { event ->
            if (event == "client.begin") fixture.slots.clientValue = clientB
        }

        fixture.retirement.retire(closeClient = false, preserveScreenSocket = false)

        assertEquals(
            baseEvents(closeClient = false, preserveScreenSocket = false)
                .filterNot { it == "sender.stop" || it == "monitor.stop" || it == "audio.stop" || it == "bridge.stop" },
            fixture.events,
        )
        assertEquals(2, fixture.slots.clientReads)
        assertSame(clientA, fixture.begunClients.single())
        assertSame(clientB, fixture.cancelledClients.single())
        assertTrue(fixture.closedClients.isEmpty())
        assertNull(fixture.slots.clientValue)
    }

    @Test
    fun audioStopRebindIsClearedAndBridgeUsesLateValue() {
        val fixture = Fixture()
        val audio = Token("audio")
        val reboundAudio = Token("audio-rebind")
        val lateBridge = Token("bridge-late")
        fixture.slots.seed(
            client = Token("client"),
            sender = Token("sender"),
            audio = audio,
            context = Token("context"),
        )
        fixture.actions.hook = { event ->
            if (event == "audio.stop") fixture.slots.audioValue = reboundAudio
            if (event == "bridge.read") fixture.slots.bridgeValue = lateBridge
        }

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(
            baseEvents(closeClient = true, preserveScreenSocket = false)
                .filterNot { it == "bridge.stop" },
            fixture.events,
        )
        assertEquals(1, fixture.slots.audioReads)
        assertEquals(1, fixture.slots.bridgeReads)
        assertSame(audio, fixture.stoppedAudio.single())
        assertTrue(fixture.stoppedBridges.isEmpty())
        assertNull(fixture.slots.audioValue)
        assertNull(fixture.slots.bridgeValue)
    }

    @Test
    fun bridgeStopRebindIsClearedByFollowingNull() {
        val fixture = Fixture()
        val bridge = Token("bridge")
        val reboundBridge = Token("bridge-rebind")
        fixture.slots.seed(
            client = Token("client"),
            sender = Token("sender"),
            audio = Token("audio"),
            bridge = bridge,
            context = Token("context"),
        )
        fixture.actions.hook = { event ->
            if (event == "bridge.stop") fixture.slots.bridgeValue = reboundBridge
        }

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(baseEvents(closeClient = true, preserveScreenSocket = false), fixture.events)
        assertEquals(1, fixture.slots.bridgeReads)
        assertSame(bridge, fixture.stoppedBridges.single())
        assertNull(fixture.slots.bridgeValue)
    }

    @Test
    fun senderLeafObservesClearedSlotAtStop() {
        val fixture = Fixture()
        val sender = Token("sender")
        fixture.slots.seed(sender = sender)

        fixture.retirement.retire(closeClient = true, preserveScreenSocket = false)

        assertEquals(1, fixture.slots.senderReads)
        assertNull(fixture.slots.senderValue)
        assertSame(sender, fixture.stoppedSenders.single())
    }

    @Test
    fun senderLeafObservesClearedSlotAtQuiesce() {
        val fixture = Fixture()
        val sender = Token("sender")
        fixture.slots.seed(sender = sender)

        fixture.retirement.retire(closeClient = false, preserveScreenSocket = true)

        assertEquals(1, fixture.slots.senderReads)
        assertNull(fixture.slots.senderValue)
        assertSame(sender, fixture.quiescedSenders.single())
    }

    private fun baseEvents(
        closeClient: Boolean,
        preserveScreenSocket: Boolean,
    ): List<String> {
        val senderEvent = if (preserveScreenSocket) "sender.quiesce" else "sender.stop"
        val clientTail = if (closeClient) listOf("client.read", "client.close") else emptyList()
        return listOf(
            "client.read",
            "client.begin",
            "recovery.clear",
            "autoplay.stop",
            "sender.read",
            "sender.clear",
            senderEvent,
            "wifi.release",
            "context.read",
            "monitor.stop",
            "dimmer.inactive",
            "screen.restore",
            "audio.read",
            "audio.stop",
            "audio.clear",
            "bridge.read",
            "bridge.stop",
            "bridge.clear",
            "secret.clear",
            "host.clear",
            "carlife.stop",
            "client.read",
            "client.cancel",
        ) + clientTail + listOf("client.clear", "session.inactive")
    }

    private fun assertLeafThrows(
        leaf: String,
        closeClient: Boolean,
        preserveScreenSocket: Boolean,
    ) {
        val fixture = Fixture()
        fixture.slots.seed(
            client = Token("client"),
            sender = Token("sender"),
            audio = Token("audio"),
            bridge = Token("bridge"),
            context = Token("context"),
        )
        val sentinel = SentinelException()
        fixture.throwingAction = leaf
        fixture.sentinel = sentinel

        val thrown =
            runCatching {
                fixture.retirement.retire(closeClient, preserveScreenSocket)
            }.exceptionOrNull()

        assertSame(sentinel, thrown)
        assertEquals(prefixThrough(leaf, closeClient, preserveScreenSocket), fixture.events)
    }

    private fun prefixThrough(
        leaf: String,
        closeClient: Boolean,
        preserveScreenSocket: Boolean,
    ): List<String> {
        val base = baseEvents(closeClient, preserveScreenSocket)
        val at = base.indexOf(leaf)
        check(at >= 0) { "$leaf 不在基础事件序列中" }
        return base.subList(0, at + 1)
    }

    private class Token(
        val name: String,
    )

    private class SentinelException : RuntimeException()

    private class WorkerException : RuntimeException()

    private class Fixture {
        val events = mutableListOf<String>()
        val cancelledClients = mutableListOf<Token>()
        val closedClients = mutableListOf<Token>()
        val begunClients = mutableListOf<Token>()
        val stoppedSenders = mutableListOf<Token>()
        val quiescedSenders = mutableListOf<Token>()
        val stoppedAudio = mutableListOf<Token>()
        val stoppedBridges = mutableListOf<Token>()
        val stoppedMonitors = mutableListOf<Token>()
        var throwingAction: String? = null
        var sentinel: Throwable? = null
        var monitorLock: Any? = null
        val threadIds = mutableMapOf<String, Long>()
        val holdsLocks = mutableMapOf<String, Boolean>()
        val slots = RecordingSlots(events, cancelledClients)
        val fixtureSlots = slots
        val actions = RecordingActions(events, this)
        val retirement = RoundRetirement(slots, actions)

        class RecordingSlots(
            private val events: MutableList<String>,
            private val cancelledClients: MutableList<Token>,
        ) : RoundSlots<Token, Token, Token, Token, Token> {
            var clientReads = 0
            var senderReads = 0
            var audioReads = 0
            var bridgeReads = 0
            var contextReads = 0
            var contextValue: Token? = null
            var clientValue: Token? = null
            var senderValue: Token? = null
            var audioValue: Token? = null
            var bridgeValue: Token? = null
            var slotHook: ((String) -> Unit)? = null

            override var client: Token?
                get() {
                    clientReads++
                    events.add("client.read")
                    return clientValue
                }
                set(value) {
                    events.add("client.clear")
                    clientValue = value
                }

            override var sender: Token?
                get() {
                    senderReads++
                    events.add("sender.read")
                    return senderValue
                }
                set(value) {
                    events.add("sender.clear")
                    senderValue = value
                }

            override var audio: Token?
                get() {
                    audioReads++
                    events.add("audio.read")
                    return audioValue
                }
                set(value) {
                    events.add("audio.clear")
                    audioValue = value
                }

            override var bridge: Token?
                get() {
                    bridgeReads++
                    events.add("bridge.read")
                    return bridgeValue
                }
                set(value) {
                    events.add("bridge.clear")
                    bridgeValue = value
                }

            override val context: Token?
                get() {
                    contextReads++
                    events.add("context.read")
                    return contextValue
                }

            fun seed(
                client: Token? = null,
                sender: Token? = null,
                audio: Token? = null,
                bridge: Token? = null,
                context: Token? = null,
            ) {
                clientValue = client
                senderValue = sender
                audioValue = audio
                bridgeValue = bridge
                contextValue = context
            }

            override fun clearRecovery() {
                events.add("recovery.clear")
            }

            override fun clearSharedSecret() {
                events.add("secret.clear")
            }

            override fun clearAudioHost() {
                events.add("host.clear")
            }

            override fun markCancelled(client: Token) {
                events.add("client.cancel")
                cancelledClients.add(client)
                slotHook?.invoke("client.cancel")
            }

            override fun setSessionInactive() {
                events.add("session.inactive")
            }
        }

        class RecordingActions(
            private val events: MutableList<String>,
            private val fixture: Fixture,
        ) : RoundActions<Token, Token, Token, Token, Token> {
            var hook: ((String) -> Unit)? = null

            override fun beginClient(client: Token) {
                events.add("client.begin")
                fixture.begunClients.add(client)
                after("client.begin")
            }

            override fun stopAutoPlayWatch() {
                events.add("autoplay.stop")
                after("autoplay.stop")
            }

            override fun stopSender(sender: Token) {
                assertClearedSender()
                events.add("sender.stop")
                fixture.stoppedSenders.add(sender)
                after("sender.stop")
            }

            override fun quiesceSender(sender: Token) {
                assertClearedSender()
                events.add("sender.quiesce")
                fixture.quiescedSenders.add(sender)
                after("sender.quiesce")
            }

            override fun releaseWifiLock() {
                events.add("wifi.release")
                after("wifi.release")
            }

            override fun stopScreenMonitor(context: Token) {
                events.add("monitor.stop")
                fixture.stoppedMonitors.add(context)
                after("monitor.stop")
            }

            override fun setDimmerInactive() {
                events.add("dimmer.inactive")
                after("dimmer.inactive")
            }

            override fun restorePhoneScreen(sleepIfCarSide: Boolean) {
                events.add("screen.restore")
                after("screen.restore")
            }

            override fun stopAudio(audio: Token) {
                events.add("audio.stop")
                fixture.stoppedAudio.add(audio)
                after("audio.stop")
            }

            override fun stopBridge(bridge: Token) {
                events.add("bridge.stop")
                fixture.stoppedBridges.add(bridge)
                after("bridge.stop")
            }

            override fun stopFormalCarLife() {
                events.add("carlife.stop")
                after("carlife.stop")
            }

            override fun closeClient(client: Token) {
                events.add("client.close")
                fixture.closedClients.add(client)
                after("client.close")
            }

            private fun assertClearedSender() {
                assertTrue("sender slot must be cleared before stop or quiesce", fixture.slots.senderValue == null)
            }

            private fun after(event: String) {
                fixture.threadIds[event] = Thread.currentThread().id
                fixture.monitorLock?.let { lock ->
                    fixture.holdsLocks[event] = Thread.holdsLock(lock)
                }
                hook?.invoke(event)
                fixture.throwingAction?.takeIf { it == event }?.let {
                    throw requireNotNull(fixture.sentinel) { "missing sentinel" }
                }
            }
        }
    }
}
