package com.mcarplay.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.mcarplay.app.bt.TargetDevicePrefs
import com.mcarplay.app.carlife.CarLifeDiagnostics
import com.mcarplay.app.carlife.CarLifeLauncher
import com.mcarplay.app.carlife.ExternalHuController
import com.mcarplay.app.compat.ReceiverDetection
import com.mcarplay.app.compat.ReceiverDetectionRuntime
import com.mcarplay.app.compat.VehiclePreset
import com.mcarplay.app.compat.VehiclePresetRuntime
import com.mcarplay.app.diagnostics.ConnectionDiagnostics
import com.mcarplay.app.diagnostics.ConnectionProgress
import com.mcarplay.app.diagnostics.ConnectionTimeline
import com.mcarplay.app.diagnostics.DiagnosticCode
import com.mcarplay.app.diagnostics.DiagnosticRedactor
import com.mcarplay.app.diagnostics.DiagnosticText
import com.mcarplay.app.i18n.AppLanguage
import com.mcarplay.app.legal.DisclaimerDialog
import com.mcarplay.app.legal.DisclaimerPrefs
import com.mcarplay.app.legal.needsDisclaimerAtLaunch
import com.mcarplay.app.media.MediaSessionsState
import com.mcarplay.app.media.NotificationAccess
import com.mcarplay.app.service.BatteryExemption
import com.mcarplay.app.service.CarPlayService
import com.mcarplay.app.service.ExternalHuService
import com.mcarplay.app.settings.MediaPrefs
import com.mcarplay.app.shizuku.CarInputClient
import com.mcarplay.app.shizuku.Shell
import com.mcarplay.app.shizuku.ShizukuGate
import com.mcarplay.app.ui.BtIdentityDialog
import com.mcarplay.app.ui.DarkMessageDialog
import com.mcarplay.app.ui.EdgeToEdge
import com.mcarplay.app.ui.LogConsoleController
import com.mcarplay.app.ui.LogExportController
import com.mcarplay.app.ui.PanelWindow
import com.mcarplay.app.ui.ReceiverDetectionDialog
import com.mcarplay.app.ui.ScreenDimmer
import com.mcarplay.app.ui.TargetPickerController
import com.mcarplay.app.ui.WhatsNewDialog
import com.mcarplay.app.ui.kit.StepperView
import com.mcarplay.app.update.WhatsNew
import com.mcarplay.app.util.Bg
import com.mcarplay.app.util.DeviceIdentity
import com.mcarplay.app.util.LogExporter

/**
 * 手机侧界面（Material 3 暗色重构，设计稿 design/phone-ui-proto.html）：
 * 英雄卡承载连接状态与主操作；连接准备清单化；Shell 状态与日志折叠收纳。
 * 会话管线（对象、日志缓冲）全部由 [SessionController] 持有——Activity 只是挂屏：
 * 销毁重建不影响会话，重进界面日志自动续显；连接状态由 1s 轮询对账。
 */
class MainActivity : AppCompatActivity() {
    private enum class HeroState { IDLE, CONNECTING, CONNECTED }

    private companion object {
        const val KEY_DISCLAIMER_DEADLINE = "disclaimer_deadline"
    }

    private lateinit var deviceSubtitle: TextView
    private lateinit var shizukuChip: TextView
    private lateinit var shizukuDesc: TextView
    private lateinit var shizukuBadge: TextView
    private lateinit var btIdentityDesc: TextView
    private lateinit var btIdentityBadge: TextView

    /** 「本机蓝牙地址」行的后台实时读取是否在进行（Shizuku shell 最长 4 秒，不能压在 UI 线程）。 */
    @Volatile private var btIdentityProbeRunning = false
    private lateinit var heroDot: View
    private lateinit var heroStateText: TextView
    private lateinit var btnResidualCleanup: TextView
    private lateinit var heroSubline: TextView
    private lateinit var heroStats: TextView
    private lateinit var heroProgress: View
    private lateinit var ctaButton: LinearLayout
    private lateinit var ctaIcon: ImageView
    private lateinit var ctaLabel: TextView
    private lateinit var audioTag: TextView
    private lateinit var readyCount: TextView
    private lateinit var readyBody: View
    private lateinit var readyChevron: ImageView

    /** 连接准备清单：就绪 4/4 时自动折叠一次；用户点过标题后不再自动改变。 */
    private var readyAutoCollapsed = false
    private lateinit var ssidInput: EditText
    private lateinit var btDesc: TextView
    private lateinit var btBadge: TextView
    private lateinit var btnPickTarget: TextView
    private lateinit var audioDesc: TextView
    private lateinit var btnAudio: TextView
    private lateinit var carLifeSelfTestDesc: TextView
    private lateinit var btnCarLifeSelfTest: TextView
    private lateinit var externalHuDesc: TextView
    private lateinit var btnExternalHu: TextView
    private lateinit var switchAlwaysOn: androidx.appcompat.widget.SwitchCompat
    private lateinit var switchAutoPlayMusic: androidx.appcompat.widget.SwitchCompat
    private lateinit var advBody: View
    private lateinit var advChevron: ImageView
    private lateinit var shellStateText: TextView
    private lateinit var logBody: View
    private lateinit var logChevron: ImageView
    private lateinit var switchLog: androidx.appcompat.widget.SwitchCompat
    private lateinit var logText: TextView
    private lateinit var console: LogConsoleController
    private lateinit var snackbar: TextView
    private lateinit var appVersionFooter: TextView

    // ---- 英雄卡状态机（1s 轮询 SessionController 对账）----
    private var heroState: HeroState? = null
    private var connectedAt = 0L

    // 启动残留屏检查进行中（英雄卡显示「检查中」）；发现的残留暂缓清理时
    // 记在 pendingResidualDisplays，状态旁的「清理残留屏」按钮可再次发起
    private var startupCheckRunning = false
    private var pendingResidualDisplays: List<Pair<Int, String>>? = null

    // 英雄卡实时帧率：1s 轮询对 VideoSender 已发帧计数取差值
    private var lastSentFrames = 0L

    /** 免责声明倒计时截止时刻（elapsedRealtime；旋转重建后透传，不重新计时）。 */
    private var disclaimerDeadlineAt = 0L

    private var activeDisclaimer: androidx.appcompat.app.AlertDialog? = null
    private var activeWhatsNew: androidx.appcompat.app.AlertDialog? = null

    /** Shizuku 授权结果监听（onDestroy 必须注销，否则跨 Activity 泄漏）。 */
    private var shizukuPermissionListener:
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 日志导出流程（确认 → 写文件 → 完成弹窗）。 */
    private val logExporter =
        LogExportController(
            this,
            object : LogExportController.Host {
                override fun log(message: String) = this@MainActivity.log(message)

                override fun snack(
                    message: String,
                    durationMs: Long,
                ) = this@MainActivity.snack(message, durationMs)

                override fun diagnosticNames() = this@MainActivity.diagnosticNames()
            },
        )

