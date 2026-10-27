package com.mcarplay.app.compat

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.mcarplay.app.BuildConfig
import com.mcarplay.app.carlife.CarLifeLauncher
import com.mcarplay.app.diagnostics.ConnectionDiagnostics
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** I/O 仅在单个后台执行器上进行；媒体回调只更新内存，保存使用 AtomicFile。 */
object ReceiverDetectionRuntime {
    val detection = ReceiverDetection()
    private val initializing = AtomicBoolean(false)
    private val epochs = AtomicLong()
    private val sessions = AtomicLong()

    @Volatile
    private var claimed: ReceiverObservation? = null

    @Volatile
    private var profileTrialUntil = 0L
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "receiver-detection").apply { isDaemon = true } }

    @Volatile
    private var store: ReceiverConfigurationStore? = null

    @Volatile
    private var file: AtomicFile? = null

    @Volatile
    private var environment: String? = null

    @Volatile
    private var application: Context? = null

    @Volatile
    var storageError: Boolean = false
        private set

    @Volatile
    var saving: Boolean = false
        private set

    @Volatile
    var onBudgetExpired: ((Long) -> Unit)? = null

    val ready: Boolean get() = store != null && environment != null

    fun initialize(context: Context) {
        if (!initializing.compareAndSet(false, true)) return
        val app = context.applicationContext
        application = app
        worker.execute {
            runCatching {
                val atomic = AtomicFile(File(app.filesDir, "receiver-configurations.json"))
                file = atomic
                val loaded =
                    if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) {
                        atomic.openRead().use { input ->
                            val buffer = ByteArray(ReceiverConfigurationStore.MAX_BYTES + 1)
                            var size = 0
                            while (size < buffer.size) {
                                val count = input.read(buffer, size, buffer.size - size)
                                if (count < 0) break
                                size += count
                            }
                            require(size <= ReceiverConfigurationStore.MAX_BYTES)
                            ReceiverConfigurationStore.decode(String(buffer, 0, size, Charsets.UTF_8))
                        }
                    } else {
                        ReceiverConfigurationStore().also { require(persist(it)) }
                    }
                environment = environmentOf(app)
                store = loaded
            }.onFailure { storageError = true }
        }
        worker.scheduleWithFixedDelay({ runCatching { tick() } }, 1, 1, TimeUnit.SECONDS)
    }

    fun binding(address: String?): String? = store?.binding(address)

    fun saved(address: String?): LocalReceiverConfiguration? {
        val key = binding(address) ?: return null
        return store?.entries?.firstOrNull { it.binding == key }
    }

    fun begin(address: String): Boolean {
        val key = binding(address) ?: return false
        val env = environment ?: return false
        if (saving || !detection.begin(key, env)) return false
        note("begin")
        return true
    }

    fun connectionStarted(attempt: String) {
        claimed = null
        profileTrialUntil = 0
        detection.connectionStarted(epochs.incrementAndGet(), attempt)
        // CarWith/CarLife 可以在本进程存活时更新；每次新连接都后台重新核对环境。
        val app = application
        if (app != null && store != null) {
            environment = null
            worker.execute {
                val latest = environmentOf(app)
                environment = latest
                detection.environmentUpdated(latest)
            }
        }
    }

    fun observation(address: String?): ReceiverObservation? =
        binding(address)?.let { ReceiverObservation(it, epochs.get(), sessions.incrementAndGet()) }

    fun isCurrent(observation: ReceiverObservation?): Boolean =
        observation == null || (observation.epoch == epochs.get() && observation.session >= (claimed?.session ?: 0))

    fun attach(
        observation: ReceiverObservation?,
        attempt: String,
    ) {
        if (observation != null && isCurrent(observation)) claimed = observation
        if (observation != null && detection.attach(observation, attempt)) note("endpoint")
    }

    fun collecting(observation: ReceiverObservation?): Boolean {
        val draft = detection.snapshot() ?: return false
        return observation != null &&
            isCurrent(observation) &&
            draft.state == ReceiverDetection.State.COLLECTING &&
            draft.binding == observation.binding
    }

    fun bluetooth(address: String?): BluetoothBringup {
        val key = binding(address) ?: return BluetoothBringup.KEEP_PROFILES
        val draft = detection.snapshot()
        if (draft?.binding == key && draft.state == ReceiverDetection.State.COLLECTING) return draft.bluetooth
        return configuration(key)?.bluetooth ?: BluetoothBringup.KEEP_PROFILES
    }

    fun infoRequest(observation: ReceiverObservation?): InfoRequest {
        if (observation == null || collecting(observation)) return InfoRequest.GET_EMPTY_PLIST
        return configuration(observation.binding)?.infoRequest ?: InfoRequest.GET_EMPTY_PLIST
    }

    /** 只屏蔽本轮有意断开配置文件时产生的短暂 ACL 事件。 */
    fun profileTrialStarting(address: String?): Long? {
        if (bluetooth(address) != BluetoothBringup.DISCONNECT_PROFILES) return null
        profileTrialUntil = monotonicMs() + 5_000
        return epochs.get()
    }

    fun profileTrialCompleted(
        epoch: Long?,
        known: Boolean,
        disconnected: Boolean,
        hadProfiles: Boolean,
    ) {
        if (epoch != null && isEpoch(epoch)) detection.profileTrialCompleted(known, disconnected, hadProfiles)
    }

    val awaitingProfileTrial: Boolean get() = monotonicMs() < profileTrialUntil

    fun preferredAudio(
        observation: ReceiverObservation?,
        capabilities: String?,
    ): String? {
        if (observation == null || capabilities == null || collecting(observation) || !isCurrent(observation)) return null
        return configuration(observation.binding)?.takeIf { it.capabilities == capabilities }?.audioProfile
    }

    fun capabilities(
        observation: ReceiverObservation?,
        digest: String,
        request: InfoRequest,
    ) {
        if (observation != null) detection.capabilities(observation, digest, request)
    }

    fun audioSelected(
        observation: ReceiverObservation?,
        profile: String,
    ) {
        if (observation != null) detection.audioSelected(observation, profile)
    }

    fun touch(observation: ReceiverObservation?) {
        if (observation != null) detection.touch(observation)
    }

    fun mediaEnded(observation: ReceiverObservation?) {
        if (observation != null && isCurrent(observation) && detection.snapshot()?.observation == observation) {
            tickProgress()
            detection.stop("VIDEO_CLOSED")
        }
    }

    fun stop(
        issue: String,
        cancelled: Boolean = false,
    ) {
        retireConnection()
        tickProgress()
        detection.stop(issue, cancelled)
        profileTrialUntil = 0
        claimed = null
        note(if (cancelled) "cancel" else "stop")
    }

    fun retireConnection() {
        epochs.incrementAndGet()
        claimed = null
        profileTrialUntil = 0
    }

    /** 显式对照只允许一次，调用方随后停止旧连接、重新启动正常连接流程。 */
    fun retryBluetooth(): Boolean {
        tickProgress()
        if (!detection.retryBluetooth()) return false
        note("bluetooth_trial")
        return true
    }

    fun save(
        verification: ReceiverVerification,
        onComplete: (Boolean) -> Unit,
    ) {
        if (!ready || saving) {
            onComplete(false)
            return
        }
        tickProgress()
        val configuration = detection.prepareSave(verification, System.currentTimeMillis(), BuildConfig.VERSION_NAME)
        if (configuration == null) {
            onComplete(false)
            return
        }
        val id = detection.snapshot()!!.id
        saving = true
        worker.execute {
            val next = store?.with(configuration)
            val ok = next != null && persist(next)
            if (ok) store = next
            detection.saved(id, ok)
            saving = false
            note(if (ok) "saved" else "save_failed")
            onComplete(ok)
        }
    }

    fun delete(
        address: String,
        onComplete: (Boolean) -> Unit,
    ) {
        val key = binding(address)
        if (key == null || saving || detection.snapshot()?.state == ReceiverDetection.State.COLLECTING) {
            onComplete(false)
            return
        }
        saving = true
        worker.execute {
            val next = store?.without(key)
            val ok = next != null && persist(next)
            if (ok) {
                store = next
                detection.forget(key)
            }
            saving = false
            note(if (ok) "deleted" else "delete_failed")
            onComplete(ok)
        }
    }

    /** 仅固定枚举与计数；不导出绑定摘要、环境摘要、能力摘要或目标名称。 */
    fun reportSummary(): String {
        val current = detection.snapshot()
        return "ready=$ready storageError=$storageError savedCount=${store?.entries?.size ?: 0} " +
            "state=${current?.state ?: "none"} trial=${current?.trial ?: 0} stage=${current?.stage ?: "none"} " +
            "videoFrames=${current?.videoFrames ?: 0} audioPackets=${current?.audioPackets ?: 0} touches=${current?.touches ?: 0}"
    }

    private fun configuration(binding: String): LocalReceiverConfiguration? = environment?.let { store?.find(binding, it) }

    fun environmentMatches(configuration: LocalReceiverConfiguration): Boolean = configuration.environment == environment

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

    private fun tickProgress() {
        val current = ConnectionDiagnostics.timeline.snapshot() ?: return
        detection.progress(
            current.id,
            current.stage.name,
            current.code?.name,
            current.sourceFrames,
            current.videoFrames,
            current.audioChunks,
            current.audioPackets,
        )
    }

    private fun tick() {
        tickProgress()
        if (detection.timedOut()) {
            val epoch = epochs.get()
            detection.stop("BUDGET_EXHAUSTED")
            note("budget_exhausted")
            onBudgetExpired?.invoke(epoch)
        }
    }

    fun isEpoch(epoch: Long): Boolean = epoch == epochs.get()

    private fun note(event: String) {
        val timeline = ConnectionDiagnostics.timeline
        timeline.snapshot()?.let { timeline.note(it.id, "DETECT event=$event ${reportSummary()}") }
    }

    private fun persist(next: ReceiverConfigurationStore): Boolean {
        val atomic = file ?: return false
        var output: java.io.FileOutputStream? = null
        return try {
            val encoded = next.encode()
            // 自报字符串也必须通过白名单，避免写出下一次启动读不回来的配置。
            ReceiverConfigurationStore.decode(encoded)
            output = atomic.startWrite()
            output.write(encoded.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            true
        } catch (_: Exception) {
            atomic.failWrite(output)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun componentVersion(
        context: Context,
        name: String,
    ): String =
        runCatching {
            context.packageManager
                .getPackageInfo(
                    name,
                    0,
                ).let { "${it.versionName}/${it.longVersionCode}" }
        }.getOrDefault("missing")

    private fun environmentOf(context: Context): String =
        ReceiverFingerprint.digest(
            listOf(
                ReceiverConfigurationStore.ENGINE.toString(),
                Build.FINGERPRINT.orEmpty(),
                Build.VERSION.SDK_INT.toString(),
                componentVersion(context, CarLifeLauncher.CARWITH_PACKAGE),
                componentVersion(context, CarLifeLauncher.CARLIFE_PACKAGE),
            ),
        )
}
