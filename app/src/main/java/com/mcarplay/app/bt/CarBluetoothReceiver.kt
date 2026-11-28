package com.mcarplay.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.mcarplay.app.service.CarPlayService
import com.mcarplay.app.util.DebugFileLog

/**
 * 上车自动连接 / 下车自动收摊。
 *
 * **为什么用清单静态接收器**：`ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` 在
 * 隐式广播豁免清单里（Android 开发者文档「隐式广播例外情况」明确列出），所以即使
 * 应用进程被杀，车机蓝牙连上的瞬间也能把进程拉起来。
 *
 * **为什么能启动前台服务**：Android 12+ 限制后台启动 FGS，豁免清单里**没有**
 * 「蓝牙广播」这一项；本应用能起是因为持有 `SYSTEM_ALERT_WINDOW`（暗幕/触摸探针
 * 依赖它）——它是官方豁免项（targetSdk 34 下只需要权限存在；Android 15+ 才额外
 * 要求当时有可见悬浮窗）。没授悬浮窗权限时 `startForegroundService` 会抛
 * `ForegroundServiceStartNotAllowedException`，这里只记日志——用户手动打开 App
 * 连接一次即可（那次有可见 Activity，不受限制）。
 *
 * **目标过滤**：优先用户选定的目标车机（[TargetDevicePrefs]）；未选定时按蓝牙设备类别
 * （车载音响/免提）与车机名称关键词判定，见 [CarDeviceClassifier]。耳机/手表等 ACL 连接不会误触发。
 *
 * 音频注意：自动连接只推视频——AudioPlaybackCapture 需要 MediaProjection 授权令牌，
 * 系统不允许无令牌静默获取（appops PROJECT_MEDIA 只是其中一道闸，不产生令牌）。
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED &&
            action != BluetoothDevice.ACTION_BOND_STATE_CHANGED
        ) {
            return
        }
        val device = deviceOf(intent) ?: return
        val app = context.applicationContext
        // 这些访问需要 BLUETOOTH_CONNECT；未授权时放弃（无法判断是不是目标车机）
        val address = runCatching { device.address }.getOrNull() ?: return
        if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
            Iap2Pairings.current(app).bondChanged(address, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR))
            return
        }
        // ACL 状态无论是否开启自动连接都要记：出站探测据此在车机离线后停下（见 OutboundProbePolicy）
        BtAclTracker.record(address, action == BluetoothDevice.ACTION_ACL_CONNECTED, SystemClock.elapsedRealtime())
        val autoConnectEnabled = AutoConnectPrefs.enabled(app)
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED && !autoConnectEnabled) return
        val name = runCatching { device.name }.getOrNull()
        val deviceClass = runCatching { device.bluetoothClass?.deviceClass }.getOrNull()

        val target = TargetDevicePrefs.get(app)
        val isCarDevice = CarDeviceClassifier.isCarDevice(target?.address, address, name, deviceClass)
        when (decideAclBroadcastAction(action, autoConnectEnabled, isCarDevice)) {
            AclBroadcastAction.IGNORE -> return
            AclBroadcastAction.IGNORE_NON_CAR_LOG -> {
                DebugFileLog.append(
                    app,
                    "AUTO: 忽略非车机蓝牙 ${name ?: "?"}（$address，class=${CarDeviceClassifier.describeClass(deviceClass)}）",
                )
                return
            }
            AclBroadcastAction.AUTO_START -> {
                if (runCatching { device.bondState }.getOrNull() == BluetoothDevice.BOND_BONDING) {
                    Iap2Pairings.current(app).bondChanged(address, BluetoothDevice.BOND_BONDING)
                }
                if (AutoConnectPrefs.isManualStopCoolingDown(app, address)) {
                    DebugFileLog.append(app, "AUTO: 手动断开后的 30 秒冷却期——忽略蓝牙回连")
                    return
                }
                AutoReconnectGuard.block(app, address)?.let { block ->
                    DebugFileLog.append(
                        app,
                        "AUTO: 连续 ${block.failures} 次会话在出画前被车机断开——暂停自动重连 ${block.remainingSeconds} 秒" +
                            "（可在 App 内手动连接）",
                    )
                    return
                }
                DebugFileLog.append(app, "AUTO: 车机蓝牙已连上 ${name ?: "?"}（$address）——自动拉起会话")
                runCatching {
                    context.startForegroundService(
                        Intent(context, CarPlayService::class.java)
                            .setAction(CarPlayService.ACTION_AUTO_START_SESSION)
                            // 未选定目标车机（自动模式）时，熔断仍按这条 ACL 的对端地址隔离。
                            .putExtra(CarPlayService.EXTRA_PEER_ADDRESS, address),
                    )
                }.onFailure { error ->
                    DebugFileLog.append(
                        app,
                        "AUTO: 自动启动被系统拦截（${error.javaClass.simpleName}: ${error.message}）" +
                            "——请授予「显示在其他应用上层」后重试，或手动打开 App 连接一次",
                    )
                }
            }
            AclBroadcastAction.AUTO_STOP -> {
                DebugFileLog.append(app, "AUTO: 车机蓝牙已断开 ${name ?: "?"}（$address）——检查是否需要结束会话")
                // 会话在跑时服务就是前台进程，startService 允许；不在跑说明本来就没有会话
                runCatching {
                    context.startService(
                        Intent(context, CarPlayService::class.java)
                            .setAction(CarPlayService.ACTION_AUTO_STOP_SESSION)
                            // 只有断开的就是本轮车机时才收摊/记账，见 CarPlayService 的地址校验。
                            .putExtra(CarPlayService.EXTRA_PEER_ADDRESS, address),
                    )
                }
            }
        }
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}

internal enum class AclBroadcastAction {
    IGNORE,
    IGNORE_NON_CAR_LOG,
    AUTO_START,
    AUTO_STOP,
}

/**
 * ACL 广播分流（纯函数，单测钉死）：
 * - 「上车自动连接」只控制 [BluetoothDevice.ACTION_ACL_CONNECTED] 的自动拉起；
 * - 即使关闭了自动连接，手动发起的会话在车机熄火断开蓝牙（[BluetoothDevice.ACTION_ACL_DISCONNECTED]）时
 *   仍须投递 [CarPlayService.ACTION_AUTO_STOP_SESSION]，由前台服务校验本轮地址与媒体状态后收摊。
 */
internal fun decideAclBroadcastAction(
    action: String,
    autoConnectEnabled: Boolean,
    isCarDevice: Boolean,
): AclBroadcastAction =
    when (action) {
        BluetoothDevice.ACTION_ACL_CONNECTED ->
            when {
                !autoConnectEnabled -> AclBroadcastAction.IGNORE
                !isCarDevice -> AclBroadcastAction.IGNORE_NON_CAR_LOG
                else -> AclBroadcastAction.AUTO_START
            }
        BluetoothDevice.ACTION_ACL_DISCONNECTED ->
            when {
                isCarDevice -> AclBroadcastAction.AUTO_STOP
                autoConnectEnabled -> AclBroadcastAction.IGNORE_NON_CAR_LOG
                else -> AclBroadcastAction.IGNORE
            }
        else -> AclBroadcastAction.IGNORE
    }
