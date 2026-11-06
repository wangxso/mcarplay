# 艾瑞泽 8：协议报告适用性与回归矩阵

更新：2026-11-06。本文把用户提供的固件/协议报告转换为 mcarplay **手机发送端**的检查点和测试项。
报告中的真 iPhone 成功会话是接收端行为参考；本轮仅做源码核对和离线测试，没有新增 MCarPlay 实车验收。

## 对当前链路的帮助

报告的当前实车是 `IC2172 / 703000564AA / HW002`，SOC 为 `M1E_IC2172/82_03_21.387_250619_R`，
MCU 为 `M1E_IC2172_18_01_41.11_250609_R`，与仓库已有艾瑞泽 8 证据的 SOC 分支一致。
`CHERY / Tiggo / srcvers=320.17.7` 是当前代码识别的协议画像；`srcvers` 不是完整车机固件版本。

最有用的结论是分层定位，而非重写链路：

| 报告结论 | 当前实现与回归重点 |
| --- | --- |
| SPP/iAP2 → SoftAP/DHCP → Bonjour/Control → pair/MFi → session/media | `SessionController` 已按这些阶段编排；还需区分本机 CarWith 源是否出流。iPhone 报告不覆盖 CarWith 七通道。 |
| 车机反向 GET 手机 `/ctrl-int/1/connect` | `CarPlayCtrlAdvertiser` 发布 `_carplay-ctrl._tcp`；`CtrlInviteGate` 消费邀请，随后手机连接接收端。不能让双方都等对方主动建立 AirPlay。 |
| iAP2 PASS 后仍可 pair 失败 | `IAP2_AUTHENTICATED`、`PAIR_VERIFY`、`AUTH_SETUP`、`SESSION_SETUP`、`RECORD` 分开记录。iAP2 当前会自报 `0xAA05`，该里程碑也不证明证书/签名已被本机验证。 |
| 三套 OEM 状态的值相同但语义不同 | 应用用独立的阶段、里程碑、媒体计数表示本轮进展；不直接导入 OEM 数字枚举。`WirelessCarPlayStatus=1` 是能力更新，Linker `Connect(0)` 是会话事件，均不作为视频已显示的证据。 |
| 当前车机主音频 AAC-LC / 44100 / 2；辅音频另有 AAC-ELD / 48000 / 1 | 音频仍按实时 `/info` 能力协商，不能把 alert 编码当成主媒体编码，也不能仅凭该报告给所有接收端固定 44.1 kHz。 |
| `ScreenStreamStart` 晚于连接状态成功 | `RECORD` 确认、CarWith 源帧、视频写出、音频发包分别计时；显示、听感、触控由实车确认。 |
| pair 失败后 TimerThread 约 30 s 超时，再 Stop/END | 以车机 `TimerThread begin` 为精确起点；手机 `BT_LINK` 只近似对时。`ConnectionProgress` 的 30 s 提示不会结束尝试。后续 Start 是新的车机尝试；报告不证明上层一定自动重试。 |
| 会话后 Idle / SIGSEGV（正常结束和 `kConnectionErr` 均有样本） | 历史分析已定位周期回调读取空 server 的直接崩溃机制；重复 RECORD / 多次 SessionStart 仍是触发候选。按下节独立验收，不能把约 20 s 崩溃与建链 30 s 超时混算。 |

当前 CHERY 策略已包含固定 32B MFi 挑战、`X-Apple-HKP: 0`、会话内 `auth-setup`、真实 Wi-Fi MAC、
`disableBluetooth`、跳过 type-130 隧道；只有 `CHERY + Tiggo + 320.17.7` 自动命中单次 RECORD。
本轮不扩大策略匹配范围。`AirPlayRecordLifecycleTest` 继续保护其他接收端原有的重复 RECORD 行为。

**源码核对发现的认证边界：** `AirPlayClient.runAuthSetup()` 只记录响应结构，HTTP 200 就记录 `AUTH_SETUP`，
不校验证书/签名；HTTP 错误或 I/O 失败后仍继续 SETUP。`Iap2SenderSession` 也保留自报 `0xAA05` 的实验性行为。
因此本项目这些“认证”里程碑不能当作密码学认证成功；新增测试的合成 SAP 外形也没有证明 MFi 信任。
本轮仅补测试与说明，没有改变上述既有策略。真正的 MFi 校验及失败处理仍是未覆盖的实现边界。

## 使用报告时的边界

