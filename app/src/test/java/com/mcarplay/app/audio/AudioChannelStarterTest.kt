package com.mcarplay.app.audio

import com.mcarplay.app.airplay.AirPlayClient
import com.mcarplay.app.airplay.ModeArbiter
import com.mcarplay.app.session.AttemptScope
import com.mcarplay.app.session.EventCommandRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** J01–J26：真实启动控制流，媒体/时钟/会话均为每例独立 fake。 */
class AudioChannelStarterTest {
    private val pcm = AudioProfile("media", 1L shl 15, 48_000, 110)
    private val aac = AudioProfile("media", 1L shl 22, 44_100, 90, 102, AudioCodec.AAC_LC)
    private val compat = AudioProfile("compatibility", 1L shl 11, 44_100, 120)

    private class FakeClock(
        private val trace: MutableList<String>,
    ) {
        var time = 1_000L

        fun now(): Long {
            trace += "clock:$time"
            return time
        }
    }

    private class FakeClient {
        var generation = 7L
        var current = true
        var stopping = false
        var owns = true
        var claim = AudioClaim("claim", true, true)
        var identity = "receiver"
        var candidates: List<AudioProfile> = emptyList()
        var candidateHook: (Boolean, Boolean) -> Unit = { _, _ -> }
        var claimHook: () -> Unit = {}
        var setupHook: (AudioProfile) -> AudioSetup? = { AudioSetup(123, 40000, 40001, byteArrayOf(9), 44_100) }
        var recordHook: () -> Boolean = { true }
    }

    private class FakeSender {
        var running = false
    }

    private class FakeBridge

    private class FakeSessionPort(
        private val trace: MutableList<String>,
    ) : AudioSessionPort<FakeClient, Any, FakeSender, FakeBridge> {
        val monitor = Any()
        var clientValue: FakeClient? = null
        var contextValue: Any? = Any()
        var secretValue: ByteArray? = byteArrayOf(1, 2)
        var hostValue: String? = "host"
        var enabledValue = true
        var preferValue = false
        var baselineValue = true
        override val currentClient get() = clientValue.also { trace += "client" }
        override val context get() = contextValue.also { trace += "context" }
        override val secret get() = secretValue.also { trace += "secret" }
        override val host get() = hostValue.also { trace += "host" }
        override val enabled get() = enabledValue.also { trace += "enabled" }
        override val preferCompat get() = preferValue.also { trace += "prefer:$it" }
        private var senderValue: FakeSender? = null
        private var bridgeValue: FakeBridge? = null
        override var sender: FakeSender?
            get() = senderValue.also { trace += "sender" }
            set(value) {
                trace += "sender=${value != null}"
                senderValue = value
            }
        override var bridge: FakeBridge?
            get() = bridgeValue.also { trace += "bridge" }
            set(value) {
                trace += "bridge=${value != null}"
                bridgeValue = value
            }

        override fun baselineAllowed() = baselineValue.also { trace += "baseline:$it" }

        override fun <T> withControllerLock(block: () -> T): T =
            synchronized(monitor) {
                trace += "lock"
                try {
                    block()
                } finally {
                    trace += "unlock"
                }
            }
    }

    private class FakeMemory(
        private val trace: MutableList<String>,
    ) : AudioStartMemory<Any> {
        var entry = AudioProfileMemory.Entry()
        val rejected = mutableListOf<Triple<String, Set<String>, Boolean>>()

        override fun load(
            context: Any,
            identity: String,
        ) = entry.also { trace += "load:$identity" }

        override fun reset(
            context: Any,
            identity: String,
        ) {
            trace += "reset:$identity"
        }

        override fun recordRejected(
            context: Any,
            identity: String,
            key: String,
            allCandidates: Set<String>,
            markExhausted: Boolean,
        ) {
            trace += "reject:$key"
            rejected += Triple(key, allCandidates, markExhausted)
        }
    }

    private class TraceSlots(
        private val trace: MutableList<String>,
    ) : ActiveAudioSlots {
        private val state = AudioChannelState()
        override var profile: AudioProfile?
            get() = state.profile.also { trace += "read.profile" }
            set(value) {
                trace += "profile=${value?.key}"
                state.profile = value
            }
        override var receiverKey: String
            get() = state.receiverKey.also { trace += "read.key" }
            set(value) {
                trace += "key=$value"
                state.receiverKey = value
            }
        override var startedAtMs: Long
            get() = state.startedAtMs.also { trace += "read.time" }
            set(value) {
                trace += "time=$value"
                state.startedAtMs = value
            }
    }

    private class FakeSourceFactory(
        private val trace: MutableList<String>,
    ) : AudioSourcePort<Any, Any, FakeSender, FakeBridge> {
        var projectionValue: Any? = Any()
        var native = false
        val senders = mutableListOf<FakeSender>()
        val bridges = mutableListOf<FakeBridge>()
        var spec: AudioSenderSpec? = null
        var context: Any? = null
        var packet: () -> Unit = {}
        var hook: (String) -> Unit = {}
        var started = true

        override fun projection() = projectionValue.also { trace += "projection" }

        override fun nativeAvailable(context: Any) = native.also { trace += "native" }

        override fun applicationContext(context: Any) = context.also { trace += "applicationContext" }

        override fun isRunning(sender: FakeSender) = sender.running.also { trace += "running" }

        override fun createSender(
            spec: AudioSenderSpec,
            context: Any,
            log: (String) -> Unit,
            onPacketSent: () -> Unit,
        ): FakeSender {
            trace += "factory"
            hook("factory")
            this.spec = spec
            this.context = context
            packet = onPacketSent
            return FakeSender().also { senders += it }
        }

        override fun createBridge(
            sender: FakeSender,
            sampleRate: Int,
            context: Any,
            log: (String) -> Unit,
        ): FakeBridge {
            trace += "bridgeFactory:$sampleRate"
            hook("bridgeFactory")
            return FakeBridge().also { bridges += it }
        }

        override fun startCapture(
            sender: FakeSender,
            projection: Any,
        ): Boolean {
            trace += "capture"
            hook("capture")
            sender.running = started
            return started
        }

        override fun startExternal(sender: FakeSender): Boolean {
            trace += "external"
            hook("external")
            sender.running = started
            return started
        }

        override fun startBridge(bridge: FakeBridge) {
            trace += "bridge.start"
            hook("bridge.start")
        }

        override fun stopSender(sender: FakeSender) {
            trace += "sender.stop"
            sender.running = false
        }

        override fun stopBridge(bridge: FakeBridge) {
            trace += "bridge.stop"
        }
    }

