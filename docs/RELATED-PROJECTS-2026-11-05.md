# 相关 CarPlay 桥接项目调研与 mcarplay 借鉴清单

调研日期：2026-11-05（Asia/Shanghai）。调研时 mcarplay 的代码基线是 `3561308`（分支 `fix/chery-inbound-wait`，未合入 main）
加当时工作区的修改；文中的行号链接已按 main `9dd5894` 加本分支的改动更新。
外部仓库固定在下表所列提交。本文是对源码与文档的离线对照。第 1、2 项已在同日实现并通过单元测试，但没有上车验证；其余建议
都没有实现。外部项目的实车结果不是 mcarplay 的兼容性证据（见 `AGENTS.md`）。

**结论：三个项目和 mcarplay 走同一条技术路线；它们修过的基础问题，mcarplay 大多已经覆盖。值得跟进的是几项针对具体车机的做法，
而且大多要先用 mcarplay 自己的报告确认同样的现象，再动代码。** 改动小、可以先做的有两项：断开时补发 `0xEA01`，以及网关兜底优先
选 IPv4。`0xEA01` 已有定义，但代码从未发送。「返回车机」要先用已有的「未处理命令」日志确认 CarWith 发的是哪条命令，
再对比手机向车机发 `requestUI` 和 `modesChanged` 两种做法的效果。

## 调研对象

