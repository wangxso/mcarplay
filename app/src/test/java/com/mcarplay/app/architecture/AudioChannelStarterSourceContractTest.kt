package com.mcarplay.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S01–S03：指定真实文件/函数，缺失即失败；变异仅在内存中执行。 */
class AudioChannelStarterSourceContractTest {
    private val controller get() = SourceTree.mainKotlinFiles.getValue("SessionController.kt").readText()
    private val starter get() = SourceTree.mainKotlinFiles.getValue("audio/AudioChannelStarter.kt").readText()
    private val state get() = SourceTree.mainKotlinFiles.getValue("audio/AudioChannelState.kt").readText()

    private fun norm(s: String) =
        s
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("\\s*([(),])\\s*"), "$1")

    private fun code(s: String) = norm(KotlinSource.scan(s).code)

    private fun body(
        source: String,
        name: String,
    ): String {
        val scan = KotlinSource.scan(source).code
        val at = scan.indexOf("fun $name(")
        check(at >= 0) { "missing function $name" }
        if (name == "createAudioChannelStarter") {
            val call = scan.indexOf("AudioChannelStarter(", scan.indexOf('=', at))
            check(call >= at)
            var end = scan.indexOf('(', call)
            var parens = 0
            do {
                when (scan[end++]) {
                    '(' -> parens++
                    ')' -> parens--
                }
            } while (parens > 0 && end < scan.length)
            check(parens == 0)
            return norm(scan.substring(call, end))
        }
        var cursor = scan.indexOf('(', at)
        var depth = 0
        do {
            when (scan[cursor++]) {
                '(' -> depth++
                ')' -> depth--
            }
        } while (depth > 0 && cursor < scan.length)
        check(depth == 0)
        val open = scan.indexOf('{', cursor)
        val equals = scan.indexOf('=', cursor)
        if (equals >= cursor && (open < 0 || equals < open)) {
            val end = scan.indexOf("\n\n", equals).takeIf { it >= 0 } ?: scan.length
            return norm(scan.substring(equals, end))
        }
        check(open >= 0) { "missing body $name" }
        cursor = open
        depth = 0
        do {
            when (scan[cursor++]) {
                '{' -> depth++
                '}' -> depth--
            }
        } while (depth > 0 && cursor < scan.length)
        check(depth == 0)
        return norm(scan.substring(open, cursor))
    }

    private fun contains(
        source: String,
        text: String,
    ) {
        assertTrue(
            "missing $text in $source",
            norm(source).contains(norm(text)),
        )
    }

    private fun ordered(
        source: String,
        vararg parts: String,
    ) {
        var after = 0
        parts.forEach { part ->
            val at = norm(source).indexOf(norm(part), after)
            assertTrue(
                "missing ordered $part after $after in $source",
                at >= after,
            )
            after = at + norm(part).length
        }
    }

    private fun contract(
        sc: String,
        helper: String,
        slots: String,
    ) {
        val wiring = body(sc, "createAudioChannelStarter")
        contains(sc, "private val audioChannelState = AudioChannelState()")
        contains(sc, "private val audioChannelStarter = createAudioChannelStarter()")
        assertEquals(1, Regex("AudioChannelState\\(").findAll(KotlinSource.scan(sc).code).count())
        assertEquals(1, Regex("AudioChannelStarter\\(").findAll(wiring).count())
        contains(wiring, "active = audioChannelState")
        listOf(
            "override val currentClient get() = this@SessionController.currentClient",
            "override val context get() = appContext",
            "override val secret get() = currentSharedSecret",
            "override val host get() = currentAudioHost",
            "override val enabled get() = audioEnabledForSession",
            "override val preferCompat get() = audioPreferCompat",
            "get() = audioSender",
            "get() = nativeAudioBridge",
            "set(value) { audioSender = value }",
            "set(value) { nativeAudioBridge = value }",
            "synchronized(this@SessionController) { block() }",
            "client.isConnectionCurrent(generation)",
            "client.connectionGeneration",
            "client.sessionStopping",
            "client.receiverIdentity()",
            "client.audioCandidates(preferCompat = preferCompat, allowBaseline = allowBaseline)",
            "client.claimMainAudioForStreaming(generation)",
            "AudioClaim(claim.note, claim.claimed, claim.announced)",
            "client.modes.phoneOwnsAudio",
            "client.runAudioStream(secret, profile, tuning, expectedEpoch = expectedEpoch)",
            "AudioSetup(it.streamConnectionId, it.dataPort, it.controlPort, it.streamKey, it.sampleRate)",
            "client.record()",
            "CarPlayService.mediaProjection",
            "CarLifeLauncher.isAvailable(context)",
            "context.applicationContext",
            "CarLifeAudioBridge(sender, sampleRate, AudioDuckingPrefs.config(context), log)",
            "sender.start(projection)",
            "sender.startExternalSource()",
            "bridge.start()",
            "sender.stop()",
            "bridge.stop()",
            "ReceiverDetectionRuntime.collecting(client.currentEndpoint.observation)",
            "ReceiverDetectionRuntime.preferredAudio(client.currentEndpoint.observation, client.capabilityDigest)",
            "ReceiverDetectionRuntime.audioSelected(client.currentEndpoint.observation, key)",
            "ConnectionDiagnostics.timeline.snapshot()?.id",
            "ConnectionDiagnostics.timeline.note(attempt, line)",
            "ConnectionDiagnostics.timeline.audioPacket(attempt)",
            "nowMs = { SystemClock.uptimeMillis() }",
            "log = ::appendLog",
            "AudioProfileMemory.load(context, identity)",
            "AudioProfileMemory.reset(context, identity)",
            "AudioProfileMemory.recordRejected(context, identity, key, allCandidates, markExhausted)",
        ).forEach { contains(wiring, it) }
        listOf(
            "host",
            "dataPort",
            "streamKey",
            "sampleRate",
            "codec",
            "streamType",
            "adtsFrames",
            "controlPort",
        ).forEach {
            contains(wiring, "$it = spec.$it")
        }
        contains(
            wiring,
            "context = context",
        )
        contains(
            wiring,
            "log = log",
        )
        contains(
            wiring,
            "onPacketSent = onPacketSent",
        )
        val helperCode = code(helper)
        assertEquals(1, Regex("AudioStartGate\\(\\)").findAll(helperCode).count())
        contains(helperCode, "private val audioStartGate = AudioStartGate()")
        val start = body(helper, "startAudioChannel")
        ordered(
            start,
            "audioStartGate.tryAcquire()",
            "return try",
            "startAudioChannelLocked(context, client, sharedSecret, host)",
            "finally",
            "audioStartGate.release()",
        )
        val record = body(helper, "startAudioAndRecord")
        ordered(
            record,
            "startAudioChannel(context, client, secret, host)",
            "if (started)",
            "if (recordAfterSetup)",
            "log(logLine)",
            "clients.record(client)",
            "return started",
        )
        assertFalse(record.contains("try"))
        assertFalse(record.contains("audioStartGate"))
        assertFalse(record.contains("isConnectionCurrent"))
        val locked = body(helper, "startAudioChannelLocked")
        ordered(
            locked,
            "session.sender",
            "clients.generation(client)",
            "clients.isConnectionCurrent(client, connectionGeneration)",
            "diagnostics.currentAttemptId()",
            "!session.enabled",
            "sharedSecret == null",
            "host == null",
            "source.projection()",
            "source.nativeAvailable(context)",
        )
        ordered(
            locked,
            "memory.load(",
            "clients.candidates(",
            "AudioProfileMemory.order(candidates, entry)",
            "detection.preferredAudio(client)",
            "ReceiverProbePlan.audio(baseline, preferred)",
            "clients.claim(client, connectionGeneration)",
        )
        contains(locked, "markExhausted = false")
        ordered(
            locked,
            "source.createSender(",
            "startingSender = sender",
            "source.createBridge(",
            "startingBridge = bridge",
            "val published = session.withControllerLock",
            "session.currentClient !== client",
            "session.sender = sender",
            "session.bridge = bridge",
            "active.profile = profile",
            "active.receiverKey = identity",
            "active.startedAtMs = nowMs()",
            "if (!published) return false",
            "detection.audioSelected(client, profile.key)",
            "diagnostics.note(",
            "source.startExternal(sender)",
        )
        contains(locked, "source.startExternal(sender).also { if (it) source.startBridge(bridge) }")
        contains(locked, "projection != null && source.startCapture(sender, projection)")
        contains(locked, "diagnosticAttempt?.let { diagnostics.audioPacket(it) }")
        contains(locked, "if (session.sender === sender)")
        contains(locked, "if (session.bridge === bridge && bridge != null)")
        contains(locked, "if (startingSender != null && session.sender === startingSender)")
        contains(locked, "if (startingBridge != null && session.bridge === startingBridge)")
        contains(locked, "catch (error: Exception)")
        assertFalse(locked.contains("Throwable"))
        ordered(
            locked.substringAfter("catch (error: Exception)"),
            "log(",
            "startingBridge?.let(source::stopBridge)",
            "startingSender?.let(source::stopSender)",
            "session.withControllerLock",
            "active.clear()",
        )
        val retry = body(helper, "retryAudioAfterClaimReturned")
        ordered(
            retry,
            "session.withControllerLock",
            "client !== session.currentClient",
            "clients.isStopping(client)",
            "!session.enabled",
            "session.sender",
            "session.context",
            "session.secret",
            "Triple(context, secret, session.host)",
            "} ?: return",
            "startAudioAndRecord(ready.first, client, ready.second, ready.third,",
        )
        val keys =
            code(
                helper.substring(
                    helper.indexOf("fun audioCandidateKeys("),
                    helper.indexOf("internal class AudioStartGate"),
                ),
            )
        ordered(
            keys,
            "runCatching",
            "session.currentClient",
            "preferCompat = session.preferCompat",
            "session.baselineAllowed()",
            "preferCompat = !session.preferCompat",
            "session.baselineAllowed()",
            "getOrDefault(emptySet())",
        )
        assertEquals(keys, 2, Regex("clients\\s*\\.\\s*candidates\\(").findAll(keys).count())
        // 构造体仅保存端口及创建 CAS gate；不读取会话、来源、时间或 active。
        val constructor = helperCode.substringBefore("fun maybeStartAudioNow(")
        assertFalse(Regex("\\b(session|source|memory|detection|diagnostics|active)\\.").containsMatchIn(constructor))
        assertFalse(constructor.contains("nowMs()"))
        assertFalse(constructor.contains("init {"))
        val words = KotlinSource.identifierWords(KotlinSource.scan(helper).code)
        assertTrue(
            (
                words intersect
                    setOf(
                        "mib3",
                        "passat",
                        "audi",
                        "chery",
                        "arrizo",
                        "honda",
                        "cadillac",
                        "diplay",
                        "winplay",
                        "macplay",
                        "id7",
                        "vw",
                        "cinemo",
                    )
            ).isEmpty(),
        )
        assertFalse(
            Regex(
                "com\\.mcarplay\\.app\\.(SessionController|MainActivity|SettingsContentActivity|SenderApp|" +
                    "session|service|ui|settings|update|legal)\\b",
            ).containsMatchIn(helperCode),
        )
        assertEquals(3, Regex("@Volatile").findAll(code(slots)).count())
        contains(code(slots), "override var profile: AudioProfile? = null")
        contains(code(slots), "override var receiverKey: String = \"\"")
        contains(code(slots), "override var startedAtMs: Long = 0L")
        assertFalse(code(slots).contains("synchronized"))
        assertFalse(code(slots).contains("Atomic"))
        assertFalse(code(slots).contains("init {"))
        val clear = body(helper, "clear")
        assertEquals("{ profile = null startedAtMs = 0L }", clear)
        contains(
            helperCode,
            "fun snapshot(): AudioTeardownState = AudioTeardownState(profile, startedAtMs, receiverKey)",
        )
        val teardown = code(sc.substringAfter("private val audioTeardown =").substringBefore("@Volatile"))
        ordered(
            teardown,
            "state = {",
            "profile = audioChannelState.profile",
            "startedAtMs = audioChannelState.startedAtMs",
            "receiverKey = audioChannelState.receiverKey",
            "clearState = { clearActiveAudioState() }",
            "watchdog = {",
            "currentClient?.let { client ->",
            "Mib3LinkWatchdog(",
            "armed = client.mib3LinkArmed",
            "controlSessionDone = client.mib3ControlSessionDone",
        )
        contains(teardown, "watchdog = { currentClient?.let { client ->")
        contains(body(sc, "clearActiveAudioState"), "audioChannelState.clear()")
    }

    @Test
    fun s01ProductionPortsAndSlotsPreserveLeafIdentityLockAndOrder() {
        contract(controller, starter, state)
    }

    @Test
    fun s02AllFacadesAndInitialPolicyRemainAtOriginalEntrypoints() {
        contains(controller, "@Synchronized\n    fun maybeStartAudioNow(context: Context)")
        contains(body(controller, "maybeStartAudioNow"), "audioChannelStarter.maybeStartAudioNow(context)")
        contains(
            body(
                controller,
                "startAudioChannel",
            ),
            "= audioChannelStarter.startAudioChannel(context, client, sharedSecret, host)",
        )
        contains(
            body(
                controller,
                "startAudioAndRecord",
            ),
            "= audioChannelStarter.startAudioAndRecord(context, client, secret, host, " +
                "logLine, recordAfterSetup)",
        )
        contains(
            body(
                controller,
                "retryAudioAfterClaimReturned",
            ),
            "audioChannelStarter.retryAudioAfterClaimReturned(client)",
        )
        contains(
            body(
                controller,
                "audioCandidateKeys",
            ),
            "= audioChannelStarter.audioCandidateKeys(identity)",
        )
        contains(
            code(controller),
            """startAudioAndRecord( context, client, sessionKey, host, "", recordAfterSetup = false, )""",
        )
        contains(
            code(controller),
            """if (audioEnabledForSession && !audioStarted &&
            !client.modes.audioClaimAwaitingReturn && client.modes.phoneOwnsAudio) {
            ConnectionDiagnostics.timeline.issue(diagnosticAttempt, DiagnosticCode.AUDIO_UNAVAILABLE)""",
        )
        contains(controller, "this@SessionController.retryAudioAfterClaimReturned(client)")
        contains(
            body(
                starter,
                "maybeStartAudioNow",
            ),
            "startAudioAndRecord(context, client, secret, session.host,",
        )
        val scCode = code(controller)
        assertFalse(scCode.contains("activeAudioProfile"))
        assertFalse(scCode.contains("activeAudioStartedAt"))
        assertFalse(scCode.contains("activeAudioReceiverKey"))
    }

    @Test
    fun sourceContractFailsClosedForMiswiringEagerReadsAndStateMutations() {
        contract(controller, starter, state)
        val mutations =
            listOf(
                Triple(controller, "override val currentClient get()", "override val currentClient"),
                Triple(controller, "profile = audioChannelState.profile", "profile = null"),
                Triple(controller, "profile = audioChannelState.profile", "profile = cachedState.profile"),
                Triple(controller, "currentClient?.let { client ->", "cachedClient?.let { client ->"),
                Triple(controller, "startedAtMs = audioChannelState.startedAtMs", "startedAtMs = 0L"),
                Triple(controller, "clearState = { clearActiveAudioState() }", "clearState = {}"),
                Triple(controller, "watchdog = {", "watchdog = { Mib3LinkWatchdog(false, false);"),
                Triple(starter, "session.currentClient !== client", "session.currentClient != client"),
                Triple(
                    starter,
                    "private val audioStartGate = AudioStartGate()",
                    "private val audioStartGate = AudioStartGate().also { session.currentClient }",
                ),
                Triple(
                    starter,
                    "profile = null\n        startedAtMs = 0L",
                    "profile = null\n        receiverKey = \"\"\n        startedAtMs = 0L",
                ),
                Triple(
                    starter,
                    "AudioTeardownState(profile, startedAtMs, receiverKey)",
                    "AudioTeardownState(profile, 0L, receiverKey)",
                ),
                Triple(state, "@Volatile", ""),
            )
        mutations.forEach { (source, before, after) ->
            assertTrue("mutation target absent $before", source.contains(before))
            val changed = source.replaceFirst(before, after)
            var rejected = false
            try {
                contract(
                    if (source == controller) changed else controller,
                    if (source == starter) changed else starter,
                    if (source == state) changed else state,
                )
            } catch (_: AssertionError) {
                rejected = true
            }
            assertTrue("mutation accepted $before -> $after", rejected)
        }
        // Phase 2g-2 在 createStreamPipeline() 里也注入了一处控制器锁（ScreenRecovery 闭包用）。
        // 锁身份变异必须在音频接线内定向替换，否则 replaceFirst 会命中更靠前的那一处而误判为「已拒绝」。
        val audioStart = controller.indexOf("private fun createAudioChannelStarter()")
        val lockAt = controller.indexOf("synchronized(this@SessionController)", audioStart)
        check(audioStart >= 0 && lockAt > audioStart) { "missing audio lock binding" }
        val tornAudio = "synchronized(this)"
        val mutilated =
            controller.substring(0, lockAt) +
                tornAudio +
                controller.substring(lockAt + "synchronized(this@SessionController)".length)
        var lockRejected = false
        try {
            contract(mutilated, starter, state)
        } catch (_: AssertionError) {
            lockRejected = true
        }
        assertTrue("mutation accepted audio lock identity swap -> $tornAudio", lockRejected)
        listOf(
            "import com.mcarplay.app.SessionController\n",
            "private val mib3 = true\n",
        ).forEach { injected ->
            var rejected = false
            try {
                contract(
                    controller,
                    injected + starter,
                    state,
                )
            } catch (_: AssertionError) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }

    @Test
    fun sourceContractRejectsAttributionBeforeShellCleanup() {
        val source = SourceTree.mainKotlinFiles.getValue("VideoEndedHandling.kt").readText()

        fun checkOrder(text: String) {
            ordered(
                body(text, "handle"),
                "actions.cleanupShellFallbackNetwork()",
                "actions.attributeAudioTeardown()",
                "actions.recordAutoReconnectOutcome(",
            )
        }
        checkOrder(source)
        val original = "actions.cleanupShellFallbackNetwork()\n        actions.attributeAudioTeardown()"
        val swapped = "actions.attributeAudioTeardown()\n        actions.cleanupShellFallbackNetwork()"
        assertTrue(source.contains(original))
        var rejected = false
        try {
            checkOrder(source.replace(original, swapped))
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("attribution before shell cleanup must fail", rejected)
    }

    @Test
    fun s03All26AudioTemplatesRemainExactlyOnceAtReachableProductionSites() {
        val expected =
            listOf(
                "\"AUDIO: 档位 \${profile.label} 存活 \${livedMillis / 1000}s——记为本车机可用档位\"",
                "\"AUDIO: ⚠ 档位 \${profile.label} 起流 \${audioLivedLabel(livedMillis)} 后整条会话被车机拆掉——\"",
                "\"AUDIO: 会话正在结束——跳过动态开启声音\"",
                "\"AUDIO: 动态加入音频流——检查会话激活\"",
                "\"AUDIO: 初始音频流已加入——沿用已激活会话的 RECORD\"",
                "\"AUDIO: 车机归还主音频后音频流已建立——检查会话激活\"",
                "\"AUDIO: 已有音频通道正在建立——跳过本次重复启动\"",
                "\"AUDIO: 已有音频发送器在运行——跳过重复启动\"",
                "\"AUDIO: 本次连接未开启车机侧声音（「启动连接」弹窗选择）——仅推视频\"",
                "\"AUDIO: 明文会话（未配对）无共享密钥——跳过音频通道\"",
                "\"AUDIO: 无接收端可用地址，跳过音频通道\"",
                "\"AUDIO: 缺少投屏权限（播放捕获依赖 MediaProjection）——音频通道未启动。\"",
                "\"AUDIO: 本车机（\$identity）有「全部档位拆链」的旧记忆，已自动清除并重试\"",
                "\"AUDIO: 没有可用音频档位（候选=\${candidates.map { it.label }}）——仅推视频\"",
                "\"AUDIO: 音频档位候选=\${ordered.map { it.label }}\"",
                "\"AUDIO: 主音频资源未接管（\${audioClaim.note}）——本次不发送 SETUP，等车机归还主音频后重试\"",
                "\"AUDIO: 音频流建立前主音频资源\"",
                "\"AUDIO: 主音频资源在协商期间转为车机占用——停止候选轮换，不记为格式失败\"",
                "\"AUDIO: 主音频 SETUP 被拒时资源已归车机——不记为格式失败\"",
                "\"AUDIO: 档位 \${candidate.label} 协商失败——本会话继续试下一档\"",
                "\"AUDIO: 全部候选档位协商失败（共 \${ordered.size} 档）——本次仅推视频\"",
                "\"AUDIO: 通道启动异常 - \${error.javaClass.simpleName}: \${error.message}\"",
                "\"AUDIO: 音频流已加入——检查会话激活\"",
                "\"AUDIO: 音频加入后会话被车机拆掉（链路协商未完成，\$shape）——不记音频档位\"",
                "\"AUDIO: 车机已归还主音频——后台重试建立音频流\"",
                "\"AUDIO: requestUI 重进——主音频此前未接管，后台重试建立音频流\"",
            )
        val actual =
            listOf(
                controller,
                starter,
                SourceTree.mainKotlinFiles.getValue("session/EventCommandRouter.kt").readText(),
            ).flatMap { it.lines() }
                .filter { it.contains("AUDIO:") }
                .map { it.substring(it.indexOf('"'), it.lastIndexOf('"') + 1) }
        assertEquals(26, actual.size)
        assertEquals(expected.sorted(), actual.sorted())
        listOf(
            "MODE: 已启用声音（当前无会话，下次连接时接入）",
            "MODE: 明文会话（未配对）无共享密钥——不补启音频",
            "MODE: 会话进行中，动态补启音频通道…",
            "现在点「申请投屏权限」也可即时补启（无需重连）；注意「断开连接」会停服务并连带失效投屏授权",
        ).forEach { contains(starter, it) }
    }
}
