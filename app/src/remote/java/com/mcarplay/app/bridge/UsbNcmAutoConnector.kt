package com.mcarplay.app.bridge

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.usb.UsbMediaEndpoint
import com.mcarplay.app.usb.UsbNcmDiscovery
import com.mcarplay.app.util.Bg
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * remote-debug 专用的一键 USB NCM 媒体入口。
 *
 * root shell 只执行打包在本 APK 内的固定脚本；外部输入只有随机 MAC、本进程 PID、由系统
 * `ip` 输出解析出的 `usbX` 与链路本地 IPv6。不会调用车机角色切换，也不会写入 Type-C 角色节点。
 */
object UsbNcmAutoConnector {
    enum class Phase {
        IDLE,
        REQUESTING_ROOT,
        PREPARING_USB,
        FINDING_PEER,
        STARTING_AIRPLAY,
        CONNECTED,
        STOPPING,
        FAILED,
    }

    data class Snapshot(
        val phase: Phase,
        val detail: String,
    )

    private const val REMOTE_SCRIPT = "/data/local/tmp/mcarplay-ncm-bench.sh"
    private const val REMOTE_LOG = "/data/local/tmp/mcarplay-ncm-bench.log"
    private const val LEASE_SECONDS = 1_800
    private val ROOT_SU_CANDIDATES =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
    private val generation = AtomicInteger()
    private val random = SecureRandom()

    @Volatile
    private var snapshot = Snapshot(Phase.IDLE, "尚未连接")

    @Volatile
    private var observer: ((Snapshot) -> Unit)? = null

    fun observe(callback: ((Snapshot) -> Unit)?) {
        observer = callback
        callback?.invoke(snapshot)
    }

    fun snapshotPhase(): Phase = snapshot.phase

    @Synchronized
    fun connect(context: Context) {
        if (snapshot.phase in setOf(Phase.REQUESTING_ROOT, Phase.PREPARING_USB, Phase.FINDING_PEER, Phase.STARTING_AIRPLAY)) {
            return
        }
        if (snapshot.phase == Phase.CONNECTED || SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
            publish(Snapshot(Phase.FAILED, "已有连接或自检正在运行，请先断开"))
            return
        }
        val runId = generation.incrementAndGet()
        val appContext = context.applicationContext
        SessionController.ensure(appContext)
        Bg.run("usb-ncm-connect") {
            var rootStarted = false
            try {
                update(runId, Phase.REQUESTING_ROOT, "等待 root 管理器授权…")
                val identity =
                    runRoot("id -u", 120_000L).also {
                        check(it.exitCode == 0 && it.output.lineSequence().any { line -> line.trim() == "0" }) {
                            "root 授权失败：${it.output.takeLast(180).ifBlank { "su 返回 ${it.exitCode}" }}"
                        }
                    }
                checkActive(runId)
                SessionController.appendLog("USB-BENCH: root 已授权 ${identity.output.takeLast(80)}")

                installScript(appContext, runId)
                runRoot("sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} down", 20_000L)
                checkActive(runId)

                update(runId, Phase.PREPARING_USB, "正在配置 USB NCM，期间 USB ADB 会暂时断开…")
                val token = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                val deviceMac = randomLocalMac()
                val hostMac = randomLocalMac()
                launchRootDaemon(token, deviceMac, hostMac)
                rootStarted = true

                val interfaceInfo = awaitInterface(runId)
                SessionController.appendLog("USB-BENCH: interface=${interfaceInfo.replace('\n', '|').take(240)}")
                val iface = UsbNcmDiscovery.parseInterfaceName(interfaceInfo) ?: error("root 输出中没有有效 USB 网卡名")
                val ifindex = UsbNcmDiscovery.parseIfindex(interfaceInfo) ?: error("无法读取 $iface 的 ifindex")
                val localAddress =
                    UsbNcmDiscovery.parseLocalLinkLocal(interfaceInfo, iface) ?: error("无法读取 $iface 的链路本地 IPv6 地址")
                SessionController.appendLog("USB-BENCH: $iface 已就绪 ifindex=$ifindex，正在发现 Mac 接收端")

                update(runId, Phase.FINDING_PEER, "请在 MacPlay 选择“有线CarPlay”并点击“启动接收”…")
                val peer = awaitPeer(iface, ifindex, localAddress, runId)
                runRoot(
                    "sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} peer ${UsbNcmDiscovery.shellQuote(peer)}",
                    20_000L,
                ).also { check(it.exitCode == 0) { "USB 单目标路由建立失败：${it.output.takeLast(180)}" } }
                checkActive(runId)

                update(runId, Phase.STARTING_AIRPLAY, "已发现 $iface / $peer，正在连接 MacPlay :17000…")
                SessionController.startUsbMediaBench(
                    appContext,
                    UsbMediaEndpoint(iface, peer, 17000, ifindex),
                    cleanup = {
                        stopRootResources()
                        publish(Snapshot(Phase.IDLE, "USB 台架已停止并回滚"))
                    },
                )
                update(runId, Phase.CONNECTED, "$iface → $peer:17000，视频正在建立")
            } catch (error: Throwable) {
                if (generation.get() != runId) {
                    if (rootStarted) runCatching(::stopRootResources)
                    return@run
                }
                if (rootStarted) runCatching(::stopRootResources)
                val detail = error.message?.take(300) ?: error.javaClass.simpleName
                SessionController.appendLog("USB-BENCH: 自动连接失败：$detail")
                publish(Snapshot(Phase.FAILED, detail))
            }
        }
    }

