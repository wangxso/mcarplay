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
     * 该 observation 所属 RFCOMM 链路是否仍存活。
     *
     * [com.mcarplay.app.compat.ReceiverDetectionRuntime.isCurrent] 只比较 epoch/session，
     * 已关闭的链路仍可能返回 true；暂存端点与唤醒必须用本集合判断，否则会把端点
     * 从已断开的链路重新拉起来。观察身份未知（null）时，只有仍有未知链路或以
     * 已知链路在跑才认为存活——全关时不得复活。
     */
    @Synchronized
    fun isActive(observation: ReceiverObservation?): Boolean =
        if (observation == null) unknown > 0 || active.isNotEmpty() else active.contains(observation)
}
