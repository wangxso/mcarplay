package com.mcarplay.app.carlife

/** Owns one mDNS registration and its multicast lease, including partial-start failures. */
internal class EasyConnPublication(
    private val acquireMulticast: () -> AutoCloseable,
    private val openRegistration: (ExternalHuDiscoveryProtocol.ServiceIdentity) -> Registration,
    private val log: (String) -> Unit,
) {
    interface Registration : AutoCloseable {
        fun register()
    }

    private data class Active(
        val identity: ExternalHuDiscoveryProtocol.ServiceIdentity,
        val multicast: AutoCloseable,
        val registration: Registration,
    )

    private var active: Active? = null

    @Volatile
    var state: String = "idle"
        private set

    @Volatile
    var published: Boolean = false
        private set

    @Synchronized
    fun start(identity: ExternalHuDiscoveryProtocol.ServiceIdentity): Boolean {
        if (active?.identity == identity) return true
        stop()
        if (identity.ipv4Address == null || !ExternalHuDiscoveryProtocol.isMatchingInstanceName(identity.instanceName)) {
            state = "P2P IPv4 或 EasyConn 实例名未就绪"
            log("EASYCONN-MDNS: $state")
            return false
        }
        var multicast: AutoCloseable? = null
        var registration: Registration? = null
        return try {
            multicast = acquireMulticast()
            registration = openRegistration(identity)
            registration.register()
            active = Active(identity, multicast, registration)
            published = true
            state = "EasyConn mDNS 已发布"
            log("EASYCONN-MDNS: 已发布 ${identity.instanceName} on ${identity.ipv4Address.hostAddress}")
            true
        } catch (error: Exception) {
            closeResource(registration)
            closeResource(multicast)
            state = "mDNS 发布失败: ${error.message ?: error.javaClass.simpleName}"
            log("EASYCONN-MDNS: $state")
            false
        }
    }

    @Synchronized
    fun stop() {
        val previous = active
        active = null
        published = false
        state = "stopped"
        if (previous == null) return
        closeResource(previous.registration)
        closeResource(previous.multicast)
        log("EASYCONN-MDNS: 已停止")
    }

    private fun closeResource(resource: AutoCloseable?) {
        runCatching { resource?.close() }.onFailure { error ->
            log("EASYCONN-MDNS: 释放资源失败 ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
