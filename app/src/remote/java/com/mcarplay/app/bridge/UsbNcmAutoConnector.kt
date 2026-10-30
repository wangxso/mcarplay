package com.mcarplay.app.bridge

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.mcarplay.app.R
import com.mcarplay.app.SessionController
import com.mcarplay.app.usb.UsbMediaEndpoint
import com.mcarplay.app.usb.UsbNcmDiscovery
import com.mcarplay.app.usb.UsbNcmIdentityMode
import com.mcarplay.app.usb.WiredCarPlayStartFile
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
 * remote-debug 专用的一键 USB 有线 CarPlay 入口。
 *
 * root shell 只执行打包在本 APK 内的固定脚本；外部输入只有随机 MAC、本进程 PID、由系统
 * `ip` 输出解析出的 `usbX` 与链路本地 IPv6。台架提供 NCM、最小 usbmux/lockdown/carkit
 * 和既有 AirPlay 媒体发送端，不会调用车机角色切换，也不会写入 Type-C 角色节点。
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

    private data class PeerResult(
        val addresses: List<String>,
        val port: Int,
        val source: String,
    )

    private const val REMOTE_SCRIPT = "/data/local/tmp/mcarplay-ncm-bench.sh"
    private const val REMOTE_LOG = "/data/local/tmp/mcarplay-ncm-bench.log"
    private const val IDENTITY_PREFS = "usb_ncm_identity"
    private const val PSEUDO_SERIAL_KEY = "pseudo_serial_v1"

    /**
     * root 脚本的兜底期限。一键入口的真实回滚条件是 App 进程退出（脚本按 PID 监视）与会话结束
     * （SessionController 在推流结束或建立失败时调用 cleanup）；期限只防止 App 存活但失控，
     * 不能短到截断正在进行的会话（此前 30 分钟会在推流中途拆掉 gadget）。
     */
    private const val LEASE_SECONDS = 86_400
    private const val START_SESSION_WAIT_MILLIS = 45_000L
    private const val START_SESSION_POLL_MILLIS = 500L
    private val ROOT_SU_CANDIDATES =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
    private val PSEUDO_SERIAL = Regex("[0-9a-f]{40}")
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
    fun connect(
        context: Context,
        enableAudio: Boolean = true,
        manualHost: String? = null,
        preferIphoneIdentity: Boolean = true,
    ) {
        if (
            snapshot.phase in
            setOf(
                Phase.REQUESTING_ROOT,
                Phase.PREPARING_USB,
                Phase.FINDING_PEER,
                Phase.STARTING_AIRPLAY,
                Phase.STOPPING,
            )
        ) {
            return
        }
        if (snapshot.phase == Phase.CONNECTED || SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
            publish(Snapshot(Phase.FAILED, "已有连接或自检正在运行，请先断开"))
            return
        }
        val runId = generation.incrementAndGet()
        val appContext = context.applicationContext
        val directHost =
            manualHost
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { UsbNcmDiscovery.normalizePeerAddress(it) }
        if (manualHost?.isNotBlank() == true && directHost == null) {
            publish(Snapshot(Phase.FAILED, "直连地址无效"))
            return
        }
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

                val identityAttempts = UsbNcmIdentityMode.attempts(preferIphoneIdentity)
                val token = stablePseudoSerial(appContext)
                val deviceMac = randomLocalMac()
                val hostMac = randomLocalMac()
                var boundInterfaceInfo: String? = null
                var identityMode = UsbNcmIdentityMode.GENERIC
                for ((index, mode) in identityAttempts.withIndex()) {
                    if (index > 0) {
                        update(runId, Phase.PREPARING_USB, "${identityAttempts[index - 1].displayName}未就绪，正在回退到${mode.displayName}…")
                        stopRootResources()
                    } else {
                        update(
                            runId,
                            Phase.PREPARING_USB,
                            "正在配置 USB 有线 CarPlay（${mode.displayName}），期间 USB ADB 会暂时断开…",
                        )
                    }
                    launchRootDaemon(token, deviceMac, hostMac, mode)
                    rootStarted = true
                    val bound =
                        runCatching { awaitInterface(runId) }
                            .getOrElse { error ->
                                SessionController.appendLog(
                                    "USB-BENCH: ${mode.displayName}失败：${error.message ?: error.javaClass.simpleName}",
                                )
                                null
                            }
                    if (bound != null) {
                        boundInterfaceInfo = bound
                        identityMode = mode
                        break
                    }
                    if (index == identityAttempts.lastIndex) {
                        error("${mode.displayName}未在 30 秒内枚举出 USB 有线设备")
                    }
                }
                val boundOutput = checkNotNull(boundInterfaceInfo) { "所有 USB 身份均未枚举出 USB 设备" }
                val iface = UsbNcmDiscovery.parseInterfaceName(boundOutput) ?: error("root 输出中没有有效 USB 网卡名")
                val ifindex = UsbNcmDiscovery.parseIfindex(boundOutput) ?: error("无法读取 $iface 的 ifindex")
                SessionController.appendLog(
                    "USB-BENCH: ${identityMode.displayName}已绑定 $iface ifindex=$ifindex；身份已锁定，地址单独等待",
                )
                update(
                    runId,
                    Phase.PREPARING_USB,
                    "$iface 已由${identityMode.displayName}枚举" +
                        if (identityMode == UsbNcmIdentityMode.IPHONE_COMPAT) {
                            "；USBMUX/Lockdown 服务已启动，正在等待 USB 地址"
                        } else {
                            "，正在等待 USB 地址"
                        },
                )
                val addressOutput = awaitAddress(runId)
                val addressIface = UsbNcmDiscovery.parseInterfaceName(addressOutput) ?: error("root 输出中没有有效 USB 网卡名")
                check(addressIface == iface) { "USB 网卡在地址等待期间从 $iface 变为 $addressIface" }
                val localAddresses = UsbNcmDiscovery.parseLocalAddresses(addressOutput, iface)
                val mdnsAddresses = localAddresses.mdnsBindAddresses
                check(mdnsAddresses.isNotEmpty()) { "$iface 没有可绑定的 IPv4/IPv6 地址" }
                SessionController.appendLog(
                    "USB-BENCH: $iface 已就绪 ifindex=$ifindex " +
                        "v6=${localAddresses.ipv6LinkLocal ?: "无"} v4=${localAddresses.ipv4 ?: "无"}",
                )

                val peer =
                    if (directHost != null) {
                        SessionController.appendLog("USB-BENCH: 使用手动直连地址 $directHost:17000")
                        PeerResult(listOf(directHost), 17000, "手动")
                    } else {
                        update(
                            runId,
                            Phase.FINDING_PEER,
                            if (identityMode == UsbNcmIdentityMode.IPHONE_COMPAT) {
                                "iPhone 兼容身份与 USBMUX/Lockdown 已启动；等待接收端 iAP2 StartSession（DiPlay/车机）" +
                                    "或 MacPlay mDNS…"
                            } else {
                                "请在 MacPlay 选择“有线CarPlay”并点击“启动接收”…"
                            },
                        )
                        awaitPeer(
                            iface = iface,
                            ifindex = ifindex,
                            localAddresses = mdnsAddresses,
                            localIpv4 = localAddresses.ipv4,
                            runId = runId,
                            expectStartSession = identityMode == UsbNcmIdentityMode.IPHONE_COMPAT,
                        )
                    }
                configurePeerRoutes(peer.addresses)
                checkActive(runId)

                update(
                    runId,
                    Phase.STARTING_AIRPLAY,
                    "通过${peer.source}发现 ${peer.addresses.joinToString()}，正在连接 :${peer.port}（声音自动降级）…",
                )
                SessionController.startUsbMediaBench(
                    appContext,
                    UsbMediaEndpoint(iface, peer.addresses, peer.port, ifindex),
                    audioEnabled = enableAudio,
                    cleanup = {
                        runCatching(::stopRootResources).fold(
                            onSuccess = {
                                publish(Snapshot(Phase.IDLE, "USB 台架已停止并回滚"))
                            },
                            onFailure = { error ->
                                val detail = error.message?.take(240) ?: error.javaClass.simpleName
                                publish(Snapshot(Phase.FAILED, "媒体已停止，但 USB 回滚失败：$detail"))
                            },
                        )
                    },
                )
                update(
                    runId,
                    Phase.CONNECTED,
                    "$iface → ${peer.addresses.first()}:${peer.port}，USB 有线 CarPlay 视频${if (enableAudio) "和声音" else ""}" +
                        "正在建立；${identityMode.displayName}" +
                        if (identityMode == UsbNcmIdentityMode.IPHONE_COMPAT) "，USBMUX/Lockdown 服务运行中" else "",
                )
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
        // 只结束本入口建立的 USB 会话：其 cleanup 回调负责回滚并发布结果。其它情况（仍在建立、
        // 会话已结束，或当前是无线会话）不打断现有连接，直接回滚 root 资源，否则 STOPPING 无人收尾。
        if (SessionController.hasUsbMediaCleanup()) {
            SessionController.disconnect(context)
        } else {
            Bg.run("usb-ncm-stop") {
                runCatching(::stopRootResources).fold(
                    onSuccess = {
                        publish(Snapshot(Phase.IDLE, "USB 台架已停止并回滚"))
                    },
                    onFailure = { error ->
                        val detail = error.message?.take(240) ?: error.javaClass.simpleName
                        SessionController.appendLog("USB-BENCH: 停止失败：$detail")
                        publish(Snapshot(Phase.FAILED, "USB 回滚失败：$detail"))
                    },
                )
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
        identityMode: UsbNcmIdentityMode,
    ) {
        val cleared =
            runRoot(
                "rm -f ${UsbNcmDiscovery.shellQuote(REMOTE_LOG)}",
                10_000L,
            )
        check(cleared.exitCode == 0) { "清理旧 USB 台架日志失败：${cleared.output.takeLast(180)}" }
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
                identityMode.wireValue,
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
        check(result.exitCode == 0) { "USB 有线设备未在 30 秒内枚举：${result.output.takeLast(480)}" }
        return result.output
    }

    private fun awaitAddress(runId: Int): String {
        val command = "sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} wait-address"
        val result = runRoot(command, 130_000L)
        checkActive(runId)
        check(result.exitCode == 0) {
            "USB 身份已保留，但网卡未在 120 秒内取得可绑定地址：${result.output.takeLast(720)}"
        }
        return result.output
    }

    /**
     * iPhone 兼容身份下，车机/DiPlay 在 iAP2 `0x4301` 中给出 AirPlay 地址，不发布 mDNS；
     * MacPlay 只发布 mDNS。两者并行等待，先到者胜出；都没有时再回退到 USB 邻居表。
     */
    private fun awaitPeer(
        iface: String,
        ifindex: Int,
        localAddresses: List<String>,
        localIpv4: String?,
        runId: Int,
        expectStartSession: Boolean,
    ): PeerResult {
        val mdnsResult = AtomicReference<PeerResult?>()
        val mdnsDone = CountDownLatch(1)
        Thread({
            try {
                localAddresses
                    .asSequence()
                    .mapNotNull { localAddress ->
                        if (generation.get() != runId) return@mapNotNull null
                        awaitPeerOnAddress(iface, ifindex, localAddress, runId)
                    }.firstOrNull()
                    ?.let(mdnsResult::set)
            } catch (_: Throwable) {
                // 取消或 mDNS 初始化失败时由主循环决定下一步。
            } finally {
                mdnsDone.countDown()
            }
        }, "usb-ncm-mdns").apply {
            isDaemon = true
            start()
        }
        val startDeadline = SystemClock.uptimeMillis() + if (expectStartSession) START_SESSION_WAIT_MILLIS else 0L
        while (true) {
            checkActive(runId)
            if (expectStartSession) readStartSession()?.let { return it }
            mdnsResult.get()?.let { return it }
            if (mdnsDone.count == 0L && SystemClock.uptimeMillis() >= startDeadline) break
            Thread.sleep(START_SESSION_POLL_MILLIS)
        }
        val discovered = mdnsResult.get()
        checkActive(runId)
        val neighbors =
            runRoot(
                "sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} peers",
                10_000L,
            ).also {
                if (it.exitCode != 0) {
                    SessionController.appendLog("USB-BENCH: 邻居地址读取失败：${it.output.takeLast(160)}")
                }
            }.output
        val neighborHosts =
            runCatching {
                UsbNcmDiscovery.parseNeighborAddresses(neighbors, iface, limit = 8)
            }.getOrDefault(emptyList())
        val addresses = (discovered?.addresses.orEmpty() + neighborHosts).distinct().take(8)
        if (addresses.isEmpty()) {
            error(
                "USB 已建立，但 $iface 上既没有接收端 iAP2 StartSession / MacPlay mDNS，也没有可用邻居地址；" +
                    "可在入口填写直连 IP 后重试",
            )
        }
        val source =
            when {
                discovered != null && neighborHosts.isEmpty() -> " mDNS "
                discovered != null -> " mDNS/邻居回退 "
                else -> " USB 邻居回退 "
            }
        if (discovered == null) {
            SessionController.appendLog("USB-BENCH: mDNS 未发现服务，降级尝试 USB 邻居 ${addresses.joinToString()} :17000")
        } else if (neighborHosts.isNotEmpty()) {
            SessionController.appendLog("USB-BENCH: 追加邻居候选 ${neighborHosts.joinToString()}")
        }
        if (localIpv4 != null && addresses.any { it.contains('.') }) {
            SessionController.appendLog("USB-BENCH: IPv4 候选将通过 $iface 的 $localIpv4 路由")
        }
        return PeerResult(addresses, discovered?.port ?: 17000, source.trim())
    }

    /** 读取 root usbmux 进程写下的 `0x4301`；文件不存在（exit 3）表示尚未收到。 */
    private fun readStartSession(): PeerResult? {
        val result = runRoot("sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} start-session", 5_000L)
        if (result.exitCode != 0) return null
        val start = WiredCarPlayStartFile.decode(result.output) ?: return null
        val addresses = start.addresses.mapNotNull(UsbNcmDiscovery::normalizePeerAddress).distinct()
        if (addresses.isEmpty()) {
            SessionController.appendLog("USB-BENCH: iAP2 StartSession 地址不可用：${start.addresses.joinToString()}")
            return null
        }
        SessionController.appendLog(
            "USB-BENCH: iAP2 StartSession → ${addresses.joinToString()} port=${start.port} " +
                "device=${start.deviceIdentifier ?: "无"} publicKey=${start.publicKeyLength}",
        )
        return PeerResult(addresses, start.port, "iAP2 StartSession")
    }

    private fun awaitPeerOnAddress(
        iface: String,
        ifindex: Int,
        localAddress: String,
        runId: Int,
    ): PeerResult? {
        val address =
            try {
                val parsed = InetAddress.getByName(localAddress)
                if (parsed is Inet6Address) {
                    Inet6Address.getByAddress(null, parsed.address, ifindex)
                } else {
                    parsed
                }
            } catch (error: Exception) {
                SessionController.appendLog("USB-BENCH: 无法为 $iface 绑定 mDNS 地址 $localAddress：${error.message}")
                return null
            }
        val resolved = AtomicReference<PeerResult?>()
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
                    val preferred =
                        info.port > 0 && (info.port == 17000 || event.name.contains("macplay", ignoreCase = true))
                    val advertised = info.inet4Addresses.toList() + info.inet6Addresses.toList()
                    val candidates =
                        advertised
                            .asSequence()
                            .mapNotNull { UsbNcmDiscovery.endpointHostFromMdns(it, ifindex) }
                            .distinct()
                            .take(8)
                            .toList()
                    SessionController.appendLog(
                        "USB-BENCH: mDNS ${event.name} → ${candidates.joinToString()} port=${info.port} " +
                            "advertised=${advertised.joinToString()}" +
                            when {
                                !preferred -> "（非 MacPlay，忽略）"
                                candidates.isEmpty() -> "（没有 USB 链路本地地址，忽略）"
                                else -> ""
                            },
                    )
                    if (preferred &&
                        candidates.isNotEmpty() &&
                        resolved.compareAndSet(null, PeerResult(candidates, info.port, "mDNS"))
                    ) {
                        latch.countDown()
                    }
                }

                override fun serviceRemoved(event: ServiceEvent) = Unit
            }
        val mdns =
            try {
                val family = if (address is Inet6Address) "v6" else "v4"
                JmDNS.create(address, "mcarplay-usb-$runId-$family")
            } catch (error: Exception) {
                SessionController.appendLog("USB-BENCH: $localAddress 上的 mDNS 初始化失败：${error.message}")
                return null
            }
        try {
            mdns.addServiceListener(serviceType, listener)
            SessionController.appendLog("USB-BENCH: 正在通过 $iface/$localAddress 查询 $serviceType")
            latch.await(10, TimeUnit.SECONDS)
        } finally {
            runCatching { mdns.removeServiceListener(serviceType, listener) }
            runCatching { mdns.close() }
        }
        checkActive(runId)
        return resolved.get().also { peer ->
            if (peer == null) {
                SessionController.appendLog("USB-BENCH: $localAddress 上 10 秒内未发现 MacPlay，尝试下一候选")
            }
        }
    }

    private fun configurePeerRoutes(addresses: List<String>) {
        var configured = 0
        for (host in addresses) {
            val result =
                runRoot(
                    "sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} peer ${UsbNcmDiscovery.shellQuote(host)}",
                    20_000L,
                )
            if (result.exitCode == 0) {
                configured++
            } else {
                SessionController.appendLog("USB-BENCH: 候选 $host 路由失败：${result.output.takeLast(160)}")
            }
        }
        check(configured > 0) { "所有 USB 候选地址的独立路由均建立失败" }
    }

    private fun stopRootResources() {
        val result = runRoot("sh ${UsbNcmDiscovery.shellQuote(REMOTE_SCRIPT)} down", 20_000L)
        check(result.exitCode == 0) {
            "停止脚本失败：${result.output.takeLast(240).ifBlank { "exit=${result.exitCode}" }}"
        }
    }

    private fun randomLocalMac(): String = UsbNcmDiscovery.formatLocalMac(ByteArray(6).also(random::nextBytes))

    /**
     * MacPlay 把 USB 序列号当作设备选择键。使用应用内固定的随机伪序列号，既能跨重连保持同一目标，
     * 也不需要读取或复用手机的真实硬件标识。
     */
    private fun stablePseudoSerial(context: Context): String {
        val preferences = context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
        preferences.getString(PSEUDO_SERIAL_KEY, null)?.takeIf(PSEUDO_SERIAL::matches)?.let { return it }
        val generated = ByteArray(20).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        check(preferences.edit().putString(PSEUDO_SERIAL_KEY, generated).commit()) { "无法保存本地台架身份" }
        return generated
    }

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
        check(File(REMOTE_LOG).exists()) { "root 台架未在 2 秒内启动" }
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
