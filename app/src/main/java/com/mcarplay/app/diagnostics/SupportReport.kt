package com.mcarplay.app.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.mcarplay.app.BuildConfig
import com.mcarplay.app.carlife.CarLifeLauncher
import com.mcarplay.app.compat.ReceiverDetectionRuntime
import com.mcarplay.app.util.DebugFileLog
import com.mcarplay.app.util.DeviceIdentity
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.TimeZone

object SupportReport {
    fun create(
        context: Context,
        reportId: String,
        scope: String,
        currentSessionOnly: Boolean,
        fallback: String,
        maxLogBytes: Int = 512_000,
        knownNames: List<String> = emptyList(),
    ): String {
        val log = DebugFileLog.readRecent(context, maxLogBytes, currentSessionOnly).orEmpty()
        val summary =
            listOfNotNull(
                ConnectionDiagnostics.timeline.snapshot()?.let { "CarPlay 连接：" + exportSummary(it) },
                ConnectionDiagnostics.selfTest.snapshot()?.let { "CarWith 自检：" + exportSummary(it) },
                "RECEIVER_DETECTION " + ReceiverDetectionRuntime.reportSummary(),
            ).joinToString("\n\n").ifEmpty { "本进程尚无连接尝试；请复现问题后再导出" }
        return render(
            reportId = reportId,
            environment = environment(context),
            summary = summary,
            journal = ConnectionDiagnostics.history(),
            log = if (log.isBlank()) "[file log unavailable; using UI fallback]\n$fallback" else log,
            exitInfo = exitInfo(context),
            scope = scope,
            storage = ConnectionDiagnostics.storageStatus(),
            knownNames = knownNames,
        )
    }

    private fun exportSummary(state: ConnectionTimeline.Snapshot): String =
        state.summary() + "\nEXPORT_SNAPSHOT sourceVideoFrames=${state.sourceFrames} videoFramesSent=${state.videoFrames} " +
            "nativeAudioChunks=${state.audioChunks} audioPacketsSent=${state.audioPackets} lastVideoMsAgo=${state.lastVideoMsAgo}"

    internal fun render(
        reportId: String,
        environment: Map<String, String>,
        summary: String,
        journal: String,
        log: String,
        exitInfo: String,
        scope: String,
        storage: String,
        knownNames: List<String> = emptyList(),
    ): String {
        val redactor = DiagnosticRedactor(knownNames)
        val safeLog = redactor.redact(log)
        val safeExits = redactor.redact(exitInfo)
        val safeJournal =
            journal.lineSequence().joinToString("\n") { line ->
                if (!line.startsWith('{')) {
                    redactor.redact(line)
                } else {
                    runCatching {
                        val record = JSONObject(line)
                        record.keys().asSequence().toList().forEach { key ->
                            val value = record.opt(key)
                            if (value is String) record.put(key, redactor.redact(value))
                        }
                        record.toString()
                    }.getOrElse { "[unreadable diagnostic event omitted]" }
                }
            }
        val untrusted =
            buildString {
                appendLine("=== CONNECTION SUMMARY / 连接摘要 ===")
                appendLine(redactor.redact(summary))
                appendLine("=== CONNECTION TIMELINE / 最近进程与连接事件 (JSONL schema=1) ===")
                appendLine(safeJournal)
                appendLine("=== SYSTEM EXIT HISTORY / 本应用系统退出记录 ===")
                appendLine(safeExits)
                appendLine("=== RUNTIME LOG / 运行日志 ===")
                appendLine(safeLog)
            }
        return buildString {
            appendLine("mcarplay support report / 诊断报告 schema=1")
            appendLine("reportId=$reportId")
            appendLine("generatedAt=${Instant.now()} timezone=${TimeZone.getDefault().id}")
            appendLine("runtimeScope=$scope; timeline includes recent previous processes")
            appendLine("$storage; UI log display does not disable file diagnostics")
            appendLine("PRIVACY: addresses/names are report-local aliases; credentials and wire payloads are omitted.")
            appendLine("No automatic upload. Review before sharing; redaction cannot guarantee removal of arbitrary user text.")
            appendLine("请同时提供：复现步骤、故障时间、接收端品牌/型号/固件、屏幕提示，以及是否有声/有画面/能触控。")
            appendLine("缺少 END 只代表记录中断，不足以认定崩溃；无系统退出记录也不等于从未退出。")
            appendLine(
                "sourceVideoFrames/native audio counters are input; videoFrames/audioPacketsSent are transport writes, not proof of display or sound.",
            )
            appendLine("=== ENVIRONMENT / 导出时环境（不等于故障发生时状态） ===")
            environment.toSortedMap().forEach { (key, value) -> appendLine("$key=$value") }
            append(untrusted)
            appendLine()
        }
    }

