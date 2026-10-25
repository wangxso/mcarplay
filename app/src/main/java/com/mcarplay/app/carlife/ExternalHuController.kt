package com.mcarplay.app.carlife

import android.content.Context
import com.mcarplay.app.SessionController
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Owns experimental HU discovery; success means an accepted EasyConn probe. */
object ExternalHuController {
    enum class Step {
        IDLE,
        STARTING,
        SDP_LISTENING,
        HU_CONNECTED,
        P2P_GROUP_READY,
        MDNS_PUBLISHED,
        ACCEPTED,
        FAILED,
    }

    data class Snapshot(
        val running: Boolean,
        val step: Step,
        val detail: String,
        val sdpListening: Boolean,
        val huConnected: Boolean,
        val p2pReady: Boolean,
        val mdnsPublished: Boolean,
        val accepted: Boolean,
    )

    private class Run {
        val revision = AtomicLong()
        var bridge: ExternalHuSdpBridge? = null
        var p2p: ExternalHuP2pCoordinator? = null

        @Volatile
        var discovery: Discovery? = null

        @Volatile
        var error: String? = null
    }

    private data class Discovery(
        val revision: Long,
        val responder: CarWithDiscoveryResponder,
        val mdns: EasyConnMdnsPublisher,
    )

    private val lock = Any()
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "carwith-hu-discovery").apply { isDaemon = true }
        }

    @Volatile
    private var current: Run? = null

    private var lastError: String? = null

    fun start(context: Context): Boolean =
        synchronized(lock) {
            if (current != null) return@synchronized true
            val app = context.applicationContext
            val run = Run()
            current = run
            lastError = null
            log("HU-SIM: 启动亿连发现诊断，等待对端请求")
            try {
                val bridge = ExternalHuSdpBridge(app, ::log)
                run.bridge = bridge
                val p2p =
                    ExternalHuP2pCoordinator(
                        context = app,
                        log = ::log,
                        onReady = { ready -> networkReady(app, run, ready) },
                        onGroupLost = { networkLost(run) },
                    )
                run.p2p = p2p
                check(bridge.start()) { bridge.state }
                check(p2p.start()) { p2p.state }
                true
            } catch (error: Exception) {
                lastError = error.message ?: error.javaClass.simpleName
                stopRunLocked(run)
                log("HU-SIM: 启动失败 $lastError")
                false
            }
        }

    fun stop() =
        synchronized(lock) {
            lastError = null
            current?.let(::stopRunLocked)
        }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            val run = current
            val active = run?.discovery?.takeIf { it.revision == run.revision.get() }
            val p2pReady = run?.p2p?.groupEstablished == true
            val mdnsPublished = p2pReady && active?.mdns?.published == true
            val accepted = mdnsPublished && active?.responder?.accepted == true
            val error = run?.error ?: lastError
            val step =
                when {
                    error != null -> Step.FAILED
                    run == null -> Step.IDLE
                    accepted -> Step.ACCEPTED
                    mdnsPublished -> Step.MDNS_PUBLISHED
                    p2pReady -> Step.P2P_GROUP_READY
                    run.bridge?.isConnected == true -> Step.HU_CONNECTED
                    run.bridge?.isListening == true -> Step.SDP_LISTENING
                    else -> Step.STARTING
                }
            Snapshot(
                running = run != null,
                step = step,
                detail =
                    error ?: when {
                        accepted -> active?.responder?.state.orEmpty()
                        mdnsPublished -> active?.mdns?.state.orEmpty()
                        p2pReady -> "P2P 已就绪，等待发布发现服务"
                        run != null -> run.p2p?.state ?: run.bridge?.state ?: "启动中"
                        else -> "未运行"
                    },
                sdpListening = run?.bridge?.isListening == true,
                huConnected = run?.bridge?.isConnected == true,
                p2pReady = p2pReady,
                mdnsPublished = mdnsPublished,
                accepted = accepted,
            )
        }

    private fun networkReady(
        context: Context,
        run: Run,
        ready: ExternalHuP2pReady,
    ) {
        val revision =
            synchronized(lock) {
                if (current !== run) return
                run.error = null
                run.bridge?.clearAdvertisement()
                run.revision.incrementAndGet()
            }
        worker.execute { publish(context, run, revision, ready) }
    }

    private fun networkLost(run: Run) {
        val revision =
            synchronized(lock) {
                if (current !== run) return
                run.error = null
                run.bridge?.clearAdvertisement()
                run.revision.incrementAndGet()
            }
        worker.execute {
            if ((run.discovery?.revision ?: Long.MAX_VALUE) < revision) closeDiscovery(run)
        }
    }

    private fun publish(
        context: Context,
        run: Run,
        revision: Long,
        ready: ExternalHuP2pReady,
    ) {
        if (!isCurrent(run, revision)) return
        closeDiscovery(run)
        if (!isCurrent(run, revision)) return
        val active =
            Discovery(
                revision,
                CarWithDiscoveryResponder(
                    log = ::log,
                    onAccepted = {
                        worker.execute {
                            if (isCurrent(run, revision)) log("HU-SIM: 10930 已接受探测，媒体出流仍待单独验证")
                        }
                    },
                ),
                EasyConnMdnsPublisher(context, ::log),
            )
        run.discovery = active
        try {
            check(active.responder.start(ready.identity.ipv4Address)) { active.responder.state }
            if (isCurrent(run, revision)) {
                check(active.mdns.start(ExternalHuDiscoveryProtocol.serviceIdentity(ready.identity))) { active.mdns.state }
            }
            synchronized(lock) {
                if (isCurrent(run, revision)) run.bridge?.setAdvertisement(ready.advertisement)
            }
        } catch (error: Exception) {
            if (isCurrent(run, revision)) {
                run.error = error.message ?: error.javaClass.simpleName
                log("HU-SIM: 发现服务启动失败 ${run.error}")
            }
            closeDiscovery(run)
        }
        if (!isCurrent(run, revision)) closeDiscovery(run)
    }

    private fun isCurrent(
        run: Run,
        revision: Long,
    ): Boolean = current === run && run.revision.get() == revision

    private fun stopRunLocked(run: Run) {
        if (current === run) current = null
        run.revision.incrementAndGet()
        runCatching { run.bridge?.stop() }
        runCatching { run.p2p?.stop() }
        worker.execute { closeDiscovery(run) }
        log("HU-SIM: 已停止；发现服务与本轮 P2P 组正在回收")
    }

    private fun closeDiscovery(run: Run) {
        val active = run.discovery ?: return
        run.discovery = null
        runCatching { active.responder.stop() }
        runCatching { active.mdns.stop() }
    }

    private fun log(message: String) = SessionController.appendLog(message)
}
