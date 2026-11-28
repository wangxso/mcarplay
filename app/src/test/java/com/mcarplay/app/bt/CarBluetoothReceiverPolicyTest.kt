package com.mcarplay.app.bt

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class CarBluetoothReceiverPolicyTest {
    @Test
    fun autoConnectEnabledStartsOnlyForCarDevicesAndLogsIgnoredNonCars() {
        assertEquals(
            AclBroadcastAction.AUTO_START,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_ACL_CONNECTED,
                autoConnectEnabled = true,
                isCarDevice = true,
            ),
        )
        assertEquals(
            AclBroadcastAction.IGNORE_NON_CAR_LOG,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_ACL_CONNECTED,
                autoConnectEnabled = true,
                isCarDevice = false,
            ),
        )
    }

    @Test
    fun autoConnectDisabledSuppressesAutoStartWithoutBlockingCarDisconnectStop() {
        assertEquals(
            AclBroadcastAction.IGNORE,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_ACL_CONNECTED,
                autoConnectEnabled = false,
                isCarDevice = true,
            ),
        )
        assertEquals(
            AclBroadcastAction.IGNORE,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_ACL_CONNECTED,
                autoConnectEnabled = false,
                isCarDevice = false,
            ),
        )
        assertEquals(
            AclBroadcastAction.AUTO_STOP,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_ACL_DISCONNECTED,
                autoConnectEnabled = false,
                isCarDevice = true,
            ),
        )
        assertEquals(
            AclBroadcastAction.IGNORE,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_ACL_DISCONNECTED,
                autoConnectEnabled = false,
                isCarDevice = false,
            ),
        )
    }

    @Test
    fun carDisconnectAlwaysTriggersAutoStopCheckWhenAutoConnectEnabled() {
        assertEquals(
            AclBroadcastAction.AUTO_STOP,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_ACL_DISCONNECTED,
                autoConnectEnabled = true,
                isCarDevice = true,
            ),
        )
        assertEquals(
            AclBroadcastAction.IGNORE_NON_CAR_LOG,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_ACL_DISCONNECTED,
                autoConnectEnabled = true,
                isCarDevice = false,
            ),
        )
        assertEquals(
            AclBroadcastAction.IGNORE,
            decideAclBroadcastAction(
                action = BluetoothDevice.ACTION_BOND_STATE_CHANGED,
                autoConnectEnabled = true,
                isCarDevice = true,
            ),
        )
    }
}
