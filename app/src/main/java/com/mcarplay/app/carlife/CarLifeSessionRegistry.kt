package com.mcarplay.app.carlife

/** Identity token shared by a CarLife session and its process-wide lease. */
class CarLifeSessionLease internal constructor()

/**
 * Process-wide ownership for the two independent CarLife entry points.
 *
 * CarWith can serve only one bridge at a time. Formal CarPlay and the
 * standalone self-test therefore share one lease, while each caller can
 * release only the exact lease it acquired.
 */
class CarLifeSessionRegistry {
    enum class Owner {
        FORMAL,
        DIAGNOSTIC,
    }

    data class Lease(
        val owner: Owner,
        val token: CarLifeSessionLease,
    )

    private var active: Lease? = null

    @Synchronized
    fun acquire(
        owner: Owner,
        token: CarLifeSessionLease,
    ): Boolean {
        val current = active
        if (current?.token === token) return current.owner == owner
        if (current != null) return false
        active = Lease(owner, token)
        return true
    }

    @Synchronized
    fun ownerOf(token: CarLifeSessionLease): Owner? = active?.takeIf { it.token === token }?.owner

    @Synchronized
    fun release(token: CarLifeSessionLease): Boolean {
        if (active?.token !== token) return false
        active = null
        return true
    }

    @Synchronized
    fun clearAll() {
        active = null
    }
}