    @Suppress("DEPRECATION")
    private fun environment(context: Context): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("app.version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            put("app.variant", "${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE}")
            put("app.process", ConnectionDiagnostics.processId)
            put(
                "app.apk.sha256",
                value {
                    val digest = MessageDigest.getInstance("SHA-256")
                    File(context.applicationInfo.sourceDir).inputStream().use { input ->
                        val buffer = ByteArray(32_768)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                },
            )
            put(
                "app.signing.sha256",
                value {
                    context.packageManager
                        .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo
                        ?.apkContentsSigners
                        ?.joinToString(",") { digest(it.toByteArray()) } ?: "unknown"
                },
            )
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("system", "Android ${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} build=${Build.DISPLAY}")
            put("system.uptimeMs", SystemClock.elapsedRealtime().toString())
            put("carwith.version", packageVersion(context, CarLifeLauncher.CARWITH_PACKAGE))
            put("carlife.version", packageVersion(context, CarLifeLauncher.CARLIFE_PACKAGE))
            // 只记来源（Shizuku/系统设置/手动/已保存/未获取），不记地址本身
            put("bluetooth.identitySource", value { DeviceIdentity.peek(context).source.name })
            put(
                "carwith.entry",
                value {
                    val info =
                        context.packageManager.getActivityInfo(
                            ComponentName(CarLifeLauncher.CARWITH_PACKAGE, CarLifeLauncher.CARWITH_ENTRY),
                            0,
                        )
                    "exported=${info.exported} enabled=${info.enabled}"
                },
            )
            val permissions =
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                ) +
                    if (Build.VERSION.SDK_INT >= 33) {
                        listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        emptyList()
                    }
            permissions.forEach { permission ->
                put(
                    "permission.${permission.substringAfterLast('.')}",
                    (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED).toString(),
                )
            }
            put(
                "bluetooth.enabled",
                value {
                    context
                        .getSystemService(BluetoothManager::class.java)
                        ?.adapter
                        ?.isEnabled
                        .toString()
                },
            )
            put(
                "wifi.enabled",
                value {
                    context.applicationContext
                        .getSystemService(WifiManager::class.java)
                        ?.isWifiEnabled
                        .toString()
                },
            )
            put("network.active", value { network(context, false) })
            put("network.bound", value { network(context, true) })
            put("network.note", "Unvalidated local car Wi-Fi is not by itself an error")
            val runtime = Runtime.getRuntime()
            put("memory.javaUsedBytes", (runtime.totalMemory() - runtime.freeMemory()).toString())
            put("memory.javaMaxBytes", runtime.maxMemory().toString())
            put("threads", Thread.getAllStackTraces().size.toString())
        }

    private fun network(
        context: Context,
        bound: Boolean,
    ): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unavailable"
        val network = (if (bound) manager.boundNetworkForProcess else manager.activeNetwork) ?: return "none"
        val caps = manager.getNetworkCapabilities(network) ?: return "unknown"
        val transports =
            listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
                NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
                NetworkCapabilities.TRANSPORT_VPN to "VPN",
            ).filter { caps.hasTransport(it.first) }.joinToString(",") { it.second }
        return "$transports validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    @Suppress("DEPRECATION")
    private fun packageVersion(
        context: Context,
        name: String,
    ): String =
        value {
            context.packageManager.getPackageInfo(name, 0).let { "${it.versionName} (${it.longVersionCode})" }
        }

    private fun exitInfo(context: Context): String =
        value {
            val manager = context.getSystemService(ActivityManager::class.java)
            val records = manager?.getHistoricalProcessExitReasons(context.packageName, 0, 5).orEmpty()
            if (records.isEmpty()) return@value "[no exit records available; not proof of no previous termination]"
            buildString {
                records.forEach { exit ->
                    appendLine(
                        "time=${Instant.ofEpochMilli(exit.timestamp)} reason=${exit.reason}(${exitReason(exit.reason)}) " +
                            "status=${exit.status} " +
                            "importance=${exit.importance} pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description}",
                    )
                }
                val newest =
                    records.firstOrNull {
                        it.reason in
                            listOf(
                                ApplicationExitInfo.REASON_ANR,
                                ApplicationExitInfo.REASON_CRASH,
                                ApplicationExitInfo.REASON_CRASH_NATIVE,
                            )
                    } ?: records.first()
                runCatching {
                    newest.traceInputStream?.use { input ->
                        val bytes = ByteArray(64_000)
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = input.read(bytes, offset, bytes.size - offset)
                            if (count <= 0) break
                            offset += count
                        }
                        appendLine("[exit trace for ${Instant.ofEpochMilli(newest.timestamp)}, at most 64000 bytes]")
                        appendLine(String(bytes, 0, offset, Charsets.UTF_8))
                    }
                }.onFailure { appendLine("[exit trace unavailable: ${it.javaClass.simpleName}]") }
            }
        }

    private fun exitReason(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            else -> "OTHER_SYSTEM_REASON"
        }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun value(block: () -> String): String = runCatching(block).getOrElse { "unavailable:${it.javaClass.simpleName}" }
}
