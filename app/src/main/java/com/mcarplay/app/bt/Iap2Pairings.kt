package com.mcarplay.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.mcarplay.app.transport.Iap2LinkChannel

/** 保留应用观察到的新配对的首次蓝牙会话时序。 */
internal class Iap2Pairings(
    private val completed: (String) -> Boolean,
    private val save: (String, Boolean) -> Unit,
) {
    private val pending = mutableMapOf<String, Any>()

    @Synchronized
    fun bondChanged(
        address: String,
        state: Int,
    ) {
        when (state) {
            BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING -> {
                pending[address] = Any()
                save(address, false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun attempt(socket: BluetoothSocket): Attempt {
        val peer = runCatching { socket.remoteDevice }.getOrNull()
        val address = runCatching { peer?.address }.getOrNull()
        val bond = runCatching { peer?.bondState }.getOrNull()
        return attempt(address, bond)
    }

    @Synchronized
    fun canRespond(
        address: String?,
        bond: Int?,
    ): Boolean = address != null && bond == BluetoothDevice.BOND_BONDED && completed(address)

    @Synchronized
    fun attempt(
        address: String?,
        bond: Int?,
    ): Attempt {
        val pairing = address?.let { pending.getOrPut(it) { Any() } }
        val role =
            if (canRespond(address, bond)) {
                Iap2LinkChannel.Role.RESPONDER
            } else {
                Iap2LinkChannel.Role.INITIATOR
            }
        return Attempt(role) {
            synchronized(this) {
                if (address != null && bond == BluetoothDevice.BOND_BONDED && pending[address] === pairing) {
                    save(address, true)
                }
            }
        }
    }

    class Attempt(
        val role: Iap2LinkChannel.Role,
        val complete: () -> Unit,
    )

    companion object {
        private var instance: Iap2Pairings? = null

        @Synchronized
        fun current(context: Context): Iap2Pairings {
            instance?.let { return it }
            val prefs = context.getSharedPreferences("iap2_pairings", Context.MODE_PRIVATE)
            return Iap2Pairings({ prefs.getBoolean(it, false) }) { address, done ->
                prefs.edit().putBoolean(address, done).apply()
            }.also { instance = it }
        }
    }
}
