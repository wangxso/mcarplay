package com.mcarplay.app.net

/** Owns one request and its process binding; network values may be recreated by callbacks. */
internal class NetworkBinding<N : Any, R : Any>(
    private val bindProcess: (N?) -> Boolean,
    private val isUsable: (N) -> Boolean,
) {
    private var request: R? = null
    private var network: N? = null

    @Synchronized
    fun replaceRequest(next: R?): R? {
        val previous = request
        request = next
        clearNetwork()
        return previous
    }

    @Synchronized
    fun isCurrent(owner: R): Boolean = request === owner

    @Synchronized
    fun bind(
        owner: R,
        candidate: N,
    ): Boolean {
        if (request !== owner || !bindProcess(candidate)) return false
        network = candidate
        return true
    }

    @Synchronized
    fun lost(
        owner: R,
        lost: N,
    ): Boolean {
        if (request !== owner || network != lost) return false
        clearNetwork()
        return true
    }

    @Synchronized
    fun current(): N? {
        val current = network ?: return null
        if (!isUsable(current)) {
            clearNetwork()
            return null
        }
        return current
    }

    private fun clearNetwork() {
        if (network == null) return
        network = null
        bindProcess(null)
    }
}
