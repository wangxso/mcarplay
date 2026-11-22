package com.mcarplay.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.mcarplay.app.transport.Iap2LinkChannel
import java.util.concurrent.ConcurrentHashMap

/** 保留应用观察到的新配对的首次蓝牙会话时序。 */
internal class Iap2Pairings {
    private val pending = ConcurrentHashMap<String, Any>()

    fun bondChanged(
        address: String,
        state: Int,
    ) {
        when (state) {
            BluetoothDevice.BOND_NONE -> pending.remove(address)
            BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_BONDED -> pending.putIfAbsent(address, Any())
        }
    }

    @SuppressLint("MissingPermission")
    fun attempt(socket: BluetoothSocket): Attempt {
        val peer = runCatching { socket.remoteDevice }.getOrNull()
        val address = runCatching { peer?.address }.getOrNull()
        val bond = runCatching { peer?.bondState }.getOrNull()
        return attempt(address, bond)
    }

    fun attempt(
        address: String?,
        bond: Int?,
    ): Attempt {
        val pairing = address?.let(pending::get)
        val role =
            if (address != null && bond == BluetoothDevice.BOND_BONDED && pairing == null) {
                Iap2LinkChannel.Role.RESPONDER
            } else {
                Iap2LinkChannel.Role.INITIATOR
            }
        return Attempt(role) {
            if (address != null && pairing != null) pending.remove(address, pairing)
        }
    }

    class Attempt(
        val role: Iap2LinkChannel.Role,
        val complete: () -> Unit,
    )

    companion object {
        val current = Iap2Pairings()
    }
}
