# HyperCar 增量洁净室复盘与车机适配对照记录

复盘日期：2026-11-28（Asia/Shanghai）。本文在 [`RELATED-PROJECTS-2026-11-05.md`](RELATED-PROJECTS-2026-11-05.md) 的基础上，结合
[`fw867/hypercar`](https://github.com/fw867/hypercar) 近期的公开更新与议题记录，独立梳理无线 CarPlay / CarWith 车机适配差异、
已在本地落地的工程修复，以及仍须等待自有实车证据才能决策的协议假设。

**许可与证据红线：**
- `fw867/hypercar` 采用 **GPL-3.0**，`mcarplay` 采用 [MIT](../LICENSE)。全程遵守洁净室（clean-room）原则：**不克隆外部仓库入本工作区，
  不复制其任何 Kotlin / Rust 代码、注释或文档原文**，所有实现均在本仓库既有架构内独立设计与编写。
- 外部项目的车型观察与代码分支**不构成** `mcarplay` 对任何车型或固件的支持证据（见 [`AGENTS.md`](../AGENTS.md)）。

---

## 1. 自 2026-11-05 初次调研以来本仓库已吸收或已覆盖的方向

与 11 月初相比，`mcarplay` 在后续迭代中已独立完成并验证了多项同域机制：

1. **CarWith 车机原生尺寸协商（`DISABLE_RESOLUTION_OPTION=1`）**
   [`CarWithVideoNegotiation.kt`](../app/src/main/java/com/mcarplay/app/carlife/CarWithVideoNegotiation.kt) 与
   [`ReceiverCompatibility.kt`](../app/src/main/java/com/mcarplay/app/compat/ReceiverCompatibility.kt) 已对具备合法 `/info displays[0]`
   几何的接收端默认开启 `preferVehicleResolution`，允许长边突破 1920（如 `2560×1200`、`1624×1278`），并在手机端兜底尺寸下自动抑制该标志。
2. **传祺 GS3 首轮窗口与显式启动主屏认领**
   手动预设 `TRUMPCHI_GS3_IAP2_WINDOW` 已覆盖先等对端 `FF 55 02 00 EE 10` 探针、`maxOutgoing=0x7F`、`SETUP` 交接期断蓝牙保留网络会话及
   用户显式点击连接时的受限主屏接管（`claimScreenOnExplicitStart`）。
3. **跨接收端策略隔离与自适应探测**
   按协议能力（`features`、消息清单、EA 标识、已核对固件）纯函数判定策略，配合 [`ReceiverPairingStore`](../app/src/main/java/com/mcarplay/app/airplay/ReceiverPairingStore.kt)
   与音频记忆作用域隔离，避免全局硬编码单一品牌假设污染其他车机。

---

## 2. 本轮增量对比识别出的 5 个车机适配点与处置

### 2.1 手动连接且关闭「上车自动连接」时的熄火蓝牙断开收摊（本地缺陷修复）

- **问题现象：** [`CarBluetoothReceiver.kt`](../app/src/main/java/com/mcarplay/app/bt/CarBluetoothReceiver.kt) 原先在处理
  `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` 分支前统一判断 `if (!AutoConnectPrefs.enabled(app)) return`。当用户关闭了「上车自动连接」、
  采用手动点击连接上车使用时，熄火下车导致的车机蓝牙 ACL 断开广播会被直接丢弃，不会向 [`CarPlayService`](../app/src/main/java/com/mcarplay/app/service/CarPlayService.kt)
  投递 `ACTION_AUTO_STOP_SESSION`，导致部分保持热点供电或延迟断电的车机无法及时收尾会话。
- **洁净室落地：** 抽出纯函数 `decideAclBroadcastAction`（单测 [`CarBluetoothReceiverPolicyTest.kt`](../app/src/test/java/com/mcarplay/app/bt/CarBluetoothReceiverPolicyTest.kt)）：
  `AutoConnectPrefs.enabled` 仅门控 `ACTION_ACL_CONNECTED` 的自动拉起与非车机忽略日志；目标/已识别车机的 `ACTION_ACL_DISCONNECTED`
  无论自动连接开关状态如何均投递 `ACTION_AUTO_STOP_SESSION`，由前台服务按既有的活跃轮次、对端地址匹配与 `bluetoothLossAction` 安全裁决。

### 2.2 蓝牙 RFCOMM 在途出站拨号抢占与「连通后秒断」回连让路（本地仲裁优化）

- **问题现象：**
  1. **在途拨号阻塞真实入站：** [`BtSessionGate`](../app/src/main/java/com/mcarplay/app/bt/Iap2BluetoothClient.kt) 原先从 `beginOutbound`
     起就无条件拒绝 `admitInbound()`。当出站 `socket.connect()` 正在阻塞等待（最长 11 s 有界拨号或系统默认超时）而车机恰好主动回连手机的
     `…cafe` 监听口时，[`Iap2BluetoothServer`](../app/src/main/java/com/mcarplay/app/bt/Iap2BluetoothServer.kt) 会把已经建立的真实入站
     socket 直接关掉。
  2. **连通后秒断踩满 3 次快速失败熔断：** 在 Phase 3b 台架回归中（见 [`CARWITH-ENTRY-FINDINGS.md`](CARWITH-ENTRY-FINDINGS.md) MacPlay `1.2.4` S2），
     对端先接受出站 RFCOMM `connect()` 再于 ~90 ms 内在端点交付前拆链；客户端每 3 s 重拨一次，约 8 s 内连败 3 轮触发 180 s 自动重连断路器并连同
     `Iap2BluetoothServer` 一起关停，刚好错开接收端约 12 s 的 SDP 刷新与主动反连窗口。
- **洁净室落地：**
  1. `BtSessionGate` 区分「在途 `connect()` 阻塞中」与「出站已连通」：当出站仍阻塞在 `connectOutbound` 内时，真实入站调用 `admitInbound()`
     会原子作废在途拨号、先关闭出站 socket 再放行入站，并将该次出站标记为取消（不计入失败统计）；已连通的出站会话仍拒绝后续重复入站
     （单测见 [`BtSessionGateTest.kt`](../app/src/test/java/com/mcarplay/app/bt/BtSessionGateTest.kt)）。
  2. [`InboundWaitPolicy.kt`](../app/src/main/java/com/mcarplay/app/bt/InboundWaitPolicy.kt) 将 `connected` 重置时点后移至出站会话真正交付端点之时，
     并新增 `postConnectDroppedBeforeEndpoint`：当出站 RFCOMM 已连通但在端点交付前于 `< 5 s` 内被对端快速关闭连续达 **2 次**（早于自动熔断的 3 次阈值）时，
     即使 `boundedDial=true` 也暂停主动出站 **30 秒**，保持 `Iap2BluetoothServer` 无竞争监听等待接收端回连；60 s / 90 s 慢协商超时（如保时捷退化
     `0x1d01`）不触发该暂停，继续沿用原有 3 轮熔断收尾（单测见 [`InboundWaitPolicyTest.kt`](../app/src/test/java/com/mcarplay/app/bt/InboundWaitPolicyTest.kt)）。

### 2.3 CarWith 静默回退 SPS 尺寸且车机零 `forceKeyFrame` 时的主动转码实验开关

- **问题现象：** 现有 [`TranscodePolicy.shouldTranscode`](../app/src/main/java/com/mcarplay/app/video/TranscodePolicy.kt) 刻意要求「尺寸不符 **且**
  出现车机 `forceKeyFrame` 风暴」才切手机端转码，以避免在奇瑞、DiPlay 等能直接缩放异形尺寸码流的接收端上白白增加手机发热。但实测已观察到两类例外：
  1. WinPlay 设 `3000×2000` 时，CarWith 虽回 `VIDEO_INIT_DONE 3000x2000@30`，实际 H.264 SPS 却静默回退为 `1920×720`；
  2. 部分严格车机（如 issue #62 奔驰 MBUX `MB_E-P-4`，全程 `forceKeyFrame=0`）收到与协商主屏几何不符的 `VideoConfig`/码流时既不报错也不发要帧请求，
     直接保持黑屏。
- **洁净室落地：**
  新增默认关闭的兼容实验开关 **「实验：源画面尺寸不符时直接转码」**（`CompatibilityPrefs.transcodeOnGeometryMismatch` →
  `ReceiverCompatibility.Policy.transcodeOnGeometryMismatch` → `ScreenSenderPlan` → `VideoSender`，手动「通用」预设显式关闭）：
  - 仅在用户显式开启、且本轮取得合法 `/info displays[0]` 几何（`setup.displayFromInfo == true`，排除手机端 `1080×2320` 兜底）时生效；
  - [`VideoSender.drainExternalVideo`](../app/src/main/java/com/mcarplay/app/video/VideoSender.kt) 在向车机写出任何透传 `VideoConfig` 之前检查首个源帧尺寸，
    一旦不符立即在后台启动 `CarWithTranscoder` 并将首个含 SPS/PPS 的 IDR 帧无缝喂入解码器，期间保持屏幕流 keepAlive 且不向车机泄漏错误尺寸的 `VideoConfig`。
- **同日追加（奇瑞 1280/1920 报告）：** 源画面比车机流宽超过 10% 时不再依赖这个默认关闭的开关，首个源帧自动转码；
  触摸映射同时改为优先使用 HID 描述符 Logical Minimum/Maximum，而不是 `/info displays[0]`。详见
  [`CARWITH-ENTRY-FINDINGS.md`](CARWITH-ENTRY-FINDINGS.md) 的 2026-11-28 条目。

---

## 3. 暂不合入生产路径、须待自有实车证据的协议差异点

以下观察点虽在外部无线实现中出现，但在缺乏本仓库脱敏支持报告或实车 A/B 对照前，贸然改动可能破坏已验证车机（艾瑞泽 8、帕萨特、奥迪 MMI、宝马等），
因此仅记录排查方向，**不在本轮修改默认协议行为**：

| 维度 | 外部项目做法概要 | `mcarplay` 当前实现 | 暂不盲改的原因与后续触发条件 |
| --- | --- | --- | --- |
| **网络隧道 `VirtualIap2Link` 时序与非 MIB3 订阅脉冲** | 对所有车机在 `type=130` 隧道认证后发一组电源/媒体状态消息；曾针对奔驰 MBUX 讨论流建立与隧道探测顺序 | [`VirtualIap2Link.kt`](../app/src/main/java/com/mcarplay/app/airplay/VirtualIap2Link.kt) 仅对 `CLASSIC_MIB3` 发送会话期状态脉冲；`type=130` 在初始 `SETUP`（早于 `RECORD` 与屏幕流 `SETUP`）建立 | 奇瑞等车机不支持 `type=130`（报 `Unsupported stream type: 130`），且向未订阅消息的现代车机乱发 iAP2 负载存在静默断链风险。待取得 issue #62 同款车机开启「尺寸不符直接转码」后的新报告再做单变量对照 |
| **External Accessory 会话号字节宽度** | 按 `uint16` 解析 `StartExternalAccessoryProtocolSession`（`0xEA00`）中的会话标识符 | 需对照既有回放夹具核实参数 TLV 长度（1 字节 vs 2 字节大端） | 若实车抓包出现 `0xEA00` 参数长度 ≠ 既有预期，用 TLV 自身长度自适应解析即可，无需破坏现有解析 |
| **蓝牙/隧道 `0xAE01 PowerUpdate` 参数集** | 对电源订阅回固定电源能力字段 | [`Iap2SenderSession.kt`](../app/src/main/java/com/mcarplay/app/iap2/Iap2SenderSession.kt) 已处理常规鉴定与认证闭环 | 无线场景下无物理 VBUS 供电协商；仅当支持报告显示某车机卡在 `0xAE00` 订阅后不发 `0x4E09`/`0x4301` 时再按车机 `0x0007` 接收清单门控补齐 |

---

## 4. 离线验证记录

- Python 诊断与工具单测：`/usr/bin/python3 -m unittest discover -s tools -p 'test_*.py'`（90 项通过）。
- Android / JVM 完整门禁：`./gradlew spotlessCheck :app:testStandardDebugUnitTest :app:lintStandardDebug :app:assembleStandardDebug :app:testRemoteDebugUnitTest --tests 'com.mcarplay.app.bridge.*'`（standard 单测 1,977 项、remote bridge 单测 25 项通过，0 失败 / 0 错误 / 0 跳过）。
- **边界声明：** 上述结果仅为本地编译与 JVM 单测验证，不代表奔驰 MBUX、MacPlay 或任何未复测实车已完成兼容性验收。