    private inner class Rig {
        val trace = java.util.Collections.synchronizedList(mutableListOf<String>())
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val clock = FakeClock(trace)
        val client = FakeClient().apply { candidates = listOf(pcm, aac, compat) }
        val session = FakeSessionPort(trace).apply { clientValue = client }
        val source = FakeSourceFactory(trace)
        val memory = FakeMemory(trace)
        val active = TraceSlots(trace)
        var collecting = false
        var preferred: String? = null
        var attempt = "attempt-1"
        var hook: (String) -> Unit = {}
        var setupCount = 0
        var recordCount = 0
        val setupSecrets = mutableListOf<ByteArray>()
        val starter =
            AudioChannelStarter(
                session,
                object : AudioClientPort<FakeClient> {
                    override fun generation(client: FakeClient) = client.generation.also { trace += "generation" }

                    override fun isConnectionCurrent(
                        client: FakeClient,
                        generation: Long,
                    ) = (client.current && client.generation == generation).also { trace += "current:$generation" }

                    override fun isStopping(client: FakeClient) = client.stopping.also { trace += "stopping" }

                    override fun receiverIdentity(client: FakeClient) = client.identity.also { trace += "identity" }

                    override fun candidates(
                        client: FakeClient,
                        preferCompat: Boolean,
                        allowBaseline: Boolean,
                    ): List<AudioProfile> {
                        trace += "candidates:$preferCompat:$allowBaseline"
                        client.candidateHook(preferCompat, allowBaseline)
                        return client.candidates
                    }

                    override fun claim(
                        client: FakeClient,
                        generation: Long,
                    ): AudioClaim {
                        trace += "claim:$generation"
                        client.claimHook()
                        return client.claim
                    }

                    override fun phoneOwnsAudio(client: FakeClient) = client.owns.also { trace += "owns" }

                    override fun setup(
                        client: FakeClient,
                        secret: ByteArray,
                        profile: AudioProfile,
                        tuning: AudioTuning,
                        expectedEpoch: Long,
                    ): AudioSetup? {
                        assertEquals(7L, expectedEpoch)
                        assertEquals(AudioTuning.RAW_SPF, tuning)
                        trace += "setup:${profile.key}"
                        setupSecrets += secret
                        setupCount++
                        return client.setupHook(profile)
                    }

                    override fun record(client: FakeClient): Boolean {
                        trace += "record"
                        recordCount++
                        return client.recordHook()
                    }
                },
                source,
                memory,
                object : AudioDetectionPort<FakeClient> {
                    override fun collecting(client: FakeClient) = collecting.also { trace += "collecting" }

                    override fun preferredAudio(client: FakeClient) = preferred.also { trace += "preferred" }

                    override fun audioSelected(
                        client: FakeClient,
                        key: String,
                    ) {
                        trace += "selected:$key"
                        hook("selected")
                    }
                },
                object : AudioStartDiagnostics {
                    override fun currentAttemptId() = attempt.also { trace += "attempt" }

                    override fun note(
                        attempt: String,
                        line: String,
                    ) {
                        trace += "note:$attempt:$line"
                        hook("note")
                    }

                    override fun audioPacket(attempt: String) {
                        trace += "packet:$attempt"
                    }
                },
                active,
                clock::now,
                {
                    logs += it
                    trace += "log:$it"
                    hook("log:$it")
                },
            )

        fun start(
            secret: ByteArray? = session.secretValue,
            host: String? = session.hostValue,
        ) = starter.startAudioChannel(session.contextValue!!, client, secret, host)

        fun record(skip: Boolean = false): Boolean =
            starter.startAudioAndRecord(session.contextValue!!, client, session.secretValue!!, session.hostValue, "joined", !skip)
    }

    private fun order(
        trace: List<String>,
        vararg entries: String,
    ) {
        var from = -1
        entries.forEach { entry ->
            val at = trace.indices.firstOrNull { it > from && trace[it] == entry }
            assertNotNull("missing ordered $entry in $trace", at)
            from = at!!
        }
    }

    private fun setups(r: Rig) = r.trace.filter { it.startsWith("setup:") }

    @Test fun j01AndJ02CandidatesUseMemoryThenProbePreferenceWithoutRewritingFormats() {
        listOf(false, true).forEach { prefer ->
            val r = Rig()
            r.session.preferValue = prefer
            r.client.candidates = listOf(pcm, aac, compat, pcm)
            r.memory.entry = AudioProfileMemory.Entry(working = aac.key, rejected = setOf(pcm.key))
            r.preferred = compat.key
            r.client.setupHook = { null }
            assertFalse(r.start())
            assertEquals(listOf(compat, aac, pcm).map { "setup:${it.key}" }, setups(r))
            order(r.trace, "load:receiver", "candidates:$prefer:true", "preferred", "claim:7")
        }
        val all = Rig()
        all.memory.entry = AudioProfileMemory.Entry(rejected = setOf(pcm.key, aac.key, compat.key))
        all.client.setupHook = { null }
        assertFalse(all.start())
        assertEquals(all.client.candidates.map { "setup:${it.key}" }, setups(all))
        val empty = Rig()
        empty.client.candidates = emptyList()
        assertFalse(empty.start())
        assertFalse(empty.trace.any { it.startsWith("claim:") })
        assertEquals(listOf("AUDIO: 没有可用音频档位（候选=[]）——仅推视频"), empty.logs)
    }

