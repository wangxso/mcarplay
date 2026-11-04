package com.mcarplay.app.bt

import com.mcarplay.app.diagnostics.DiagnosticExportPolicy

/**
 * 蓝牙建链的有界、无设备标识诊断。epoch 隔离旧轮回调；日志 sink 必须异步写盘。
 * 本地 SDP 注册成功不代表车机已经看见或接受公告。
 */
class BluetoothDiagnostics(
    private val log: (String) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class Service(
        val uuid: String,
    ) {
        IAP2("00000000-deca-fade-deca-deafdecacafe"),
        CARPLAY("2d8d2466-e14d-451c-88bc-7301abea291a"),
    }

    enum class SdpOutcome { COMPLETE, TIMEOUT, NOT_STARTED, ERROR, CANCELLED }

    enum class PeerMatch { TARGET, OTHER, UNSELECTED }

    class ConnectAttempt internal constructor(
        val epoch: Long,
        val number: Int,
        val peer: Int,
        val startedAtMs: Long,
    )

    private class State(
        val epoch: Long = 0,
        val attempt: String = "none",
        val startedAtMs: Long = 0,
        var active: Boolean = false,
    ) {
        var endedAtMs: Long? = null
        var client = false
        var iap2Registered = false
        var carPlayRegistered = false
        var iap2Listening = false
        var carPlayListening = false
        var outboundAttempts = 0
        var outboundSuccess = 0
        var outboundFailures = 0
        var outboundCancelled = 0
        var inFlight: ConnectAttempt? = null
        var incomingAccepted = 0
        var incomingRejected = 0
        var capabilityConnections = 0
        var bondEvents = 0
        var aclConnected = 0
        var aclDisconnected = 0
        var sdpQueries = 0
        var sdpCompleted = 0
        var sdpTimeouts = 0
        var sdpErrors = 0
        var lastFailure = "none"
        var lastFailurePhase = "none"
        var lastFailurePeer = 0
        var lastFailureLogAt: Long? = null
        var lastStartLogAt: Long? = null
        var lastStartPeer = 0
        val peers = linkedMapOf<String, Int>()
    }

    private var state = State()

    @Synchronized
    fun begin(attempt: String) {
        state = State(state.epoch + 1, attempt.takeIf { it.matches(Regex("[a-f0-9]{8}")) } ?: "unknown", nowMs(), true)
        emit("BEGIN", "attempt=${state.attempt}")
    }

    @Synchronized
    fun epoch(): Long = state.epoch

    @Synchronized
    fun finish() {
        if (!state.active) return
        state.inFlight?.let { connectFinished(it, cancelled = true) }
        state.endedAtMs = nowMs()
        state.active = false
        emit("END", summary())
    }

    @Synchronized
    fun summary(): String =
        with(state) {
            "schema=1 run=$epoch attempt=$attempt active=$active elapsedMs=${duration(startedAtMs, endedAtMs ?: nowMs())} " +
                "client=$client iap2Registered=$iap2Registered carPlayRegistered=$carPlayRegistered " +
                "iap2Listening=$iap2Listening carPlayListening=$carPlayListening " +
                "outboundAttempts=$outboundAttempts outboundSuccess=$outboundSuccess outboundFailures=$outboundFailures " +
                "outboundCancelled=$outboundCancelled inFlight=${inFlight?.number ?: 0} " +
                "inFlightMs=${inFlight?.let { duration(it.startedAtMs, endedAtMs ?: nowMs()) } ?: 0} " +
                "incomingAccepted=$incomingAccepted incomingRejected=$incomingRejected capabilityConnections=$capabilityConnections " +
                "bondEvents=$bondEvents aclConnected=$aclConnected aclDisconnected=$aclDisconnected " +
                "sdpQueries=$sdpQueries sdpCompleted=$sdpCompleted sdpTimeouts=$sdpTimeouts sdpErrors=$sdpErrors " +
                "lastFailurePeer=$lastFailurePeer lastFailurePhase=$lastFailurePhase lastFailure=[$lastFailure]"
        }

    @Synchronized
    fun client(
        epoch: Long,
        started: Boolean,
    ) = update(epoch) {
        client = started
        emit("CLIENT", "running=$started")
    }

    @Synchronized
    fun adapter(
        epoch: Long,
        adapterState: Int?,
        scanMode: Int?,
        discovering: Boolean?,
    ) = update(epoch) {
        emit("ADAPTER", "state=${adapterState ?: "unknown"} scanMode=${scanMode ?: "unknown"} discovering=${discovering ?: "unknown"}")
    }

    @Synchronized
    fun registration(
        epoch: Long,
        service: Service,
        elapsedMs: Long,
        error: Throwable? = null,
    ) = update(epoch) {
        if (service == Service.IAP2) iap2Registered = error == null else carPlayRegistered = error == null
        emit(
            "SDP_REGISTER",
            "service=$service uuid=${service.uuid} elapsedMs=${elapsedMs.coerceAtLeast(0)} " +
                if (error == null) "outcome=OK evidence=LOCAL_REGISTRATION_ONLY" else "outcome=FAILED ${BluetoothFailure.describe(error)}",
        )
        error?.let { DiagnosticExportPolicy.exception("BT_REGISTER_$service", it) }
    }

    @Synchronized
    fun listener(
        epoch: Long,
        service: Service,
        listening: Boolean,
        error: Throwable? = null,
    ) = update(epoch) {
        if (service == Service.IAP2) iap2Listening = listening else carPlayListening = listening
        emit("LISTENER", "service=$service listening=$listening" + (error?.let { " ${BluetoothFailure.describe(it)}" } ?: ""))
        error?.let { DiagnosticExportPolicy.exception("BT_LISTENER_$service", it) }
    }

    @Synchronized
    fun serverStopped(epoch: Long) =
        update(epoch) {
            iap2Registered = false
            carPlayRegistered = false
            iap2Listening = false
            carPlayListening = false
            emit("SERVER_STOPPED", "recordsReleased=true")
        }

    @Synchronized
    fun sdp(
        epoch: Long,
        address: String,
        outcome: SdpOutcome,
        elapsedMs: Long,
        cachedServices: Int,
        hasIap2: Boolean,
        error: Throwable? = null,
    ) = update(epoch) {
        sdpQueries++
        when (outcome) {
            SdpOutcome.COMPLETE -> sdpCompleted++
            SdpOutcome.TIMEOUT -> sdpTimeouts++
            SdpOutcome.ERROR, SdpOutcome.NOT_STARTED -> sdpErrors++
            SdpOutcome.CANCELLED -> Unit
        }
        emit(
            "SDP_QUERY",
            "peer=${peer(address)} outcome=$outcome elapsedMs=${elapsedMs.coerceAtLeast(0)} " +
                "cachedServices=$cachedServices cachedIap2=$hasIap2" + (error?.let { " ${BluetoothFailure.describe(it)}" } ?: ""),
        )
        error?.let { DiagnosticExportPolicy.exception("BT_SDP_QUERY", it) }
    }

    @Synchronized
    fun connectStarted(
        epoch: Long,
        address: String,
        bondState: Int?,
        match: PeerMatch,
    ): ConnectAttempt? {
        if (!current(epoch)) return null
        return ConnectAttempt(epoch, ++state.outboundAttempts, peer(address), nowMs()).also {
            state.inFlight = it
            if (state.lastStartPeer != it.peer || state.lastStartLogAt?.let { last -> duration(last, it.startedAtMs) >= 15_000 } != false) {
                state.lastStartLogAt = it.startedAtMs
                state.lastStartPeer = it.peer
                emit("CONNECT_START", "number=${it.number} peer=${it.peer} match=$match bond=${bond(bondState)} service=ACCESSORY_IAP2")
                DiagnosticExportPolicy.rawEvent(
                    "BT_CONNECT_START",
                    "run=${state.epoch} number=${it.number} peer=${it.peer} address=$address match=$match bond=${bond(bondState)}",
                )
            }
        }
    }

    @Synchronized
    fun connectFinished(
        attempt: ConnectAttempt?,
        creatingSocket: Boolean = false,
        error: Throwable? = null,
        cancelled: Boolean = false,
    ) {
        if (attempt == null || !current(attempt.epoch) || state.inFlight != attempt) return
        state.inFlight = null
        val elapsedMs = duration(attempt.startedAtMs, nowMs())
        val phase = if (creatingSocket) "CREATE_SOCKET" else "CONNECT"
        val outcome =
            when {
                cancelled -> "CANCELLED".also { state.outboundCancelled++ }
                error != null -> "FAILED".also { state.outboundFailures++ }
                else -> "OK".also { state.outboundSuccess++ }
            }
        val failure = error?.takeUnless { cancelled }?.let(BluetoothFailure::describe)
        val changed =
            failure != null && (state.lastFailure != failure || state.lastFailurePhase != phase || state.lastFailurePeer != attempt.peer)
        if (failure != null) {
            state.lastFailure = failure
            state.lastFailurePhase = phase
            state.lastFailurePeer = attempt.peer
        }
        if (outcome != "FAILED" || changed || state.lastFailureLogAt?.let { duration(it, nowMs()) >= 15_000 } != false) {
            if (outcome == "FAILED") state.lastFailureLogAt = nowMs()
            emit(
                "CONNECT_END",
                "number=${attempt.number} peer=${attempt.peer} phase=$phase outcome=$outcome elapsedMs=$elapsedMs" +
                    (failure?.let { " $it" } ?: ""),
            )
            if (!cancelled) error?.let { DiagnosticExportPolicy.exception("BT_$phase", it) }
        }
    }

    @Synchronized
    fun incoming(
        epoch: Long,
        service: Service,
        address: String?,
        match: PeerMatch,
    ) = update(epoch) {
        if (service == Service.CARPLAY) {
            capabilityConnections++
        } else if (match == PeerMatch.OTHER) {
            incomingRejected++
        } else {
            incomingAccepted++
        }
        val decision =
            when {
                service == Service.CARPLAY -> "CAPABILITY_ONLY"
                match == PeerMatch.OTHER -> "REJECTED_TARGET"
                else -> "ACCEPTED_IAP2"
            }
        emit("INCOMING", "service=$service peer=${address?.let(::peer) ?: 0} match=$match decision=$decision")
        DiagnosticExportPolicy.rawEvent(
            "BT_INCOMING",
            "run=${state.epoch} service=$service address=$address match=$match decision=$decision",
        )
    }

    @Synchronized
    fun bondChanged(
        epoch: Long,
        address: String,
        match: PeerMatch,
        before: Int?,
        after: Int?,
    ) = update(epoch) {
        bondEvents++
        emit("BOND", "peer=${peer(address)} match=$match before=${bond(before)} after=${bond(after)}")
        DiagnosticExportPolicy.rawEvent(
            "BT_BOND",
            "run=${state.epoch} address=$address match=$match before=${bond(before)} after=${bond(after)}",
        )
    }

    @Synchronized
    fun acl(
        epoch: Long,
        address: String,
        match: PeerMatch,
        connected: Boolean,
    ) = update(epoch) {
        if (connected) aclConnected++ else aclDisconnected++
        emit("ACL", "peer=${peer(address)} match=$match connected=$connected")
        DiagnosticExportPolicy.rawEvent("BT_ACL", "run=${state.epoch} address=$address match=$match connected=$connected")
    }

    @Synchronized
    fun monitor(
        epoch: Long,
        started: Boolean,
        error: Throwable? = null,
    ) = update(epoch) {
        emit("STATE_MONITOR", "registered=$started" + (error?.let { " ${BluetoothFailure.describe(it)}" } ?: ""))
    }

    private fun update(
        epoch: Long,
        action: State.() -> Unit,
    ) {
        if (current(epoch)) state.action()
    }

    private fun current(epoch: Long): Boolean = state.active && state.epoch == epoch

    private fun peer(address: String): Int =
        state.peers[address] ?: if (state.peers.size < 64) (state.peers.size + 1).also { state.peers[address] = it } else 0

    private fun emit(
        event: String,
        fields: String,
    ) {
        runCatching { log("BT-DIAG schema=1 run=${state.epoch} event=$event $fields") }
    }

    private fun duration(
        start: Long,
        end: Long,
    ): Long = if (state.epoch == 0L) 0 else (end - start).coerceAtLeast(0)

    private fun bond(value: Int?): String =
        when (value) {
            10 -> "NONE"
            11 -> "BONDING"
            12 -> "BONDED"
            else -> "UNKNOWN"
        }
}
