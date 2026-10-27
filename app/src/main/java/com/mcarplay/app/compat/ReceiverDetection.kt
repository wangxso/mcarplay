package com.mcarplay.app.compat

import com.mcarplay.app.iap2.ReceiverProfile
import java.security.MessageDigest
import java.util.Locale

enum class BluetoothBringup { KEEP_PROFILES, DISCONNECT_PROFILES }

enum class InfoRequest { GET_EMPTY_PLIST, POST_EMPTY_PLIST }

enum class ReceiverCheck { UNVERIFIED, CONFIRMED, FAILED }

/** 本机绑定与连接代次，不包含原始蓝牙地址，也不进入支持报告。 */
data class ReceiverObservation(
    val binding: String,
    val epoch: Long,
    val session: Long,
)

data class ReceiverVerification(
    val video: ReceiverCheck = ReceiverCheck.UNVERIFIED,
    val audio: ReceiverCheck = ReceiverCheck.UNVERIFIED,
    val touch: ReceiverCheck = ReceiverCheck.UNVERIFIED,
)

data class LocalReceiverConfiguration(
    val binding: String,
    val environment: String,
    val capabilities: String,
    val savedAtMs: Long,
    val build: String,
    val bluetooth: BluetoothBringup,
    val infoRequest: InfoRequest,
    val audioProfile: String?,
    val verification: ReceiverVerification,
    val observedSeconds: Long,
)

