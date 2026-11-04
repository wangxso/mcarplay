package com.mcarplay.app.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import com.mcarplay.app.SessionController
import com.mcarplay.app.bt.TargetDevicePrefs
import com.mcarplay.app.carlife.CarLifeLauncher
import com.mcarplay.app.diagnostics.DiagnosticExportPolicy
import com.mcarplay.app.util.DeviceIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Collections

/** 只在 remote/debug 导出时由后台线程调用，全部是可读取的连接/组件/本进程信息。 */
object RemoteDiagnosticCollector {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun snapshot(context: Context): Map<String, String> {
        if (!DiagnosticExportPolicy.unredacted) return emptyMap()
        val result = linkedMapOf<String, String>()

        fun record(
            key: String,
            read: () -> Any?,
        ) {
            result["raw.$key"] =
                try {
                    val text = read()?.toString() ?: "unavailable"
                    if (text.length > 64_000) result["raw.$key.truncated"] = "true"
                    text.take(64_000)
                } catch (error: Exception) {
                    "unavailable:${error.javaClass.name}: ${error.message}"
                }
        }
        record("build") {
            JSONObject()
                .put("fingerprint", Build.FINGERPRINT)
                .put("device", Build.DEVICE)
                .put("product", Build.PRODUCT)
                .put("board", Build.BOARD)
                .put("hardware", Build.HARDWARE)
                .put("incremental", Build.VERSION.INCREMENTAL)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
        }
        record("bluetooth.identity") {
            val identity = DeviceIdentity.peek(context)
            JSONObject().put("source", identity.source.name).put("value", identity.mac)
        }
        record("bluetooth.target") {
            TargetDevicePrefs.get(context)?.let { JSONObject().put("name", it.name).put("address", it.address) }
        }
        record("bluetooth.adapter") {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            JSONObject()
                .put("name", adapter?.name)
                .put("address", adapter?.address)
                .put("state", adapter?.state)
                .put("scanMode", adapter?.scanMode)
                .put("discovering", adapter?.isDiscovering)
        }
        record("bluetooth.bonded") {
            val devices =
                context
                    .getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.bondedDevices
                    .orEmpty()
            JSONArray(
                devices.take(64).map { device ->
                    JSONObject()
                        .put("name", device.name)
                        .put("address", device.address)
                        .put("bondState", device.bondState)
                        .put("deviceClass", device.bluetoothClass?.deviceClass)
                        .put("cachedUuids", JSONArray(device.uuids?.map { it.uuid.toString() }.orEmpty()))
                },
            )
        }
        record("wifi.current") {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
            JSONObject()
                .put("ssid", wifi?.ssid)
                .put("bssid", wifi?.bssid)
                .put("macAddress", wifi?.macAddress)
                .put("networkId", wifi?.networkId)
                .put("rssi", wifi?.rssi)
                .put("frequency", wifi?.frequency)
                .put("linkSpeed", wifi?.linkSpeed)
                .put("supplicantState", wifi?.supplicantState?.name)
        }
        record("wifi.lastReceiverSsid") { SessionController.lastCarWifiSsid }
        record("networks") {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            JSONArray(
                manager
                    ?.allNetworks
                    ?.take(32)
                    ?.map { network ->
                        val links = manager.getLinkProperties(network)
                        JSONObject()
                            .put("handle", network.toString())
                            .put("active", network == manager.activeNetwork)
                            .put("bound", network == manager.boundNetworkForProcess)
                            .put("capabilities", manager.getNetworkCapabilities(network)?.toString())
                            .put("interface", links?.interfaceName)
                            .put("addresses", JSONArray(links?.linkAddresses?.map { it.toString() }.orEmpty()))
                            .put("dns", JSONArray(links?.dnsServers?.map { it.hostAddress }.orEmpty()))
                            .put("routes", JSONArray(links?.routes?.map { it.toString() }.orEmpty()))
                            .put("domains", links?.domains)
                    }.orEmpty(),
            )
        }
        record("interfaces") {
            JSONArray(
                NetworkInterface
                    .getNetworkInterfaces()
                    ?.let { Collections.list(it) }
                    ?.take(32)
                    ?.map { network ->
                        JSONObject()
                            .put("name", network.name)
                            .put("up", network.isUp)
                            .put("loopback", network.isLoopback)
                            .put("multicast", network.supportsMulticast())
                            .put("addresses", JSONArray(Collections.list(network.inetAddresses).map { it.hostAddress }))
                    }.orEmpty(),
            )
        }
        for (packageName in listOf(context.packageName, CarLifeLauncher.CARWITH_PACKAGE, CarLifeLauncher.CARLIFE_PACKAGE)) {
            record("package.$packageName") {
                val info =
                    context.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                JSONObject()
                    .put("versionName", info.versionName)
                    .put("versionCode", info.longVersionCode)
                    .put("uid", info.applicationInfo?.uid)
                    .put("flags", info.applicationInfo?.flags)
                    .put("sourceDir", info.applicationInfo?.sourceDir)
                    .put("nativeLibraryDir", info.applicationInfo?.nativeLibraryDir)
                    .put(
                        "signingSha256",
                        JSONArray(
                            info.signingInfo
                                ?.apkContentsSigners
                                ?.map { signature -> sha256(signature.toByteArray()) }
                                .orEmpty(),
                        ),
                    ).put(
                        "permissions",
                        JSONArray(
                            info.requestedPermissions.orEmpty().mapIndexed { index, permission ->
                                val granted =
                                    context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
                                JSONObject()
                                    .put("permission", permission)
                                    .put("flags", info.requestedPermissionsFlags?.getOrNull(index))
                                    .put("granted", granted)
                            },
                        ),
                    ).put(
                        "services",
                        JSONArray(
                            info.services.orEmpty().take(96).map { service ->
                                JSONObject()
                                    .put("class", service.name)
                                    .put("process", service.processName)
                                    .put("enabled", service.enabled)
                                    .put("exported", service.exported)
                                    .put("permission", service.permission)
                            },
                        ),
                    )
            }
        }
        record("carwith.entryEnabledSetting") {
            context.packageManager.getComponentEnabledSetting(ComponentName(CarLifeLauncher.CARWITH_PACKAGE, CarLifeLauncher.CARWITH_ENTRY))
        }
        record("threads") {
            JSONArray(
                Thread.getAllStackTraces().entries.take(96).map { (thread, stack) ->
                    JSONObject()
                        .put("name", thread.name)
                        .put("state", thread.state.name)
                        .put("stack", JSONArray(stack.take(24).map { it.toString() }))
                },
            )
        }
        return result
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
