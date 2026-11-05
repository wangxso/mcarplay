package com.mcarplay.app.iap2

/**
 * 断开蓝牙控制链路前是否发 `0xEA01` StopEASession（纯函数，单测覆盖）。
 *
 * 背景：MFi 认证后手机为车机声明的每个 EA 协议发 `0xEA00`，此前断开时从不发
 * `0xEA01`，EA 会话只能靠车机超时回收。同生态项目在奥迪 MIB3 上观察到 EA 会话没收干净
 * 会挡住下一次（`EAP session … already exist`），并在断开前补发 `0xEA01`
 * （CarPToCarL `docs/changes-20261104.md`，其效果同样未经实车确认）。
 *
 * 规则只拦「发了也没用或可能不认识」的情况：
 *  - 本轮没发过 `0xEA00`（认证前就断开，或车机没声明 EA 协议）；
 *  - 已经发过一次；
 *  - 车机的可收清单**明确声明了**且不含 `0xEA01`（没有清单时照发：奥迪 9310 形态不发清单）；
 *  - 链路已关。
 *
 * 不沿用「对端 5 秒无帧就不发」：Wi-Fi 交接后蓝牙控制链路本来就可能长时间空闲，
 * 这条判据会让稳态断开几乎永远不发；而 `0xEA01` 是发后不管、随后立即退役链路，
 * 写进停摆的链路也只是丢掉这几帧。可能阻塞的写由调用方放到后台线程。
 */
object EaSessionStop {
    enum class Decision(
        val label: String,
    ) {
        SEND("发送"),
        NOT_STARTED("本轮未发过 0xEA00"),
        ALREADY_SENT("已发过 0xEA01"),
        UNDECLARED("车机可收清单不含 0xEA01"),
        LINK_CLOSED("链路已关"),
    }

    /**
     * @param startedProtocols 本轮已成功发出 `0xEA00` 的协议数
     * @param alreadyStopped 本轮是否已发过 `0xEA01`
     * @param declaresStop 车机可收清单是否含 `0xEA01`；没有清单为 null
     * @param linkAlive 链路是否仍可写
     */
    fun decide(
        startedProtocols: Int,
        alreadyStopped: Boolean,
        declaresStop: Boolean?,
        linkAlive: Boolean,
    ): Decision =
        when {
            startedProtocols <= 0 -> Decision.NOT_STARTED
            alreadyStopped -> Decision.ALREADY_SENT
            declaresStop == false -> Decision.UNDECLARED
            !linkAlive -> Decision.LINK_CLOSED
            else -> Decision.SEND
        }
}