- mcarplay 是手机端：车机保留 pair 失败状态直至 TimerThread 超时，不要求手机在明确拒绝后继续使用失败会话。
  不能照搬报告末尾按车机侧框架提出的“保留底层状态机”建议，更不能把 30 s 改成所有接收端的断开期限。
- 车机日志的 TCP 7000、5 GHz / 149、`192.168.33.0/24` 是该次环境的观测值。控制监听口、媒体口、DHCP 租约、
  mDNS/`0x4301` 声明地址与端口仍动态获取。Android `authType`、native `wifiAPsecurity` 和 iAP2 `securityType` 分属不同枚举。
- 当前画布依运行时 `/info` 和实际视频 SPS 决定；不能把旧 `airplay.conf` 默认的 1280 代替当前注册的 1920×720。
- 84/88 是包内与运行时动作/字符串的对照数量；778/924 是 token 可追溯率。两者都不是协议报文覆盖率或本项目测试通过率。
- 每条证据按具体动作分级：A 是可定位的运行时观察，B 是静态能力，C 是推断；源码核对、合成测试和本轮设备验收另外标注。
  例如 UUID 字符串存在是 B，报告中的运行时连接字节与该 UUID 一致才构成 A；不能把整个报告统一视为抓包事实。
- 本轮没有 pcap、pair HTTP body、plist/TLV 原始材料；新增数据包、声明清单、凭据和时间线是合成数据，不能称为逐字节实车回放。
- 2172/2182 的 SOC payload 相同，但 MCU/型号不同，且均早于当前车机版本；不导入固件或第三方 APK，不将旧库替换到当前环境。
  USB 驱动、`StartWiredCarPlay` 仅为 B 级设计能力，本轮有线实测状态仍为“未验证”，无线失败也没有自动切 USB 的证据。

## 断开后约 20 秒崩溃：机制、候选与验收

2026-11-06 用户转述的另一边分析，与 [设备证据](CARWITH-ENTRY-FINDINGS.md) 中 11 月 3 日的原厂库静态分析和
13 份历史 tombstone 摘要一致。以下沿用这些已有证据；本次只核对记录和发送端源码，没有新增原始 tombstone 复核或设备实验。

| 结论层次 | 目前证据与边界 |
| --- | --- |
| 直接崩溃机制已定位 | 原厂 `_PerformPeriodTasks+640` 先取 `session+0x10` 的 server，再读 `[server+0xac]`；历史现场 server 为 0，访问 `0xac` 导致 SIGSEGV。Finalize 已清零该引用，周期回调仍执行。 |
| 约 20 秒的来源 | server `+0xac` 的默认空闲阈值为 20 秒；历史现场计时约 20.001 / 20.002 秒，Finalize 后仍见 Idle 10 / 20 秒。它与建链时从 `TimerThread begin` 起算的约 30 秒 watchdog 是两条路径。 |
| 优先触发候选，尚未证实 | 同一 session 多次成功 SessionStart 可能多次创建 timer，并覆盖 `session+0xd8`；清理只能取消当前保存的句柄，旧 timer 可能遗留。重复 RECORD 是否实际触发这条链，需要运行时关联证据。 |
| 当前发送端保护 | 精确 CHERY 画像每条控制连接最多发送一次 RECORD，并发共享结果，超时/拒绝也不在原连接重发；停止时拦住新业务请求，按连接代次丢弃迟到任务。仅证明本机行为受限，不能证明原厂 timer 已完全退出。 |

已有实验中，以下差异被修正或对齐后仍出现相同崩溃，不能再把它们单独当作已成立的触发解释：

| 已对照的方面 | 记录结论 |
| --- | --- |
| 蓝牙关闭顺序、type-130、真实 Wi-Fi MAC、来源名、`speechMode` | 对齐或禁用后仍复现。 |
| HKP、`auth-setup`、MFi 32B 挑战 | 相关流程已正常完成的会话仍在结束后崩溃；挑战长度导致的认证挂起另行归档。 |
| 结束原因、TCP 残留 | 正常结束和 `kConnectionErr` 均有崩溃；TCP 已清理的样本仍在 Idle 后崩溃。 |

一次 `session started` 日志不等于实际只执行过一次 Start，也不能代替 timer 创建/取消计数。
当前候选同时调整了 RECORD 和停止流程，即使实车不再崩溃，也只能先记“组合候选验收通过”。
要归因于单次 RECORD，需保持停止逻辑及其他参数一致，仅对 RECORD 策略作单变量对照；本轮没有制作或执行该对照构建。
不为补足计数而修改原厂库；原厂观测不到的字段标记为缺失，不凭手机计数补写。

