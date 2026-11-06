package com.mcarplay.app.diagnostics

/**
 * 手机侧可观测的连接里程碑，按所属层归入 [ConnectionStage]；每轮只记首次到达的时刻。
 *
 * 分层依据 2026-11-02 艾瑞泽 8 车机侧日志的分析：iAP2 认证、热点入网、ctrl 邀请/Bonjour、
 * HomeKit 配对、MFi auth-setup 和 AirPlay 会话是相互独立的阶段，前一层成功不代表后一层成功
 * （车机侧 iAP2 `AUTHENTICATION_PASS` 之后仍出现过 pair-setup 被拒、30 s 超时重置）。
 * 注释里的车机侧信号只用于对照该车机的 logcat，不是其他车机的已验证行为。
 */
enum class ConnectionMilestone(
    val stage: ConnectionStage,
) {
    /** iAP2 链路同步完成（RFCOMM 已通）；可近似对齐艾瑞泽 8 的 TimerThread begin，不是其精确起点。 */
    BT_LINK(ConnectionStage.BLUETOOTH),

    /** 已回 0x1D02 IdentificationAccepted。 */
    IAP2_IDENTIFIED(ConnectionStage.BLUETOOTH),

    /** 已发 0xAA05 自报认证完成；不代表本机校验了证书/签名，也不代表 AirPlay 配对成功。 */
    IAP2_AUTHENTICATED(ConnectionStage.BLUETOOTH),

    /** 拿到车机热点信息（0x5703，或带 SSID 的 0x4301）。 */
    WIFI_CREDENTIALS(ConnectionStage.BLUETOOTH),

    /** 进程已绑定车机网络；车机侧对应 DHCPACK。 */
    WIFI_JOINED(ConnectionStage.WIFI),

    /** 收到车机 `GET /ctrl-int/1/connect`：车机已经发现手机发布的 `_carplay-ctrl._tcp`。 */
    CTRL_INVITED(ConnectionStage.DISCOVERY),

    /** 接收端 AirPlay 地址与端口已确定（0x4301、mDNS 或网关兜底）。 */
    RECEIVER_RESOLVED(ConnectionStage.DISCOVERY),

    /** TCP 已连上接收端 AirPlay 端口。 */
    AIRPLAY_CONNECTED(ConnectionStage.PAIRING),

    /** pair-setup 完成；复用已保存的信任时直接 pair-verify，不经过此项。 */
    PAIR_SETUP(ConnectionStage.PAIRING),

    /** pair-verify 完成，控制通道已加密。 */
    PAIR_VERIFY(ConnectionStage.PAIRING),

    /** `POST /auth-setup` 得到 HTTP 200；当前仅记录响应结构，不代表 MFi 证书/签名已验证。 */
    AUTH_SETUP(ConnectionStage.SETUP),

    /** 初始 SETUP 被接受；车机侧对应 `_AirPlayHandleSessionCreated`。 */
    SESSION_SETUP(ConnectionStage.SETUP),

    /** RECORD 被接受，会话已激活；车机侧对应 `AirPlay session started`。 */
    RECORD(ConnectionStage.SETUP),

    /** 收到首个 CarWith 视频帧。 */
    CARWITH_FRAME(ConnectionStage.CARWITH),

    /** 首个视频帧已写出；车机侧对应 `ScreenStreamStart`，但写出不等于车机已显示。 */
    FIRST_VIDEO(ConnectionStage.STREAMING),

    /** 首个音频包已写出；车机侧对应 Main audio setting up。 */
    FIRST_AUDIO(ConnectionStage.STREAMING),
}
