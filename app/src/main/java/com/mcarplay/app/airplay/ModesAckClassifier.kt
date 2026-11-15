package com.mcarplay.app.airplay

/**
 * `modesChanged` 应答体的语义分类（只读诊断）。
 *
 * 成功案例里车机用 `appStates` 回显模式切换结果（`appStateID=1, state=true` 表示主屏已给手机）；
 * issue #62（奔驰 MB_E-P-4，AirPlay 480.7.4）109 s 内每次应答都是 **42B `{}`**，从未出现任何
 * `appStates`，同期车机也从未订阅 MIB3（0x4157/0x4154/0x4170/0x5000 一条都没往返）。这可能是
 * 「车机没有处理本次模式切换」，也可能是「该固件不回显、用别的通道确认」。本类只把应答体
 * 归到三类并计数，供报告区分这两种情况；**不据此改变**发送时序或仲裁结果。
 */
internal object ModesAckClassifier {
    enum class Kind {
        /** 应答体为空：车机没给任何业务回执。 */
        EMPTY,

        /** 应答体是字典但没有任何 `appStates` 条目：结构合法、语义为空（如 `{}`）。 */
        NO_APP_STATES,

        /** 应答体带 `appStates`：车机回显了模式状态（是否确认仍需看内容）。 */
        APP_STATES,

        /** 应答体解析不出 plist，或顶层不是字典。 */
        UNREADABLE,
    }

    /** 分类一段应答体；[body] 为原始字节。 */
    fun classify(body: ByteArray): Kind {
        if (body.isEmpty()) return Kind.EMPTY
        val root =
            runCatching { BinaryPlist.parseRecovered(body).value as? Map<*, *> }
                .getOrNull() ?: return Kind.UNREADABLE
        val states = root["appStates"] as? List<*> ?: return Kind.NO_APP_STATES
        return if (states.isEmpty()) Kind.NO_APP_STATES else Kind.APP_STATES
    }

    /** 一行说明：类别 + 字节数 + 形状（`appStates` 存在时附带是否含 `state=true`）。 */
    fun describe(
        body: ByteArray,
        shape: String?,
    ): String {
        val kind = classify(body)
        val evidence =
            when (kind) {
                Kind.APP_STATES -> {
                    val confirmed =
                        runCatching {
                            val root = BinaryPlist.parseRecovered(body).value as? Map<*, *>
                            val states = (root?.get("appStates") as? List<*>).orEmpty()
                            states.any { entry ->
                                val map = entry as? Map<*, *>
                                (map?.get("state") as? Boolean) == true
                            }
                        }.getOrDefault(false)
                    if (confirmed) "，含 state=true" else "，无 state=true"
                }
                Kind.EMPTY, Kind.NO_APP_STATES -> "——车机未回显模式状态"
                Kind.UNREADABLE -> "——无法解析"
            }
        return "${body.size}B ${kind.name}$evidence" + (shape?.let { " 形状=$it" } ?: "")
    }
}
