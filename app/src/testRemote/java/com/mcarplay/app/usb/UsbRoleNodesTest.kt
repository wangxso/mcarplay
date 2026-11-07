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
    fun automaticSwitchNeverWritesTypecDataRole() {
        // 小米 17 实测的三个节点：写 Type-C data_role 会发起 PD 角色互换，曾导致手机黑屏重启
        val nodes =
            UsbRoleNodes.fromPaths(
                listOf(
                    "/sys/class/usb_role/a600000.ssusb-role-switch/role",
                    "/sys/class/typec/port0/data_role",
                    "/sys/devices/platform/soc/a600000.ssusb/mode",
                ),
            )

        assertEquals(
            listOf(UsbRoleNode.Source.USB_ROLE_CLASS, UsbRoleNode.Source.QCOM_SSUSB_MODE),
            UsbRoleNodes.forAutomaticSwitch(nodes).map { it.source },
        )
    }

    @Test
    fun detectsPhoneSourcingVbus() {
        // 小米 17 未接线时的实测读回
        assertEquals(false, UsbRoleNodes.isPowerSource("source [sink]"))
        assertEquals(true, UsbRoleNodes.isPowerSource("[source] sink"))
        assertEquals(false, UsbRoleNodes.isPowerSource(null))
    }

    @Test
    fun emptyWhenNothingMatches() {
        assertTrue(UsbRoleNodes.fromPaths(listOf("/sys/class/typec/port0/power_role")).isEmpty())
    }
}