    fun stop(context: Context) {
        generation.incrementAndGet()
        publish(Snapshot(Phase.STOPPING, "正在断开媒体并恢复 Android USB…"))
        if (SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
            SessionController.disconnect(context)
        } else {
            Bg.run("usb-ncm-stop") {
                runCatching(::stopRootResources).onFailure {
                    SessionController.appendLog("USB-BENCH: 停止失败：${it.message}")
                }
                publish(Snapshot(Phase.IDLE, "USB 台架已停止并回滚"))
            }
        }
    }

    private fun installScript(
        context: Context,
        runId: Int,
    ) {
        val local = File(context.cacheDir, "wired_ncm_bench.sh")
        context.resources.openRawResource(R.raw.wired_ncm_bench).bufferedReader().use { reader ->
            local.writeText(reader.readText())
        }
        val result =
            runRoot(
                "cat ${UsbNcmDiscovery.shellQuote(local.absolutePath)} > ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} && " +
                    "chmod 700 ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)}",
                20_000L,
            )
        check(result.exitCode == 0) { "安装 root 台架脚本失败：${result.output.takeLast(180)}" }
        checkActive(runId)
    }

    private fun launchRootDaemon(
        token: String,
        deviceMac: String,
        hostMac: String,
    ) {
        val arguments =
            listOf(
                "setsid",
                "sh",
                REMOTE_SCRIPT,
                "up",
                LEASE_SECONDS.toString(),
                token,
                deviceMac,
                hostMac,
                "",
                Process.myPid().toString(),
                "",
            ).joinToString(" ") { UsbNcmDiscovery.shellQuote(it) }
        val command =
            "command -v setsid >/dev/null && umask 077 && $arguments " +
                "> ${UsbNcmDiscovery.shellQuote(REMOTE_LOG)} 2>&1 < /dev/null &"
        launchDetachedRoot(command)
    }

    private fun awaitInterface(runId: Int): String {
        val command = "sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} wait-iface"
        val result = runRoot(command, 40_000L)
        checkActive(runId)
        check(result.exitCode == 0) { "USB NCM 未在 30 秒内就绪：${result.output.takeLast(240)}" }
        return result.output
    }

