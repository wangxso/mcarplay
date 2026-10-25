package com.mcarplay.app.carlife

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Maintains the real P2P group, identity, and lifecycle of the experimental HU endpoint. */
class ExternalHuP2pCoordinator(
    context: Context,
    private val log: (String) -> Unit,
    private val onReady: (ExternalHuP2pReady) -> Unit,
    private val onGroupLost: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val readiness = ExternalHuP2pReadiness()
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var localDevice: WifiP2pDevice? = null
    private var group: WifiP2pGroup? = null
    private var connectionInfo: WifiP2pInfo? = null
    private var creatingGroup = false
    private var ownsGroup = false
    private var ownedNetworkName: String? = null

    @Volatile
    var groupEstablished = false
        private set

    @Volatile
    var identity: ExternalHuDiscoveryProtocol.Identity? = null
        private set

    @Volatile
    var state: String = "idle"
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val token = generation.incrementAndGet()
        val permission = missingWifiPermission()
        val p2pManager = manager
        if (permission != null ||
            p2pManager == null ||
            !appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        ) {
            running.set(false)
            updateState(if (permission != null) "缺少 $permission" else "设备不支持 Wi-Fi Direct")
            return false
        }
        return try {
            channel =
                p2pManager.initialize(appContext, Looper.getMainLooper()) {
                    if (isCurrent(token)) {
                        markGroupLost("P2P 通道断开，准备重试")
                        mainHandler.postDelayed({
                            if (isCurrent(token)) {
                                stop(removeGroup = false)
                                start()
                            }
                        }, RESTART_DELAY_MS)
                    }
                }
            checkNotNull(channel) { "P2P 通道不可用" }
            registerReceiver(token)
            updateState("请求 Wi-Fi Direct 组")
            poll(token)
            true
        } catch (error: Exception) {
            stop()
            updateState("P2P 启动失败: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop(removeGroup: Boolean = true) {
        if (!running.compareAndSet(true, false)) return
        generation.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
        val oldChannel = channel
        channel = null
        val shouldRemove = removeGroup && (ownsGroup || creatingGroup) && missingWifiPermission() == null
        localDevice = null
        group = null
        connectionInfo = null
        creatingGroup = false
        ownsGroup = false
        ownedNetworkName = null
        clearReadiness()
        updateState("stopped")
        if (oldChannel == null) return
        val cleanupHandler = Handler(Looper.getMainLooper())
        val cleanup =
            ExternalHuP2pCleanup(
                scheduleTimeout = { cleanupHandler.postDelayed(it, GROUP_REMOVE_TIMEOUT_MS) },
                cancelTimeout = { cleanupHandler.removeCallbacks(it) },
                closeChannel = { runCatching { oldChannel.close() } },
                onTimeout = { log("HU-P2P: 移组回调超时，关闭通道；P2P 组是否移除仍待设备确认") },
            )
        runCatching {
            if (shouldRemove && manager != null) {
                cleanup.release { finished ->
                    manager.removeGroup(
                        oldChannel,
                        object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                log("HU-P2P: 本轮 P2P 组已移除")
                                finished()
                            }

                            override fun onFailure(reason: Int) {
                                log("HU-P2P: 移组失败 reason=$reason")
                                finished()
                            }
                        },
                    )
                }
            } else {
                cleanup.release()
            }
        }.onFailure { log("HU-P2P: 清理异常 ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun registerReceiver(token: Long) {
        val activeReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (!isCurrent(token)) return
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                            localDevice = intent.value(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                            evaluate()
                        }
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                            observeGroup(intent.value(WifiP2pManager.EXTRA_WIFI_P2P_GROUP))
                            connectionInfo = intent.value(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                            evaluate()
                            requestInfo(token)
                        }
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            val enabled =
                                intent.getIntExtra(
                                    WifiP2pManager.EXTRA_WIFI_STATE,
                                    WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                                ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            if (enabled) requestInfo(token) else markGroupLost("Wi-Fi 已关闭")
                        }
                    }
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(activeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(activeReceiver, filter)
        }
        receiver = activeReceiver
    }

    private fun poll(token: Long) {
        if (!isCurrent(token)) return
        requestInfo(token)
        mainHandler.postDelayed({ poll(token) }, IDENTITY_POLL_MS)
    }

    @SuppressLint("MissingPermission")
    private fun requestInfo(token: Long) {
        val p2pManager = manager ?: return
        val activeChannel = channel ?: return
        if (!isCurrent(token) || missingWifiPermission() != null) return
        runCatching {
            p2pManager.requestDeviceInfo(activeChannel) { device ->
                if (!isCurrent(token)) return@requestDeviceInfo
                localDevice = device
                evaluate()
            }
            p2pManager.requestGroupInfo(activeChannel) { currentGroup ->
                if (!isCurrent(token)) return@requestGroupInfo
                observeGroup(currentGroup)
                evaluate()
                if (currentGroup == null && connectionInfo?.groupFormed != true) createGroup(token)
            }
            p2pManager.requestConnectionInfo(activeChannel) { info ->
                if (!isCurrent(token)) return@requestConnectionInfo
                connectionInfo = info
                evaluate()
            }
        }.onFailure { updateState("读取 P2P 组失败: ${it.message ?: it.javaClass.simpleName}") }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup(token: Long) {
        val p2pManager = manager ?: return
        val activeChannel = channel ?: return
        if (!isCurrent(token) || creatingGroup || missingWifiPermission() != null) return
        creatingGroup = true
        try {
            // The autonomous-group overload makes this device the group owner.
            p2pManager.createGroup(
                activeChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!isCurrent(token)) return
                        creatingGroup = false
                        ownsGroup = true
                        ownedNetworkName = null
                        updateState("P2P 组已创建，等待身份")
                        requestInfo(token)
                    }

                    override fun onFailure(reason: Int) {
                        if (!isCurrent(token)) return
                        creatingGroup = false
                        updateState("创建 P2P 组失败 reason=$reason，等待重试")
                    }
                },
            )
        } catch (error: Exception) {
            creatingGroup = false
            updateState("创建 P2P 组异常: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun evaluate() {
        if (!running.get()) return
        val info = connectionInfo
        val currentGroup = group
        val address = info?.groupOwnerAddress as? Inet4Address
        val candidate =
            if (address != null && localDevice != null) {
                ExternalHuDiscoveryProtocol.Identity(
                    deviceName = localDevice?.deviceName?.trim().orEmpty(),
                    macAddress = localDevice?.deviceAddress?.trim().orEmpty(),
                    interfaceName = ExternalHuP2pInterfaceFinder.find(address).interfaceName.orEmpty(),
                    ipv4Address = address,
                )
            } else {
                null
            }
        val change =
            readiness.update(
                groupFormed = currentGroup != null && info?.groupFormed == true,
                isGroupOwner = currentGroup?.isGroupOwner == true && info?.isGroupOwner == true,
                identity = candidate,
                networkName = currentGroup?.networkName?.trim().orEmpty(),
            )
        identity = readiness.current?.identity
        groupEstablished = readiness.current != null
        val reason =
            if (change.evaluation.reason == "identity-pending") {
                candidate?.unavailableReason() ?: if (localDevice == null) "device-pending" else "ipv4-pending"
            } else {
                change.evaluation.reason
            }
        updateState(
            when (change.evaluation.state) {
                ExternalHuDiscoveryProtocol.GroupState.READY -> "P2P group owner 已就绪"
                ExternalHuDiscoveryProtocol.GroupState.NOT_OWNER -> "本机不是 P2P group owner"
                ExternalHuDiscoveryProtocol.GroupState.FORMING -> "等待 P2P 组与身份：$reason"
            },
        )
        if (change.lost) onGroupLost()
        change.ready?.let(onReady)
    }

    private fun markGroupLost(reason: String) {
        group = null
        connectionInfo = null
        ownsGroup = false
        ownedNetworkName = null
        clearReadiness()
        updateState(reason)
    }

    private fun observeGroup(value: WifiP2pGroup?) {
        if (ownsGroup) {
            if (ownedNetworkName != null && value?.networkName != ownedNetworkName) ownsGroup = false
            ownedNetworkName = value?.networkName.takeIf { ownsGroup }
        }
        group = value
    }

    private fun clearReadiness() {
        groupEstablished = false
        identity = null
        if (readiness.clear()) onGroupLost()
    }

    private fun updateState(value: String) {
        if (state == value) return
        state = value
        log("HU-P2P: $value")
    }

    private fun isCurrent(token: Long): Boolean = running.get() && generation.get() == token

    private fun missingWifiPermission(): String? {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
        return if (ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED) {
            permission.substringAfterLast('.')
        } else {
            null
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> Intent.value(key: String): T? = getParcelableExtra(key)

    private companion object {
        const val IDENTITY_POLL_MS = 1_000L
        const val RESTART_DELAY_MS = 1_000L
        const val GROUP_REMOVE_TIMEOUT_MS = 2_000L
    }
}
