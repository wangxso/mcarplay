package com.mcarplay.app

import com.mcarplay.app.compat.ReceiverObservation

/** 同时有多个候选或身份未知时，不能把 Wi-Fi 邀请猜成属于其中某一条 RFCOMM 链路。 */
internal class ActiveReceiverObservations {
    private val active = mutableSetOf<ReceiverObservation>()
    private var unknown = 0

    @Synchronized
    fun begin(observation: ReceiverObservation?) {
        if (observation == null) unknown++ else active.add(observation)
    }

    @Synchronized
    fun end(observation: ReceiverObservation?) {
        if (observation == null) unknown = (unknown - 1).coerceAtLeast(0) else active.remove(observation)
    }

    @Synchronized
    fun single(): ReceiverObservation? = if (unknown == 0) active.singleOrNull() else null

    /**
     * 是否还有任何一条 RFCOMM 链路存活（身份未知的也算）。
     *
     * 不能用 `single() != null` 代替：只要有未知身份的链路，或剩下两条以上已知链路，
     * [single] 就返回 null，那会被误读成「没有别的链路」。判断「另一条链路是否还活着、
     * 因此不能替它收尾」时必须用它。
     */
    @Synchronized
    fun hasAny(): Boolean = unknown > 0 || active.isNotEmpty()

    /** 存活链路条数（身份未知的计入），只用于日志与判定。 */
    @Synchronized
    fun count(): Int = unknown + active.size

    /**
     * 该 observation 所属 RFCOMM 链路是否仍存活。
     *
     * [com.mcarplay.app.compat.ReceiverDetectionRuntime.isCurrent] 只比较 epoch/session，
     * 已关闭的链路仍可能返回 true；暂存端点与唤醒必须用本集合判断，否则会把端点
     * 从已断开的链路重新拉起来。观察身份未知（null）时，只有仍有未知链路或以
     * 已知链路在跑才认为存活——全关时不得复活。
     */
    @Synchronized
    fun isActive(observation: ReceiverObservation?): Boolean = if (observation == null) hasAny() else active.contains(observation)
}