A8-07 / A8-08 / A8-09 的断开稳定性记录补充以下判据：

| 检查项 | 操作与验收 | 必留证据 |
| --- | --- | --- |
| 环境和会话边界 | 从干净的原厂 CarPlay 进程开始；同手机、车机固件、配对状态、操作步骤分别记录。 | APK SHA-256、策略命中、手机 attempt/控制连接代次、车机进程 PID；同 session 的身份仅在本地关联后脱敏。 |
| RECORD 与动态音频 | 正常视频起流、动态开声音、暂停/恢复声音与返回 CarPlay 后逐一核对；同一控制连接实际发出的 RECORD 不超过一次。 | 线上请求数与应答、客户端调用与线上发送的区别；可获得的原厂 SessionStart、timer 创建/取消及 Finalize 时间。 |
| 正常和自然异常退出 | 手动断开、动态开声音后断开、自然掉网结束分别记录；屏幕 TEARDOWN → 会话 TEARDOWN 后继续观察至少 60 秒。 | 分别以最后 TEARDOWN 和原厂 Finalize（如可见）对时；Idle、进程存活/重启、tombstone、观察区间与重连结果。提前重连会打断独立观察窗口，另开一轮。 |
| 重复观察和判定 | 每种可复现场景独立重复至少三轮，每轮完成观察后再重连；未自然出现的异常场景标记未验证。 | 逐轮结果及失败样本；无 Idle 日志不等于无回调，进程已重启也不能算未崩溃。 |
| RECORD 的因果归因 | 只有单变量对照和同 session 的 Start/timer 身份能关联“多次 Start → 旧 timer 遗留 → Finalize 后回调”，才升级触发结论。 | 同条件对照差异及完整时间轴；只有“不再崩溃”或一条 started 日志时仍保留候选状态。 |

现有 `AirPlayRecordLifecycleTest` 的 9 项真实 TCP 回环已覆盖单次/并发 RECORD、拒绝/超时不重发、停止门控、
重连与旧代次隔离；本轮新增的音频协商场景也检查动态音频后不重复 RECORD。这些测试验证发送端防护；
原厂周期任务及其退出仍需设备证据。当前设备验收状态仍为待验证。

## 自动回归

所有新增测试都进入现有 `:app:testStandardDebugUnitTest`，不依赖被忽略的私有 `.jsonl`、车机库、固件或原始日志。
以下“覆盖”仅指发送端的相关软件行为；JVM 的 Android stub 不验证系统 NSD、蓝牙、DHCP 或硬件 MFi。