/** 只对稳定的协议事实取摘要；地址、动态端口、设备显示名不参与能力判定。 */
object ReceiverFingerprint {
    fun digest(values: List<String>): String {
        val input = values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun identity(signals: ReceiverProfile.Signals): List<String> =
        listOf(
            signals.model.normalized(),
            signals.manufacturer.normalized(),
            signals.firmwareVersion.normalized(),
            signals.srcvers.normalized(),
            signals.features?.toString().orEmpty(),
            signals.sendIds?.sorted()?.joinToString(",") ?: "missing",
            signals.receiveIds?.sorted()?.joinToString(",") ?: "missing",
            signals.externalAccessoryProtocols
                .map { it.normalized() }
                .sorted()
                .joinToString(","),
        )

    fun handshake(
        binding: String?,
        signals: ReceiverProfile.Signals,
    ): String? = binding?.takeIf { it.isNotBlank() }?.let { "mfi2_" + digest(listOf(it) + identity(signals)) }

    private fun String?.normalized(): String = orEmpty().trim().lowercase(Locale.ROOT)
}

/** 纯状态机：试验结果只存在草稿中，只有显式保存才能生成持久化配置。 */
class ReceiverDetection(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class State { COLLECTING, STOPPED, SAVING, SAVED, CANCELLED }

    data class Draft(
        val id: Long,
        val binding: String,
        val environment: String,
        val startedAt: Long,
        val state: State = State.COLLECTING,
        val trial: Int = 0,
        val bluetooth: BluetoothBringup = BluetoothBringup.KEEP_PROFILES,
        val profileDisconnectConfirmed: Boolean = false,
        val observation: ReceiverObservation? = null,
        val attempt: String? = null,
        val stage: String = "BLUETOOTH",
        val issue: String? = null,
        val capabilities: String? = null,
        val infoRequest: InfoRequest = InfoRequest.GET_EMPTY_PLIST,
        val audioProfile: String? = null,
        val sourceFrames: Long = 0,
        val videoFrames: Long = 0,
        val audioChunks: Long = 0,
        val audioPackets: Long = 0,
        val touches: Long = 0,
        val mediaStartedAt: Long? = null,
        val stoppedAt: Long? = null,
    ) {
        val canConfirmVideo: Boolean get() = sourceFrames > 0 && videoFrames > 0
        val canConfirmAudio: Boolean get() = canConfirmVideo && audioChunks > 0 && audioPackets > 0 && audioProfile != null
        val canConfirmTouch: Boolean get() = canConfirmVideo && touches > 0
        val canRetryBluetooth: Boolean
            get() = state in listOf(State.COLLECTING, State.STOPPED) && trial == 0 && observation == null && stage == "BLUETOOTH"
    }

    private var nextId = 0L
    private var epoch = 0L
    private var draft: Draft? = null

    @Synchronized
    fun snapshot(): Draft? = draft

    @Synchronized
    fun begin(
        binding: String,
        environment: String,
    ): Boolean {
        if (binding.isBlank() || environment.isBlank() || draft?.state in listOf(State.COLLECTING, State.SAVING)) return false
        draft = Draft(++nextId, binding, environment, now())
        return true
    }

    @Synchronized
    fun connectionStarted(
        connectionEpoch: Long,
        attempt: String? = null,
    ) {
        epoch = connectionEpoch
        draft?.takeIf { it.state == State.COLLECTING }?.let { draft = it.copy(attempt = attempt) }
    }

    @Synchronized
    fun environmentUpdated(environment: String) {
        val current = draft ?: return
        if (current.state != State.COLLECTING || current.environment == environment) return
        draft =
            if (current.videoFrames == 0L) {
                current.copy(environment = environment)
            } else {
                current.copy(state = State.STOPPED, issue = "ENVIRONMENT_CHANGED", stoppedAt = now())
            }
    }

    @Synchronized
    fun retryAvailable(): Boolean = draft?.let { it.canRetryBluetooth && now() - it.startedAt < BUDGET_MS } == true

    @Synchronized
    fun attach(
        observation: ReceiverObservation,
        attempt: String,
    ): Boolean {
        val current = draft ?: return false
        if (current.state != State.COLLECTING || observation.epoch != epoch || observation.binding != current.binding) return false
        if (current.observation == observation && current.attempt == attempt) return true
        if (observation.session < (current.observation?.session ?: 0)) return false
        if (current.videoFrames > 0) {
            draft = current.copy(state = State.STOPPED, issue = "SUPERSEDED", stoppedAt = now())
            return false
        }
        draft =
            current.copy(
                observation = observation,
                attempt = attempt,
                capabilities = null,
                audioProfile = null,
                touches = 0,
                sourceFrames = 0,
                videoFrames = 0,
                audioChunks = 0,
                audioPackets = 0,
                stage = "WIFI",
                issue = null,
                mediaStartedAt = null,
            )
        return true
    }

    @Synchronized
    fun capabilities(
        observation: ReceiverObservation,
        digest: String,
        request: InfoRequest,
    ) {
        val current = active(observation) ?: return
        if (digest.isBlank()) return
        draft = current.copy(capabilities = digest, infoRequest = request)
    }

    @Synchronized
    fun audioSelected(
        observation: ReceiverObservation,
        profile: String,
    ) {
        val current = active(observation) ?: return
        draft = current.copy(audioProfile = profile)
    }

    @Synchronized
    fun touch(observation: ReceiverObservation) {
        val current = active(observation) ?: return
        draft = current.copy(touches = current.touches + 1)
    }

    @Synchronized
    fun profileTrialCompleted(
        known: Boolean,
        disconnected: Boolean,
        hadProfiles: Boolean,
    ) {
        val current = draft ?: return
        if (current.state == State.COLLECTING && current.trial == 1 && known && disconnected && hadProfiles) {
            draft = current.copy(profileDisconnectConfirmed = true)
        }
    }

    @Synchronized
    fun progress(
        attempt: String,
        stage: String,
        issue: String?,
        sourceFrames: Long,
        videoFrames: Long,
        audioChunks: Long,
        audioPackets: Long,
    ) {
        val current = draft ?: return
        if (current.state != State.COLLECTING || current.attempt != attempt) return
        draft =
            current.copy(
                stage = stage,
                issue = issue,
                sourceFrames = sourceFrames,
                videoFrames = videoFrames,
                audioChunks = audioChunks,
                audioPackets = audioPackets,
                mediaStartedAt = current.mediaStartedAt ?: now().takeIf { videoFrames > 0 },
            )
    }

    /** 只在尚未交付端点的蓝牙阶段允许一次对照；不会在配对或出流中拆蓝牙。 */
    @Synchronized
    fun retryBluetooth(): Boolean {
        val current = draft ?: return false
        if (!current.canRetryBluetooth || now() - current.startedAt >= BUDGET_MS) return false
        draft = current.copy(state = State.COLLECTING, trial = 1, bluetooth = BluetoothBringup.DISCONNECT_PROFILES, issue = null)
        return true
    }

    @Synchronized
    fun stop(
        issue: String,
        cancelled: Boolean = false,
    ) {
        val current = draft ?: return
        if (current.state != State.COLLECTING && !(cancelled && current.state == State.STOPPED)) return
        draft = current.copy(state = if (cancelled) State.CANCELLED else State.STOPPED, issue = issue, stoppedAt = now())
    }

    @Synchronized
    fun timedOut(): Boolean {
        val current = draft ?: return false
        return current.state == State.COLLECTING && current.videoFrames == 0L && now() - current.startedAt >= BUDGET_MS
    }

    @Synchronized
    fun prepareSave(
        verification: ReceiverVerification,
        wallTimeMs: Long,
        build: String,
    ): LocalReceiverConfiguration? {
        val current = draft ?: return null
        if (current.issue == "ENVIRONMENT_CHANGED") return null
        if (current.state !in listOf(State.COLLECTING, State.STOPPED) ||
            current.capabilities == null ||
            verification.video != ReceiverCheck.CONFIRMED ||
            !current.canConfirmVideo ||
            (verification.audio == ReceiverCheck.CONFIRMED && !current.canConfirmAudio) ||
            (verification.touch == ReceiverCheck.CONFIRMED && !current.canConfirmTouch)
        ) {
            return null
        }
        draft = current.copy(state = State.SAVING)
        return LocalReceiverConfiguration(
            binding = current.binding,
            environment = current.environment,
            capabilities = current.capabilities,
            savedAtMs = wallTimeMs,
            build = build,
            bluetooth = if (current.profileDisconnectConfirmed) current.bluetooth else BluetoothBringup.KEEP_PROFILES,
            infoRequest = current.infoRequest,
            audioProfile = current.audioProfile.takeIf { verification.audio == ReceiverCheck.CONFIRMED },
            verification = verification,
            observedSeconds = current.mediaStartedAt?.let { ((current.stoppedAt ?: now()) - it).coerceAtLeast(0) / 1000 } ?: 0,
        )
    }

    @Synchronized
    fun saved(
        id: Long,
        success: Boolean,
    ) {
        val current = draft ?: return
        if (current.id == id && current.state == State.SAVING) {
            draft = current.copy(state = if (success) State.SAVED else State.STOPPED, issue = if (success) null else "SAVE_FAILED")
        }
    }

    @Synchronized
    fun forget(binding: String) {
        if (draft?.binding == binding && draft?.state !in listOf(State.COLLECTING, State.SAVING)) draft = null
    }

    private fun active(observation: ReceiverObservation): Draft? =
        draft?.takeIf { it.state == State.COLLECTING && it.observation == observation && observation.epoch == epoch }

    companion object {
        const val BUDGET_MS = 8 * 60_000L
    }
}
