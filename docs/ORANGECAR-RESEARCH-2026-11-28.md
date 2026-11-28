# OrangeCar（有线 CarPlay 发射端）洁净室调研与 mcarplay 对照记录

调研日期：2026-11-28（Asia/Shanghai）。本文基于公开资料与用户提供的协议摘要，对开源项目
[`sbuysse/orangecar`](https://github.com/sbuysse/orangecar) 做离线洁净室（clean-room）对照。

**许可与证据红线：**
- `sbuysse/orangecar` 采用 **GPL-3.0**，`mcarplay` 采用 [MIT](../LICENSE)。与
  [`RELATED-PROJECTS-2026-11-05.md`](RELATED-PROJECTS-2026-11-05.md) 中对 `hypercar` 的边界相同：**绝不将外部仓库克隆入本仓库，
  绝不复制其任何代码、注释或文档原文**，仅就公开协议现象与工程机制做独立分析。
- 该项目仅在单一有线接收端（Jaguar F-PACE，Intel `8086:9999`，`srcvers 280.33.12.1`，1200×512）上验证；外部项目的实车观察
  **不构成** `mcarplay` 的车型兼容证据（见 [`AGENTS.md`](../AGENTS.md)）。
- 本轮为 Phase 3b 实车回归之后、Phase 3c 之前的离线调研：**不修改 APK 生产代码**，不触碰运行时日志骨架与协议时序，仅新增
  `Iap2LinkEngine` 边界单测与离线诊断脚本能力。

---

## 1. 架构定位差异：为什么免 root 安卓手机不走「有线前四步」

OrangeCar 运行在带 OTG 双角色控制器（`dwc3`）的 Linux 单板机（Orange Pi 5 Plus / RK3588，Rust 自绘仪表盘 + `libx264` 软编）上，
走的是**纯有线 CarPlay** 六阶段；`mcarplay` 运行在免 root 的小米手机上，透传 CarWith 硬件 H.264 码流，走的是**纯无线 CarPlay**。

| 层次 | OrangeCar（有线 SBC） | `mcarplay`（无线免 root 手机） | 能否在免 root APK 内复用 |
| --- | --- | --- | --- |
| 物理发现与角色建立 | USB Gadget 暴露 `05AC` 4-config 高速描述符 → 车机发 `C0 53` / `40 51 wValue=1` → 拆 Gadget、`dwc3` 翻转成 USB Host、解绑 `typec_fusb302` 防 VBUS 抖动回退 | 手机注册蓝牙 iAP2/CarPlay SDP（[`PhoneSdpRecords`](../app/src/main/java/com/mcarplay/app/bt/)）并建立 RFCOMM 通道 | **否**：`configfs`、`debugfs/dwc3` 与内核驱动解绑均需 root/内核权限，Shizuku `shell` 也无法操作 |
| USB 总线隐藏时钟 | `usbcore.quirks=8086:9999:ak`（关 USB2 LPM/BESL 解「6 秒截断」、绕 64B 字符串无 ZLP）、随机持久化 USB 序列号防角色切换闩锁 | 不经过 USB OTG 主从翻转 | **否**：纯有线 USB 控制器层问题 |
| IP 承载网 | 打补丁的内核 `cdc_ncm`（随机 MAC 兜底、`SET_INTERFACE` 前 `msleep(220)`）、关 IPv6 DAD、只走 `fe80::` | 蓝牙 `0x5703` / `0x4301` 取得车机 Wi-Fi AP 凭据，经 [`CarWifiJoiner`](../app/src/main/java/com/mcarplay/app/net/CarWifiJoiner.kt) 入网并绑定进程网络 | **否**：无线走标准 WLAN AP |
| 会话期 iAP2 载体 | 全程固定跑在 USB Bulk 端点上，与 RTSP TCP 物理隔离 | 蓝牙阶段跑 RFCOMM；入网后迁至 AirPlay `type=130` DataStream 隧道或 RTSP 控制/事件通道 `iAPSendMessage`（[`VirtualIap2Link`](../app/src/main/java/com/mcarplay/app/airplay/VirtualIap2Link.kt)） | 无线更复杂，需维护双阶段链路与通道复用 |
| iAP2 认证与 AirPlay 会话 | `0xAA05` 免芯片自报、mDNS、RTSP `/setup` → `/info` → 事件通道 → `RECORD` → 屏幕/音频流 + HID 回传 | 同一套上层协议族 | **是**（共享协议层，逐项对照见下） |

> **外置硬件桥推论：** 若未来要服务「仅有线 CarPlay、无原厂无线 CarPlay」的存量车型，OrangeCar 证明有线发射端同样无需 MFi 芯片——
> 独立廉价 Linux OTG 板卡可在一侧充当有线 iPhone 端，另一侧承接手机 CarWith 七路流；但这属于独立硬件工程，不在本 APK 范围内。

---

## 2. `mcarplay` 已覆盖（或已超越单车假设）的共享层机制

有线与无线共用的阶段 3、5、6 中，OrangeCar 踩到的核心机制在 `mcarplay` 代码库中均已实现，且针对多代无线车机做了更细的分派：

1. **MFi 认证方向不对称（免芯片直接回 `0xAA05`）**
   [`Iap2SenderSession.kt`](../app/src/main/java/com/mcarplay/app/iap2/Iap2SenderSession.kt#L333-L366) 与
   [`VirtualIap2Link.kt`](../app/src/main/java/com/mcarplay/app/airplay/VirtualIap2Link.kt#L468-L509) 均在收下车机证书与挑战应答后
   跳过本地验签、自报 `0xAA05 AuthenticationSucceeded`。
2. **未配对会话显式写 `et=1`（`None`）+ AES-128-CTR 全零会话密钥跨帧连续加密**
   OrangeCar 指出漏写 `et=1`（误发 `0` 或缺省）会让车机按「需密钥」返回 `466 Key Management Error`。`mcarplay` 在
   [`AirPlayClient.buildSessionSetupBody`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt#L3671-L3675) 中对未加密会话
   显式写入 `fields["et"] = 1L`，在 [`StreamFrameCipher.kt`](../app/src/main/java/com/mcarplay/app/airplay/StreamFrameCipher.kt#L44-L64)
   按 `SHA-512("AirPlayStreamKey/IV" + connId + 16×0)[0..16]` 派生密钥，并由
   [`AirPlayCrypto.Aes128CtrStream`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayCrypto.kt#L110-L125) 保持单流单实例跨帧连续异或。
3. **保存长期信任优先跑 `pair-verify`，遇到 `MaxPeers` 立即停试**
   [`ReceiverPairingStore`](../app/src/main/java/com/mcarplay/app/airplay/ReceiverPairingStore.kt) 按控制器 ID 与接收端实例持久化信任；
   [`PairingHandshake`](../app/src/main/java/com/mcarplay/app/airplay/PairingHandshake.kt#L39-L53) 命中缓存时只跑 `pair-verify`，仅在
   被 `Authentication` 明确拒绝后才重走一次新连接 `pair-setup`；
   [`PairingRejection`](../app/src/main/java/com/mcarplay/app/airplay/PairingRejection.kt#L45-L71) 对 `MAX_PEERS` / `MAX_TRIES` 立即停止
   重试并提示用户在车机侧清理旧配对记录。
4. **音频 `SETUP` 慢应答（2.7–3.2 s）按 `CSeq` 异步派发，不阻塞心跳**
   OrangeCar 测得一次音频 `SETUP` 耗时 3.2 s；`mcarplay` 在奥迪实测中同样遇到 ~2.7 s 音频 `SETUP` 阻塞同步读导致 `/feedback`
   3 s 看门狗断链，已在 [`AirPlayClient.kt`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt#L3268-L3332) 拆出独立读线程与
   `pendingResponses[cseq]` 映射。
5. **NTP-over-RTCP 计时服务早于初始 `SETUP` 就绪**
   [`TimingServer`](../app/src/main/java/com/mcarplay/app/airplay/TimingServer.kt#L104-L147) 在 `runSetup()` 发初始 `/setup` 前绑定端口，
   并平移到 1900 NTP 纪元（避免 40 位单调时钟触发部分接收端 `/feedback` bplist 编码越界返回 500）。
6. **`MainAudio` 归车机或高约束借用时不强发音频 `SETUP`**
   [`ModeArbiter.onAudioStreamStarting`](../app/src/main/java/com/mcarplay/app/airplay/ModeArbiter.kt#L117-L143) 先检查主音频归属与约束，
   高于 `userInitiated` 时不抢占、不发 `SETUP`，待车机归还后由 `consumeAudioClaimRetry()` 补建音频流。
7. **动态解析 `/info` HID 描述符与 16 位大端 PCM**
   触摸单指/多槽由 [`TouchReports.formatFromDescriptor`](../app/src/main/java/com/mcarplay/app/input/TouchReports.kt#L39-L76) 判定，
   媒体键 Array 下标与 Variable 位图由 [`ConsumerKeys.parseDescriptor`](../app/src/main/java/com/mcarplay/app/input/ConsumerKeys.kt#L80-L120)
   解析，PCM 样本由 [`AudioSender.bigEndianS16`](../app/src/main/java/com/mcarplay/app/audio/AudioSender.kt#L92-L103) 转成大端序。

---

## 3. 绝不能照搬进 `mcarplay` 的 4 条「单车/有线经验」

以下 4 条规则在有线 `280.33.12.1` 老车机上成立，但在无线多车型实测中有明确反例，照搬会直接引入回归：

1. **「只拨 `fe80::` IPv6 link-local、不走 IPv4」**
   有线 CDC-NCM 常只监听 `fe80::`；但无线热点场景下，奥迪 MIB3（4851/4910）的 `0x4301` 或首条默认路由给出 `fe80::` 从未连通，
   真正可用的是 `_airplay._tcp` 给出的 IPv4（`10.174.189.1:7000`）。[`EndpointHosts`](../app/src/main/java/com/mcarplay/app/net/EndpointHosts.kt#L12-L16)
   必须维持 `IPv4 → 全局 IPv6 → 链路本地 IPv6（自动补 %scopeId）` 的顺序。
2. **「`_carplay-ctrl` 广播 `srcvers=566.25.21` 且不等 `/ctrl-int/1/connect` 邀请」**
   - 奥迪 MMI 3315 实车对照（[`CarPlayCtrlAdvertiser.kt`](../app/src/main/java/com/mcarplay/app/net/CarPlayCtrlAdvertiser.kt#L35-L41)）：
     把 `_carplay-ctrl._tcp` TXT 的 `srcvers` 改为 `566.25.21` 后，车机不再发 `ctrl-int` 探测、也不下发 `0x4301`；保持 `950.7.1`
     则 0.65 s 内下发 `0x4301`（`566.25.21` 仅用于 RTSP 头与 `/setup` 请求体）。
   - 帕萨特 2025（`VW4211`，[`CarPlayCtrlAdvertiser.kt`](../app/src/main/java/com/mcarplay/app/net/CarPlayCtrlAdvertiser.kt#L84-L90)）：
     不等车机刚发出的 `/ctrl-int/1/connect` 邀请就抢先连 `:7000`，初始 `SETUP` 虽回 200 但随即被车机关闭控制通道。
3. **「严格按 `0x1D01` 参数 7（`0x0007` 可收清单）门控，未列出的消息一律不发」**
   真实车机的 `0x1D01` 清单经常漏项甚至把收发方向写反（见 [`ReceiverProfileTest.kt`](../app/src/test/java/com/mcarplay/app/iap2/ReceiverProfileTest.kt#L192-L260)）：
   - 奥迪 MMI 3315 实录 `receiveIds = {0x5703, 0x4e09, 0x4e0b, 0x4e0d, 0x4e0e}`（把配件发出的 `0x5703` 错写进可收清单，漏写 `0x5702`），
     实测跳过 `0x5702` 会导致车机永不启动 AirPlay（45 次 `ECONNREFUSED`），照发则 122–368 ms 回 `0x5703`；
   - 本田 HFT 实录 `receiveIds = {0x5703, 0x6800}`，同样把配件发出的消息填进了可收清单。
4. **「固定 32B MFi 挑战 + 默认明文起步 + `/auth-setup` 必 403」**
   现代无线车机（`320.x` / `480.x` / `950.x`）普遍带 HomeKit 配对位（bit 38）并要求加密控制通道；且部分奥迪/Delphi 车机收到 32B
   MFi 挑战会静默挂死，需靠 [`Iap2SenderSession`](../app/src/main/java/com/mcarplay/app/iap2/Iap2SenderSession.kt#L342-L363) 的
   `20B / 32B / 16B` 分派与 [`MfiLengthMemory`](../app/src/main/java/com/mcarplay/app/iap2/MfiLengthMemory.kt) 按车机记忆收敛。

---

## 4. 离线改进评估与落地项

### 4.1 已评估不改生产行为：`Iap2LinkEngine` 为何不能照搬对端 SYN 的短重传超时

**问题来源：** OrangeCar 提到 iAP2 重传超时应从车机 SYN（常见 `500 ms`）读取，不要写死长超时。检查
[`Iap2LinkEngine.kt`](../app/src/main/java/com/mcarplay/app/iap2/Iap2LinkEngine.kt#L690-L717) 可见：`mcarplay` 解析并回显了
`peerSynchronization.retransmissionTimeoutMillis`，但出站帧 `deadlineMillis` 仍固定按构造参数 `retransmissionTimeoutMillis = 4_000L`
（最多 4 次，共 16 s）计算。

**离线评估结论（保持 `4_000L` 不变）：**
- **底层传输可靠性不同**：OrangeCar 的有线 USB Bulk-OUT 在冷握手期间会出现物理层 `-71`（`EPROTO`）丢包，帧真正在总线上丢失，
  因此需要 500 ms 快速重传；而 `mcarplay` 的三条 iAP2 载体（蓝牙 RFCOMM、AirPlay `type=130` TCP、RTSP 控制/事件通道 TCP）底层全部是
  **可靠有序字节流**，不存在静默丢单帧问题。对端迟迟不回链路 ACK 只有两种情况：要么车机对特定消息有意不 ACK（如奥迪对 `0x4E0E`，
  已用 `awaitAck = false` 旁路），要么对端栈正在慢速阻塞处理。
- **跨通道锁复用风险**：在 MIB3 / 事件通道形态下，`VirtualIap2Link` 的出站帧经 [`AirPlayClient.postControlIap`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt#L2030-L2083)
  走 RTSP 控制连接，与 `/info`、屏幕/音频 `SETUP` 共用 `rtspWriteLock`。实测音频 `SETUP` 往返可达 2.7–3.2 s、车机 MFi 协处理器签名偶发
  数秒延迟；若把引擎重传预算缩到对端 SYN 常见的 `500 ms × 3 = 1.5 s` 或 `500 ms × 4 = 2.0 s`，一次正常的慢 `SETUP` 就会触发
  `die("packet X not acknowledged")` 误杀健康的虚拟链路或蓝牙链路。

**本次落地：** 生产代码零改动；在 [`Iap2LinkEngineTest.kt`](../app/src/test/java/com/mcarplay/app/iap2/Iap2LinkEngineTest.kt) 新增 3 项回归单测，
锁死「对端 SYN 报 `500 ms × 3` 时仍维持本端 4 s 预算」「`awaitAck = false` 永不重传杀链」「`maxRetransmissions = 0` 关闭重传追踪」三条边界。

### 4.2 已落地（离线诊断工具）：断链时刻反向对齐与最小方差分析

**问题来源：** 车机侧存在大量未公开的内部看门狗（如艾瑞泽 8 从自身 `TimerThread begin` 起算 `30.002 s`、奥迪 MMI 3315 对 `keepAlive`
从 `RECORD` 后 `10.001 s` 断链）。单看从 `BEGIN` 起算的总耗时（`elapsedMs`），每次连接因蓝牙握手、入网或用户点击快慢不同而大幅波动，
掩盖了真正的触发点；多轮日志从 `END` 反向计算 `T_end - T_anchor`，方差最小的里程碑/阶段即为看门狗起算锚点的头号候选。

**本次落地：** 扩展 [`tools/analyze_diagnostics.py`](../tools/analyze_diagnostics.py)（对应测试
[`tools/test_analyze_diagnostics.py`](../tools/test_analyze_diagnostics.py)，说明同步至 [`docs/DIAGNOSTICS.md`](DIAGNOSTICS.md)）：
- 单文件不带新参数时输出结构 100% 不变（兼容 `tools/logbot/diagnose.py` 与既有脚本）；
- 新增 `align_attempts_to_end()` 与命令行 `--align-end`（支持一次传入 1..N 份脱敏支持报告）：遍历全部已结束的 `carplay` 尝试，
  默认剔除良性结束码（`NONE`、`USER_STOP`、`SUPERSEDED`、`ENDPOINT_PENDING`，可用 `--include-benign` 纳入），对 `BEGIN`、各首次
  `MILESTONE:*` 及首次 `STAGE:*` 计算 `T_end - T_anchor` 的样本数、最小/最大/跨度、均值与总体标准差 `stddevMs`，按最小标准差排序并给出
  `bestAnchor`（至少 2 个样本时生效）。输出只含文件名 `basename` 与时间线统计量，不引入任何未脱敏字段。

### 4.3 待有自有报告证据后再评估的候选方向

按仓库「先在自有脱敏报告中确认同款现象，再改协议行为」的原则，以下两项暂不动生产代码：

1. **AirPlay 多候选地址阶梯式竞速（Happy Eyeballs）**
   - **现状**：[`AirPlayClient.ensureConnected`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt#L3087-L3137) 按
     `EndpointHosts.candidates` 顺序串行连接（配对期每地址超时 `2_000 ms`，常规 `5_000 ms`）。
   - **触发条件**：若后续某车型的支持报告明确出现「端点同时含 IPv4 与 `fe80::`，IPv4 吞 SYN 每次耗满超时、只有 `fe80::` 连通，
     且串行等待错过了车机邀请窗口」，再引入 IPv4 先行 250–300 ms 后并发拨 `fe80::` 的有界竞速。
2. **非 MIB3 车机的额外 iAP2 订阅应答与问候兼容性核查**
   - **现状**：蓝牙阶段 [`Iap2SenderSession`](../app/src/main/java/com/mcarplay/app/iap2/Iap2SenderSession.kt) 暂存/记录订阅帧而不主动回空态
     （宝马 iD9 蓝牙发 `0xAE00`/`0x5000`/`0x5200`/`0x4157` 仍正常下发 `0x4301`）；虚拟链路 [`VirtualIap2Link`](../app/src/main/java/com/mcarplay/app/airplay/VirtualIap2Link.kt#L681-L715)
     仅在 MIB3/事件通道分支应答 `0x4157`/`0x4154`/`0x4170`/`0x5000`，未应答 `0xAE00`（电源）或 `0x4C00`（媒体库），其余均进入
     `IAPLINK: 未处理的 CSM` 前 20 条全量 hex 日志。
   - **排查用法**：针对奔驰 `MB_E-P-4`（issue #62，「链路全通、CarWith 出帧但车机无画面且零 MIB3 订阅」）等疑难报告，先核查
     `IAPLINK: 未处理的 CSM` 是否出现 `0xAE00`/`0x4C00` 等订阅，以及 `0x1D01` 双清单与突发消息的交集，确认缺失后再按兼容策略补齐。