    /** 目标车机选择弹窗（含蓝牙扫描/配对）；须在此处构造，内部注册了 ActivityResult 回调。 */
    private val targetPickerController =
        TargetPickerController(
            this,
            object : TargetPickerController.Host {
                override fun log(message: String) = this@MainActivity.log(message)

                override fun snack(message: String) = this@MainActivity.snack(message)

                override fun onTargetChanged() = updateReadyRow()
            },
        )
    private val heroTick: Runnable =
        object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                tickHero()
                mainHandler.postDelayed(this, 1_000L)
            }
        }

    /**
     * 挂屏注册：日志续显（含划掉界面重进时已有会话的历史日志）+ SSID 回填。
     */
    private val uiHintSinkRef: (String) -> Unit = { message ->
        runOnUiThread { if (!isFinishing && !isDestroyed) snack(message, 6_000L) }
    }

    /** 当前打开的「加入热点」确认弹窗：取消连接时联动关闭（触发流程中止）。 */
    private var wifiJoinDialog: androidx.appcompat.app.AlertDialog? = null
    private var receiverDetectionDialog: androidx.appcompat.app.AlertDialog? = null
    private var pendingDetectionTarget: TargetDevicePrefs.Target? = null

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) console.append(line)
        }
    }

    private val ssidSink: (String) -> Unit = { ssid ->
        runOnUiThread { ssidInput.setText(ssid) }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("BLUETOOTH_CONNECT 授权: $granted")
            if (pendingStartAfterBtPermission.compareAndSet(true, false)) {
                ConnectionDiagnostics.permissionResult(granted, DiagnosticCode.BT_PERMISSION)
                if (granted) resumeConnectionChecks() else pendingDetectionTarget = null
            }
        }

    /** 蓝牙权限授权后自动继续启动连接。 */
    private val pendingStartAfterBtPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("MODE: RECORD_AUDIO 授权: $granted（音频通道需要）")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                log("MODE: 投屏权限被拒绝（车机侧声音不可用）")
                return@registerForActivityResult
            }
            // Android 14+ 顺序：先把结果交给服务，由服务升级 FGS 类型后调 getMediaProjection
            CarPlayService.pendingProjectionResult =
                result.resultCode to data
            CarPlayService
                .start(this)
            log("MODE: ✓ 投屏权限已授予（重启手机后需重新授权）")
            // 会话已在进行中授权（授权晚于连接）：等投影就绪后动态补启音频通道
            Bg.run("audio-late-start") {
                val deadline = SystemClock.uptimeMillis() + 5_000L
                while (SystemClock.uptimeMillis() < deadline) {
                    if (CarPlayService.mediaProjection != null) break
                    Thread.sleep(200)
                }
                if (CarPlayService.mediaProjection == null) {
                    log("MODE: 投影 5 秒未就绪（服务异常？），音频通道本次未启动")
                    return@run
                }
                SessionController.maybeStartAudioNow(applicationContext)
                // 「启动连接」时选了开启声音、投屏授权在此刻才拿到：接着启动连接
                if (pendingStartAfterProjection.compareAndSet(true, false)) {
                    startConnectionNow()
                }
            }
        }

    /** 投屏授权是「启动连接」的前置（选了开启声音且尚未授权）时置位。 */
    private val pendingStartAfterProjection =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * 「启动连接」前的音频选择弹窗：
     * 音频与视频同时推流会显著增加负载（实测 48k 立体声 PCM ≈1.5Mbps + 视频 ≈4Mbps），
     * 测试台出现画面卡顿与断连；真车链路能力未知。因此默认不推音频，需用户显式开启。
     *
     * 三个选项竖排（横排会挤爆窄屏）：推荐项「仅视频」放第一位并用实心强调
     * ——与正文「以看视频为主时建议暂不开启」一致；取消在最下。
     */
    private fun showAudioChoiceDialog() {
        // 默认档 = 高质量媒体通路（type=102 + AAC-LC，真 iPhone 走这条）；
        // 「兼容档」= 强制只走 PCM 旧通路（type=100），AAC 被车机直接拒掉时的人工退路。
        // 按钮竖排每个约 36dp：横屏屏高只有 ~300dp，再多一个「取消」就会顶出屏幕，
        // 因此横屏省略「取消」（弹窗默认可返回键/点外部关闭）。
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val actions =
            buildList {
                add(
                    DarkMessageDialog.Action(
                        getString(R.string.ma_audio_video_only),
                        DarkMessageDialog.Emphasis.PRIMARY,
                    ) { beginWithAudio(enabled = false) },
                )
                add(DarkMessageDialog.Action(getString(R.string.ma_audio_enable)) { beginWithAudio(enabled = true) })
                if (!landscape) add(DarkMessageDialog.Action(getString(R.string.action_cancel)) {})
            }
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.ma_audio_choice_title),
            message =
                getString(R.string.ma_audio_choice_body) +
                    if (CarLifeLauncher.isAvailable(this)) {
                        getString(R.string.ma_audio_choice_carwith)
                    } else {
                        getString(R.string.ma_audio_choice_projection)
                    },
            actions = actions,
        )
    }

    /** 「取消连接」确认弹窗（防误触）：中止当前连接尝试并断开蓝牙/WiFi 链路。 */
    private fun promptCancelConnecting() {
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.ma_cancel_connect_title),
            message = getString(R.string.ma_cancel_connect_body),
            actions =
                listOf(
                    DarkMessageDialog.Action(getString(R.string.ma_keep_connecting)) {},
                    DarkMessageDialog.Action(getString(R.string.ma_cancel_connect), DarkMessageDialog.Emphasis.DANGER) {
                        // 关闭仍在等待的「加入热点」确认弹窗（其 onChoice(null) 会与
                        // connectCancelled 一起让 autoJoin 流程立即中止）
                        wifiJoinDialog?.cancel()
                        wifiJoinDialog = null
                        SessionController.cancelConnecting(this)
                    },
                ),
        )
    }

    /**
     * 启动连接的前置检查：蓝牙权限 → 蓝牙开关 → WLAN 位置权限（仅 Android 12/12L）。
     * 任一不满足都弹窗引导（蓝牙关闭时可一键跳系统蓝牙设置），通过后回调继续。
     */
    private fun ensureBluetoothReady(onReady: () -> Unit) {
        if (!CarLifeLauncher.isAvailable(this)) {
            ConnectionDiagnostics.precheck(DiagnosticCode.CARWITH_FAILED)
            DarkMessageDialog.show(
                this,
                title = getString(R.string.ma_carwith_missing_title),
                message = getString(R.string.ma_carwith_missing_body),
                actions =
                    listOf(
                        DarkMessageDialog.Action(getString(R.string.ma_export_report)) { logExporter.export() },
                        DarkMessageDialog.Action(getString(R.string.action_close)) {},
                    ),
            )
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterBtPermission.set(true)
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        ensureWifiLocationReady {
            val on =
                try {
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                        ?.isEnabled == true
                } catch (_: SecurityException) {
                    true
                }
            if (!on) {
                ConnectionDiagnostics.precheck(DiagnosticCode.BT_DISABLED)
                DarkMessageDialog.show(
                    activity = this,
                    title = getString(R.string.ma_bt_off_title),
                    message = getString(R.string.ma_bt_off_body),
                    actions =
                        listOf(
                            DarkMessageDialog.Action(getString(R.string.action_cancel)) {},
                            DarkMessageDialog.Action(getString(R.string.ma_bt_open_settings), DarkMessageDialog.Emphasis.PRIMARY) {
                                runCatching {
                                    startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
                                    )
                                }.onFailure { log("BT: 无法打开蓝牙设置，请手动开启") }
                            },
                        ),
                )
                log("BT: 蓝牙未开启——本次连接未发起")
                return@ensureWifiLocationReady
            }
            onReady()
        }
    }

    /**
     * 应用专属网络（WifiNetworkSpecifier）在 Android 12/12L 需要位置权限与位置服务；
     * 13+ 由 NEARBY_WIFI_DEVICES 接管、不再要求定位。缺权限时系统会直接
     * `requestNetwork` 失败（表现为「未能加入车机热点」，很难自查），因此前置拦截。
     */
    private fun ensureWifiLocationReady(onReady: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                pendingStartAfterLocationPermission.set(true)
                locationPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                return
            }
            onReady()
            return
        }
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStartAfterLocationPermission.set(true)
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val locationOn =
            try {
                androidx.core.location.LocationManagerCompat
                    .isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
            } catch (_: Exception) {
                true
            }
        if (!locationOn) {
            ConnectionDiagnostics.precheck(DiagnosticCode.LOCATION_REQUIRED)
            DarkMessageDialog.show(
                activity = this,
                title = getString(R.string.ma_location_title),
                message = getString(R.string.ma_location_body),
                actions =
                    listOf(
                        DarkMessageDialog.Action(getString(R.string.action_cancel)) {},
                        DarkMessageDialog.Action(getString(R.string.ma_turn_on), DarkMessageDialog.Emphasis.PRIMARY) {
                            runCatching {
                                startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                )
                            }.onFailure { log("WIFI: 无法打开位置服务设置，请手动开启") }
                        },
                    ),
            )
            log("WIFI: 位置服务未开启——Android 12 下应用专属网络不可用，本次连接未发起")
            return
        }
        onReady()
    }

    /** 位置权限授权后自动继续启动连接（仅 Android 12/12L）。 */
    private val pendingStartAfterLocationPermission =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            log("WIFI: 所需权限授权: $granted")
            if (pendingStartAfterLocationPermission.compareAndSet(true, false)) {
                ConnectionDiagnostics.permissionResult(
                    granted,
                    if (Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.TIRAMISU
                    ) {
                        DiagnosticCode.WIFI_PERMISSION
                    } else {
                        DiagnosticCode.LOCATION_REQUIRED
                    },
                )
                if (granted) resumeConnectionChecks() else pendingDetectionTarget = null
            }
        }

    private val externalHuPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val required = externalHuRequiredPermissions()
            val granted =
                required.all {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }
            log("HU-SIM: 权限结果 ${grants.filterKeys { it in required }}")
            if (granted) {
                ExternalHuService.start(applicationContext)
            } else {
                snack(getString(R.string.ma_hu_permissions))
            }
        }

    /**
     * 「断开连接」确认弹窗（防误触）：断开会立即中断车机画面，
     * 且投屏授权随保活服务停止而失效（下次要车机侧声音需重新授权）。
     */
    private fun promptDisconnect() {
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.ma_disconnect_title),
            message =
                getString(R.string.ma_disconnect_body) +
                    if (CarLifeLauncher.isAvailable(this)) {
                        ""
                    } else {
                        getString(R.string.ma_disconnect_projection_note)
                    },
            actions =
                listOf(
                    DarkMessageDialog.Action(getString(R.string.action_cancel)) {},
                    DarkMessageDialog.Action(getString(R.string.ma_disconnect), DarkMessageDialog.Emphasis.DANGER) {
                        SessionController.disconnect(this)
                    },
                ),
        )
    }

    /**
     * 静默补授予通知使用权（幂等；后台线程）。
     *
     * **不能只在 onCreate 试一次**：那次如果 Shizuku 未就绪就会永久跳过，
     * 用户看到的是「音乐在播、卡片却显示媒体信息未连接」——未授权时
     * `getActiveSessions()` 仍返回会话（播放状态正常）但 `metadata` 恒为 null。
     * 因此在每个「Shizuku 确认就绪」的时机都补调一次；已授权时内部直接返回，
     * 不产生额外 shell 往返。
     */
    private fun ensureNotificationAccessAsync() {
        Bg.run("notification-access") {
            if (ShizukuGate.state(this) != ShizukuGate.State.READY) {
                log("MEDIA: Shizuku 未就绪——暂无法自动授予通知使用权（就绪后会补）")
                return@run
            }
            NotificationAccess.ensureGranted(this, ::log)
        }
    }

    /** Shizuku 是可选辅助能力：只展示安装/启动/授权方式，不阻断连接。 */
    private fun showShizukuGuidance(state: ShizukuGate.State) {
        val guidance = ShizukuGate.guidanceOf(state)
        val actions = mutableListOf<DarkMessageDialog.Action>()
        actions += DarkMessageDialog.Action(getString(R.string.action_close)) {}
        when (state) {
            ShizukuGate.State.NOT_RUNNING ->
                actions +=
                    DarkMessageDialog.Action(getString(R.string.ma_open_shizuku), DarkMessageDialog.Emphasis.PRIMARY) {
                        packageManager.getLaunchIntentForPackage(ShizukuGate.PACKAGE_NAME)?.let {
                            runCatching { startActivity(it) }
                        }
                    }

            ShizukuGate.State.UNAUTHORIZED ->
                actions +=
                    DarkMessageDialog.Action(getString(R.string.ma_request_permission), DarkMessageDialog.Emphasis.PRIMARY) {
                        ShizukuGate.requestPermission()
                    }

            ShizukuGate.State.NOT_INSTALLED, ShizukuGate.State.READY -> Unit
        }
        DarkMessageDialog.show(
            activity = this,
            title = getString(guidance.title),
            message = guidance.message?.let { getString(it) }.orEmpty(),
            actions = actions,
        )
    }

    private fun beginWithAudio(enabled: Boolean) {
        SessionController.audioEnabledForSession = enabled
        // 声音只剩一个模式：通路协商（AAC-LC 优先，不行自动退 PCM）在会话内自动完成，
        // 不再有「兼容档」这种人工分支。
        SessionController.audioPreferCompat = false
        if (!enabled) {
            log("MODE: 本次连接仅推视频（未开启车机侧声音）")
            startConnectionNow()
            return
        }
        if (CarLifeLauncher.isAvailable(this) || CarPlayService.mediaProjection != null) {
            log("MODE: 已选择开启声音（音频来源已就绪）")
            startConnectionNow()
            return
        }
        // 开启声音需要投屏权限（播放捕获的授权来源）：先申请，授权完成后再启动连接
        log("MODE: 开启声音需要投屏权限——先后申请（音频捕获依赖），授权后自动开始连接")
        pendingStartAfterProjection.set(true)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    /**
     * CarWith 入口在 RECORD 之后、应用多半已在后台时才打开：小米未允许「后台弹出界面」时
     * 会被静默拒绝（7240 一直 ECONNREFUSED），故连接前先提示授权，用户仍可选择继续。
     */
    private fun startConnectionNow() {
        runOnUiThread {
            if (needsBtAddressPrompt()) {
                log("IDENTITY: 未获取本机真实蓝牙地址（Shizuku 未就绪且未手动填写）——提示手动填写")
                showBtAddressGuidance()
            } else {
                continueAfterBtIdentityCheck()
            }
        }
    }

    /**
     * 不用 Shizuku 时读不到真实蓝牙地址：WinPlay 等接收端会拒绝占位地址（SETUP 403 / 不邀请）。
     * Shizuku 就绪时连接过程会实时读取，不需要提示；已手动填写或保存过也不提示。
     */
    private fun needsBtAddressPrompt(): Boolean =
        ShizukuGate.state(this) != ShizukuGate.State.READY &&
            DeviceIdentity.peek(this).source == DeviceIdentity.Source.NONE

    private fun showBtAddressGuidance() {
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.ma_bt_address_title),
            message = getString(R.string.ma_bt_address_body),
            actions =
                listOf(
                    DarkMessageDialog.Action(getString(R.string.action_connect_anyway)) { continueAfterBtIdentityCheck() },
                    DarkMessageDialog.Action(getString(R.string.ma_enter_manually), DarkMessageDialog.Emphasis.PRIMARY) {
                        BtIdentityDialog.show(this) {
                            updateReadyRow()
                            // 填好后接着走原来的连接流程；清除或未保存时不自动连接
                            if (DeviceIdentity.peek(this).source != DeviceIdentity.Source.NONE) continueAfterBtIdentityCheck()
                        }
                    },
                ),
        )
    }

    private fun continueAfterBtIdentityCheck() {
        runOnUiThread {
            if (CarLifeLauncher.isAvailable(this) && CarLifeLauncher.backgroundStartAllowed(this) == false) {
                log("CARLIFE: 未允许「后台弹出界面」，CarPlay 会话建立后可能无法打开 CarWith 入口")
                showBackgroundStartGuidance()
            } else {
                continueAfterBackgroundStartCheck()
            }
        }
    }

    /** 本进程已提示过电池优化（「仍然连接」后不再每次连接都打断）。 */
    private var batteryPromptShown = false

    /** 未豁免电池优化时锁屏会被系统冻结断网（见 [BatteryExemption]）：连接前提示一次，用户仍可继续。 */
    private fun continueAfterBackgroundStartCheck() {
        runOnUiThread {
            val exempt = BatteryExemption.isExempt(this)
            if (BatteryExemption.shouldPrompt(exempt, batteryPromptShown)) {
                batteryPromptShown = true
                log("POWER: 未豁免电池优化——锁屏后系统可能冻结 mcarplay 并断网，提示用户设置")
                showBatteryExemptionGuidance()
            } else {
                startConnectionUnchecked()
            }
        }
    }

    private fun showBatteryExemptionGuidance() {
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.ma_battery_title),
            message = getString(R.string.ma_battery_body),
            actions =
                listOf(
                    DarkMessageDialog.Action(getString(R.string.action_connect_anyway)) { startConnectionUnchecked() },
                    DarkMessageDialog.Action(getString(R.string.action_go_grant), DarkMessageDialog.Emphasis.PRIMARY) {
                        if (!BatteryExemption.openSettings(this)) {
                            log("POWER: 无法打开电池优化设置，请到 设置 → 应用 → mcarplay → 省电策略 手动设为无限制")
                        }
                    },
                ),
        )
    }

    private fun showBackgroundStartGuidance() {
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.ma_background_title),
            message = getString(R.string.ma_background_body),
            actions =
                listOf(
                    DarkMessageDialog.Action(getString(R.string.action_connect_anyway)) { continueAfterBackgroundStartCheck() },
                    DarkMessageDialog.Action(getString(R.string.action_go_grant), DarkMessageDialog.Emphasis.PRIMARY) {
                        if (!CarLifeLauncher.openBackgroundStartSettings(this)) {
                            log("CARLIFE: 无法打开权限页，请到 设置 → 应用 → mcarplay → 权限 手动开启")
                        }
                    },
                ),
        )
    }

    /** 「启动连接」主体（权限检查留在这里；会话启动交给 [SessionController]，幂等不双开）。 */
    private fun startConnectionUnchecked() {
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return@runOnUiThread
            }
            // 通知权限（API 33+）：前台服务通知可见；拒绝也不影响保活
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
            val detectionTarget = pendingDetectionTarget
            pendingDetectionTarget = null
            if (detectionTarget != null &&
                (
                    SessionController.isSessionRunning() ||
                        SessionController.sessionStarting.get() ||
                        TargetDevicePrefs.get(this)?.address != detectionTarget.address ||
                        !ReceiverDetectionRuntime.begin(detectionTarget.address)
                )
            ) {
                snack(getString(R.string.ma_detection_not_started))
                return@runOnUiThread
            }
            SessionController.startConnection(this)
            if (detectionTarget != null) showReceiverDetection()
        }
    }

    private fun resumeConnectionChecks() {
        ensureBluetoothReady {
            if (pendingDetectionTarget != null) beginWithAudio(enabled = true) else showAudioChoiceDialog()
        }
    }

    private fun vehiclePresetLabel(preset: VehiclePreset): String =
        getString(
            when (preset) {
                VehiclePreset.AUTO -> R.string.vehicle_preset_auto
                VehiclePreset.GENERIC -> R.string.vehicle_preset_generic
                VehiclePreset.CHERY -> R.string.vehicle_preset_chery
                VehiclePreset.VOLKSWAGEN -> R.string.vehicle_preset_volkswagen
                VehiclePreset.WINPLAY -> R.string.vehicle_preset_winplay
                VehiclePreset.DIPLAY -> R.string.vehicle_preset_diplay
                VehiclePreset.CLASSIC_MIB3 -> R.string.vehicle_preset_classic_mib3
            },
        )

    private fun updateVehiclePresetRow() {
        val preset = VehiclePresetRuntime.current
        findViewById<TextView>(R.id.vehiclePresetDesc).text =
            if (preset == VehiclePreset.AUTO) {
                getString(R.string.main_vehicle_auto)
            } else {
                getString(R.string.main_vehicle_manual, vehiclePresetLabel(preset))
            }
    }

    private fun vehiclePresetDialogTitle(): View {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val title =
            TextView(this).apply {
                setText(R.string.main_vehicle_dialog_title)
                textSize = 20f
                setTextColor(getColor(R.color.m3_on_surface))
            }
        val hint =
            TextView(this).apply {
                setText(R.string.main_vehicle_dialog_hint)
                textSize = 13f
                setTextColor(getColor(R.color.m3_on_var))
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, (8 * density).toInt())
            addView(title)
            addView(hint)
        }
    }

    private fun showVehiclePresetDialog() {
        val presets = VehiclePreset.entries
        val labels = presets.map(::vehiclePresetLabel).toTypedArray()
        var checked = presets.indexOf(VehiclePresetRuntime.current)
        androidx.appcompat.app.AlertDialog
            .Builder(this)
            // 有 setMessage 时 AlertDialog 不显示单选列表，说明文字放进自定义标题
            .setCustomTitle(vehiclePresetDialogTitle())
            .setSingleChoiceItems(labels, checked) { _, which -> checked = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = presets[checked]
                if (chosen != VehiclePresetRuntime.current) {
                    VehiclePresetRuntime.set(this, chosen)
                    SessionController.appendLog("COMPAT: 手动车型适配 → ${chosen.name}")
                    if (SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
                        android.widget.Toast
                            .makeText(this, R.string.main_vehicle_busy, android.widget.Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                updateVehiclePresetRow()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showReceiverDetection() {
        receiverDetectionDialog?.dismiss()
        receiverDetectionDialog =
            ReceiverDetectionDialog.show(
                this,
                connecting = { SessionController.isSessionRunning() || SessionController.sessionStarting.get() },
                onStart = {
                    val target = TargetDevicePrefs.get(this)
                    if (target == null) {
                        targetPickerController.show()
                    } else if (SessionController.isSessionRunning() ||
                        SessionController.sessionStarting.get() ||
                        SessionController.runningDiagnosticCarLifeSession != null
                    ) {
                        snack(getString(R.string.ma_stop_first))
                    } else {
                        pendingDetectionTarget = target
                        resumeConnectionChecks()
                    }
                },
                onRetry = {
                    if (!SessionController.retryReceiverDetection(this)) snack(getString(R.string.ma_retry_not_allowed))
                },
                onCancel = {
                    wifiJoinDialog?.cancel()
                    SessionController.cancelConnecting(this)
                },
                onPickTarget = { targetPickerController.show() },
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 沉浸：顶部让给状态栏，底部手势条的高度加到标签栏上，让标签栏底色一直铺到屏幕底缘
        EdgeToEdge.enable(this)
        val pageColumn = findViewById<View>(R.id.pageColumn)
        val tabBar = findViewById<View>(R.id.tabBar)
        EdgeToEdge.onInsets(findViewById(android.R.id.content)) {
            pageColumn.setPadding(it.left, it.top, it.right, 0)
            tabBar.setPadding(0, 0, 0, it.bottom)
        }
        // 旋转/重建：沿用原倒计时截止时刻（onResume 据此续弹）
        disclaimerDeadlineAt = savedInstanceState?.getLong(KEY_DISCLAIMER_DEADLINE, 0L) ?: 0L

        appVersionFooter = findViewById(R.id.appVersionFooter)
        appVersionFooter.text = "${getString(R.string.app_name)} v${BuildConfig.VERSION_NAME}"
        deviceSubtitle = findViewById(R.id.deviceSubtitle)
        shizukuChip = findViewById(R.id.shizukuChip)
        shizukuDesc = findViewById(R.id.shizukuDesc)
        shizukuBadge = findViewById(R.id.shizukuBadge)
        btIdentityDesc = findViewById(R.id.btIdentityDesc)
        btIdentityBadge = findViewById(R.id.btIdentityBadge)
        heroDot = findViewById(R.id.heroDot)
        heroStateText = findViewById(R.id.heroStateText)
        btnResidualCleanup = findViewById(R.id.btnResidualCleanup)
        btnResidualCleanup.setOnClickListener {
            pendingResidualDisplays?.let { promptResidualDisplays(it) }
        }
        heroSubline = findViewById(R.id.heroSubline)
        heroStats = findViewById(R.id.heroStats)
        heroProgress = findViewById(R.id.heroProgress)
        ctaButton = findViewById(R.id.ctaButton)
        ctaIcon = findViewById(R.id.ctaIcon)
        ctaLabel = findViewById(R.id.ctaLabel)
        audioTag = findViewById(R.id.audioTag)
        readyCount = findViewById(R.id.readyCount)
        setupTabs()
        readyBody = findViewById(R.id.readyBody)
        readyChevron = findViewById(R.id.readyChevron)
        findViewById<View>(R.id.readyHeader).setOnClickListener {
            readyAutoCollapsed = true
            setReadyExpanded(readyBody.visibility != View.VISIBLE)
        }
        ssidInput = findViewById(R.id.ssidInput)
        btDesc = findViewById(R.id.btDesc)
        btBadge = findViewById(R.id.btBadge)
        btnPickTarget = findViewById(R.id.btnPickTarget)
        audioDesc = findViewById(R.id.audioDesc)
        btnAudio = findViewById(R.id.btnAudio)
        carLifeSelfTestDesc = findViewById(R.id.carLifeSelfTestDesc)
        btnCarLifeSelfTest = findViewById(R.id.btnCarLifeSelfTest)
        externalHuDesc = findViewById(R.id.externalHuDesc)
        btnExternalHu = findViewById(R.id.btnExternalHu)
        switchAlwaysOn = findViewById(R.id.switchAlwaysOn)
        switchAutoPlayMusic = findViewById(R.id.switchAutoPlayMusic)
        advBody = findViewById(R.id.advBody)
        advChevron = findViewById(R.id.advChevron)
        shellStateText = findViewById(R.id.shellStateText)
        logBody = findViewById(R.id.logBody)
        logChevron = findViewById(R.id.logChevron)
        switchLog = findViewById(R.id.switchLog)
        logText = findViewById(R.id.logText)
        console = LogConsoleController(this, logText)
        snackbar = findViewById(R.id.snackbar)

        deviceSubtitle.text = deviceName()
        logText.movementMethod = ScrollingMovementMethod()
        // 控制台在长页面 ScrollView 内：按下时申请父级不拦截竖向手势，否则无法手动滚动
        logText.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    v.parent.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP,
                -> console.updateBottomButton()
            }
            false
        }
        findViewById<ImageView>(R.id.btnLogBottom).setOnClickListener {
            // 跳到底部：滚动后自动跟底也随之恢复
            console.scrollToBottom()
        }

        // 进程重启兜底：若上次会话未正常退役，「常亮」改过的屏幕超时需要恢复
        run {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.contains("screen_off_timeout_backup")) {
                val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
                prefs.edit().remove("screen_off_timeout_backup").apply()
                Bg.run("screen-timeout-restore") {
                    Shell.exec(
                        "settings put system screen_off_timeout $backup",
                    )
                }
            }
        }

        // Shizuku 授权结果只刷新辅助能力状态；CarWith 连接不依赖该授权。
        shizukuPermissionListener =
            rikka.shizuku.Shizuku
                .OnRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode != ShizukuGate.PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        log("SHIZUKU: 授权结果 granted=$granted")
                        updateReadyRow()
                        updateShizukuChip()
                        if (granted) {
                            // Shizuku 刚就绪时补一次通知使用权；失败不影响 CarWith 主链路。
                            ensureNotificationAccessAsync()
                        } else {
                            snack(getString(R.string.ma_shizuku_denied))
                        }
                    }
                }.also {
                    runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(it) }
                }

        // 会话中枢（进程级）：登记 applicationContext + 创建会话对象；
        // 挂屏注册：日志续显 + SSID 回填
        SessionController.ensure(this)
        SessionController.logListener = logListener
        // 连接流程的重要提示（WiFi 未开启/热点加入限制等）以 Snackbar 长显
        SessionController.uiHintSink = uiHintSinkRef
        // 加入热点确认弹窗：继续自动加入 / 打开 WLAN 设置手动连。
        // 取消连接时 cancel() 联动关闭（触发 onChoice(null) → 流程检查取消标志中止）
        SessionController.dialogSink = { spec, onChoice ->
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    onChoice(null)
                    return@runOnUiThread
                }
                var answered = false
                // 取消（返回键/点外部/被 cancelConnecting 联动关闭）= null，流程据此中止；
                // 「打开 WLAN」除了回调 false，还要把用户送到系统 WLAN 页手动连。
                val dialog =
                    DarkMessageDialog.show(
                        activity = this,
                        title = spec.title,
                        message = spec.message,
                        actions =
                            listOf(
                                DarkMessageDialog.Action(spec.negative) {
                                    answered = true
                                    runCatching {
                                        startActivity(
                                            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
                                        )
                                    }
                                    onChoice(false)
                                },
                                DarkMessageDialog.Action(
                                    spec.positive,
                                    DarkMessageDialog.Emphasis.PRIMARY,
                                ) {
                                    answered = true
                                    onChoice(true)
                                },
                            ),
                        onCancel = { if (!answered) onChoice(null) },
                    )
                wifiJoinDialog = dialog
            }
        }
        SessionController.ssidSink = ssidSink
        console.render(SessionController.logText())

        setUpUiControls()
        // 以横屏启动时不会触发 onConfigurationChanged，这里按当前方向定一次排布。
        // 首次调用时 contentArea 未测量（width=0，只能退回屏宽估算），
        // 布局完成后再精确重算一次，避免英雄卡宽度与右栏让位差几个像素。
        val startLandscape =
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        applyContentLayout(startLandscape)
        findViewById<android.widget.FrameLayout>(R.id.contentArea).post {
            if (!isFinishing && !isDestroyed) applyContentLayout(startLandscape)
        }

        // 免责声明：未接受当前版本时强制弹（不可取消、需倒计时 + 滑到底）。
        // savedState 非空（旋转/重建）时复用原截止时刻，不重新计时。
        if (savedInstanceState == null &&
            needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            )
        ) {
            showDisclaimer(requireAcceptance = true)
        } else {
            maybeShowWhatsNew()
        }
    }

    /**
     * 覆盖安装后首次打开：按界面语言展示本版本的更新内容（免责声明优先，接受后再弹）。
     * 弹出即记为已看，旋转重建或切换语言不会重复弹。
     */
    private fun maybeShowWhatsNew() {
        if (activeWhatsNew?.isShowing == true || activeDisclaimer?.isShowing == true) return
        val pending = WhatsNew.pending(this) ?: return
        WhatsNew.markShown(this, pending.updateTime)
        activeWhatsNew = WhatsNewDialog.show(this, pending).also { dialog -> dialog.setOnDismissListener { activeWhatsNew = null } }
    }

    /** 界面语言选择：中文 / English，选中后 Activity 由系统按新语言重建。 */
    private fun showLanguagePicker() {
        val current = AppLanguage.current(this)
        val options =
            listOf(
                AppLanguage.Language.ZH to R.string.language_name_zh,
                AppLanguage.Language.EN to R.string.language_name_en,
            )
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.language_title),
            message = getString(R.string.language_note),
            actions =
                options.map { (language, label) ->
                    DarkMessageDialog.Action(
                        if (language == current) "✓ " + getString(label) else getString(label),
                        if (language == current) DarkMessageDialog.Emphasis.PRIMARY else DarkMessageDialog.Emphasis.TEXT,
                    ) {
                        if (language != current) {
                            log("UI: 界面语言切换为 ${language.tag}")
                            AppLanguage.set(this, language)
                        }
                    }
                },
        )
    }

    private fun updateLanguageRow() {
        findViewById<TextView>(R.id.languageDesc).text =
            getString(
                if (AppLanguage.current(this) == AppLanguage.Language.EN) R.string.language_name_en else R.string.language_name_zh,
            )
    }

    /** 底部「免责声明」入口：只读重看（不限制时长、不要求滑到底）。 */
    private fun showDisclaimer(requireAcceptance: Boolean) {
        if (activeDisclaimer?.isShowing == true) return
        if (requireAcceptance && disclaimerDeadlineAt == 0L) {
            disclaimerDeadlineAt = SystemClock.elapsedRealtime() + DisclaimerDialog.COUNTDOWN_MS
        }
        activeDisclaimer =
            DisclaimerDialog
                .show(
                    activity = this,
                    requireAcceptance = requireAcceptance,
                    deadlineAt = disclaimerDeadlineAt,
                    onDecline = {
                        // 放弃使用：结束本任务并退出（不用 System.exit，保留正常生命周期收尾）。
                        // 若已有会话在跑，先正常断开，避免留下无人管理的推流与虚拟屏。
                        runCatching { SessionController.disconnect(this) }
                        finishAndRemoveTask()
                    },
                    onDismiss = {
                        activeDisclaimer = null
                        if (DisclaimerPrefs.hasAcceptedCurrent(this)) maybeShowWhatsNew()
                    },
                )
    }

    /** onCreate 的 UI 初始化尾部：开关/按钮/折叠卡（会话逻辑不变，仅挂新界面）。 */
    private fun setUpUiControls() {
        val preferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        findViewById<View>(R.id.btnCheckUpdates).setOnClickListener {
            startActivity(Intent(this, com.mcarplay.app.update.UpdateActivity::class.java))
        }
        findViewById<View>(R.id.btnBtSnoop).setOnClickListener {
            startActivity(Intent(this, com.mcarplay.app.diagnostics.btsnoop.BtSnoopActivity::class.java))
        }
        findViewById<View>(R.id.btnLanguage).setOnClickListener { showLanguagePicker() }
        updateLanguageRow()
        // 清理旧版本遗留的模式偏好（模式已固定为「隐形屏」启动器）
        if (preferences.contains("mode")) {
            preferences.edit().remove("mode").apply()
        }

        // ---- 免责声明（只读重看）----
        findViewById<LinearLayout>(R.id.btnDisclaimer).setOnClickListener {
            showDisclaimer(requireAcceptance = false)
        }

        // ---- 远程调试入口：仅 remote 调试包存在该组件时显示（正式包 resolve 为 null，恒隐藏；
        // 主代码不引用任何 bridge 类，靠组件探测实现 flavor 差异，调试代码零混入正式产物）----
        val bridgeSetup =
            Intent().setClassName(packageName, "com.mcarplay.app.bridge.BridgeSetupActivity")
        if (packageManager.resolveActivity(bridgeSetup, 0) != null) {
            findViewById<LinearLayout>(R.id.btnBridgeSetup).visibility = View.VISIBLE
            findViewById<LinearLayout>(R.id.btnBridgeSetup).setOnClickListener {
                startActivity(bridgeSetup)
            }
        }

        findViewById<LinearLayout>(R.id.rowBtIdentity).setOnClickListener {
            BtIdentityDialog.show(this) { updateReadyRow() }
        }

        // ---- Shizuku 状态行：辅助能力，不阻止 CarWith 连接 ----
        findViewById<LinearLayout>(R.id.rowShizuku).setOnClickListener {
            val state = ShizukuGate.state(this)
            if (state == ShizukuGate.State.READY) {
                snack(getString(R.string.ma_shizuku_ready_snack))
            } else {
                showShizukuGuidance(state)
            }
        }

        // ---- 常亮+暗幕 ----
        ScreenDimmer.enabled =
            preferences.getBoolean("alwaysOn", true)
        switchAlwaysOn.isChecked = ScreenDimmer.enabled
        switchAlwaysOn.setOnCheckedChangeListener { _, checked ->
            ScreenDimmer.enabled = checked
            preferences.edit().putBoolean("alwaysOn", checked).apply()
            if (checked) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    promptOverlayPermission(getString(R.string.ma_always_on_enabled))
                } else {
                    ScreenDimmer
                        .attach(this)
                }
            } else {
                ScreenDimmer
                    .detach()
            }
            log("MODE: 常亮+暗幕 ${if (checked) "开启" else "关闭"}")
        }

        // ---- 上车自动播放音乐 ----
        switchAutoPlayMusic.isChecked = MediaPrefs.autoPlayOnConnect(this)
        switchAutoPlayMusic.setOnCheckedChangeListener { _, checked ->
            MediaPrefs.setAutoPlayOnConnect(this, checked)
            log("MODE: 上车自动播放音乐 ${if (checked) "开启" else "关闭"}")
        }

        // ---- 英雄卡主操作 ----
        ctaButton.setOnClickListener {
            when (heroState) {
                HeroState.CONNECTED -> promptDisconnect()
                // CarWith 路径不依赖 Shizuku；蓝牙/热点仍是车机 CarPlay 链路的前置
                HeroState.IDLE -> {
                    pendingDetectionTarget = null
                    ensureBluetoothReady { showAudioChoiceDialog() }
                }
                // 连接中可取消：卡在错误网络/等待回连时用户主动脱身（弹窗防误触）
                HeroState.CONNECTING -> promptCancelConnecting()
                null -> {}
            }
        }

        // ---- 连接准备 ----
        findViewById<View>(R.id.rowReceiverDetection).setOnClickListener { showReceiverDetection() }
        findViewById<View>(R.id.rowVehiclePreset).setOnClickListener { showVehiclePresetDialog() }
        updateVehiclePresetRow()
        findViewById<LinearLayout>(R.id.rowCarLifeSelfTest).setOnClickListener {
            toggleCarLifeSelfTest()
        }
        findViewById<LinearLayout>(R.id.rowExternalHu).setOnClickListener {
            toggleExternalHu()
        }
        findViewById<TextView>(R.id.btnCarWithSettings).setOnClickListener {
            if (!CarLifeLauncher.openSettings(this)) snack(getString(R.string.ma_carwith_settings_failed))
        }
        findViewById<TextView>(R.id.btnWifi).setOnClickListener {
            val ssid = ssidInput.text.toString().trim()
            // 自动加入不可用时的手动兜底：密码先进剪贴板，系统 WLAN 页粘贴即可
            val psk = SessionController.lastWifiPsk
            if (psk != null) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("carplay-wifi", psk))
                snack(getString(R.string.ma_psk_copied, psk.length))
                log(
                    "WiFi: 热点密码已复制到剪贴板，选择 \"$ssid\" 粘贴密码；" +
                        "若提示\"无法访问互联网\"请选择保持连接",
                )
            } else {
                snack(getString(R.string.ma_psk_missing))
                log(
                    "WiFi: 尚未拿到热点密码（需蓝牙会话 0x5703）——打开 WLAN 设置手动选择" +
                        if (ssid.isEmpty()) "车机热点（名称未知：车机未经蓝牙下发，请在车机的 WLAN/CarPlay 设置里查看热点名与密码）" else " \"$ssid\"",
                )
            }
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                log("WiFi: 无法打开WLAN设置，请手动前往 设置 > WLAN")
            }
        }

        // 目标车机选择：配对了多台车机时指定唯一目标（只向它发请求/只接受它连入）
        btnPickTarget.setOnClickListener { targetPickerController.show() }

        // 车机侧声音授权（原「申请投屏权限」）：RECORD_AUDIO + MediaProjection 一起请求
        btnAudio.setOnClickListener {
            if (CarLifeLauncher.isAvailable(this)) {
                SessionController.audioEnabledForSession = true
                // 音频 SETUP 是控制通道上的网络请求，不能在主线程发
                Bg.run("audio-native-start") { SessionController.maybeStartAudioNow(applicationContext) }
                snack(getString(R.string.ma_native_audio_requested))
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }

        // ---- 车机显示 ----
        findViewById<LinearLayout>(R.id.rowDim).setOnClickListener {
            if (!ScreenDimmer
                    .isAttached()
            ) {
                snack(getString(R.string.ma_dimmer_not_ready))
                return@setOnClickListener
            }
            ScreenDimmer
                .enterDimNow()
            log("DIMMER: 手动假息屏（点击屏幕恢复）")
        }

        // ---- 折叠卡：高级 / 日志 ----
        bindExpandable(R.id.advHeader, advBody, advChevron)
        bindExpandable(R.id.logHeader, logBody, logChevron)

        // ---- Shell 全局状态 ----
        findViewById<View>(R.id.btnShellRefresh).setOnClickListener { refreshShellState() }
        findViewById<View>(R.id.btnShellReset).setOnClickListener { resetShellState() }
        refreshShellState(startupCheck = true)

        // UI display only; private file diagnostics remain available for support reports.
        var logEnabled = preferences.getBoolean("logEnabled", true)
        SessionController.logEnabled = logEnabled
        switchLog.isChecked = logEnabled
        switchLog.setOnCheckedChangeListener { _, checked ->
            logEnabled = checked
            SessionController.logEnabled = checked
            preferences.edit().putBoolean("logEnabled", checked).apply()
            console.render(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnClearLog).setOnClickListener {
            // 清除缓冲后必须显式重渲染：日志回调是增量追加语义，clearLog 的通知
            // 无法清 UI（见 SessionController.clearLog 注释，「点了没反应」根因）
            SessionController.clearLog()
            console.render(SessionController.logText())
        }
        findViewById<ImageView>(R.id.btnCopyLog).setOnClickListener {
            val text = DiagnosticRedactor(diagnosticNames()).redact(SessionController.logText())
            if (text.isEmpty()) {
                snack(getString(R.string.ma_log_empty))
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ma_log_clip_label), text))
            snack(getString(R.string.ma_log_copied, text.length))
        }
        findViewById<ImageView>(R.id.btnExportLog).setOnClickListener { logExporter.export() }
        findViewById<View>(R.id.btnExportDiagnostics).setOnClickListener { logExporter.export() }
        // Long-press includes the larger retained history; every export still applies redaction.
        findViewById<ImageView>(R.id.btnExportLog).setOnLongClickListener {
            logExporter.export(LogExporter.Scope.ALL)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 通知使用权（媒体会话枚举，音乐卡数据源）：Shizuku 就绪时可静默授予。
        // 幂等可重试——启动时不一定就绪（重启后 Shizuku 未运行、改包名后未授权），
        // 所以 Shizuku 授权成功、连接建立等时机都会再调一次。
        ensureNotificationAccessAsync()

        // ---- 车机显示设置（流画布缩放 / 帧率 / 息屏 / 自动连接 / HID 日志） ----
        findViewById<LinearLayout>(R.id.btnCarSettings).setOnClickListener {
            startActivity(android.content.Intent(this, SettingsContentActivity::class.java))
        }

        // 启动即检查：常亮开 + 无悬浮窗权限 → 主动请求
        if (ScreenDimmer.enabled) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                promptOverlayPermission(getString(R.string.ma_always_on_already))
            } else {
                // attach 只挂触摸探针、不排自动暗幕定时器：自动暗幕/常亮由会话驱动
                // （ScreenDimmer.setSessionActive），否则未连接时也会 45s 后变黑
                // ——用户实测「会话断了还会启动暗幕」即此（2026-10-19）。
                ScreenDimmer
                    .attach(this)
            }
        }

        CarInputClient.logSink = { line -> log(line) }
        MediaSessionsState.logSink = { line -> log(line) }

        log("mcarplay Phase 0 就绪")
        log("真车：与车机蓝牙配对 + 手机加入车机热点 -> 点「启动连接」（自动主动连入车机）")
        log("测试台：两台手机蓝牙配对 -> 备用机跑 xcertplay 无线模式 -> 点「启动连接」等待回连")
    }

    /** 英雄卡分层进度：已过的层着主色，当前层加粗，失败层标红，未到的层置灰。自检轨不显示。 */
    private fun updateConnectionSteps(snapshot: ConnectionTimeline.Snapshot?) {
        val view = findViewById<TextView>(R.id.connectionSteps)
        val stepper = findViewById<StepperView>(R.id.connectionStepper)
        val progress = snapshot?.let(ConnectionProgress::of)
        if (progress == null) {
            view.visibility = View.GONE
            stepper.visibility = View.GONE
            return
        }
        stepper.setSteps(progress.steps)
        stepper.visibility = View.VISIBLE
        val dim = getColor(R.color.m3_outline)
        val text = SpannableStringBuilder()
        DiagnosticText.progressSegments(this, progress).forEachIndexed { index, (label, state) ->
            if (index > 0) {
                val separatorStart = text.length
                text.append(" › ")
                text.setSpan(
                    ForegroundColorSpan(getColor(R.color.m3_outline_var)),
                    separatorStart,
                    text.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            val start = text.length
            text.append(label)
            val color =
                when (state) {
                    ConnectionProgress.StepState.DONE -> getColor(R.color.m3_primary)
                    ConnectionProgress.StepState.ACTIVE -> getColor(R.color.m3_on_surface)
                    ConnectionProgress.StepState.FAILED -> getColor(R.color.m3_error)
                    ConnectionProgress.StepState.STOPPED -> getColor(R.color.m3_on_var)
                    ConnectionProgress.StepState.PENDING -> dim
                }
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (state == ConnectionProgress.StepState.ACTIVE || state == ConnectionProgress.StepState.FAILED) {
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        DiagnosticText.progressDetail(this, progress).takeIf { it.isNotEmpty() }?.let { text.append("\n").append(it) }
        view.text = text
        view.visibility = View.VISIBLE
    }

    private fun bindExpandable(
        headerId: Int,
        body: View,
        chevron: ImageView,
    ) {
        findViewById<View>(headerId).setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            body.visibility = if (opening) View.VISIBLE else View.GONE
            chevron
                .animate()
                .rotation(if (opening) 180f else 0f)
                .setDuration(250L)
                .start()
        }
    }

    // ==================== 英雄卡状态机 ====================

    private fun tickHero() {
        val diagnosis = ConnectionDiagnostics.timeline.snapshot()
        val latest = ConnectionDiagnostics.latestSnapshot()
        // 还没有连接记录时不占首页空间：提示语与导出入口都等有报告后再出现（诊断页另有导出入口）。
        val diagnosticStatus = findViewById<TextView>(R.id.diagnosticStatus)
        diagnosticStatus.text = latest?.let { DiagnosticText.summary(this, it) }.orEmpty()
        val reportVisibility = if (latest == null) View.GONE else View.VISIBLE
        diagnosticStatus.visibility = reportVisibility
        findViewById<View>(R.id.btnExportDiagnostics).visibility = reportVisibility
        updateConnectionSteps(latest)
        val s =
            when {
                SessionController.videoSender?.let { it.isRunning && it.framesSentTotal > 0 } == true -> HeroState.CONNECTED
                SessionController.isSessionRunning() || SessionController.sessionStarting.get() ->
                    HeroState.CONNECTING
                else -> HeroState.IDLE
            }
        if (s != heroState) {
            if (s == HeroState.CONNECTED) connectedAt = SystemClock.elapsedRealtime()
            heroState = s
            applyHeroState(s)
        }
        // 连接中：副标题跟随阶段提示（英雄卡内即可见，不必划到底部看日志）
        if (s == HeroState.CONNECTING) {
            heroSubline.text = SessionController.phaseHint ?: getString(R.string.ma_connecting_hint)
        }
        if (s != HeroState.CONNECTED && diagnosis?.status == "failed") {
            heroStateText.text =
                getString(if (s == HeroState.CONNECTING) R.string.ma_hero_waiting_reconnect else R.string.ma_hero_incomplete)
            heroSubline.text = diagnosis.code?.let { DiagnosticText.advice(this, it) }
        }
        // 启动残留屏检查进行中：状态与副标题临时切换为检查提示
        if (s == HeroState.IDLE && startupCheckRunning) {
            heroStateText.text = getString(R.string.ma_hero_checking)
            heroSubline.text = getString(R.string.ma_hero_checking_residual)
            heroProgress.visibility = View.VISIBLE
        }
        updateConnectedSubline()
        updateHeroStats()
        updateAudioTag()
        updateReadyRow()
        updateShizukuChip()
        updateCarLifeSelfTestRow()
        updateExternalHuRow()
        val selected = TargetDevicePrefs.get(this)
        val binding = ReceiverDetectionRuntime.binding(selected?.address)
        val detectionState =
            ReceiverDetectionRuntime.detection
                .snapshot()
                ?.takeIf { it.binding == binding }
                ?.state
        findViewById<TextView>(R.id.receiverDetectionDesc).text =
            getString(
                when (detectionState) {
                    ReceiverDetection.State.COLLECTING -> R.string.ma_detection_collecting
                    ReceiverDetection.State.SAVING -> R.string.ma_detection_saving
                    ReceiverDetection.State.SAVED -> R.string.ma_detection_saved
                    else ->
                        if (ReceiverDetectionRuntime.saved(selected?.address) != null) {
                            R.string.ma_detection_has_record
                        } else {
                            R.string.main_detection_idle
                        }
                },
            )
    }

    private fun toggleCarLifeSelfTest() {
        val running = SessionController.runningDiagnosticCarLifeSession != null
        if (running) {
            CarPlayService.stopDiagnostic(applicationContext)
            log("CARLIFE: 自检已停止")
        } else {
            if (!CarLifeLauncher.isAvailable(this)) {
                ConnectionDiagnostics.precheck(DiagnosticCode.CARWITH_FAILED, ConnectionDiagnostics.selfTest)
                snack(getString(R.string.ma_carwith_not_installed))
                return
            }
            log("CARLIFE: 用户请求启动 CarWith 出流自检")
            CarPlayService.startDiagnostic(applicationContext)
        }
        updateCarLifeSelfTestRow()
    }

    private fun updateCarLifeSelfTestRow() {
        val running = SessionController.runningDiagnosticCarLifeSession != null
        btnCarLifeSelfTest.text =
            getString(
                if (running) R.string.carlife_self_test_stop else R.string.carlife_self_test_start,
            )
        carLifeSelfTestDesc.text = CarLifeDiagnostics.status(this)
    }

    private fun toggleExternalHu() {
        val snapshot = ExternalHuController.snapshot()
        if (snapshot.running) {
            ExternalHuService.stop(applicationContext)
            log("HU-SIM: 用户停止亿连发现诊断")
        } else {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
                snack(getString(R.string.ma_no_wifi_direct))
                return
            }
            val missing =
                externalHuRequiredPermissions().filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
            if (missing.isNotEmpty()) {
                externalHuPermissionLauncher.launch(missing.toTypedArray())
            } else {
                ExternalHuService.start(applicationContext)
            }
        }
        updateExternalHuRow()
    }

    private fun externalHuRequiredPermissions(): List<String> =
        buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

    private fun updateExternalHuRow() {
        val snapshot = ExternalHuController.snapshot()
        btnExternalHu.text =
            getString(
                if (snapshot.running) R.string.carlife_hu_bridge_stop else R.string.carlife_hu_bridge_start,
            )
        externalHuDesc.text =
            if (!snapshot.running && snapshot.step == ExternalHuController.Step.FAILED) {
                getString(R.string.ma_hu_start_failed, snapshot.detail)
            } else if (!snapshot.running) {
                getString(R.string.carlife_hu_bridge_idle)
            } else {
                val stage =
                    getString(
                        when (snapshot.step) {
                            ExternalHuController.Step.STARTING -> R.string.ma_hu_step_starting
                            ExternalHuController.Step.SDP_LISTENING -> R.string.ma_hu_step_sdp
                            ExternalHuController.Step.HU_CONNECTED -> R.string.ma_hu_step_hu
                            ExternalHuController.Step.P2P_GROUP_READY -> R.string.ma_hu_step_p2p
                            ExternalHuController.Step.MDNS_PUBLISHED -> R.string.ma_hu_step_mdns
                            ExternalHuController.Step.ACCEPTED -> R.string.ma_hu_step_accepted
                            ExternalHuController.Step.FAILED -> R.string.ma_hu_step_failed
                            ExternalHuController.Step.IDLE -> R.string.ma_hu_step_idle
                        },
                    )
                "$stage · ${snapshot.detail}"
            }
    }

    /**
     * 英雄卡统计行（仅连接时显示）：运行时分辨率 + 实际/目标帧率。
     * 实际帧率 = 1s 轮询对已发帧计数取差值；新会话计数归零时先对齐基线。
     */
    private fun updateHeroStats() {
        if (heroState != HeroState.CONNECTED) return
        val sent = SessionController.videoSender?.framesSentTotal ?: 0
        if (sent < lastSentFrames) lastSentFrames = sent
        val fps = sent - lastSentFrames
        lastSentFrames = sent
        heroStats.text =
            getString(R.string.ma_hero_stats, SessionController.activeScalePercent, fps.toInt(), SessionController.activeTargetFps)
    }

    private fun applyHeroState(s: HeroState) {
        heroDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                when (s) {
                    HeroState.IDLE -> getColor(R.color.ui_text_secondary)
                    HeroState.CONNECTING -> getColor(R.color.m3_primary)
                    HeroState.CONNECTED -> getColor(R.color.m3_ok_dot)
                },
            )

        heroStateText.text =
            getString(
                when (s) {
                    HeroState.IDLE -> R.string.main_hero_idle
                    HeroState.CONNECTING -> R.string.ma_hero_connecting
                    HeroState.CONNECTED -> R.string.ma_hero_connected
                },
            )
        heroProgress.visibility = if (s == HeroState.CONNECTING) View.VISIBLE else View.GONE
        heroStats.visibility = if (s == HeroState.CONNECTED) View.VISIBLE else View.GONE

        when (s) {
            HeroState.IDLE -> {
                val pending = pendingResidualDisplays
                heroSubline.text =
                    if (pending != null) {
                        getString(R.string.ma_hero_residual, pending.size)
                    } else {
                        getString(R.string.main_hero_idle_hint)
                    }
                styleCta(R.drawable.bg_cta_fill, R.color.m3_on_primary, R.drawable.ic_play, getString(R.string.main_cta_start))
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTING -> {
                heroSubline.text = SessionController.phaseHint ?: getString(R.string.ma_connecting_hint)
                styleCta(R.drawable.bg_cta_fill, R.color.m3_on_primary, R.drawable.ic_close, getString(R.string.ma_cancel_connect))
                audioTag.visibility = View.GONE
            }
            HeroState.CONNECTED -> {
                styleCta(R.drawable.bg_cta_outline_err, R.color.m3_error, R.drawable.ic_power, getString(R.string.ma_cta_disconnect))
                audioTag.visibility = View.VISIBLE
            }
        }

        ctaIcon.rotation = 0f
    }

    /** 英雄卡主操作按钮统一样式（三个状态仅风格/图标/文案不同）。 */
    private fun styleCta(
        bgRes: Int,
        colorRes: Int,
        iconRes: Int,
        label: String,
    ) {
        ctaButton.setBackgroundResource(bgRes)
        ctaLabel.setTextColor(getColor(colorRes))
        ctaIcon.imageTintList =
            android.content.res.ColorStateList
                .valueOf(getColor(colorRes))
        ctaIcon.setImageResource(iconRes)
        ctaLabel.text = label
        ctaButton.isEnabled = true
    }

    private fun updateConnectedSubline() {
        if (heroState != HeroState.CONNECTED) return
        val elapsed = SystemClock.elapsedRealtime() - connectedAt
        val mm = elapsed / 60_000
        val ss = elapsed % 60_000 / 1_000
        val ssid =
            ssidInput.text
                .toString()
                .trim()
                .ifEmpty { getString(R.string.main_hotspot_title) }
        heroSubline.text = getString(R.string.ma_connected_subline, ssid, mm, ss)
    }

    private fun updateAudioTag() {
        if (heroState != HeroState.CONNECTED) return
        val on =
            SessionController.audioEnabledForSession &&
                SessionController.audioSender?.isRunning == true
        val icon =
            ContextCompat.getDrawable(
                this,
                if (on) R.drawable.ic_music_small else R.drawable.ic_volume_off_small,
            )!!
        DrawableCompat.setTint(icon.mutate(), getColor(if (on) R.color.m3_ok else R.color.m3_outline))
        audioTag.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        audioTag.text = getString(if (on) R.string.main_audio_tag_on else R.string.ma_audio_tag_off)
    }

    /** 连接准备计数：CarWith/CarLife 组件 / 热点 SSID / 蓝牙配对 / 音频来源。 */
    private fun updateReadyRow() {
        var ready = 0
        val shizukuState = ShizukuGate.state(this)
        if (CarLifeLauncher.isAvailable(this)) ready++
        updateShizukuRow(shizukuState)
        updateBtIdentityRow(shizukuState)
        if (ssidInput.text
                .toString()
                .trim()
                .isNotEmpty()
        ) {
            ready++
        }
        val btGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        var bondedCount = -1
        if (btGranted) {
            try {
                val adapter =
                    (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                        .adapter
                bondedCount = adapter?.bondedDevices?.size ?: -1
            } catch (_: SecurityException) {
                bondedCount = -1
            }
        }
        if (bondedCount > 0) ready++
        if (CarLifeLauncher.isAvailable(this) || CarPlayService.mediaProjection != null) ready++

        btBadge.visibility = if (bondedCount >= 0) View.VISIBLE else View.GONE
        if (bondedCount >= 0) {
            val target = TargetDevicePrefs.get(this)
            if (bondedCount > 0) {
                btBadge.background.mutate().setTint(getColor(R.color.m3_ok_dot))
                btBadge.text = getString(R.string.main_badge_ready)
                btDesc.text =
                    if (target != null) {
                        getString(R.string.ma_bt_target, target.name)
                    } else if (bondedCount > 1) {
                        getString(R.string.ma_bt_multi, bondedCount)
                    } else {
                        getString(R.string.ma_bt_single, bondedCount)
                    }
            } else {
                btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                btBadge.text = getString(R.string.ma_badge_unpaired)
                btDesc.text = getString(R.string.ma_bt_none)
            }
            // 目标设备已不在已配对列表（如系统里删了配对）时提示重选
            if (target != null && bondedCount > 0) {
                val stillBonded =
                    try {
                        (getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager)
                            .adapter
                            ?.bondedDevices
                            ?.any { TargetDevicePrefs.matches(target, it.address) } == true
                    } catch (_: SecurityException) {
                        true
                    }
                if (!stillBonded) {
                    btBadge.background.mutate().setTint(getColor(R.color.m3_amber))
                    btBadge.text = getString(R.string.ma_badge_repick)
                    btDesc.text = getString(R.string.ma_bt_target_gone, target.name)
                }
            }
            // 弹窗内可扫描并直接配对未配对设备，因此不再只在「已有配对」时显示：
            // 一台都没配对时，这里正是用户的入口。
            btnPickTarget.visibility = View.VISIBLE
        } else {
            btnPickTarget.visibility = View.GONE
        }
        val projectionReady =
            CarPlayService.mediaProjection != null
        if (CarLifeLauncher.isAvailable(this)) {
            val enabled = SessionController.audioEnabledForSession
            btnAudio.text = getString(if (enabled) R.string.ma_audio_enabled else R.string.ma_audio_on)
            btnAudio.setTextColor(getColor(if (enabled) R.color.m3_ok else R.color.m3_primary))
            audioDesc.text = getString(R.string.ma_audio_carwith_desc)
        } else if (projectionReady) {
            btnAudio.text = getString(R.string.ma_audio_granted)
            btnAudio.setTextColor(getColor(R.color.m3_ok))
            audioDesc.text = getString(R.string.ma_audio_projection_ready)
        } else {
            btnAudio.text = getString(R.string.main_audio_grant)
            btnAudio.setTextColor(getColor(R.color.m3_primary))
            audioDesc.text = getString(R.string.main_audio_desc_needs)
        }
        readyCount.text = "$ready/4"
        if (ready >= 4 && !readyAutoCollapsed) {
            readyAutoCollapsed = true
            setReadyExpanded(false)
        }
    }

    /** 底部标签：0 连接、1 设置、2 诊断。三页是同一个 Activity 里的三个 ScrollView，切换只改可见性。 */
    private var currentTab = 0

    private fun setupTabs() {
        val tabs = intArrayOf(R.id.tabConnect, R.id.tabSettings, R.id.tabDiagnostics)
        tabs.forEachIndexed { index, id -> findViewById<View>(id).setOnClickListener { setTab(index) } }
        setTab(currentTab)
    }

    private fun setTab(index: Int) {
        currentTab = index
        val pages = intArrayOf(R.id.mainScroll, R.id.settingsScroll, R.id.diagnosticsScroll)
        pages.forEachIndexed { i, id -> findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE }
        listOf(R.id.tabConnect, R.id.tabSettings, R.id.tabDiagnostics).forEachIndexed { i, id ->
            findViewById<View>(id).isSelected = i == index
        }
        // 横屏时英雄卡是 contentArea 里的固定层（见 applyContentLayout），只属于连接页
        findViewById<View>(R.id.heroCard).visibility = if (index == 0) View.VISIBLE else View.GONE
    }

    private fun setReadyExpanded(expanded: Boolean) {
        readyBody.visibility = if (expanded) View.VISIBLE else View.GONE
        readyChevron
            .animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(250L)
            .start()
    }

    /** 「本机蓝牙地址」行：只读缓存/本地记录；Shizuku 就绪但尚未读取时在后台读一次再刷新。 */
    private fun updateBtIdentityRow(state: ShizukuGate.State) {
        val resolved = DeviceIdentity.peek(this)
        val ready = resolved.source != DeviceIdentity.Source.NONE
        btIdentityBadge.background.mutate().setTint(getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber))
        btIdentityBadge.setTextColor(getColor(if (ready) R.color.m3_ok else R.color.m3_on_sec_container))
        btIdentityBadge.text = getString(if (ready) R.string.main_badge_ready else R.string.ma_badge_not_set)
        btIdentityDesc.text =
            if (ready) {
                "${resolved.mac} · ${DeviceIdentity.sourceLabel(this, resolved.source)}"
            } else {
                getString(R.string.ma_bt_identity_missing)
            }
        val live = resolved.source == DeviceIdentity.Source.SHIZUKU || resolved.source == DeviceIdentity.Source.SETTINGS
        if (!live && state == ShizukuGate.State.READY && !btIdentityProbeRunning) {
            btIdentityProbeRunning = true
            val app = applicationContext
            Bg.run("bt-identity-probe") {
                try {
                    DeviceIdentity.resolve(app, forceRetry = true)
                } finally {
                    btIdentityProbeRunning = false
                }
                runOnUiThread { if (!isFinishing && !isDestroyed) updateBtIdentityRow(ShizukuGate.state(this)) }
            }
        }
    }

    /** 「连接准备」里的 Shizuku 状态行；这是可选辅助能力，不是连接门槛。 */
    private fun updateShizukuRow(state: ShizukuGate.State) {
        val ready = state == ShizukuGate.State.READY
        shizukuBadge.background.mutate().setTint(
            getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
        )
        shizukuBadge.setTextColor(getColor(if (ready) R.color.m3_ok else R.color.m3_on_sec_container))
        shizukuBadge.text = getString(if (ready) R.string.main_badge_ready else R.string.ma_badge_not_ready)
        shizukuDesc.text =
            getString(
                when (state) {
                    ShizukuGate.State.READY -> R.string.ma_shizuku_ready
                    ShizukuGate.State.NOT_RUNNING -> R.string.ma_shizuku_not_running
                    ShizukuGate.State.UNAUTHORIZED -> R.string.ma_shizuku_unauthorized
                    ShizukuGate.State.NOT_INSTALLED -> R.string.ma_shizuku_not_installed
                },
            )
    }

    /** 设备营销名（ro.product.marketname），取不到时退回 Build.MODEL。 */
    private fun deviceName(): String =
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            (get.invoke(null, "ro.product.marketname") as? String)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (_: Exception) {
            Build.MODEL
        }

    private fun updateShizukuChip() {
        val ready =
            ShizukuGate
                .state(this) ==
                ShizukuGate.State.READY
        shizukuChip.text = getString(if (ready) R.string.main_shizuku_chip_online else R.string.ma_shizuku_chip_off)
        shizukuChip.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
        // 副标题与原型一致：设备名 · Shizuku 状态（点色随状态）
        deviceSubtitle.text = getString(if (ready) R.string.ma_subtitle_shizuku_ready else R.string.ma_subtitle_shizuku_off, deviceName())
        deviceSubtitle.compoundDrawables.getOrNull(0)?.let {
            DrawableCompat.setTint(
                it.mutate(),
                getColor(if (ready) R.color.m3_ok_dot else R.color.m3_amber),
            )
        }
    }

    // ==================== 通用 UI 工具 ====================

    private fun log(message: String) {
        // 写入进程级缓冲（logcat + 文件 + 挂屏渲染均由 SessionController 统一处理）
        SessionController.appendLog(message)
    }

    /** Shell 查询/执行（仅后台线程调用；两个诊断面板共用，勿再各写局部函数）。 */
    private fun shExec(
        cmd: String,
        timeoutMs: Long = 4_000L,
    ): Shell.Result =
        Shell
            .exec(cmd, timeoutMs)

    private fun sh(cmd: String): String = shExec(cmd).output

    private fun diagnosticNames(): List<String> =
        listOfNotNull(
            ssidInput.text?.toString()?.takeIf { it.isNotBlank() },
            SessionController.lastCarWifiSsid,
            TargetDevicePrefs.get(this)?.name,
        )

    private var snackRunnable: Runnable? = null

    /** 自绘 M3 Snackbar（底部反色胶囊，替代 Toast）。 */
    private fun snack(
        message: String,
        durationMs: Long = 2_400L,
    ) {
        snackbar.text = message
        snackbar.visibility = View.VISIBLE
        snackbar.translationY = snackbar.height + 60f
        snackbar
            .animate()
            .translationY(0f)
            .setDuration(260L)
            .start()
        snackRunnable?.let { mainHandler.removeCallbacks(it) }
        snackRunnable =
            Runnable {
                snackbar
                    .animate()
                    .translationY(snackbar.height + 60f)
                    .setDuration(260L)
                    .withEndAction { snackbar.visibility = View.GONE }
                    .start()
            }.also { mainHandler.postDelayed(it, durationMs) }
    }

    /**
     * 解析 dumpsys display 中的 CarPlay 虚拟屏：返回 (displayId, 名称)。
     * 注意 uniqueId 形如 virtual:com.android.shell,2000,CarPlayDock,36——尾数 36 是
     * 代次计数器而非显示屏 ID；真实 ID 在 "displayId=39, uniqueId='…'" 段里。
     */
    private fun parseCarPlayDisplays(dump: String): List<Pair<Int, String>> =
        Regex("displayId=(\\d+), uniqueId='[^']*CarPlay(Dock|AppHi|App|Map)")
            .findAll(dump)
            .map { it.groupValues[1].toInt() to "CarPlay${it.groupValues[2]}" }
            .distinctBy { it.first }
            .toList()

    /** 读取 shell 全局状态（后台线程 + UI 回填）。[startupCheck] 时顺带做残留虚拟屏检测。 */
    private fun refreshShellState(startupCheck: Boolean = false) {
        if (startupCheck) {
            startupCheckRunning = true
            tickHero()
        }
        shellStateText.text = getString(R.string.ma_shell_reading)
        Bg.run("shell-state") {
            if (ShizukuGate
                    .state(this) !=
                ShizukuGate.State.READY
            ) {
                runOnUiThread {
                    startupCheckRunning = false
                    if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                    shellStateText.text = getString(R.string.ma_shell_needs_shizuku)
                }
                return@run
            }

            val letterbox =
                Regex("Aspect ratio:\\s*([0-9.]+)")
                    .find(sh("cmd window get-letterbox-style"))
                    ?.groupValues
                    ?.get(1) ?: "?"
            val forceResizable = sh("settings get global force_resizable_activities").ifBlank { "null" }
            val overlay = sh("settings get global overlay_display_devices").ifBlank { "null" }
            val wmSize = sh("wm size").replace("\n", " / ").ifBlank { "?" }
            val wmDensity = sh("wm density").replace("\n", " / ").ifBlank { "?" }
            val carDisplays = parseCarPlayDisplays(sh("dumpsys display"))
            val displays = carDisplays.joinToString(", ") { "${it.second}(${it.first})" }
            val timeout = sh("settings get system screen_off_timeout").ifBlank { "?" }
            val text =
                buildString {
                    appendLine(getString(R.string.ma_shell_letterbox, letterbox))
                    appendLine(getString(R.string.ma_shell_resizable, forceResizable))
                    appendLine(getString(R.string.ma_shell_overlay, overlay))
                    appendLine("wm size: $wmSize")
                    appendLine("wm density: $wmDensity")
                    appendLine(
                        getString(
                            R.string.ma_shell_displays,
                            displays.trim().replace("\n", ", ").ifEmpty { getString(R.string.ma_shell_none) },
                        ),
                    )
                    appendLine(getString(R.string.ma_shell_timeout, timeout))
                }
            runOnUiThread { shellStateText.text = text.trim() }
            if (startupCheck) {
                var residual = carDisplays.filter { it.first !in currentSessionDisplayIds() }
                if (residual.isNotEmpty()) {
                    log(
                        "SHELL: 启动检测到 ${residual.size} 个残留虚拟屏: " +
                            residual.joinToString(", ") { "${it.second}(${it.first})" },
                    )
                    // 先自动清理一轮（含旧版本 UserService 进程回收——其持有的屏跨进程
                    // 无法 release，只有进程回收能释放；重装 APK 后 Shizuku 按版本复用的
                    // 旧进程正是「点清理释放 0/N」的根因，2026-10-16）。清不掉的再问用户。
                    val released = releaseDisplays(residual.map { it.first })
                    log("SHELL: 启动残留自动清理（释放 $released/${residual.size}）")
                    if (released > 0) {
                        residual =
                            parseCarPlayDisplays(sh("dumpsys display"))
                                .filter { it.first !in currentSessionDisplayIds() }
                    }
                }
                runOnUiThread {
                    startupCheckRunning = false
                    if (residual.isNotEmpty()) {
                        // 用户「暂不」时保留 pending，状态旁的按钮可再次发起清理
                        pendingResidualDisplays = residual
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        promptResidualDisplays(residual)
                        // 自动展开日志卡：启动清理的两条记录直接可见（卡片默认折叠）
                        findViewById<View>(R.id.logBody).visibility = View.VISIBLE
                        findViewById<ImageView>(R.id.logChevron)
                            .animate()
                            .rotation(180f)
                            .setDuration(250L)
                            .start()
                    } else if (heroState == HeroState.IDLE) {
                        // 无残留：恢复「未连接」默认状态展示
                        applyHeroState(HeroState.IDLE)
                    }
                }
            }

            // 预绑定 UserService：预热触摸注入，并触发旧版本残留进程回收（绑定回调内含
            // reapStaleUserServices——重装 APK 后 Shizuku 不回收的旧进程，其持有的虚拟屏
            // 只有进程回收能释放）。**必须晚于上方残留扫描**：回收会让旧进程的虚拟屏立即
            // 消失，若先于扫描发生，残留清单可能变空 → resetAll（强制横屏复原）被跳过。
            CarInputClient.ensure(this)
        }
    }

    /** 当前会话持有的虚拟屏（不参与残留清理）。桌面已移除，本进程不再创建虚拟屏，所以恒为空。 */
    private fun currentSessionDisplayIds(): Set<Int> = emptySet()

    /** 启动检测到残留 CarPlay 虚拟屏：询问是否立即清理（上次会话异常退出遗留）。 */
    private fun promptResidualDisplays(residual: List<Pair<Int, String>>) {
        if (isFinishing || isDestroyed) return
        val names = residual.joinToString("\n") { "· ${it.second}（displayId=${it.first}）" }
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.ma_residual_title),
            message = getString(R.string.ma_residual_body, residual.size, names),
            actions =
                listOf(
                    DarkMessageDialog.Action(getString(R.string.action_not_now)) {},
                    DarkMessageDialog.Action(getString(R.string.ma_clean), DarkMessageDialog.Emphasis.PRIMARY) {
                        pendingResidualDisplays = null
                        updateResidualChip()
                        if (heroState == HeroState.IDLE) applyHeroState(HeroState.IDLE)
                        Bg.run("residual-cleanup") {
                            val released = releaseDisplays(residual.map { it.first })
                            log("SHELL: 残留虚拟屏清理完成（释放 $released/${residual.size}）")
                            runOnUiThread {
                                snack(getString(R.string.ma_residual_released, released, residual.size))
                                refreshShellState()
                            }
                        }
                    },
                ),
            onCancel = {
                // 返回键/点外部关闭 = 暂不：保留英雄卡的「清理残留屏」入口
                updateResidualChip()
            },
        )
    }

    /** 英雄卡状态旁的「清理残留屏」按钮显隐（有待清理残留时出现）。 */
    private fun updateResidualChip() {
        btnResidualCleanup.visibility =
            if (pendingResidualDisplays != null) View.VISIBLE else View.GONE
    }

    /** 释放指定虚拟屏（CarInputClient 懒绑定 + 3 秒短等）。后台线程调用，返回成功数。 */
    private fun releaseDisplays(ids: List<Int>): Int {
        if (ids.isEmpty()) return 0
        var svc = CarInputClient.service
        if (svc == null) {
            CarInputClient
                .ensure(this)
            val deadline = SystemClock.uptimeMillis() + 3_000L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val bound = svc ?: return 0
        ids.forEach { runCatching { bound.releaseDisplay(it) } }
        // 未直接释放的屏极可能由旧版本 UserService 进程持有（releaseDisplay 只查本进程
        // map，跨进程无法释放——重装 APK 后 Shizuku 不回收旧进程的已知问题）。
        // 回收旧进程让其随进程销毁释放，再以实际扫描结果复核（进程回收是异步的）。
        CarInputClient.reapStaleUserServices()
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (true) {
            val alive = parseCarPlayDisplays(sh("dumpsys display")).map { it.first }.toSet()
            val gone = ids.count { it !in alive }
            if (gone >= ids.size || SystemClock.uptimeMillis() >= deadline) return gone
            Thread.sleep(200)
        }
    }

    /** 一键把 shell 全局状态复位为系统默认值（含残留虚拟屏释放，保留当前会话屏）。 */
    private fun resetShellState() {
        shellStateText.text = getString(R.string.ma_shell_resetting)
        Bg.run("shell-reset") {
            if (ShizukuGate
                    .state(this) !=
                ShizukuGate.State.READY
            ) {
                runOnUiThread { shellStateText.text = getString(R.string.ma_shell_reset_needs_shizuku) }
                return@run
            }

            fun sh(cmd: String) =
                Shell
                    .exec(cmd, 5_000L)
            val steps =
                listOf(
                    getString(R.string.ma_shell_step_letterbox) to "cmd window reset-letterbox-style aspectRatio",
                    getString(R.string.ma_shell_step_overlay) to "settings delete global overlay_display_devices",
                    "force_resizable" to "settings delete global force_resizable_activities",
                    "wm size" to "wm size reset",
                    "wm density" to "wm density reset",
                )
            val results =
                steps.map { (name, cmd) ->
                    val r = sh(cmd)
                    if (r.ok) "✓ $name" else "✗ $name（${r.output.take(40)}）"
                }
            // 残留虚拟屏释放：保留当前会话的各屏，其余 CarPlay* 屏逐一释放
            val ids =
                parseCarPlayDisplays(sh("dumpsys display").output)
                    .map { it.first }
                    .filter { it !in currentSessionDisplayIds() }
                    .toList()
            val released = releaseDisplays(ids)
            val summary = results.joinToString("；") + "；残留屏释放 $released/${ids.size}"
            log("SHELL-RESET: $summary")
            runOnUiThread {
                shellStateText.text = getString(R.string.ma_shell_reset_result, results.joinToString("\n"), released, ids.size)
                refreshShellState()
            }
        }
    }

    /** 悬浮窗权限请求：先弹说明弹窗，用户确认后再跳系统设置。 */
    private fun promptOverlayPermission(reason: String) {
        if (android.provider.Settings.canDrawOverlays(this)) return
        DarkMessageDialog.show(
            activity = this,
            title = getString(R.string.ma_overlay_title),
            message = getString(R.string.ma_overlay_body, reason),
            actions =
                listOf(
                    DarkMessageDialog.Action(getString(R.string.action_not_now)) {
                        log("DIMMER: 用户暂未授权悬浮窗（暗幕不可用，常亮仍生效）")
                    },
                    DarkMessageDialog.Action(getString(R.string.action_go_grant), DarkMessageDialog.Emphasis.PRIMARY) {
                        try {
                            startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                ),
                            )
                        } catch (_: Exception) {
                            log("DIMMER: 无法打开授权页，请手动前往 设置>应用>特殊权限>显示在其他应用上层")
                        }
                    },
                ),
        )
    }

    override fun onResume() {
        super.onResume()
        SessionController.onMainUiVisibilityChanged(true)
        mainHandler.post(heroTick)
        // 旋转/重建后尚未接受：用原截止时刻续弹（不重新等 10 秒）
        if (needsDisclaimerAtLaunch(
                DisclaimerPrefs.acceptedVersion(this),
                DisclaimerPrefs.CURRENT_VERSION,
            ) &&
            activeDisclaimer?.isShowing != true
        ) {
            showDisclaimer(requireAcceptance = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_DISCLAIMER_DEADLINE, disclaimerDeadlineAt)
    }

    /**
     * 旋转（已声明 configChanges，Activity 不重建）：视图与所有内存状态原样保留。
     * 需要处理两件事：
     * 1. **内容区切成双栏**——横屏屏高只有 ~300dp，英雄卡单独就占满整屏，
     *    其余内容全被挤到屏幕外（用户实测「要滑动才能看到」）；
     * 2. **重算弹窗窗口**——它们按旧方向的屏宽算过（92%），不重算会只占中间一半。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFinishing || isDestroyed) return
        applyContentLayout(landscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        PanelWindow.resizeShowing(this)
        // 目标车机弹窗不走 DarkMessageDialog，单独重算
        targetPickerController.dialog?.window?.let {
            val width = (resources.displayMetrics.widthPixels * PanelWindow.WIDTH_FRACTION).toInt()
            it.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * 内容区排布：竖屏单列（英雄卡与详情同在一个 ScrollView）⇄
     * 横屏双栏（英雄卡**固定在左侧**，只有右侧详情列滚动）。
     *
     * 横屏为什么要把英雄卡移出 ScrollView：它和详情同属一个滚动容器时，
     * 滑右栏会把英雄卡一起带走（用户实测反馈）。移入 [contentArea] 后它是固定层，
     * 滚动只作用于 mainScroll；右侧内容通过 ScrollView 的左内边距让位。
     *
     * onCreate 也要调用一次——旋转不重建，但**以横屏启动**不会触发
     * onConfigurationChanged。
     */
    private fun applyContentLayout(landscape: Boolean) {
        val contentArea = findViewById<android.widget.FrameLayout>(R.id.contentArea)
        val scroll = findViewById<android.widget.ScrollView>(R.id.mainScroll)
        val row = findViewById<android.widget.LinearLayout>(R.id.contentRow)
        val hero = findViewById<android.widget.LinearLayout>(R.id.heroCard)
        val detail = findViewById<android.widget.LinearLayout>(R.id.detailColumn)
        val density = resources.displayMetrics.density
        val gap = (12 * density).toInt()
        val padTop = ((if (landscape) 12 else 20) * density).toInt()
        val padBottom = ((if (landscape) 12 else 16) * density).toInt()
        hero.setPadding(hero.paddingStart, padTop, hero.paddingEnd, padBottom)
        row.orientation = android.widget.LinearLayout.VERTICAL
        if (landscape) {
            if (hero.parent === row) {
                row.removeView(hero)
                contentArea.addView(hero)
            }
            // 宽度用**屏幕宽减页面内边距**推算，不读 contentArea.width：
            // 旋转瞬间后者还是旧方向的测量值（竖屏 1168 → 英雄卡只有 537px，
            // 副标题被挤成三行，真机截图暴露）。
            val pagePadding =
                (16 * resources.displayMetrics.density * 2).toInt()
            val available =
                (resources.displayMetrics.widthPixels - pagePadding).coerceAtLeast(1)
            val heroWidth = (available * 0.46f).toInt().coerceAtLeast(1)
            // 高度必须 WRAP_CONTENT：FrameLayout 会把 MATCH_PARENT/WRAP 视作铺满，
            // 铺满会把按钮顶到卡片底缘、副标题挤成三行（真机截图暴露）
            hero.layoutParams =
                android.widget.FrameLayout
                    .LayoutParams(
                        heroWidth,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = android.view.Gravity.START or android.view.Gravity.TOP }
            // 右侧内容让出英雄卡宽度（含间距）：滚动条仍在屏幕右缘
            scroll.setPadding(heroWidth + gap, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        } else {
            if (hero.parent === contentArea) {
                contentArea.removeView(hero)
                row.addView(
                    hero,
                    0,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            (hero.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
                topMargin = gap
                marginEnd = 0
            }
            scroll.setPadding(0, scroll.paddingTop, scroll.paddingEnd, scroll.paddingBottom)
            (detail.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                weight = 0f
            }
        }
    }

    override fun onPause() {
        super.onPause()
        SessionController.onMainUiVisibilityChanged(false)
        mainHandler.removeCallbacks(heroTick)
    }

    override fun onStop() {
        super.onStop()
        SessionController.onMainUiStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        receiverDetectionDialog?.dismiss()
        // 强制声明不得随 Activity 销毁残留窗口（旋转时 Activity 重建，onResume 会续弹）
        runCatching { activeDisclaimer?.dismiss() }
        activeDisclaimer = null
        runCatching { activeWhatsNew?.dismiss() }
        activeWhatsNew = null
        shizukuPermissionListener?.let {
            runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(it) }
        }
        shizukuPermissionListener = null
        // 蓝牙扫描/配对监听随界面销毁收尾：Activity 退出后不再需要（也避免广播泄漏）
        targetPickerController.release()
        // 只解绑"挂屏"，不动会话——连接由前台服务保活、对象归 SessionController 持有；
        // 显式断开请用「断开连接」按钮（任意实例都能断到真管线）。
        if (SessionController.logListener === logListener) SessionController.logListener = null
        if (SessionController.uiHintSink === uiHintSinkRef) SessionController.uiHintSink = null
        SessionController.dialogSink = null
        if (SessionController.ssidSink === ssidSink) SessionController.ssidSink = null
    }
}
