package com.mcarplay.app.usb

import com.mcarplay.app.usb.WiredSwitchMachine.Action
import com.mcarplay.app.usb.WiredSwitchMachine.Event
import com.mcarplay.app.usb.WiredSwitchMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredSwitchMachineTest {
    private val nodeA = UsbRoleNode("/sys/class/usb_role/a/role", "host", "device", UsbRoleNode.Source.USB_ROLE_CLASS)
    private val nodeB = UsbRoleNode("/sys/class/typec/port0/data_role", "host", "device", UsbRoleNode.Source.TYPEC_DATA_ROLE)
    private val roleSwitch = UsbSetupPacket(0x40, 0x51, 1, 0, 0)

    private fun machine(nodes: List<UsbRoleNode> = listOf(nodeA, nodeB)) =
        WiredSwitchMachine(nodes, awaitRoleSwitchMillis = 1_000, awaitAccessoryMillis = 500)

    @Test
    fun happyPathReachesAccessoryAttached() {
        val m = machine()
        m.on(Event.GadgetBound(0))
        val actions = m.on(Event.Setup(roleSwitch))

        assertTrue(actions.contains(Action.Reply(Ep0Reply.AckOut(0))))
        assertEquals(Action.ApplyHostRole(nodeA), actions.last())
        assertEquals(State.SWITCHING_TO_HOST, m.state)

        m.on(Event.HostRoleResult(ok = true, nowMillis = 100))
        assertEquals(State.AWAITING_ACCESSORY, m.state)
        m.on(Event.AccessoryEnumerated)
        assertEquals(State.ACCESSORY_ATTACHED, m.state)
        assertTrue(m.on(Event.Tick(1_000_000)).isEmpty())
    }

    @Test
    fun otherRequestsAreAnsweredWithoutSwitching() {
        val m = machine()
        m.on(Event.GadgetBound(0))
        val actions = m.on(Event.Setup(UsbSetupPacket(0x40, 0x40, 500, 0, 0)))

        assertTrue(actions.contains(Action.Reply(Ep0Reply.AckOut(0))))
        assertTrue(actions.none { it is Action.ApplyHostRole })
        assertEquals(State.GADGET_UP, m.state)
    }

    @Test
    fun failedNodeFallsBackToNextThenRollsBackTouchingBoth() {
        val m = machine()
        m.on(Event.GadgetBound(0))
        m.on(Event.Setup(roleSwitch))

        assertEquals(Action.ApplyHostRole(nodeB), m.on(Event.HostRoleResult(false, 10)).last())
        val rollback = m.on(Event.HostRoleResult(false, 20)).single() as Action.Rollback

        assertEquals(listOf(nodeA, nodeB), rollback.touchedNodes)
        assertEquals(State.ROLLING_BACK, m.state)
        m.on(Event.RollbackDone)
        assertEquals(State.ROLLED_BACK, m.state)
    }

    @Test
    fun waitingForRoleSwitchTimesOutIntoSingleRollback() {
        val m = machine()
        m.on(Event.GadgetBound(0))
        assertTrue(m.on(Event.Tick(999)).isEmpty())

        val rollback = m.on(Event.Tick(1_000)).single() as Action.Rollback
        assertTrue(rollback.touchedNodes.isEmpty())
        // 回滚只发一次
        assertTrue(m.on(Event.Tick(5_000)).isEmpty())
        assertTrue(m.on(Event.Failure("again")).isEmpty())
    }

    @Test
    fun accessoryThatNeverEnumeratesRollsBackTheAppliedNode() {
        val m = machine()
        m.on(Event.GadgetBound(0))
        m.on(Event.Setup(roleSwitch))
        m.on(Event.HostRoleResult(true, 100))

        val rollback = m.on(Event.Tick(600)).single() as Action.Rollback
        assertEquals(listOf(nodeA), rollback.touchedNodes)
    }

    @Test
    fun roleSwitchWithoutNodesFailsImmediately() {
        val m = machine(emptyList())
        m.on(Event.GadgetBound(0))
        val actions = m.on(Event.Setup(roleSwitch))

        assertTrue(actions.contains(Action.Reply(Ep0Reply.AckOut(0))))
        assertTrue(actions.last() is Action.Rollback)
    }
}