| 用例 / 位置 | 行为与断言 | 证据性质 |
| --- | --- | --- |
| `Iap2SenderSessionTest.cheryWifiCallbackPrecedesEndpointHandoffWithoutStartSession` | 真实内存双工 iAP2 链路走识别/挑战/凭据交换；CHERY 发出一次 32B 挑战；信道 149 按无符号字节读取、iAP2 安全值 2 不串枚举；收到凭据即回调，省略 `0x4301` 时交给 Wi-Fi/mDNS，不宣称 AirPlay 已配对或出帧。 | 新增合成会话 |
| `Iap2SenderSessionTest.cheryAdvertisedEndpointKeepsItsPortAndLegacyWifiParameters` | 同一合成画像分别声明 7000 / 47001，保留声明端口与 IPv4/IPv6 地址，并优先采用旧协议 `0x5703` 的信道/凭据。 | 新增合成会话 |
| `Arrizo8ConnectionRegressionTest.authenticationNetworkAndControlSuccessDoNotCompleteAirPlayPairing` | iAP2、入网、ctrl 邀请和 TCP 连通均不能代替 pair-verify；配对失败最终保留 `PAIRING_FAILED`，视频计数为零。 | 新增诊断场景 |
| `Arrizo8ConnectionRegressionTest.sessionActivationVideoAndAudioHaveSeparateFirstArrivalTimes` | 借用 9.690 / 11.347 / 13.864 s 三个参考偏移驱动手机模型；分别保存 RECORD、视频写出、音频发包时间，不把它们映射成车机解码成功。 | 新增诊断场景 |
| `Arrizo8ConnectionRegressionTest.thirtySecondWindowIsAnObservationAndDoesNotEndThePhoneAttempt` | 30 s 从本轮 BT_LINK 近似计时；30.002 s 只提示，不自动 END；清理不覆盖原失败码或冻结的耗时。 | 新增诊断场景 |
| `Arrizo8ConnectionRegressionTest.retryAfterFailedPairingIsANewAttemptAndLateCleanupCannotReplaceIt` | 失败后新的 Start 使用新 attempt，清空里程碑；旧轮 pair、视频、音频、错误和停止回调不能污染新轮。 | 新增诊断场景 |
| `Arrizo8ProtocolRegressionTest.hkpZeroPairingAndEncryptedAuthDoNotImplySessionSetupSuccess` | 真实 TCP 完成 SRP/Ed25519 配对与控制加密；检查 HKP=0 和 Apple 头。合成 SAP HTTP 200 后 SETUP 403 仍不能标记会话/RECORD 成功。 | 新增合成回环；不验证 MFi 证书/签名 |
| `Arrizo8ProtocolRegressionTest.parsedAac44100MediaCapabilityDrivesSetupWithoutType130OrAnotherRecord` | 解析 `/info` bplist 并选 AAC-LC 44100 / 2、对应延迟；实际音频 SETUP 按类型取返回端口，跳过 type-130，音频后不重复 RECORD。 | 新增合成回环 |
| `Arrizo8ProtocolRegressionTest.audioSetupRequiresSuccessAndAMatchingStreamWithADataPort` | 拒绝 HTTP 500 携带合法流、200 携带错误流类型、200 匹配类型却没有 dataPort 三种响应；均不激活音频。 | 新增合成回环 |
| `AirPlayRecordLifecycleTest`、`AirPlayOrderlyStopTest` | 单次 RECORD、拒绝/超时、并发、代次隔离、停止期间通知与屏幕 socket 的 TEARDOWN 顺序。 | 既有回环；本轮复用工具 |
| `ReceiverCompatibilityIntegrationTest`、`AirPlayPairingTest`、`PairingRejectionTest`、`AuthSetupResponseTest` | 配对、auth-setup 响应形状、能力与会话阶段相关行为。 | 既有回环；不验证真实 MFi 信任 |
| `CtrlInviteGateTest`、`EndpointHostsTest`、`NetworkBindingTest` | 邀请新鲜度、等广播可见后重公告、有界 mDNS 回退、动态地址与旧网络回调。 | 既有自动覆盖；NSD/HTTP 邀请仍需实机检查 |
| `TouchReportsTest`、`AudioSenderTest`、`SessionSetupBodyTest`、`CarLifeJoinGateTest` | 按描述符解析输入，按能力生成媒体参数，首个 IDR/SPS/PPS 门控。 | 既有合成数据；不是该车 HID/码流实录 |

