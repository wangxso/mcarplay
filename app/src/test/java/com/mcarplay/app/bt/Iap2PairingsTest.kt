package com.mcarplay.app.bt

import android.bluetooth.BluetoothDevice
import com.mcarplay.app.transport.Iap2LinkChannel.Role
import org.junit.Assert.assertEquals
import org.junit.Test

class Iap2PairingsTest {
    @Test
    fun newPairingInitiatesUntilItsFirstSessionSucceeds() {
        val completed = mutableMapOf<String, Boolean>()

        fun restart() = Iap2Pairings({ completed[it] == true }) { address, done -> completed[address] = done }
        val pairings = restart()
        val car = "00:11:22:33:44:55"
        assertEquals(Role.INITIATOR, restart().attempt(car, BluetoothDevice.BOND_BONDED).role)
        pairings.bondChanged(car, BluetoothDevice.BOND_NONE)
        assertEquals(Role.INITIATOR, pairings.attempt(car, BluetoothDevice.BOND_NONE).role)
        pairings.bondChanged(car, BluetoothDevice.BOND_BONDING)
        pairings.bondChanged(car, BluetoothDevice.BOND_BONDED)

        assertEquals(Role.INITIATOR, pairings.attempt(car, BluetoothDevice.BOND_BONDED).role)
        val retry = pairings.attempt(car, BluetoothDevice.BOND_BONDED)
        assertEquals(Role.INITIATOR, retry.role)
        retry.complete()
        assertEquals(Role.RESPONDER, restart().attempt(car, BluetoothDevice.BOND_BONDED).role)
        pairings.bondChanged(car, BluetoothDevice.BOND_NONE)
        retry.complete()
        assertEquals(Role.INITIATOR, restart().attempt(car, BluetoothDevice.BOND_BONDED).role)
        pairings.attempt(car, BluetoothDevice.BOND_BONDED).complete()
        assertEquals(Role.RESPONDER, pairings.attempt(car, BluetoothDevice.BOND_BONDED).role)
    }
}