    @Test fun j03DisabledMemoryResetsButCollectingNeverReadsOrWritesIdentityMemory() {
        val r = Rig()
        r.memory.entry = AudioProfileMemory.Entry(disabled = true)
        assertTrue(r.start())
        order(r.trace, "load:receiver", "reset:receiver", "candidates:false:true")
        val c = Rig()
        c.collecting = true
        c.preferred = aac.key
        c.client.setupHook = { null }
        assertFalse(c.start())
        assertEquals("setup:${aac.key}", setups(c).first())
        assertFalse(c.trace.any { it == "identity" || it.startsWith("load:") || it.startsWith("reset:") || it.startsWith("reject:") })
    }

    @Test fun j04SetupGateRejectsDifferentEntryWhilePausedAndIsReusableAfterFailure() {
        val r = Rig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        r.client.setupHook = {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            null
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> { r.start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit { r.starter.retryAudioAfterClaimReturned(r.client) }
            second.get(5, TimeUnit.SECONDS)
            assertEquals(1, r.setupCount)
            assertEquals("AUDIO: 已有音频通道正在建立——跳过本次重复启动", r.logs.last())
            release.countDown()
            assertFalse(first.get(5, TimeUnit.SECONDS))
            r.client.setupHook = { AudioSetup(1, 2, 3, byteArrayOf(4), 48_000) }
            assertTrue(r.start())
            assertEquals(1, r.source.senders.size)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun j05RecordPauseIsOutsideGateAndJ15RecordFalseStillReturnsStarted() {
        val r = Rig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        r.client.recordHook = {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            false
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<Boolean> { r.record() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(r.start())
            assertEquals("AUDIO: 已有音频发送器在运行——跳过重复启动", r.logs.last())
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertEquals(1, r.recordCount)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun j06PreconditionsShortCircuitInBaselineOrder() {
        val cases =
            listOf<(Rig) -> Unit>(
                { it.session.sender = FakeSender().apply { running = true } },
                { it.client.current = false },
                { it.session.enabledValue = false },
                { it.session.secretValue = null },
                { it.session.hostValue = null },
            )
        val expected =
            listOf(
                listOf("sender", "running"),
                listOf("sender", "generation", "current:7"),
                listOf("sender", "generation", "current:7", "attempt", "enabled"),
            )
        cases.forEachIndexed { i, configure ->
            val r = Rig()
            configure(r)
            r.trace.clear()
            assertFalse(r.start())
            assertFalse(r.trace.contains("projection"))
            assertEquals(0, r.setupCount)
            assertEquals(0, r.recordCount)
            assertTrue(r.source.senders.isEmpty())
            assertTrue(r.memory.rejected.isEmpty())
            if (i < 3) assertEquals(expected[i], r.trace.filterNot { it.startsWith("log:") })
        }
    }

    @Test fun j07AllProjectionAndNativeCombinationsKeepEagerReadOrderAndNativePriority() {
        listOf(false, true).forEach { projection ->
            listOf(false, true).forEach { native ->
                val r = Rig()
                r.source.projectionValue = if (projection) Any() else null
                r.source.native = native
                assertEquals(projection || native, r.start())
                order(r.trace, "projection", "native")
                assertEquals(1, r.trace.count { it == "projection" })
                assertEquals(1, r.trace.count { it == "native" })
                assertEquals(native, r.trace.contains("external"))
                assertEquals(native, r.trace.contains("bridge.start"))
                assertEquals(projection && !native, r.trace.contains("capture"))
            }
        }
        val fail = Rig()
        fail.source.native = true
        fail.source.started = false
        assertFalse(fail.start())
        assertFalse(fail.trace.contains("bridge.start"))
    }

    @Test fun j08ClaimDenialOrUnannouncedFailureNeverRejectsFormats() {
        val denied = Rig()
        denied.client.claim = AudioClaim("busy", false, false)
        assertFalse(denied.record())
        assertEquals(0, denied.setupCount)
        assertTrue(denied.memory.rejected.isEmpty())
        assertEquals("AUDIO: 主音频资源未接管（busy）——本次不发送 SETUP，等车机归还主音频后重试", denied.logs.last())
        val uncertain = Rig()
        uncertain.client.claim = AudioClaim("unconfirmed", true, false)
        uncertain.client.setupHook = { null }
        assertFalse(uncertain.record())
        assertEquals(3, uncertain.setupCount)
        assertTrue(uncertain.memory.rejected.isEmpty())
        assertEquals(0, uncertain.recordCount)
    }

    @Test fun j09OwnershipLossBeforeOrAfterSetupStopsRotationWithoutRejection() {
        listOf(false, true).forEach { during ->
            val r = Rig()
            if (during) {
                r.client.setupHook = {
                    r.client.owns = false
                    null
                }
            } else {
                r.client.claimHook = { r.client.owns = false }
            }
            assertFalse(r.start())
            assertEquals(if (during) 1 else 0, r.setupCount)
            assertTrue(r.memory.rejected.isEmpty())
            assertEquals(
                if (during) {
                    "AUDIO: 主音频 SETUP 被拒时资源已归车机——不记为格式失败"
                } else {
                    "AUDIO: 主音频资源在协商期间转为车机占用——停止候选轮换，不记为格式失败"
                },
                r.logs.last(),
            )
        }
    }

    @Test fun j10RotationRejectsOnlyAnnouncedIdentityWithOrderedKeysAndNeverExhausts() {
        val r = Rig()
        r.client.setupHook = { if (it == pcm) null else AudioSetup(3, 4, 5, byteArrayOf(6), 44_100) }
        assertTrue(r.record())
        assertEquals(listOf(pcm, aac).map { "setup:${it.key}" }, setups(r))
        assertEquals(listOf(Triple(pcm.key, setOf(pcm.key, aac.key, compat.key), false)), r.memory.rejected)
        order(r.trace, "setup:${pcm.key}", "reject:${pcm.key}", "log:AUDIO: 档位 ${pcm.label} 协商失败——本会话继续试下一档", "setup:${aac.key}")
        val all = Rig()
        all.client.setupHook = { null }
        assertFalse(all.record())
        assertEquals(3, all.memory.rejected.size)
        assertTrue(all.memory.rejected.none { it.third })
        assertEquals("AUDIO: 全部候选档位协商失败（共 3 档）——本次仅推视频", all.logs.last())
        assertEquals(0, all.recordCount)
    }

    @Test fun j11PublishAndLeafSpecAndCapturedDiagnosticAttemptKeepExactOrder() {
        listOf(false, true).forEach { native ->
            val r = Rig()
            r.source.native = native
            val key = byteArrayOf(8, 7)
            r.client.setupHook = { AudioSetup(123, 40000, 40001, key, 44_100) }
            assertTrue(r.record())
            val spec = r.source.spec!!
            assertEquals("host", spec.host)
            assertEquals(40000, spec.dataPort)
            assertEquals(40001, spec.controlPort)
            assertSame(key, spec.streamKey)
            assertEquals(44_100, spec.sampleRate)
            assertEquals(pcm.codec, spec.codec)
            assertEquals(pcm.streamType, spec.streamType)
            assertFalse(spec.adtsFrames)
            assertSame(r.session.contextValue, r.source.context)
            assertSame(r.session.secretValue, r.setupSecrets.single())
            order(
                r.trace,
                "lock",
                "sender=true",
                "bridge=$native",
                "profile=${pcm.key}",
                "key=receiver",
                "clock:1000",
                "time=1000",
                "unlock",
                "selected:${pcm.key}",
                "note:attempt-1:AUDIO_SELECTED ${pcm.label} sampleRate=44100",
                if (native) "external" else "capture",
                "log:joined",
                "record",
            )
            r.attempt = "attempt-2"
            r.source.packet()
            assertEquals("packet:attempt-1", r.trace.last())
        }
    }

    @Test fun j12FailedStartOrStaleGenerationOrClientCleansOnlyItsOwnSlotsAndRetainsKey() {
        listOf("false", "generation", "client", "replacement").forEach { kind ->
            val r = Rig()
            r.source.native = true
            val replacementSender = FakeSender()
            val replacementBridge = FakeBridge()
            r.source.hook = { point ->
                if (point == "external") {
                    when (kind) {
                        "false" -> r.source.started = false
                        "generation" -> r.client.generation++
                        "client" -> r.session.clientValue = FakeClient()
                        "replacement" -> {
                            r.session.sender = replacementSender
                            r.session.bridge = replacementBridge
                            r.source.started = false
                        }
                    }
                }
            }
            assertFalse(r.record())
            order(r.trace, "bridge.stop", "sender.stop")
            assertEquals(0, r.recordCount)
            assertEquals("receiver", r.active.receiverKey)
            if (kind == "replacement") {
                assertSame(replacementSender, r.session.sender)
                assertSame(replacementBridge, r.session.bridge)
                assertEquals(pcm, r.active.profile)
                assertEquals(1_000L, r.active.startedAtMs)
            } else {
                assertNull(r.session.sender)
                assertNull(r.session.bridge)
                assertNull(r.active.profile)
                assertEquals(0L, r.active.startedAtMs)
            }
        }
    }

    @Test fun j13ExceptionsRespectAssignedResourceBoundariesAndGateFinally() {
        listOf("factory", "bridgeFactory", "selected", "note", "external", "bridge.start", "capture").forEach { point ->
            val r = Rig()
            r.source.native = point != "capture"
            val boom: (String) -> Unit = { if (it == point) throw IllegalStateException(point) }
            r.source.hook = boom
            r.hook = boom
            assertFalse(r.start())
            val error = "log:AUDIO: 通道启动异常 - IllegalStateException: $point"
            assertTrue(r.trace.contains(error))
            if (r.source.bridges.isNotEmpty()) {
                order(r.trace, error, "bridge.stop", "sender.stop")
            } else if (r.source.senders.isNotEmpty()) {
                order(r.trace, error, "sender.stop")
            } else {
                assertFalse(r.trace.contains("sender.stop"))
            }
            assertNull(r.session.sender)
            assertNull(r.session.bridge)
            r.source.hook = {}
            r.hook = {}
            assertTrue(r.start())
        }
        val fatal = Rig()
        fatal.source.hook = { if (it == "capture") throw AssertionError("fatal") }
        try {
            fatal.start()
            fail("Throwable must propagate")
        } catch (e: AssertionError) {
            assertEquals("fatal", e.message)
        }
        fatal.source.hook = {}
        fatal.session.sender = null
        assertTrue(fatal.start())
    }

    @Test fun j14PublishRecheckPreservesEarlyReturnWithoutStoppingUnpublishedObjects() {
        listOf(false, true).forEach { replace ->
            val r = Rig()
            r.source.native = true
            r.source.hook = { point ->
                if (point == "bridgeFactory") {
                    if (replace) {
                        r.session.clientValue = FakeClient()
                    } else {
                        r.client.generation++
                    }
                }
            }
            assertFalse(r.record())
            assertEquals(1, r.source.senders.size)
            assertEquals(1, r.source.bridges.size)
            assertNull(r.session.sender)
            assertNull(r.session.bridge)
            assertFalse(r.trace.any { it.endsWith(".stop") || it.startsWith("selected:") || it.startsWith("note:") || it == "external" })
            assertEquals(0, r.recordCount)
        }
    }

    @Test fun j15InitialSkipOnlyAndRecordExceptionsPropagateAfterGateRelease() {
        listOf(false, true).forEach { skip ->
            val r = Rig()
            assertTrue(r.record(skip))
            assertEquals(if (skip) 0 else 1, r.recordCount)
            assertEquals(if (skip) "AUDIO: 初始音频流已加入——沿用已激活会话的 RECORD" else "joined", r.logs.last())
        }
        val r = Rig()
        r.client.recordHook = { throw IllegalArgumentException("record") }
        try {
            r.record()
            fail("record must propagate")
        } catch (e: IllegalArgumentException) {
            assertEquals("record", e.message)
        }
        assertFalse(r.logs.any { it.startsWith("AUDIO: 通道启动异常") })
        r.session.sender = null
        r.client.recordHook = { true }
        assertTrue(r.record())
        val dynamic = Rig()
        dynamic.starter.maybeStartAudioNow(dynamic.session.contextValue!!)
        assertEquals(1, dynamic.recordCount)
        val retry = Rig()
        retry.starter.retryAudioAfterClaimReturned(retry.client)
        assertEquals(1, retry.recordCount)
    }

    @Test fun j16DynamicBranchesReadLiveStateWithoutEnablingAudio() {
        val absent = Rig()
        absent.session.clientValue = null
        absent.starter.maybeStartAudioNow(Any())
        assertEquals(listOf("MODE: 已启用声音（当前无会话，下次连接时接入）"), absent.logs)
        val stopping = Rig()
        stopping.client.stopping = true
        stopping.starter.maybeStartAudioNow(Any())
        assertEquals(listOf("AUDIO: 会话正在结束——跳过动态开启声音"), stopping.logs)
        val secret = Rig()
        secret.session.secretValue = null
        secret.starter.maybeStartAudioNow(Any())
        assertEquals(listOf("MODE: 明文会话（未配对）无共享密钥——不补启音频"), secret.logs)
        val running = Rig()
        running.session.sender = FakeSender().apply { this.running = true }
        running.starter.maybeStartAudioNow(Any())
        assertTrue(running.logs.isEmpty())
        val disabled = Rig()
        disabled.session.enabledValue = false
        disabled.starter.maybeStartAudioNow(Any())
        assertFalse(disabled.session.enabledValue)
        assertEquals(0, disabled.setupCount)
    }

    @Test fun j17RetryReadinessUsesShortControllerLockAndCapturedCredentials() {
        listOf<(Rig) -> Unit>(
            { it.session.clientValue = FakeClient() },
            { it.client.stopping = true },
            { it.session.enabledValue = false },
            { it.session.sender = FakeSender().apply { running = true } },
            { it.session.contextValue = null },
            {
                it.session.secretValue =
                    null
            },
        ).forEach { configure ->
            val r = Rig()
            configure(r)
            r.trace.clear()
            r.starter.retryAudioAfterClaimReturned(r.client)
            assertEquals(0, r.setupCount)
            assertEquals("lock", r.trace.first())
            assertEquals("unlock", r.trace.last())
        }
        val r = Rig()
        val originalSecret = r.session.secretValue
        val originalContext = r.session.contextValue
        r.client.setupHook = {
            assertFalse(Thread.holdsLock(r.session.monitor))
            r.session.hostValue = "new"
            r.session.secretValue = byteArrayOf(99)
            r.session.contextValue = Any()
            AudioSetup(1, 2, 3, byteArrayOf(4), 48_000)
        }
        r.starter.retryAudioAfterClaimReturned(r.client)
        order(r.trace, "lock", "client", "stopping", "enabled", "sender", "context", "secret", "host", "unlock", "setup:${pcm.key}")
        assertSame(originalSecret, r.setupSecrets.single())
        assertSame(originalContext, r.source.context)
        assertEquals("host", r.source.spec!!.host)
    }

    @Test fun j18CandidateKeysReadsBothPolaritiesAndBaselineSeparatelyFromCurrentClient() {
        val r = Rig()
        var calls = 0
        r.client.candidateHook = { _, _ ->
            if (++calls == 1) {
                r.session.preferValue = true
                r.session.baselineValue = false
            }
        }
        assertEquals(setOf(pcm.key, aac.key, compat.key), r.starter.audioCandidateKeys("unused"))
        order(
            r.trace,
            "client",
            "prefer:false",
            "baseline:true",
            "candidates:false:true",
            "prefer:true",
            "baseline:false",
            "candidates:false:false",
        )
        val other = FakeClient().apply { candidates = listOf(compat) }
        r.session.clientValue = other
        assertEquals(setOf(compat.key), r.starter.audioCandidateKeys("receiver"))
        other.candidateHook = { _, _ -> throw IllegalStateException() }
        assertTrue(r.starter.audioCandidateKeys("receiver").isEmpty())
        r.session.clientValue = null
        assertTrue(r.starter.audioCandidateKeys("receiver").isEmpty())
    }

    @Test fun j25ThreeIndependentSlotsPreserveReadWriteAndClearOrder() {
        val r = Rig()
        assertTrue(r.trace.isEmpty())
        assertTrue(r.start())
        r.trace.clear()
        assertEquals(AudioTeardownState(pcm, 1_000, "receiver"), r.active.snapshot())
        assertEquals(listOf("read.profile", "read.time", "read.key"), r.trace)
        r.trace.clear()
        r.active.clear()
        assertEquals(listOf("profile=null", "time=0"), r.trace)
        assertEquals("receiver", r.active.receiverKey)
        val state = AudioChannelState()
        assertNull(state.profile)
        assertEquals(0L, state.startedAtMs)
        assertEquals("", state.receiverKey)
        listOf("profile", "receiverKey", "startedAtMs").forEach {
            assertTrue(
                java.lang.reflect.Modifier
                    .isVolatile(AudioChannelState::class.java.getDeclaredField(it).modifiers),
            )
        }
    }

    @Test fun j26CancellationAtClaimSetupAndRecordUsesOnlyBaselineCheckpoints() {
        val claim = Rig()
        claim.client.claimHook = { claim.client.generation++ }
        assertFalse(claim.start())
        assertEquals(0, claim.setupCount)
        assertTrue(claim.memory.rejected.isEmpty())
        val failed = Rig()
        failed.client.setupHook = {
            failed.client.generation++
            null
        }
        assertFalse(failed.start())
        assertEquals(1, failed.setupCount)
        assertEquals(listOf(Triple(pcm.key, setOf(pcm.key, aac.key, compat.key), false)), failed.memory.rejected)
        order(failed.trace, "setup:${pcm.key}", "reject:${pcm.key}", "current:7")
        val record = Rig()
        record.source.hook = { if (it == "capture") record.clock.time++ }
        record.client.recordHook = {
            record.client.generation++
            false
        }
        assertTrue(record.record())
        assertEquals(1, record.recordCount)
    }

    @Test
    fun j26LatchCancellationAtEveryIoWindowKeepsBaselineChecksAndRecordOutsideGate() {
        listOf("claim", "setup", "factory", "capture", "record").forEach { stage ->
            val r = Rig()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)

            fun pause() {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            r.client.claimHook = { if (stage == "claim") pause() }
            r.client.setupHook = {
                if (stage == "setup") pause()
                AudioSetup(1, 2, 3, byteArrayOf(4), 48_000)
            }
            r.source.hook = { if (it == stage) pause() }
            r.client.recordHook = {
                pause()
                false
            }
            val pool = Executors.newSingleThreadExecutor()
            try {
                val result = pool.submit<Boolean> { r.record() }
                assertTrue("pause $stage", entered.await(5, TimeUnit.SECONDS))
                synchronized(r.session.monitor) { r.client.generation++ }
                release.countDown()
                assertEquals(stage == "record", result.get(5, TimeUnit.SECONDS))
                assertEquals(if (stage == "claim") 0 else 1, r.setupCount)
                assertEquals(if (stage == "record") 1 else 0, r.recordCount)
                when (stage) {
                    "setup", "factory" -> {
                        assertEquals(1, r.source.senders.size)
                        assertNull(r.session.sender)
                        assertFalse(r.trace.contains("sender.stop"))
                    }
                    "capture" -> {
                        assertTrue(r.trace.contains("sender.stop"))
                        assertNull(r.active.profile)
                        assertEquals("receiver", r.active.receiverKey)
                    }
                    "record" -> assertTrue(r.session.sender!!.running)
                }
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
        val afterStart = Rig()
        afterStart.hook = { if (it == "log:joined") afterStart.client.generation++ }
        assertTrue(afterStart.record())
        assertEquals(1, afterStart.recordCount)
    }

    @Test
    fun j13ExceptionCleanupDoesNotClearNewlyReplacedSenderBridgeOrActiveSlots() {
        val r = Rig()
        r.source.native = true
        val nextSender = FakeSender()
        val nextBridge = FakeBridge()
        r.source.hook = {
            if (it == "external") {
                r.session.sender = nextSender
                r.session.bridge = nextBridge
                r.active.profile = aac
                r.active.startedAtMs = 99_000
                throw IllegalStateException("replaced")
            }
        }
        assertFalse(r.start())
        assertSame(nextSender, r.session.sender)
        assertSame(nextBridge, r.session.bridge)
        assertEquals(aac, r.active.profile)
        assertEquals(99_000L, r.active.startedAtMs)
        order(r.trace, "log:AUDIO: 通道启动异常 - IllegalStateException: replaced", "bridge.stop", "sender.stop")
    }

    private inner class AttributionRig(
        val r: Rig,
    ) {
        var watchdog: Mib3LinkWatchdog? = null
        var candidateKeys: Set<String> = setOf(pcm.key, aac.key, compat.key)
        var contextReads = 0
        var stateReads = 0
        var watchdogReads = 0
        val memory = DefaultAudioTeardownMemory()
        val teardown =
            AudioTeardown(
                probeMillis = 20_000,
                memory =
                    object : AudioTeardownMemory {
                        override fun recordWorking(
                            context: Any?,
                            receiverKey: String,
                            profileKey: String,
                        ) {
                            r.trace += "memory.working:$receiverKey:$profileKey"
                            memory.recordWorking(context, receiverKey, profileKey)
                        }

                        override fun recordRejected(
                            context: Any?,
                            receiverKey: String,
                            profileKey: String,
                            allCandidates: Set<String>,
                        ): AudioProfileMemory.Entry {
                            r.trace += "memory.rejected:$receiverKey:$profileKey"
                            AudioProfileMemory.recordRejected(null, receiverKey, profileKey, allCandidates)
                            r.trace += "memory.load:$receiverKey"
                            return memory.load(context, receiverKey)
                        }

                        override fun load(
                            context: Any?,
                            receiverKey: String,
                        ) = memory.load(context, receiverKey)
                    },
                state = {
                    stateReads++
                    r.active.snapshot()
                },
                clearState = { r.active.clear() },
                watchdog = {
                    watchdogReads++
                    r.trace += "watchdog"
                    watchdog
                },
                nowMs = r.clock::now,
                context = {
                    contextReads++
                    r.trace += "teardown.context"
                    null
                },
                allCandidateKeys = {
                    r.trace += "keys:$it"
                    candidateKeys
                },
                output =
                    object : AudioTeardownOutput {
                        override fun linkTeardown(livedMillis: Long) {
                            r.trace += "link:$livedMillis"
                        }

                        override fun working(
                            profile: AudioProfile,
                            livedMillis: Long,
                        ) {
                            r.trace += "working:$livedMillis"
                        }

                        override fun rejected(
                            profile: AudioProfile,
                            livedMillis: Long,
                            allDisabled: Boolean,
                        ) {
                            r.trace +=
                                "rejected:$livedMillis:$allDisabled"
                        }

                        override fun hint(allDisabled: Boolean) {
                            r.trace += "hint:$allDisabled"
                        }
                    },
            )
    }

    @Test fun j19AbsentProfileZeroTimeAndBlankIdentityKeepBaselineReadOrder() {
        val absent = Rig()
        val a = AttributionRig(absent)
        a.teardown.attribute()
        assertEquals(listOf("read.profile", "read.time", "read.key"), absent.trace)
        assertEquals(0, a.watchdogReads)
        assertEquals(0, a.contextReads)
        val zero = Rig()
        zero.active.profile = pcm
        zero.active.receiverKey = "receiver"
        zero.trace.clear()
        val z = AttributionRig(zero)
        z.teardown.attribute()
        assertEquals(listOf("read.profile", "read.time", "read.key", "watchdog", "profile=null", "time=0"), zero.trace)
        assertEquals(0, z.contextReads)
        val blank = Rig()
        blank.active.profile = pcm
        blank.active.startedAtMs = 1_000
        blank.trace.clear()
        val b = AttributionRig(blank)
        b.teardown.attribute()
        assertEquals(listOf("read.profile", "read.time", "read.key", "clock:1000", "watchdog", "profile=null", "time=0"), blank.trace)
        assertEquals(0, b.contextReads)
    }

    @Test fun j20AndI03StarterPublicationFeedsRealTeardownAndMemoryAtProbeBoundary() {
        listOf(19_999L, 20_000L, 20_001L).forEach { lived ->
            val r = Rig()
            r.client.identity = "phase2d-boundary-$lived"
            AudioProfileMemory.reset(null, r.client.identity)
            try {
                assertTrue(r.start())
                val a = AttributionRig(r)
                r.clock.time += lived
                r.trace.clear()
                // 只有显式 attribute 才归因；时间推进本身不写记忆。
                assertTrue(AudioProfileMemory.load(null, r.client.identity).isEmpty)
                a.teardown.attribute()
                val entry = AudioProfileMemory.load(null, r.client.identity)
                order(r.trace, "clock:${1_000 + lived}", "watchdog", "profile=null", "time=0", "teardown.context")
                if (lived < 20_000) {
                    assertEquals(setOf(pcm.key), entry.rejected)
                    assertNull(entry.working)
                    assertFalse(entry.disabled)
                    order(
                        r.trace,
                        "time=0",
                        "keys:${r.client.identity}",
                        "memory.rejected:${r.client.identity}:${pcm.key}",
                        "memory.load:${r.client.identity}",
                        "rejected:$lived:false",
                        "hint:false",
                    )
                } else {
                    assertEquals(pcm.key, entry.working)
                    assertTrue(entry.rejected.isEmpty())
                    order(r.trace, "time=0", "memory.working:${r.client.identity}:${pcm.key}", "working:$lived")
                    assertFalse(r.trace.any { it.startsWith("hint:") })
                }
                assertEquals(1, a.stateReads)
                assertNull(r.active.profile)
                assertEquals(r.client.identity, r.active.receiverKey)
            } finally {
                AudioProfileMemory.reset(null, r.client.identity)
            }
        }
    }

    @Test fun j21AllWatchdogCombinationsUseOnlyArmedAndNotDoneEvenAfterTwentySeconds() {
        listOf(false, true).forEach { armed ->
            listOf(false, true).forEach { done ->
                val r = Rig()
                r.client.identity = "phase2d-watchdog-$armed-$done"
                AudioProfileMemory.reset(null, r.client.identity)
                try {
                    assertTrue(r.start())
                    val a = AttributionRig(r)
                    a.watchdog = Mib3LinkWatchdog(armed, done)
                    r.clock.time += 30_000
                    r.trace.clear()
                    a.teardown.attribute()
                    if (armed && !done) {
                        order(r.trace, "watchdog", "link:30000", "profile=null", "time=0")
                        assertTrue(AudioProfileMemory.load(null, r.client.identity).isEmpty)
                        assertEquals(0, a.contextReads)
                    } else {
                        assertEquals(pcm.key, AudioProfileMemory.load(null, r.client.identity).working)
                    }
                } finally {
                    AudioProfileMemory.reset(null, r.client.identity)
                }
            }
        }
    }

    @Test fun j22AttributionCallbacksAndSlotsAreReadAtEachCallAfterConstruction() {
        val r = Rig()
        val a = AttributionRig(r)
        assertTrue(r.trace.isEmpty())
        a.watchdog = Mib3LinkWatchdog(true, false)
        assertTrue(r.start())
        a.teardown.attribute()
        assertEquals(1, a.watchdogReads)
        assertNull(r.active.profile)
        a.watchdog = Mib3LinkWatchdog(false, true)
        val identity = "phase2d-live-state"
        AudioProfileMemory.reset(null, identity)
        try {
            r.active.profile = aac
            r.active.receiverKey = identity
            r.active.startedAtMs = 1_000
            a.candidateKeys = emptySet()
            r.clock.time = 2_000
            r.trace.clear()
            a.teardown.attribute()
            assertEquals(2, a.watchdogReads)
            assertEquals(1, a.contextReads)
            assertEquals(setOf(aac.key), AudioProfileMemory.load(null, identity).rejected)
            assertFalse(AudioProfileMemory.load(null, identity).disabled)
            val before = r.trace.size
            a.teardown.attribute()
            assertEquals(listOf("read.profile", "read.time", "read.key"), r.trace.drop(before))
            assertEquals(3, a.stateReads)
            assertEquals(2, a.watchdogReads)
        } finally {
            AudioProfileMemory.reset(null, identity)
        }
    }

    @Test fun j23RealMemoryProtectsWorkingAndOnlyDisablesNonemptyExhaustedSets() {
        listOf("working", "exhausted", "empty").forEach { kind ->
            val r = Rig()
            val identity = "phase2d-memory-$kind"
            r.client.identity = identity
            AudioProfileMemory.reset(null, identity)
            try {
                assertTrue(r.start())
                val a = AttributionRig(r)
                if (kind == "working") AudioProfileMemory.recordWorking(null, identity, pcm.key)
                a.candidateKeys = if (kind == "empty") emptySet() else setOf(pcm.key)
                r.clock.time++
                a.teardown.attribute()
                val after = AudioProfileMemory.load(null, identity)
                assertEquals(kind == "exhausted", after.disabled)
                assertEquals(if (kind == "working") pcm.key else null, after.working)
                assertEquals(kind != "working", pcm.key in after.rejected)
                assertEquals("hint:${after.disabled}", r.trace.last())
            } finally {
                AudioProfileMemory.reset(null, identity)
            }
        }
    }

    @Test fun i01RealRouterAndArbiterConsumeOnePendingRetryThenUseSameStarterGate() {
        listOf("changeModes", "requestUI").forEach { firstCommand ->
            val r = Rig()
            val modes = ModeArbiter()

            fun resources(
                type: Int,
                constraint: Long,
            ) = mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to ModeArbiter.MAIN_AUDIO,
                            "transferType" to type.toLong(),
                            "transferPriority" to 500L,
                            "takeConstraint" to constraint,
                            "borrowConstraint" to constraint,
                            "unborrowConstraint" to constraint,
                        ),
                    ),
            )
            modes.onChangeModes(resources(ModeArbiter.TRANSFER_TAKE, 1_000))
            assertFalse(modes.onAudioStreamStarting().claimed)
            assertTrue(modes.audioClaimAwaitingReturn)
            r.client.claimHook = {
                val claim = modes.onAudioStreamStarting()
                r.client.claim = AudioClaim(claim.note, claim.claimed, claim.claimed)
                r.client.owns = modes.phoneOwnsAudio
            }
            val events = mutableListOf<String>()
            val tasks = mutableListOf<() -> Unit>()
            val router =
                EventCommandRouter(
                    scope =
                        object : AttemptScope {
                            override val id = "attempt"
                            override val observation = null
                            override val isCurrent = true
                            override val isActive = true
                        },
                    setup =
                        AirPlayClient.SetupInfo(
                            eventPort = 1,
                            timingPort = 2,
                            keepAlivePort = null,
                            enabledFeatures = emptyList(),
                            displayWidth = 100,
                            displayHeight = 100,
                            displayUuid = "display",
                            maxFps = 60,
                            hidDeviceUuids = emptyList(),
                            hidDescriptors = emptyMap(),
                        ),
                    isStaleClient = { r.session.clientValue !== r.client },
                    client =
                        object : EventCommandRouter.EventClient {
                            override val modes = modes

                            override fun onIapEventBytes(data: ByteArray) = Unit
                        },
                    counters =
                        object : EventCommandRouter.EventCounters {
                            override var touchLogCount = 0
                            override var consumerReleaseCount = 0
                            override var iapEventMessageCount = 0
                            override var forceKeyFrameCount = 0
                            override var requestUiCount = 0
                        },
                    nowUptimeMillis = { 1_000 },
                    output =
                        object : EventCommandRouter.EventOutput {
                            override fun appendLog(line: String) {
                                events += "log:$line"
                                assertFalse(modes.audioClaimAwaitingReturn)
                            }

                            override fun routeCarMediaKey(
                                keyCode: Int,
                                label: String,
                            ) = Unit

                            override fun injectNavigationKey(keyCode: Int) = Unit

                            override fun enqueueModesAssert(
                                reason: String,
                                requestKeyFrame: Boolean,
                                retakeScreen: Boolean,
                                rebuildStream: Boolean,
                            ) {
                                events += "enqueue"
                                if (tasks.isEmpty()) assertTrue(modes.audioClaimAwaitingReturn)
                            }

                            override fun runBackground(
                                name: String,
                                task: () -> Unit,
                            ) {
                                events += "background:$name"
                                tasks += task
                            }

                            override fun retryAudioAfterClaimReturned() {
                                r.starter.retryAudioAfterClaimReturned(r.client)
                            }

                            override fun onTouchSent() = Unit
                        },
                    currentVideoSender = { null },
                    currentTouchTarget = { null },
                    audioEnabled = { true },
                    audioStreamRunning = { r.session.sender?.running == true },
                    resolveScreenReturnRebuild = { it },
                )
            if (firstCommand == "requestUI") modes.onChangeModes(resources(ModeArbiter.TRANSFER_UNTAKE, 100))
            val returning = mapOf("type" to "changeModes", "params" to resources(ModeArbiter.TRANSFER_UNTAKE, 100))
            router.onCommand(if (firstCommand == "changeModes") returning else mapOf("type" to "requestUI"))
            router.onCommand(if (firstCommand == "changeModes") mapOf("type" to "requestUI") else returning)
            assertEquals(1, tasks.size)
            assertEquals("enqueue", events.first())
            assertTrue(events[1].startsWith("log:AUDIO:"))
            assertEquals("background:${if (firstCommand == "changeModes") "audio-claim-retry" else "audio-request-ui-retry"}", events[2])
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            r.client.setupHook =
                {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    AudioSetup(1, 2, 3, byteArrayOf(4), 48_000)
                }
            try {
                val retry = pool.submit { tasks.single()() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(r.start())
                assertEquals(1, r.setupCount)
                release.countDown()
                retry.get(5, TimeUnit.SECONDS)
                assertEquals(1, r.recordCount)
                r.session.clientValue = FakeClient()
                val previous = events.size
                router.onCommand(returning)
                assertEquals(previous, events.size)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }
}