    private fun awaitPeer(
        iface: String,
        ifindex: Int,
        localAddress: String,
        runId: Int,
    ): String {
        val address =
            runCatching {
                val parsed = InetAddress.getByName(localAddress)
                check(parsed is Inet6Address) { "$localAddress 不是 IPv6" }
                Inet6Address.getByAddress(null, parsed.address, ifindex)
            }.getOrElse { error ->
                throw IllegalStateException("无法为 $iface 绑定 mDNS 地址：${error.message}", error)
            }
        val resolved = AtomicReference<Inet6Address?>()
        val latch = CountDownLatch(1)
        val serviceType = "_airplay._tcp.local."
        val listener =
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    SessionController.appendLog("USB-BENCH: mDNS 发现 ${event.name}，正在解析")
                    runCatching { event.dns.requestServiceInfo(event.type, event.name) }
                }

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info ?: return
                    val candidate =
                        info.inet6Addresses
                            .firstOrNull { it.isLinkLocalAddress }
                            ?.let(::withInterfaceScope)
                    SessionController.appendLog(
                        "USB-BENCH: mDNS ${event.name} → ${info.hostAddresses.joinToString()} port=${info.port}",
                    )
                    if (info.port == 17000 && candidate != null && resolved.compareAndSet(null, candidate)) {
                        latch.countDown()
                    }
                }

                override fun serviceRemoved(event: ServiceEvent) = Unit

                fun withInterfaceScope(candidate: Inet6Address): Inet6Address = Inet6Address.getByAddress(null, candidate.address, ifindex)
            }
        val mdns =
            try {
                JmDNS.create(address, "mcarplay-usb-$runId")
            } catch (error: Exception) {
                throw IllegalStateException("USB 网卡 mDNS 初始化失败：${error.message}", error)
            }
        try {
            mdns.addServiceListener(serviceType, listener)
            SessionController.appendLog("USB-BENCH: 正在通过 $iface/$localAddress 查询 $serviceType")
            latch.await(18, TimeUnit.SECONDS)
        } finally {
            runCatching { mdns.removeServiceListener(serviceType, listener) }
            runCatching { mdns.close() }
        }
        checkActive(runId)
        return resolved.get()?.hostAddress?.substringBefore('%')
            ?: error("USB 已建立，但 $iface 上没有发现 MacPlay 的 _airplay._tcp（:17000）")
    }

    private fun stopRootResources() {
        runRoot("sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} down", 20_000L)
    }

    private fun randomLocalMac(): String = UsbNcmDiscovery.formatLocalMac(ByteArray(6).also(random::nextBytes))

    private fun checkActive(runId: Int) {
        check(generation.get() == runId) { "连接已取消" }
    }

    private fun update(
        runId: Int,
        phase: Phase,
        detail: String,
    ) {
        if (generation.get() != runId) return
        SessionController.appendLog("USB-BENCH: $detail")
        publish(Snapshot(phase, detail))
    }

    private fun publish(next: Snapshot) {
        snapshot = next
        observer?.invoke(next)
    }

    private data class RootResult(
        val exitCode: Int,
        val output: String,
    )

    private fun runRoot(
        command: String,
        timeoutMs: Long,
    ): RootResult {
        val process =
            try {
                startRootProcess(command)
            } catch (error: Exception) {
                return RootResult(-1, "无法启动 su：${error.message ?: error.javaClass.simpleName}")
            }
        process.outputStream.close()
        val output = ByteArrayOutputStream()
        val reader =
            Thread({
                runCatching { process.inputStream.use { it.copyTo(output) } }
            }, "usb-ncm-root-reader").apply { start() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(1_000L)
        val code =
            if (finished) {
                process.exitValue()
            } else {
                -1
            }
        return RootResult(code, output.toString(Charsets.UTF_8.name()).trim())
    }

    private fun launchDetachedRoot(command: String) {
        val process =
            try {
                startRootProcess(command)
            } catch (error: Exception) {
                throw IllegalStateException("无法启动 root 台架：${error.message}", error)
            }
        process.outputStream.close()
        Thread({
            runCatching { process.inputStream.use { it.copyTo(ByteArrayOutputStream()) } }
        }, "usb-ncm-launcher-reader").apply { start() }
        // Magisk 个别版本会等 setsid 子进程退出；确认脚本开始执行后不再持有该 su 进程。
        val deadline = SystemClock.uptimeMillis() + 2_000L
        while (SystemClock.uptimeMillis() < deadline && process.isAlive) {
            if (File(REMOTE_LOG).exists()) return
            Thread.sleep(50)
        }
    }

    /**
     * Android 的 PATH 搜索会把 EACCES 暴露成 ProcessBuilder 启动失败，即使同一个 `su`
     * 用绝对路径可以由 KernelSU 正常拦截并授权。始终先执行系统路径。
     */
    private fun startRootProcess(command: String): java.lang.Process {
        var lastError: Exception? = null
        for (candidate in ROOT_SU_CANDIDATES) {
            try {
                return ProcessBuilder(candidate, "-c", command).redirectErrorStream(true).start()
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw IllegalStateException(
            "系统中未找到可执行的 su（${ROOT_SU_CANDIDATES.joinToString()}）",
            lastError,
        )
    }
}
