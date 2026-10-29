package com.mcarplay.app.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRoleNodesTest {
    @Test
    fun ordersCandidatesByPriorityAndIgnoresUnrelatedPaths() {
        val nodes =
            UsbRoleNodes.fromPaths(
                listOf(
                    "/sys/kernel/debug/usb/a600000.dwc3/mode",
                    "/sys/class/typec/port0/data_role",
                    "/sys/class/typec/port0/power_role",
                    "/sys/devices/platform/soc/a600000.ssusb/mode",
                    "/sys/class/usb_role/a600000.dwc3-role-switch/role",
                ),
            )

        assertEquals(
            listOf(
                UsbRoleNode.Source.USB_ROLE_CLASS,
                UsbRoleNode.Source.TYPEC_DATA_ROLE,
                UsbRoleNode.Source.QCOM_SSUSB_MODE,
                UsbRoleNode.Source.DWC3_DEBUGFS_MODE,
            ),
            nodes.map { it.source },
        )
    }

    @Test
    fun qualcommModeUsesPeripheralForDeviceRole() {
        val node = UsbRoleNodes.fromPaths(listOf("/sys/devices/platform/soc/a600000.ssusb/mode")).single()
        assertEquals("echo host > /sys/devices/platform/soc/a600000.ssusb/mode", node.hostCommand())
        assertEquals("echo peripheral > /sys/devices/platform/soc/a600000.ssusb/mode", node.deviceCommand())
    }

    @Test
    fun emptyWhenNothingMatches() {
        assertTrue(UsbRoleNodes.fromPaths(listOf("/sys/class/typec/port0/power_role")).isEmpty())
    }
}
