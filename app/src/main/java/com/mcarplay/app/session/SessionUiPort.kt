package com.mcarplay.app.session

/** 弹窗规格（加入热点确认等两按钮询问）。 */
data class DialogSpec(
    val title: String,
    val message: String,
    val positive: String,
    val negative: String,
)

/**
 * 编排代码通向挂屏界面的唯一通道（docs/ARCHITECTURE-REFACTOR.md Phase 1a）。
 *
 * 界面可能随时不在（Activity 销毁重建），所以每个方法都允许无人接收。
 * 后续抽出的模块（Wi-Fi 入网、端点解析等）只依赖这个接口，不直接读写 `SessionController` 的字段。
 */
interface SessionUiPort {
    /** 英雄卡副标题的一句话阶段提示；null 时界面显示默认文案。 */
    fun phase(hint: String?)

    /** 需要用户留意的提示（WLAN 未开启、热点加入受限等）。 */
    fun hint(message: String)

    /** 拿到车机热点 SSID，界面回填输入框。 */
    fun ssidChanged(ssid: String)

    /**
     * 弹两按钮确认框，在主线程调用。返回 false 表示当前没有界面可以弹窗，[onChoice] 不会被调用。
     * [onChoice]：true=positive；false=negative；null=被关闭。
     */
    fun ask(
        spec: DialogSpec,
        onChoice: (Boolean?) -> Unit,
    ): Boolean
}
