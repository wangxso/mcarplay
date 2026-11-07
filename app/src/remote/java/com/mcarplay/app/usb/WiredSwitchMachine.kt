package com.mcarplay.app.usb

/**
 * 有线 CarPlay 角色切换状态机（纯逻辑，时间与 I/O 由调用方注入）。
 *
 *   IDLE → GADGET_UP → SWITCHING_TO_HOST → AWAITING_ACCESSORY → ACCESSORY_ATTACHED
 *                 ↘ 超时/出错 ↙            ↘ 所有节点失败 ↙
 *                         ROLLING_BACK → ROLLED_BACK
 *
 * 不变量：任何失败路径都会产出 [Action.Rollback]，且只产出一次——USB 停在半成品状态
 * 会让手机既不能充电也不能 adb，直到重启。
 */
class WiredSwitchMachine(
    private val roleNodes: List<UsbRoleNode>,
    private val awaitRoleSwitchMillis: Long = 60_000L,
    private val awaitAccessoryMillis: Long = 10_000L,
) {
    enum class State { IDLE, GADGET_UP, SWITCHING_TO_HOST, AWAITING_ACCESSORY, ACCESSORY_ATTACHED, ROLLING_BACK, ROLLED_BACK }

    sealed interface Event {
        data class GadgetBound(
            val nowMillis: Long,
        ) : Event

        data class Setup(
            val setup: UsbSetupPacket,
        ) : Event

        data class HostRoleResult(
            val ok: Boolean,
            val nowMillis: Long,
        ) : Event

        data object AccessoryEnumerated : Event

        data class Tick(
            val nowMillis: Long,
        ) : Event

        data class Failure(
            val reason: String,
        ) : Event

        data object RollbackDone : Event
    }

    sealed interface Action {
        data class Reply(
            val reply: Ep0Reply,
        ) : Action

        /** 拆 gadget 但不把 Android gadget 绑回去，然后写入 [node] 的主机值。 */
        data class ApplyHostRole(
            val node: UsbRoleNode,
        ) : Action

        data class Rollback(
            val reason: String,
            /** 需要写回设备值的节点（已尝试过主机值的那些）。 */
            val touchedNodes: List<UsbRoleNode>,
        ) : Action

        data class Log(
            val message: String,
        ) : Action
    }

    var state: State = State.IDLE
        private set

    var failureReason: String? = null
        private set

    private var deadline = Long.MAX_VALUE
    private var nodeIndex = -1

    fun on(event: Event): List<Action> =
        when (event) {
            is Event.GadgetBound -> {
                expect(State.IDLE)
                state = State.GADGET_UP
                deadline = event.nowMillis + awaitRoleSwitchMillis
                listOf(Action.Log("gadget 已绑定，等待车机角色切换请求"))
            }

            is Event.Setup -> onSetup(event.setup)

            is Event.HostRoleResult -> onHostRoleResult(event)

            Event.AccessoryEnumerated -> {
                if (state == State.AWAITING_ACCESSORY) {
                    state = State.ACCESSORY_ATTACHED
                    deadline = Long.MAX_VALUE
                    listOf(Action.Log("车机已作为 USB 设备枚举"))
                } else {
                    emptyList()
                }
            }

            is Event.Tick ->
                if (event.nowMillis >= deadline && state in TIMED_STATES) {
                    fail("${state.name} 超时")
                } else {
                    emptyList()
                }

            is Event.Failure -> fail(event.reason)

            Event.RollbackDone -> {
                if (state == State.ROLLING_BACK) state = State.ROLLED_BACK
                emptyList()
            }
        }

    private fun onSetup(setup: UsbSetupPacket): List<Action> {
        val log = Action.Log("SETUP $setup kind=${AppleControlRequests.classify(setup)}")
        val reply = Action.Reply(AppleControlRequests.replyTo(setup))
        if (state != State.GADGET_UP || AppleControlRequests.classify(setup) != AppleControlRequests.Kind.ROLE_SWITCH) {
            return listOf(log, reply)
        }
        if (roleNodes.isEmpty()) return listOf(log, reply) + fail("收到角色切换请求，但本机没有可用的角色切换节点")
        state = State.SWITCHING_TO_HOST
        nodeIndex = 0
        return listOf(log, reply, Action.ApplyHostRole(roleNodes[0]))
    }

    private fun onHostRoleResult(event: Event.HostRoleResult): List<Action> {
        if (state != State.SWITCHING_TO_HOST) return emptyList()
        if (event.ok) {
            state = State.AWAITING_ACCESSORY
            deadline = event.nowMillis + awaitAccessoryMillis
            return listOf(Action.Log("已切到主机：${roleNodes[nodeIndex].path}"))
        }
        nodeIndex++
        return if (nodeIndex < roleNodes.size) {
            listOf(Action.Log("节点失败，换下一个"), Action.ApplyHostRole(roleNodes[nodeIndex]))
        } else {
            fail("所有角色切换节点都失败")
        }
    }

    private fun fail(reason: String): List<Action> {
        if (state == State.ROLLING_BACK || state == State.ROLLED_BACK) return emptyList()
        failureReason = reason
        state = State.ROLLING_BACK
        deadline = Long.MAX_VALUE
        val touched = if (nodeIndex >= 0) roleNodes.take(minOf(nodeIndex + 1, roleNodes.size)) else emptyList()
        return listOf(Action.Rollback(reason, touched))
    }

    private fun expect(expected: State) {
        check(state == expected) { "状态 $state 下不能执行该事件（期望 $expected）" }
    }

    private companion object {
        val TIMED_STATES = setOf(State.GADGET_UP, State.AWAITING_ACCESSORY)
    }
}