| 项目 | 固定提交 | 许可 | 视频源 | 覆盖范围 |
| --- | --- | --- | --- | --- |
| [CarPToCarL](https://github.com/jjuugk/CarPToCarL) | `70db71e`，v1.2.21，2026-11-04 | MIT，CarDeck 系 | 劫持荣耀车联中百度 CarLife+ 引擎的本机七路 | 奥迪 MIB3（3874 / C8-4580 / 4910 固件）有实车记录；大众 ID7、通用 / 凯迪拉克 Cinemo、别克、雪铁龙为分析文档 |
| [AuDiCar `AMCar` 分支](https://cnb.cool/xi_zhu/AuDiCar/-/tree/AMCar) | `ce35062`，AMCar 1.0.18，2026-11-04 | MIT，fork 自 CarPToCarL | 同一劫持路线，在小米 CarWith 4.0.21 + `com.baidu.carlife.xiaomi` 8.8.5 上测试 | 多代奥迪 MIB3 适配；真 iPhone 对照日志（`docs/iphone-ref-*.md`） |
| [hypercar](https://github.com/fw867/hypercar) | `739c6b0`，2026-11-04 | **GPL-3.0** | 通过 Shizuku 自绘界面并编码，或可选 CarWith 模式 | 宝马 MGU-55 / iD8.5；Kotlin + Rust 核心；`docs/CARWITH-SOURCE.md` 将 mcarplay@`a3a4117` 列为 CarWith 参考 |

三者与 mcarplay 的手机侧角色相同：先在蓝牙 RFCOMM 上走 iAP2，拿到车机热点后再走 Wi-Fi + AirPlay RTSP。视频都来自 CarLife
七路（7240 / 8240 / 9240 / 9241 / 9242 / 9340 / 9440），区别在于由谁启动 CarLife 引擎、怎样进入投屏状态。

**许可边界：** CarPToCarL 和 AuDiCar 是 MIT，可以移植代码，但要保留版权声明并更新 `THIRD_PARTY_NOTICES.md`。hypercar 是
GPL-3.0，只能参考其中的行为观察和设计思路，不复制代码、注释或文档原文。

## mcarplay 已有的部分

下面几项在外部项目里是专门修过的问题，mcarplay 已经处理，不重复列为待办：

- **重连首帧必须是 IDR，并带 SPS/PPS。** CarPToCarL 在 ID7 上 8 次重连都以 P 帧开头，0.2 秒内就被车机断开。mcarplay 的
  [`VideoSender`](../app/src/main/java/com/mcarplay/app/video/VideoSender.kt) 会等 CarWith 的 IDR，并在主屏拿回后重发
  VideoConfig、内联 SPS/PPS。
- **推流中晚到的 `0x4301`。** [`SessionController.kt:2521`](../app/src/main/java/com/mcarplay/app/SessionController.kt:2521)
  会忽略冗余端点，不打断现有会话。
- **CarLife 前台请求。** `HU_GO_TO_FOREGROUND`（`0x18025`）由
  [`CarLifeCommandRouter`](../app/src/main/java/com/mcarplay/app/carlife/CarLifeCommandRouter.kt) 应答。
- **车机发来的 `requestUI` 和 `changeModes`。** 两者由 [`ModeArbiter`](../app/src/main/java/com/mcarplay/app/airplay/ModeArbiter.kt)
  统一仲裁。
- **车机要关键帧时向 CarWith 发 RESET。** [`CarLifeSession.requestKeyFrame`](../app/src/main/java/com/mcarplay/app/carlife/CarLifeSession.kt:196)
  在推流状态下发 `VIDEO_ENCODER_RESET`（`0x1800B`）。
- **音频 SETUP 字段。** [`AirPlayClient.kt:2108`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt:2108) 起的
  请求体带 `type`、`streamConnectionID`、`audioFormat`、`audioType`、`audioLatencyMs`、`input=false`、
  `supportsRTPPacketRedundancy=false`、`supportsHighAccuracyTimestamps` 和 `spf`。CarPToCarL 在 ID7 上音频 SETUP 三档全部
  返回 400；mcarplay 收到的两份 VW4131 用户报告（2026-11-04）里音频 SETUP 是成功的（证据记在尚未合入 main 的 `3561308`）。字段差异可能是原因之一，但没有对照实验，未确认。

## 建议

按「改动小、证据清楚」在前排序。每项都写明 mcarplay 现状、外部依据和验证要求。

### 1. 断开时发送 `0xEA01` StopEASession

**改动前：** [`Iap2SenderSession.kt:297`](../app/src/main/java/com/mcarplay/app/iap2/Iap2SenderSession.kt:297) 起，MFi 认证后
为每个 EA 协议发 `0xEA00`。[`Iap2MessageIds.kt:69`](../app/src/main/java/com/mcarplay/app/iap2/Iap2MessageIds.kt:69) 定义了
`EA_CLOSE_SESSION = 0xEA01`，但全仓库没有任何地方发送它。mcarplay 也不发 `0xEA04` 数据帧。

**外部依据：** CarPToCarL `docs/changes-20261104.md` 记录奥迪 MIB3 固件的 `mediaDisconnectTimeout = 15000 ms`：蓝牙断开满
15 秒，车机才判定设备「已移除」，在此之前保持 `keepIAP2Session=1`。他们新增的 `closeEaSessions()` 在断开前发 `0xEA01`，
有三种情况不发：
- 从未声明过 EA 协议；
- 链路已关闭；
- 对端超过 5 秒没有回任何帧。

同一批改动还把蓝牙重连间隔下限提到 16 秒，手动断开后冷却 18 秒。另据 `docs/audi-mib3-ea-tunnel-20261104.md`，在
`0xEA03` 到达之前写 `0xEA04`，车机会拆掉整条链路。

**建议：** 在有序断开路径上补发 `0xEA01`，参考同样的不发判据，并加单元测试覆盖「已声明 / 未声明 / 链路已关」。
重连间隔不要全局调到 16 秒：WinPlay、DiPlay 的快速重连属于发布门槛，只能通过
[`ReceiverCompatibility`](../app/src/main/java/com/mcarplay/app/compat/ReceiverCompatibility.kt) 按实例和固件启用。艾瑞泽 8 断开后
约 20 秒的原厂崩溃来自另一套固件的空闲定时器，与奥迪的设备移除计时不是同一机制，只能作对照方向，不能据此认定 `0xEA01`
能修复它。

**已实现（2026-11-05，未实车验证）：** 判据在 [`EaSessionStop`](../app/src/main/java/com/mcarplay/app/iap2/EaSessionStop.kt)，
发送在 `Iap2SenderSession.stopEaSessions()`，与上面的建议有几处不同：
- 不发的情况是：本轮没发过 `0xEA00`、已经发过一次、车机可收清单**明确声明**了但不含 `0xEA01`、链路已关。没有可收清单时照发，
  因为奥迪 9310 形态不发清单。
- 没有沿用「对端 5 秒无帧就不发」。Wi-Fi 交接后蓝牙控制链路本来就可能长时间空闲，这条判据会让稳态断开几乎永远不发；日志里改为
  记录对端上一帧距今多久。
- 每个协议的 `0xEA01` 参数照抄对应 `0xEA00` 的参数（协议号 + 名称）。规范里的参数是 EA 会话号还是协议号，现有证据定不了，所以
  `0xEA03` 的日志改为输出参数形状（如 `params=0:1 1:0`：1、2 字节参数给数值，更长的只给长度），等实车日志确认。
- 只在 `finishTeardown` 和空端点等待超时两条有序断开路径上发；DiPlay 模拟交接退役的路径不发。发送放在后台线程
  （`iap2-ea-stop`），发出后等 200 ms 再关链路；没有要发的就和以前一样同步关闭。
- 客户端 `stop()` 会中断会话线程，此前保活泵会在链路仍开时空转。现在被中断后最多等 1 s 链路关闭，然后退出。

日志关键字：`iap2 tx=0xea01 stopEASession`、`iap2 不发 0xea01`、`0xea03 … params=`。上车时要确认奥迪 MIB3 收到后的反应，
以及 DiPlay、WinPlay、macplay、艾瑞泽 8 的断开与重连不受影响。

### 2. 网关兜底优先 IPv4

**改动前：** `WifiCarPlayConnector` 的
`gatewayAddress()` 取 `routes.firstOrNull { it.isDefaultRoute }`，也就是第一条默认路由。

**外部依据：** CarPToCarL 1.2.20 在奥迪 4910 上发现，排第一的默认路由是 IPv6 链路本地 `fe80::…`。8 次重连全部打到这个从没通
过的地址，而同一会话的 mDNS 给出过可用的 IPv4 地址。

**建议：** 在所有默认路由中，先选 IPv4 网关，再选全局 IPv6，最后才选链路本地，并把候选列表写进连接日志。这是纯函数改动，
可以完整做单元测试。CarPToCarL 同时修了入网互斥问题；mcarplay 的 `joinCarPlayNetwork` 进入时先 `release()`
（[`WifiCarPlayConnector.kt:204`](../app/src/main/java/com/mcarplay/app/net/WifiCarPlayConnector.kt:204)），唯一调用点在
[`SessionController.kt:1431`](../app/src/main/java/com/mcarplay/app/SessionController.kt:1431)，看来不受影响。

**已实现（2026-11-05，未实车验证）：** `WifiCarPlayConnector.gatewayCandidates()` 收集所有默认路由的网关，交给
[`EndpointHosts.gatewayCandidates`](../app/src/main/java/com/mcarplay/app/net/EndpointHosts.kt) 剔除 `0.0.0.0` / `::` 并按
IPv4 → 全局 IPv6 → 链路本地排序；`gatewayAddress()` 取第一项。mDNS 兜底日志在有多个网关时列出其余候选
（`其余默认路由网关 …`）。兜底仍只把首选网关放进端点，行为与此前一致，只是选法变了。

### 3. 「返回车机」：先定位命令，再对比两种做法

**现状：** `IMPLEMENTATION_PLAN.md` 已把这个按钮列为 11 月 4 日待解决问题。未识别的 7240 命令现在会记为
`CARLIFE: 未处理命令 kind=0x…`，但还没有复现数据。

**外部依据：** hypercar 自绘界面里的「返回车机」，由手机向车机发 `POST /command`，请求体是只含 `type: requestUI`、不带参数
的 bplist，并记录车机接受还是拒绝。它的 `docs/PROTOCOL.md` 说明 `requestUI` 请求的是原生车机界面，不等于断连。它的 README
和 `docs/VALIDATION.md` 都写明，各接收端上的实际效果（包括宝马）仍需逐一验证。

**建议：**
1. 用带「未处理命令」日志的构建复现，确认 CarWith 按钮对应哪条 CarLife 命令。
2. 在 remote 或 debug 构建里对比两种做法：
   - A：手机向车机发无参数 `requestUI`（mcarplay 已有向车机 POST `/command` 的通道，见
     [`AirPlayClient.assertModes`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt:817)，只需新增请求体）；
   - B：照真 iPhone 点 OEM 图标的序列，发出把主屏让给车机的 `modesChanged`。
3. 记录每种做法下车机是否切回原生界面、CarPlay 音频是否保留、再点 CarPlay 图标能否恢复。

修好之前，不对外宣称「返回车机」可用。

### 4. VW4131：先查 mcarplay 报告里有没有推流中的 ctrl 邀请

**外部依据：** CarPToCarL `docs/id7-blackscreen-diagnosis.md` 中的 ID7 自报 `VW4131`、AirPlay `320.17.7`、屏幕 2240×976，
与 mcarplay 用户报告的车机一致。他们观察到的链条如下：
1. 音频 SETUP 被拒（400）；
2. 车机在推流中发 ctrl 邀请，被手机忽略；
3. 15.6–20 秒后车机拆掉会话，屏幕变黑；
4. 手机重连，但首帧是 P 帧，车机再次拒绝，循环往复。

他们据此把推流中的邀请视为「要求重建」并处理。这个修法未经验证，ID7 的音频也始终没有出声。

**现状：** mcarplay 在 VW4131 上的症状不同：音频 SETUP 成功，问题出在第二次 RECORD 后车机复位，随后整机重启。针对 VW4131 的
单次 RECORD 规则在 `3561308` 里，尚未合入 main，也在等实车复测。[`CarPlayCtrlAdvertiser.kt:314`](../app/src/main/java/com/mcarplay/app/net/CarPlayCtrlAdvertiser.kt:314)
收到邀请时只计数、记日志。

**建议：** 先在已有的 VW4131 报告里查推流期间有没有邀请，以及它与拆链的时间关系。确认有同样的模式，再考虑「邀请即重建」；
在此之前不要改邀请处理。

### 5. 帧率和画布尺寸实验

**现状：** [`CarLifeProtocol.kt:79`](../app/src/main/java/com/mcarplay/app/carlife/CarLifeProtocol.kt:79) 设
`DEFAULT_FPS = 30`；`videoInitPayload` 写入宽、高和帧率（`:244`）。
[`CarLifeStateMachine.kt:215`](../app/src/main/java/com/mcarplay/app/carlife/CarLifeStateMachine.kt:215) 用默认值请求，
`videoRequestFor` 把长边限制在 1920（`:265`）。

**外部依据：**
- **帧率：** AuDiCar 向小米 CarLife 引擎申请 60 fps，README 记录实际产出约 47–53 fps，并说明 `maxFPS` 只是上限，不会把帧率
  拉高。它的 `docs/4420-cl39-connect-analysis.md` 中也有申请 60、引擎确认 20 的记录，说明引擎可能自行降帧。
- **画布：** hypercar 的 `docs/CARWITH-SOURCE.md` 按接收端 `/info` 的显示尺寸请求画布（等比限制在 2560×2560 内），但两处记录
  互相矛盾：一处写请求 2560×744 后仍收到 1920×720，另一处写实际收到 2560×744。

**建议：** 只在 remote 或 debug 构建中加开关：申请 60 fps，长边上限放宽到接收端 `/info` 的尺寸。记录：
- CarWith `VIDEO_INIT_DONE` 实际回报的尺寸和帧率；
- 源帧率；
- 编码器负载和手机温度；
- 接收端 `maxFPS`，以及是否触发转码。

没有矩阵证据之前不改默认值。触摸映射要以实际回报的画布为准。

### 6. 车机语音键和 GPS 转发

**现状：** [`CarLifeProtocol.kt:250`](../app/src/main/java/com/mcarplay/app/carlife/CarLifeProtocol.kt:250) 的 `hardKeyFor`
只映射 MEDIA_NEXT / MEDIA_PREVIOUS。[`VirtualIap2Link.kt:558`](../app/src/main/java/com/mcarplay/app/airplay/VirtualIap2Link.kt:558)
收到 `0xFFFB` LocationInformation 只回链路 ACK。

**外部依据：** 见 CarPToCarL `docs/carlife-voice-gps-20261104.md`。
- **语音键：** 车机 `0x6802` HID 报告中的 Consumer usage `0xCF`（Voice Command）转发为 CarLife 控制通道 `0x68008` 硬键，
  `keycode = 0x21`（`KEYCODE_VR_START`），之后 CarLife+ 用手机麦克风启动语音。
- **GPS：** 把 `0xFFFB` 中的 NMEA 转为 `0x18010` CarlifeCarGps，900 ms 节流，只在已定位时发送，并有开关控制。

**建议：** 两项都可以作为可关闭的实验功能。先确认小米 CarLife 引擎是否响应 `0x21` 和 `0x18010`；AuDiCar 和 CarPToCarL 都没有
给出小米引擎上的实车结果。位置属于敏感数据：日志只记样本计数和定位状态，不记经纬度；新增字段同步扩展 `DiagnosticRedactor`
及其测试。

### 7. 让用户主动选择「附着现有 CarLife」

**现状：** 默认的 `COMPATIBLE` 策略（[`CarLifeSession.kt:164`](../app/src/main/java/com/mcarplay/app/carlife/CarLifeSession.kt:164)）已经先附着已有七口，
连不上才请求 CarWith 入口；只附着、不请求入口的 `ATTACH_EXISTING`
（[`CarLifeLauncher.kt:299`](../app/src/main/java/com/mcarplay/app/carlife/CarLifeLauncher.kt:299)）在用户界面上没有入口。

**外部依据：** CarPToCarL 和 AuDiCar 都要求用户先在手机上进入 CarLife「无线 WLAN 连接」，并停在「正在连接」，引擎这时才应答
协议（`docs/carlife-hijack-findings.md`）。

**建议：** 2026-11-04 的一份用户报告里，`M2102J2SC` / Android 13 / CarWith 4.0.4 / CarLife 8.6.8 配 mcarplay 0.1.4（已是
`COMPATIBLE`）在后台弹出已允许后仍 25 秒零帧，7240 一直拒绝连接（这份证据的整理尚未合入 main）。既然默认已先附着，不必新增策略；
可以在诊断步骤里加一步：用户先在 CarWith 里手动进入无线连接，再启动 mcarplay，看 7240 是否仍被拒。CarWith 界面上是否有对应的
「正在连接」状态，需要在真机上确认。

### 8. 诊断和连接细节的对照

- **iAP2 热点凭据重试：** AuDiCar 的 `docs/iphone-ref-summary.md` 记录，真 iPhone 在交接卡住时每 3 秒发一对
  `0x4E0E + 0x5702`，最多 5 轮。mcarplay（[`Iap2SenderSession.kt:350`](../app/src/main/java/com/mcarplay/app/iap2/Iap2SenderSession.kt:350)
  起）最多发两次 `0x5702`，中间重发设备状态问候，之后轮询 ctrl 邀请；`0x4E0E` 要到 `0x4301` 之后才发。
  建议先对照艾瑞泽 8 的真 iPhone 抓包，确认差异是否影响首次配对，再决定是否改。
- **发送缓冲采样：** hypercar 用 `TCP_INFO` 和 `TIOCOUTQ` 采样 TCP 发送队列（GPL，只参考思路），可以区分「本机发送积压」和
  「接收端停止读取」。mcarplay 现有诊断区分 CarWith 源帧和传输写入，但没有内核发送队列采样。
- **其他：** 计时服务器的请求计数，以及给每次连接尝试标注来源（自动、手动、回连），见 AuDiCar `docs/CHANGELOG.md`。两者都能
  减少报告里的歧义，属于诊断增强，按 `docs/DIAGNOSTICS.md` 的规则补充和脱敏。

## 不照搬的部分

- 外部项目的实车结论（奥迪、ID7、宝马等）不写进 mcarplay 的兼容性说明。mcarplay 的支持声明只能来自
  `docs/CARWITH-ENTRY-FINDINGS.md` 中的自有证据。
- 外部项目的修复很多标为「待上车验证」，引用时保留这一状态。
- 全局时序改动（16 秒重连下限、18 秒冷却）会影响已通过发布门槛的接收端，只能按车机策略启用。
- 外部文档中的 MAC、SSID、序列号等标识不复制进本仓库，本文也没有引用。

## 后续顺序

1. 第 1、2 项已实现并通过单元测试；合入 main 前在 DiPlay、WinPlay、macplay 和艾瑞泽 8 上做实机回归，并争取在奥迪
   MIB3 上确认 `0xEA01` 的效果。
2. 第 3、4 项先分析日志和报告，确认现象后再定方案。
3. 第 5、6、7 项作为 remote 或 debug 构建的实验开关；有矩阵或实车证据后，再考虑是否进入 standard。