定向运行新增场景及相邻生命周期用例：

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'com.mcarplay.app.iap2.Iap2SenderSessionTest' --tests 'com.mcarplay.app.diagnostics.Arrizo8ConnectionRegressionTest' --tests 'com.mcarplay.app.airplay.Arrizo8ProtocolRegressionTest' --tests 'com.mcarplay.app.airplay.AirPlayRecordLifecycleTest' --tests 'com.mcarplay.app.airplay.AirPlayPairingTest'
```

仓库完整门禁：

```bash
./gradlew spotlessCheck :app:testStandardDebugUnitTest :app:lintStandardDebug :app:assembleStandardDebug :app:testRemoteDebugUnitTest --tests 'com.mcarplay.app.bridge.*'
```

## 88 阶段对应的实车测试项

所有项目当前均为**本轮待实车验证**。下面覆盖原 `FIRMWARE_CARPLAY_CHAIN.md` 的阶段编号；只有表明有本项目自动用例的部分才算软件回归覆盖。
不为验证而修改车机配置、断开认证芯片、刷旧固件或自动清除配对；负例可在合成接收端上执行，实车仅复现自然发生的故障。

| ID / 原报告阶段 | 操作与验收 | 必留证据 |
| --- | --- | --- |
| A8-01 / 1–13、15 | 从干净 CarPlay 进程/正常车辆冷启动建立环境基线；核对功能、身份、配置及运行时 1920×720/30。对 ACC OFF 的行为只记录本车结果，不能沿用旧包 true 的值。 | 手机/ROM/组件、SOC/MCU、APK SHA-256、策略规则；屏幕能力与实际 SPS 分开。 |
| A8-02 / 16–21、85 | 首次配对、已有配对重连、车机蓝牙页点选手机各测试；检查车机向手机 iAP2 服务回连。普通连接不靠 debug `BtAnchorService` 或临时 root 公告。 | SDP/ACL/SPP、目标角色、iAP2 链路与识别时间；10005 不作 RFCOMM 信道。 |
| A8-03 / 22–34 | 观察车机 AP 重建、加入、DHCP、绑定和公告迁移；变更租约后仍能连接。AP MAC 暂未就绪/全零时，区分随后恢复和持续失败。 | AP-ENABLED、实际信道、DHCP、绑定网络、公告注册；不记录明文 PSK、SSID/MAC。 |
| A8-04 / 35–48 | 分别记录 Start、TimerThread begin、iAP2 PASS 和凭据下发；固定 32B 挑战不轮换至 20/16B；首次连接至少三次。 | 双端脱敏对时、挑战长度/应答耗时、凭据“已收到”标记；无材料时不声称签名验证成功。 |
| A8-05 / 49–57 | 检查 `_airplay._tcp`、手机 `_carplay-ctrl._tcp` 的动态监听口、车机 GET 及手机 HTTP 200；再连车机 AirPlay。首次入网、切换回热点和缓存命中各复测。 | 当前解析端口、ctrl 请求/应答、CTRL_INVITED 与 AIRPLAY_CONNECTED；HTTP 可达不等同 pair 成功。 |
| A8-06 / 58–62 | 首次 pair-setup / pair-verify / 会话 MFi auth-setup；再次连接检查保存的信任优先 pair-verify。自然遇到 bad setup code / unknown peer 时独立记录，不以 iAP2 PASS 掩盖。 | 配对结果、`X-Apple-HKP: 0`、auth-setup 状态与耗时；不导出密钥、证书或签名原文。 |
| A8-07 / 63–70 | 核对 SETUP、RECORD、Modes/HID、`disableBluetooth`；确认蓝牙释放后画面/声音/触摸仍工作。CHERY 精确画像每条控制连接只发一次 RECORD。 | 同一控制连接的 RECORD 数、模式变化与蓝牙释放前后发送计数、用户三项确认。 |
| A8-08 / 71–74 | 视频出画、实际尺寸/帧率、全屏角点点击与拖动；音乐、导航混音、暂停/恢复、换曲和动态开声音；记录主/辅音频各自格式。 | RECORD / 首视频写出 / 实车首画 / 首音频包 / 实车首声各自时间；音画触控确认。type-130 不支持单独归档。 |
| A8-09 / 75 | 按“断开后约 20 秒崩溃”一节验收：手动断开、动态开声音后断开、自然掉网结束各记录；可复现场景至少三轮，每轮核对屏幕 TEARDOWN → 会话 TEARDOWN 并独立观察至少 60 s 后再重连。 | RECORD/SessionStart 与 timer 关联（如可见）、断开原因、TEARDOWN/Finalize、Idle、PID/重启、tombstone、下次新 attempt；未自然出现的场景或缺失证据标记未验证。 |
| A8-10 / 76–84 | 合成接收端注入 pair 拒绝、EOF、超时；实车自然失败时按 TimerThread begin 算约 30 s，核对 `0 → 6 → 5`、Stop、Bonjour 注销，再看上层是否有新 Start。 | 每次尝试单独时间轴，失败原因及完整清理；不能只见随后成功就记“同一轮无缝恢复”。 |
| A8-11 / 会话后补充 | 一次连续至少 30 min，覆盖导航/音乐/触控及返回原生车机后再进 CarPlay；测试锁屏、ACC OFF/ON。结果按动作分别记录，不沿用旧会话通过结论。 | 媒体计数、听感/同步、返回后触摸、退出原因；断开后另观察至少 60 s。 |
| A8-W / 14、86–88 | 有线 CarPlay 单列选测：只有用户实际插 iPhone 并观察 USB 枚举、iAP 会话、画面/声音/触控后才能改“未验证”。不算 mcarplay 无线回归的通过条件。 | USB 物理口、枚举与有线会话；`iPodDeviceIO=2`、`usb0`、函数名含 USB 均不能单独证明。 |

报告给出的 9.690 / 11.347 / 13.864 s 是参考样本，不是硬性通过阈值；从 AP READY、Wireless Start、Timer begin、手机按键开始的计时必须各自标明。
`ScreenStreamStart` 表示车机屏幕流启动，本身也不足以确认用户已看到首张正确画面。

## 合并前的跨接收端回归

任何连接/协议行为修改仍需以下四种接收端的同一候选构建验收。本轮新增测试不代替这一门槛。

- [ ] 艾瑞泽 8：A8-01 至 A8-11；特别记录单次 RECORD、动态声音、断开后 60 s 与直接重连。
- [ ] DiPlay：首次/复用配对、type-130、音画触控、导航混音、手动断开与重连。
- [ ] WinPlay：网络交接后的蓝牙释放、首帧前后会话保持、开关声音与导航混音、断开重连。
- [ ] macplay：配对、实时能力/端口协商、音画触控、断开重连；填实际应用/系统版本。

每台至少记录：测试日期/时区、手机型号、HyperOS、CarWith、小米 CarLife、接收端与固件、APK SHA-256、
车型预设（自动/手动）、操作步骤、各阶段结果、画面/声音/触摸用户确认、断开后观察秒数、重连结果和证据等级。
结果追加到 [设备证据](CARWITH-ENTRY-FINDINGS.md)，失败同样记录；不得把 iPhone 参考样本或本机写出计数填成 MCarPlay 实车通过。

## 本轮验证记录

2026-11-06，JDK 21.0.13、Android SDK 35 / Build Tools 35.0.0，使用 `--offline`：

- 新增 **9 项** `@Test`：iAP2 2 项、AirPlay 3 项、诊断 4 项；未删除既有用例。
- 定向回归 **49 项通过**，0 失败、0 错误、0 跳过；包含上述 9 项与相邻配对/生命周期用例。
- 完整 `testStandardDebugUnitTest`：**848 项通过**，0 失败、0 错误、0 跳过。
- 过滤后的 `testRemoteDebugUnitTest --tests 'com.mcarplay.app.bridge.*'`：**24 项通过**，0 失败、0 错误、0 跳过；同时编译 remote 源码。
- `spotlessCheck`、`lintStandardDebug`、`assembleStandardDebug` 全部通过。lint 为 0 Error/Fatal、61 Warning、2 Information；未改 lint 基线。
- `git diff --check` 通过；5 个输入报告指纹与 9 个新增用例索引均已核对。

定向 49 项包含在完整 standard 测试中，不与 848 相加。Python/Node 工具未修改，本轮未重复运行其测试。
上述报告对照这一轮的运行时代码只改了诊断注释，连接、配对、媒体与停止行为保持现状，当时未安装到设备。
实车、系统 NSD/蓝牙、硬件 MFi 与断开后原厂稳定性未由上述离线回归验证；有线未实测。
同日随后另做的手机端 SDP 生命周期修改与 HyperOS AVD 检查见 [手机兼容记录](HYPEROS-PHONE-COMPATIBILITY.md)，
其最新门禁为 standard 859 项、remote bridge 24 项；不能与本节的历史次数累加。

同日随后补充的崩溃机制、既有对照项和单变量验收仅修改本文、设备证据与实施计划。
文档链接、9 项新增测试索引、既有 9 项生命周期用例数量与 12 行实车矩阵完整性检查通过；此次文档补充未重复运行 Gradle。

## 报告来源与可复核指纹

输入来自用户本机 `arrizo8carplay` 分析目录；正文未复制进仓库，仅保留文件名、章节引用与 SHA-256。
PDF 通过系统 PDFKit 确认 27 页，并抽读第 1、23、24 页的协议摘要与工程建议；本轮不重新验收其排版。

| 文件 | 本文主要引用 | SHA-256 |
| --- | --- | --- |
| `CARPLAY_PROTOCOL_ANALYSIS.md` | §2 证据分级、§5 时间线、§7 状态机、§9 失败回退、§13 限制 | `fb6dc775fe1232e83adb256ad57cd272051ec1d2c6ae2ed4e588363370ded86c` |
| `FIRMWARE_CARPLAY_CHAIN.md` | §3 配置漂移、§5 链路、§6 失败、§7 的 88 阶段 | `c48895acac1ce329fb3a1740bd0fdfd482411060024d193ca549d5f662577e5e` |
| `FIRMWARE_PACKAGE_ANALYSIS.md` | §1/§13 版本、§8/§15 认证及有线能力边界 | `86b44a861015957bcaabdd60dbe5e2d8e837dea755ecbeaf9d608266883eae5d` |
| `carplay-protocol-report.html` | §10 工程建议及手机/车机角色边界 | `9754bae00713c865753d58990fa70f3d41dbdff40639ed7eb287f18034f042dd` |
| `carplay-protocol-report.pdf` | 第 23–24 页工程建议 | `d3d2f07ddacdde4e37e65bf39eb9a21d5a0427979b8371dc1a8bf7e3a63ef69a` |
