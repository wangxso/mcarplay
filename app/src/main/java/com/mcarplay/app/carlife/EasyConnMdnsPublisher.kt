package com.mcarplay.app.carlife

import android.content.Context
import android.net.wifi.WifiManager
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** Publishes EasyConn on the exact P2P IPv4 without changing the process network. */
class EasyConnMdnsPublisher(
    context: Context,
    log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val publication =
        EasyConnPublication(
            acquireMulticast = {
                val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = wifiManager.createMulticastLock("mcarplay:EasyConn").apply { setReferenceCounted(false) }
                try {
                    lock.acquire()
                } catch (error: Exception) {
                    if (lock.isHeld) runCatching { lock.release() }
                    throw error
                }
                AutoCloseable { if (lock.isHeld) lock.release() }
            },
            openRegistration = { identity ->
                val instance = JmDNS.create(requireNotNull(identity.ipv4Address), identity.instanceName)
                object : EasyConnPublication.Registration {
                    private var service: ServiceInfo? = null

                    override fun register() {
                        val info =
                            ServiceInfo.create(
                                ExternalHuDiscoveryProtocol.SERVICE_TYPE,
                                identity.instanceName,
                                ExternalHuDiscoveryProtocol.PORT,
                                0,
                                0,
                                ExternalHuDiscoveryProtocol.txtRecord(identity),
                            )
                        service = info
                        instance.registerService(info)
                    }

                    override fun close() {
                        try {
                            service?.let { instance.unregisterService(it) }
                        } finally {
                            instance.close()
                        }
                    }
                }
            },
            log = log,
        )

    val state: String get() = publication.state

    val published: Boolean get() = publication.published

    fun start(identity: ExternalHuDiscoveryProtocol.ServiceIdentity): Boolean = publication.start(identity)

    fun stop() = publication.stop()
}
