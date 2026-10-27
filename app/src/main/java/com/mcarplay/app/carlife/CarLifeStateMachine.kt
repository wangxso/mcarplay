package com.mcarplay.app.carlife

/**
 * Deterministic CarLife handshake state machine.
 *
 * Socket I/O and timers live in [CarLifeSession]. This class owns protocol
 * order, one-shot guards and transitions that duplicated or late messages
 * must not bypass.
 */
class CarLifeStateMachine(
    private val authVerifier: CarLifeAuthVerifier?,
    private val seedExchange: CarLifeSeedExchange? = null,
    private val challengeFactory: () -> String = CarLifeAuthentication::newChallenge,
) : AutoCloseable {
    enum class State {
        CONNECTED,
        VERSION_SENT,
        PROTOCOL_MATCHED,
        AUTH_REQUESTED,
        AUTH_VERIFIED,
        FEATURES_READY,
        RSA_PUBLISHED,
        ENCRYPTED,
        VIDEO_REQUESTED,
        VIDEO_RUNNING,
        FAILED,
    }

    data class Action(
        val kind: Int,
        val payload: ByteArray = ByteArray(0),
    )

    data class HandshakeResult(
        val actions: List<Action> = emptyList(),
        val changed: Boolean = false,
    )

    var state: State = State.CONNECTED
        private set

    /** Non-sensitive MD_INFO fields used by the CarLife challenge binding. */
    var deviceInfo: CarLifeProtocol.DeviceInfo? = null
        private set

    var foregroundAcknowledged = false
        private set

    /** Set only after a valid seed and peer ENCRYPT_READY. */
    var payloadCipher: CarLifePayloadCipher? = null
        private set

    var videoFormat: CarLifeProtocol.VideoFormat? = null
        private set

    val canInitializeVideo: Boolean get() = state == State.ENCRYPTED && peerAuthAccepted

    private var versionSent = false
    private var authRequested = false
    private var authResponseAccepted = false
    private var authChallenge: String? = null
    private var peerAuthAccepted = false
    private var featureRequested = false
    private var featureResponseSent = false
    private var rsaPublished = false
    private var seedAccepted = false
    private var encryptReadyReceived = false
    private var videoInitSent = false
    private var videoStartSent = false

    fun onConnected(): List<Action> {
        if (state != State.CONNECTED || versionSent) return fail()
        versionSent = true
        state = State.VERSION_SENT
        return listOf(
            Action(CarLifeProtocol.HU_PROTOCOL_VERSION, CarLifeProtocol.protocolVersionPayload()),
        )
    }

    fun onProtocolMatched(): List<Action> {
        if (state != State.VERSION_SENT) return fail()
        state = State.PROTOCOL_MATCHED
        return listOf(Action(CarLifeProtocol.HU_STATISTICS, CarLifeProtocol.statisticsPayload()))
    }

    /** Foreground requests can recur after video starts; they never advance authentication. */
    fun onForeground(): HandshakeResult {
        if (state == State.CONNECTED || state == State.VERSION_SENT || state == State.FAILED) {
            fail()
            return HandshakeResult()
        }
        foregroundAcknowledged = true
        return HandshakeResult(
            actions = listOf(Action(CarLifeProtocol.HU_GO_TO_FOREGROUND)),
            changed = true,
        )
    }

    /**
     * MD_INFO must arrive after protocol match and before the challenge is
     * sent. It is intentionally the only transition into AUTH_REQUESTED.
     */
    fun onInfo(payload: ByteArray): List<Action> {
        if (state != State.PROTOCOL_MATCHED || authRequested) return fail()
        val info = CarLifeProtocol.parseDeviceInfo(payload) ?: return fail()
        deviceInfo = info
        val challenge = challengeFactory()
        authChallenge = challenge
        authRequested = true
        state = State.AUTH_REQUESTED
        return listOf(
            Action(CarLifeProtocol.HU_INFO, CarLifeProtocol.huInfoPayload()),
            Action(CarLifeProtocol.HU_AUTH_REQUEST, CarLifeProtocol.authRequestPayload(challenge)),
        )
    }

    fun onAuthResponse(payload: ByteArray): List<Action> {
        if (state != State.AUTH_REQUESTED || authResponseAccepted) return fail()
        val challenge = authChallenge ?: return fail()
        if (authVerifier?.verify(challenge, payload, deviceInfo) != true) {
            return fail()
        }
        authResponseAccepted = true
        state = State.AUTH_VERIFIED
        return listOf(
            Action(CarLifeProtocol.HU_AUTH_RESULT, CarLifeProtocol.authResultPayload(true)),
        ) + sendFeaturesIfAuthenticated()
    }

    /** Channel admission may arrive before the phone's challenge response. */
    fun onPeerAuthResult(payload: ByteArray): List<Action> {
        val admissionPending =
            awaitingAuthentication() || state == State.FEATURES_READY || state == State.RSA_PUBLISHED || state == State.ENCRYPTED
        if (!admissionPending || peerAuthAccepted || CarLifeProtocol.firstIntField(payload, 1) != 1) return fail()
        peerAuthAccepted = true
        return listOf(Action(CarLifeProtocol.HU_MD_AUTH_RESULT_ACK)) + sendFeaturesIfAuthenticated()
    }

    fun onFeatureRequest(): List<Action> {
        if (!awaitingAuthentication() || featureRequested) return fail()
        featureRequested = true
        return sendFeaturesIfAuthenticated()
    }

    private fun awaitingAuthentication(): Boolean =
        state == State.PROTOCOL_MATCHED || state == State.AUTH_REQUESTED || state == State.AUTH_VERIFIED

    private fun sendFeaturesIfAuthenticated(): List<Action> {
        if (!authResponseAccepted || !featureRequested || featureResponseSent) return emptyList()
        featureResponseSent = true
        state = State.FEATURES_READY
        return listOf(
            Action(
                CarLifeProtocol.HU_FEATURE_RESPONSE,
                CarLifeProtocol.featureResponsePayload(),
            ),
        )
    }

    fun onRsaKeyRequest(): List<Action> {
        if (state != State.FEATURES_READY || rsaPublished || seedExchange == null) {
            return fail()
        }
        rsaPublished = true
        state = State.RSA_PUBLISHED
        return listOf(
            Action(
                CarLifeProtocol.HU_RSA_PUBLIC_KEY,
                CarLifeProtocol.pbString(1, seedExchange.createPublicKey()),
            ),
        )
    }

    /**
     * Accepts exactly one encrypted AES seed with a valid 16/24/32-byte size.
     * No ACK or video START is emitted before peer ENCRYPT_READY.
     */
    fun onEncryptedSeed(payload: ByteArray): List<Action> {
        if (state != State.RSA_PUBLISHED || seedAccepted) return fail()
        val seed =
            CarLifeProtocol
                .firstStringField(payload, 1)
                ?.let { seedExchange?.decryptSeed(it) }
                ?: return fail()
        seedAccepted = true
        payloadCipher = CarLifePayloadCipher(seed)
        seed.fill(0)
        return listOf(Action(CarLifeProtocol.HU_AES_ACK))
    }

    /** The peer's READY permits video INIT/START; it is not a media frame. */
    fun onEncryptReady(payload: ByteArray): List<Action> {
        if (state != State.RSA_PUBLISHED ||
            !seedAccepted ||
            encryptReadyReceived ||
            payloadCipher == null ||
            payload.isNotEmpty()
        ) {
            return fail()
        }
        encryptReadyReceived = true
        state = State.ENCRYPTED
        return emptyList()
    }

    fun requestVideoInit(
        width: Int = CarLifeProtocol.DEFAULT_WIDTH,
        height: Int = CarLifeProtocol.DEFAULT_HEIGHT,
    ): List<Action> {
        if (state != State.ENCRYPTED || videoInitSent) return fail()
        if (!peerAuthAccepted) return emptyList()
        videoInitSent = true
        state = State.VIDEO_REQUESTED
        return listOf(
            Action(CarLifeProtocol.VIDEO_ENCODER_INIT, CarLifeProtocol.videoInitPayload(width, height)),
        )
    }

    fun onVideoInitDone(payload: ByteArray): List<Action> {
        if (state != State.VIDEO_REQUESTED || videoStartSent) return fail()
        val format = CarLifeProtocol.parseVideoFormat(payload) ?: return fail()
        videoFormat = format
        videoStartSent = true
        state = State.VIDEO_RUNNING
        return listOf(Action(CarLifeProtocol.VIDEO_ENCODER_START))
    }

    fun fail(): List<Action> {
        state = State.FAILED
        authChallenge = null
        payloadCipher = null
        seedExchange?.clear()
        return emptyList()
    }

    override fun close() {
        authChallenge = null
        seedExchange?.clear()
        payloadCipher = null
    }
}
