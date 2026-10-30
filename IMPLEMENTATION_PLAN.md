# mcarplay：CarWith 转无线 CarPlay 实施计划

更新：2026-10-30。目标是在小米手机上取得 CarWith 的真实画面、声音和触控，再由 mcarplay 充当 CarPlay 手机端，连接支持无线 CarPlay 的车机。

首版完成范围为：手机内手动发起连接、CarWith 画面、车机扬声器声音、车机触控回传。自动重连、车机麦克风、通话和语音上行不作为首版承诺。未在真实车机验证前，不声明任何车型“已适配”。

## 当前基线与结论

- **v0.1.3-test.4（10 月 30 日）：** 发布修复范围为已合入 main 的 PR #15、#16、#17：导航提示混音与可调压低音量、音量设置原子交接、外部音频断流补静音、发现端口释放测试重试。混音和补静音分别有 WinPlay 1.2.5 听感证据；组合构建及连接中调整设置仍待设备验证。发布 standard 签名 APK，不包含 PR #14 的有线实验；中英文说明见 `CHANGELOG.md` 对应条目，不新增实车兼容性结论。
- **PR #17 外部音频断流补静音验收通过（10 月 30 日 20:26–20:48）：** `6ab4ec8` 的 remote debug 包已在小米 `2509FPN0BC` + WinPlay 1.2.5 台架复测。用户确认导航、暂停恢复、换曲和重连后声音均正常，无静音或延迟增长，并认可本次 PR 验收。日志记录 12 次 TTS 初始化、累计补静音 2846 帧、跳帧 0，以及两次手动断开后的重连。首轮推流统计跨 810.389 秒，RTP 样本时长为 810.389333 秒，未见时间轴累积落后。总体约 22 分钟分为三次会话；建议清单中的单次连续 15 分钟及第三次重连属于补充检查，不作为本次音频修复的验收阻塞项。接收端丢弃迟到包的根因仍未由接收端日志证实，不新增实车支持结论。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **v0.1.3-test.3（10 月 29 日）：** 新增「本机蓝牙地址」：Shizuku 实时读取优先并保存，否则使用手动填写；Shizuku 未就绪且无地址时，启动连接前提示填写。起因是小米 23127PN0CC（HyperOS OS3.0）无 Shizuku 时只能发占位地址。手动地址流程尚未实机验证。
- **v0.1.3-test.2（10 月 27 日）：** WinPlay 1.2.5 发布门槛已满足（默认设置下画面、触控、声音到达，蓝牙释放后持续约 25 分钟，手动断开与断网后均可重连），准备以 `v0.1.3-test.2` 预发布。检测向导实机交互与 WinPlay 1.2.5 新策略规则仍待实机验证。证据见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **发布前提（10 月 27 日）：** `v0.1.3-test.1` 之后的版本须先在 WinPlay 1.2.5 上实机验证成功（10 月 27 日由 1.2.4 改为当前最新的 1.2.5）才可发布：默认设置下画面、触控、声音到达接收端，网络交接释放蓝牙后会话持续至少 10 分钟，并能断开后重连一次。清单见 `CHANGELOG.md` 的 `[Unreleased]`；验证通过并记录证据前不打标签。

- **车机本地检测（10 月 27 日）：** 已实现「检测当前车机」：复用正式连接采集阶段与媒体证据，由用户逐项确认后原子保存本地配置；下次按实例、环境和能力核对后复用，无需车型列表或远程品牌档案。初次保留 HFP/A2DP，蓝牙阶段允许一次显式对照；网关端口与 `/info` 方法探测有界。取消保留原配置，未知结果不记为不支持；握手记忆隔离实例与固件，旧轮回调不得清理新轮连接。540 项 JVM 测试、Spotless、standard debug lint/构建通过；向导实机交互、完整音画触控与重连仍待验证，不新增设备支持结论。范围与操作见 `docs/RECEIVER-AUTODETECT.md`。
- **接收端兼容策略（10 月 27 日）：** 新增统一 `ReceiverCompatibility`，将本轮蓝牙身份/固件/EA、mDNS features/srcvers 和 `/info` 能力保留在同一端点事实链中。WinPlay、DiPlay、既有 MIB3 与未知接收端分别选择已核对的策略；危险恢复不按名称泛化，普通配对失败不触发明文降级。ACL 与 RFCOMM 断开共用网络交接判断；音频按实例、固件和能力隔离记忆，明确不支持的音频清单仅推视频。493 项 JVM 测试、Spotless、standard debug lint/构建通过。设计、版本范围及离线验证边界见 `docs/RECEIVER-COMPATIBILITY.md`，不据此新增实车支持声明。
- **10 月 30 日导航提示混音（WinPlay 1.2.5 台架已验证）：** 根因是 `CarLifeAudioBridge` 在 TTS/VOICE 包到达后 500ms 内整段丢弃音乐 PCM：导航一来音乐即断，且被丢的样本在 RTP 时间线上留下空洞，WinPlay 1.2.5 上首次提示后整个会话无声。改为手机侧混音：音乐驱动时钟，提示等音乐淡到 -15dB（250ms）后叠加，结束保持 400ms 后淡回（400ms）；音乐静止时提示单独输出。混音在编码前，不影响 AAC-LC → PCM 降级。压低幅度可在「车机设置 → 显示」调整（0 至 -30dB），会话中生效。小米 17 Pro Max + WinPlay 1.2.5 台架确认提示期间压低、结束后恢复、暂停 8.3s 后恢复有声；DiPlay、MacPlay、真实车机和设置项的其他取值待验证；短时上游停顿由 PR #17 的断流补静音处理，二者合并后的组合待台架复测。详见 `docs/AUDIO-DUCKING.md` 与 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **10 月 27 日 WinPlay 无声修正：** 蓝牙自动启动曾在 CarWith 可用时把「开启声音」改回关闭，已修正；CarLife 音频首帧与丢帧现可从日志区分。修复后 WinPlay 与 DiPlay 短时复测均收到 MEDIA INIT 44.1kHz 和连续 PCM，用户确认有画面和声音；此前一次 0 包会话根因未定。观察不足 1 分钟，未覆盖触控、10 分钟和重连，发布门槛不变。remote 包连上调试服务器时自动上报手机/HyperOS/CarWith/CarLife 版本。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **10 月 27 日 WinPlay 1.2.4 连接后立即断开：** 用户日志显示真实视频已发送（1920×1140，至少 41 帧），随后应用进入全量断开；用户确认未手动操作，关闭「上车自动连接」后可以连接。源码核对发现该开关同时控制 ACL 断开自动收尾，未区分网络 iAP 接入后的正常蓝牙释放；WinPlay 正会在网络认证通过后关闭蓝牙桥接。已加保护：Wi-Fi 视频发送端运行时不因 ACL 断开结束会话；未启动视频仍按原路径收尾，自动断开不再记录为 `USER_STOP` 或提示「已手动断开」。回环回归覆盖首帧前、发送首帧后、重复 ACL 事件及视频结束后的分支。475 项 JVM 测试、Spotless、standard debug lint/构建通过；修复包与完整 WinPlay 音画触控仍待设备复测，详见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **10 月 26 日晚间连接回归排查：** 小米 `0.1.1` 正式包向 DiPlay 同局域网模式连接失败。23:28 曾完成 AirPlay 配对、SETUP 和 RECORD，但 CarWith 七路端口未启动；后续进程保留失效网络句柄，反复报 `ENONET`。已修复网络断开按对象引用匹配、失效句柄复用和过期回调重新绑定问题，并修正同步查询拿不到 SSID 的同局域网识别；无位置权限时提供用户明确确认当前同局域网的手动路径。455 项 JVM 测试、Spotless、standard debug lint/构建通过；修复包尚未覆盖安装，首次 CarWith 端口未启动的原因与恢复出画仍待设备验证。临时位置权限对照已恢复原状态，详见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **GitHub 更新（本轮离线验证）：** 首页新增检查更新，支持正式版/预发布筛选、更新说明、可取消的 APK 下载、哈希与包/版本/签名校验，再由用户授权 Android 系统安装器。来源固定为本项目 GitHub Releases；连接和自检期间阻止更新，不自动卸载、清数据或上传日志。标签构建记录精确发行标签；旧包同基础版本无法判断预发布先后时提供发行页入口。手机端权限、覆盖安装及断网回归待完成，机制与验收见 `docs/UPDATES.md`。
- **连接体验与诊断：** 首页新增会话编号、连接阶段、错误码与处置提示，可直接导出脱敏报告；“已连接”需视频发送器实际写出帧，不再只凭发送线程启动。报告包含 APK/签名指纹、组件/权限/网络快照、最近进程关键事件、输入/发送媒体计数、内存采样、堆栈及本应用系统退出记录。提供离线摘要脚本与 GitHub 反馈表。日志有界滚动并保留前一段，不自动上传。本轮已从手机上的 0.1.1 正式包真实导出报告，并通过离线摘要和双端日志定位连接阶段；其余权限交互与修复后连接回归仍待完成。详见 `docs/DIAGNOSTICS.md`。
- **10 月 26 日退出排查：** 已修复 `CarLifeSession` 心跳睡眠被 `stop()` 中断时未捕获 `InterruptedException` 的问题；新增实际心跳线程停止回归，连续 20 次启停不进入未捕获异常处理器。CarPlay 视频结束时现在同时停止本轮 CarLife 源并释放所有权、停止原生音频桥，旧轮回调不得清理新轮状态。覆盖安装前的小米系统退出记录未显示对应时段的崩溃/ANR；Pad 在 00:24 被系统以 `LOW_MEMORY / single-cleaner` 清理，00:38 为链路重置及 DiPlay 主动拆链记录，不能混为一次 mcarplay 进程崩溃。详情见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **修复版设备回归：** 覆盖安装并由用户确认免责声明后，在 DiPlay 上恢复 1920×1140、约 20fps 画面；有意关闭接收端后手机关闭七路通道，观察 10 秒无残留视频帧。重新打开接收端可再出画，手机内手动断开后也可再次连接；同一手机进程保持存活，未新增 FATAL。仅为定向回归，不替代 10 分钟/实车验收。
- **10 月 26 日 00:07 更新：音乐音频已打通并获用户听感确认。** 小米原生 9240 INIT 为加密参数、混合模式 MEDIA_DATA 为明文 PCM；修正按帧类型解析，补齐 44.1kHz → 48kHz 双声道重采样、AAC 编码和 RTP 节奏。00:00:46–00:07:23 持续约 6 分 36 秒；DiPlay 解码输出 18,538 个缓冲，累计写入 18,982,912 样本帧，播放游标正常增长，解码丢包、欠载及写入错误均为 0。用户确认“能听到，音调和速度正常”。视频约 20fps、recoveries=0。该结果来自 Pad 仿真；导航提示、暂停恢复专项、完整 10 分钟和真实车机验收仍待完成。
- **修正前轮音频判断：** 23:51 起已观察到连续 MEDIA 帧，被统一 AES 解密规则拒绝。小米实际走 `RemoteMediaAudioProcessor → CarLifeAudioManager → PcmHoldHelper`；`AppRecorder` 不是这条通路的必经入口。现有 RSA/AES 顺序已与原厂一致，本轮没有增加特性或逆向发送 MD 请求。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **20:26 更新：已打通单机 CarWith 出流。** 修复 2.0 鉴权、统计 protobuf 必填字段、加密命令解析和渠道准入时序后，探针两次各收到 40 秒可解码 H.264（1280×720，780 / 437 帧）；第二轮通过 9340 点击进入应用列表，画面变化已核对。两份流均通过 `ffmpeg` 完整解码。
- **APK 已独立出流。** 用户点击“自检”后，由普通应用 Intent 打开已导出的 `CarlifeAdbActivity`，无需电脑执行启动命令或端口转发。20:17 起首轮超过 40 秒；最新版强制结束 CarWith/CarLife 后重启，超过 100 秒、2041 帧。Pad 接收端及蓝牙辅助服务均关闭，MediaProjection 为 `null`。3 次严格冷启动和 10 次启停统计继续补齐。
- 当前手机端默认策略恢复为 `EXPORTED_ENTRY`，保留显式 `ATTACH_EXISTING`；界面要求先断开其他接收端。普通 Intent 可用仅在上述测试版本成立，不推断所有 HyperOS/CarWith 版本兼容。
- 下一步按用户指定，在 Test-HU 安装 DiPlay 0.2.21 验证无线 CarPlay；Pad 仿真不替代真实车机验收。详细证据和旧判断修正见 `docs/CARWITH-ENTRY-FINDINGS.md`。

以下为早期诊断记录；其中“ADB 入口必然 10 秒失败”和“握手不是第一阻塞”的判断已被本轮成功实验修正：

- 测试机为 `2509FPN0BC`，HyperOS `OS4.0.4.0.XPBCNXM`，CarWith `4.0.20-20260923`，百度 CarLife 小米组件 `8.8.4`。
- 七路端口 `7240/8240/9240/9241/9242/9340/9440` 由 CarWith/CarLife 监听，mcarplay 已能作为客户端连接。严格签名鉴权、AES 交换和视频解析已有实现及单测覆盖，但当前 ADB 入口下未走到 `MD_AUTH_RESPONSE`，实机端到端仍未验证。
- **CarLife 握手不是当前第一阻塞。** 最新一次已确认设备处于解锁、亮屏状态，但 CarWith 自己宣告投屏失败：

```text
10:09:13.891 CarlinkSM sends MSG_CASTCONTROLLER_CAST_FAILURE delayMillis:10000
10:09:13.905 CastingActivity onCreate; 10:09:13.978 onDestroy
10:09:23.906 timeout processed
10:09:23.918 CastingActivity mode=9, "Casting fail:false"
10:09:25.986 onStop mCarlifeAdbActivityReOpen=false
10:09:26.007 completeDisconnect
```

- 从 `CarlifeAdbActivity` 启动后，10:12:56 七路 TCP 已连接，mcarplay 发出协议版本、前台、设备和鉴权消息；10:13:09 连接被 CarWith 重置。期间没有 `MD_AUTH_RESPONSE`、特性协商、RSA/AES 或视频初始化。七路连接只是传输层证据，不能视为投屏成功。
- `CarlifeAdbActivity` 是当前可见的导出入口，但会触发上述合成投屏生命周期；正常的 `CastingActivity` 和 `CarLifeConnectActivity` 不导出。CarWith 的 `CarlifeConnService` 只接受匹配的 CarLife UID/包，mcarplay 不能冒充系统 UID。
- 真实 CarWith 视频依赖 CarWith 的虚拟显示、`prepareCast()` 和编码器 Surface。只启动 CarLife 权限页或连接端口，不足以产生媒体。
- OcarPlay 的经验只证明“本机 CarLife 七通道 → CarPlay 桥接”这个结构可行；它启动的是 OPPO 原生 Car+，且对 OPPO 包和签名有硬编码检查，不能直接用于小米。其日志也没有把通道连接等同于鉴权或媒体成功。
- 10:27 复查：CarWith 导出的设置页可进入 `CarLife连接 → 无线连接 → 热点连接`，当前设备提示“未连接任何车机蓝牙”，七口均未监听。`--attach` 探针在建立转发前返回码 `3`；无 H.264。入口与设备证据见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- 10:48 使用 Android Pad `Test-HU` 模拟车机：Pad 上 `dev.h3bridge.hu` 的 CarLife 接收端 SDK 已启动，但反复连接小米的蓝牙串口失败。debug 专用 `BtAnchorService` 在两机之间建立真实 RFCOMM 链路后，手机已显示 Pad 的经典蓝牙 ACL 连接；此时仍未观察到七口或视频。该辅助链路只用于排查模拟环境，不计入阶段 0A 的无 ADB 验收或实车兼容性证据。
- 10:55 Pad 通过 CarWith 公开的 WLAN 直连流程成功连接：七路通道均已与 Pad 建立 TCP 连接，CarWith 创建 1280×720 虚拟显示，Pad 解码并显示真实 CarWith 画面。150 秒内解码帧数从 71 增至 3077，丢帧/解码错误为 0。阶段 0A 已有一次成功的仿真证据，但 3 次冷启动的验收尚未完成。CarLife 服务对同名通道的第二个连接会重置整个会话，故不能在 Pad 投屏时直接运行 `--attach`；阶段 0B 的 mcarplay 自行出流仍未通过。脱敏证据见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- APK 默认改为附着已有 CarWith 会话；首页“设置”打开 CarWith 的导出设置页，“自检”等待已有七口，不再自动请求会 10 秒超时的 ADB 入口。正式链路在首帧前保留手机可见，日志只称“等待已有会话”。此改动已构建和通过本地测试，尚未在真实 CarLife 车机上验收。
- 19:22 重新核对哈希匹配的 APK/JADX 结果：`SdpBluetoothUtil`、UUID `9f03b326-5d75-46f1-9a39-b71f144d1d97`、`_EasyConn._tcp.local.` 和 10930 属于亿连路径；其客户端主动连接 HU。百度 CarLife 的 `BluetoothClient.ListenThread` 则监听 SPP/SPP_PLUS，且启动受 `isCarLifeApp()` 等条件约束。两条路径必须分别验证；原先将 EasyConn 发现链视作 CarLife 前置的判断撤回。
- 首页现已接入“副机 HU 模拟”前台服务：真实 SDP 监听、Wi-Fi Direct group owner、P2P 身份、JmDNS 发布和 10930 应答共用一条状态链。纯协议、身份/组就绪和 EasyConn 帧已有 JVM 单测。该入口要求另一台设备扮演 HU；同一部手机的蓝牙适配器不能作为自己的 RFCOMM 对端，因此它不是单机 CarWith 直连方案，也不代表 CarLife 视频或 CarPlay 已出流。

**当前状态：mcarplay 已能通过普通应用入口独立取得真实视频，向 DiPlay 显示 CarWith 并播放原生音乐，用户已确认听感正常。Pad 触控已有前轮证据；真实车机、导航提示、完整稳定性及发布验收仍待完成。**

## 架构与安全边界

```text
mcarplay 界面 / 受支持的用户入口
  → CarWith 建立虚拟显示与编码器
  → 本机七路 CarLife 通道
  → mcarplay 严格鉴权、解密、解析 H.264/PCM
  → 蓝牙 iAP2 + Wi-Fi/AirPlay + HID
  → 无线 CarPlay 车机
```

不得通过启动未导出 Activity、伪造 UID/包名、绕过锁屏、注入系统授权、跳过协议校验或伪造握手成功来推进验收。当前原厂 2.0 证明使用绑定设备字段及本次随机 nonce 的 MD5 摘要，并非 RSA 身份签名；旧版 RSA 校验保留为独立兼容路径，不因 2.0 校验失败而降级。RSA/AES 媒体交换每次生成新密钥，AES-ECB/PKCS#5 仅用于本次会话。

## 阶段 0A：建立受支持的 CarWith 投屏生命周期

**目标：** 找到普通应用或用户操作可以合法触发的 CarWith 投屏流程，并证明它会建立虚拟显示和编码器 Surface。

**实施：**

1. 枚举 CarWith/CarLife 的导出组件、Intent 参数和用户可达设置页，区分“CarLife 服务启动”“CarWith 车载投屏”“ADB 调试入口”三种状态。
2. 只测试受支持的用户流程，例如 CarWith 设置/投屏入口、蓝牙配对流程或真实车机触发；ADB 仅用于观察，不作为最终依赖。
3. 将 APK 启动策略抽象为“用户触发”“观察并附着到已有会话”“诊断入口”三类。诊断工具已有 `--attach`，附着前检查七口监听，不自行启动 CarWith。
4. 补充时序日志：入口请求、CarWith 进程/服务存活、七口监听、`CastingActivity` 状态、虚拟显示创建、`prepareCast`、编码器 Surface、失败回调和会话断开。只记录脱敏状态，不记录密钥、签名原文或配对材料。
5. 删除任何以“端口已连接”“Activity 已启动”推导投屏成功的文案。计时观察至少 35 秒，覆盖当前的 10 秒失败点。

**验收：**

- 在解锁、亮屏、未授权特殊系统权限的测试机上，使用普通应用可触发的受支持流程，35 秒内不再出现 `MSG_CASTCONTROLLER_CAST_FAILURE delayMillis:10000`。
- CarWith 的投屏会话保持活动，虚拟显示和编码器 Surface 已建立；七口会话不因 10 秒超时被重置。
- 连续冷启动 3 次，至少 2 次完整进入活动状态；失败时保留可定位的脱敏时间线和明确错误。
- 全程没有直接启动未导出 Activity、伪造 UID/包名、root 或系统权限绕过。

**停止条件：** 若公开入口和普通用户流程都无法建立投屏，且唯一可行方式是系统权限、未导出组件或冒充 CarLife，则阶段 0A 失败。此时停止集成，不以录屏或旧启动器冒充 CarWith。

## 阶段 0B：取得真实 8240 媒体流

**目标：** 证明 CarWith 在稳定投屏状态下确实向本机 8240 输出经鉴权加密的 H.264，并能被 mcarplay 解析。

**实施：**

1. 使用 `tools/carlife_probe.py --attach` 连接由受支持流程启动的会话；探针已使用临时转发端口并只清理自己建立的七口连接。
2. 在隔离环境安装依赖并保留原始 H.264、脱敏日志和统计：

```bash
python3 -m venv .venv
.venv/bin/pip install cryptography
.venv/bin/python tools/carlife_probe.py 192.0.2.10:37123 \
  --attach --seconds 35 --full --video \
  --save-h264 /tmp/mcarplay-carwith.h264
```

3. 核对真实顺序：协议匹配后发送完整统计消息；收到 `MD_INFO` 后发送 HU 信息及 2.0 挑战。特性请求可能先到，需等手机证明校验通过再响应；随后完成 `RSA → AES → ENCRYPT_READY`。4.x 的 `MD_AUTH_RESULT` 在加密就绪后到达，需解密、校验为通过并回 ACK，才允许视频初始化。任何证明、准入或填充错误均失败，不用准入结果反向阻塞必需的 RSA/AES 协商。
4. 用 `ffprobe`/`ffmpeg` 验证 SPS/PPS、IDR、普通帧、时间戳和解码结果；同时抓取 CarWith 页面变化作为对照，而不是用静态启动器截图代替。
5. 若 0A 已通过但仍无帧，依次定位：虚拟显示是否绑定编码器、Surface 是否有效、视频初始化是否到达、加密是否匹配、8240 是否被提前关闭。不得先猜协议并修改鉴权。

**验收：**

- 8240 收到可解码的 SPS/PPS、至少一个 IDR、连续非 IDR 帧，时间戳跨度不少于 30 秒。
- `ffprobe` 能识别 H.264 流，`ffmpeg -v error -i <file> -f null -` 无持续解码错误；画面内容随 CarWith 页面变化。
- 3 次受控运行中至少 2 次取得满足上述条件的流；日志无重复 `Configured state` 异常。
- `--full` 的成功只建立在原厂协议的手机证明校验、手机渠道准入和 AES 交换之后；零帧或仅心跳一律判定失败。

**停止条件：** 若 CarWith 仅允许系统 UID 读取视频，或编码器只能在不可获得的系统进程中建立，记录精确断点和证据后暂停项目，不以普通 MediaProjection 录屏替代。

## 阶段 1：把稳定链路集成进 APK

**实施：**

1. 首页自检及正式链路通过普通 Intent 打开 CarWith 已导出的兼容入口，前台服务保持连接；保留附着策略供受控诊断使用。继续补齐冷启动、反复启停及失败提示验收。
2. 把 0B 的已注册鉴权、AES 解密、视频统计和断线回收接入正式会话；探针与 APK 共用相同协议实现，避免两份逻辑漂移。
3. 状态至少区分：入口已请求、CarWith 存活、七口已连接、握手进行中、首个媒体字节、30 秒稳定观察、失败原因。
4. 增加单元测试覆盖启动策略、状态转换、重复连接、部分连接回滚和视频统计；保留并扩展现有 CarLife 测试。

**验收：**

- 安装 APK 后，用户只点击应用内按钮即可完成连接，不运行 `adb shell am`、`adb forward` 或 root 命令。
- 应用统计到真实 8240 帧，而不是只显示七口 TCP 成功。
- 连续启动/断开 10 次无端口占用、残留服务、崩溃或误报成功；3 次冷启动均可复现。
- 缺少 CarWith、版本不匹配、蓝牙/Wi-Fi 未就绪时显示可操作错误，并明确区分“未连接”和“未出流”。

### 阶段 1A：亿连发现诊断（辅助实验）

**目标：** 用第二台 Android 设备验证 CarWith 内亿连客户端的无线发现流程。此阶段独立于百度 CarLife 主线；通过 10930 不作为阶段 0B 的证据。

**实现边界：**

1. Pad 上的 `ExternalHuService` 提供亿连 RFCOMM 监听、P2P group owner、mDNS 与 10930 应答；小米上的亿连客户端作为对端。首页入口现标为“亿连发现诊断”。百度 CarLife 入口不使用这条链，不能据此推断同机 CarLife 媒体可读。
2. 只有从 `requestDeviceInfo`/`THIS_DEVICE_CHANGED` 取得真实身份，且 `WifiP2pGroup` 与 `WifiP2pInfo` 均确认本机是组主、SSID 与本机 P2P IPv4 接口完整时，才允许发布 SDP 公告；禁止用蓝牙名称、占位 MAC 或猜测 SSID 代替。
3. JmDNS 必须绑定 P2P IPv4，持有 `MulticastLock` 后发布 `_EasyConn._tcp.local.`；10930 只在同一 P2P 地址监听，只接受 `0x70000010`，返回 `0x70000011` 和字符串 `"true"`。
4. 首页/日志的“SDP、HU、P2P、mDNS、10930”是逐级证据，不得把任一前序状态显示为“CarPlay 已连接”。
5. 停止时必须注销 JmDNS、释放 `MulticastLock`、关闭 10930、关闭 RFCOMM、注销广播并按需移除 P2P 组。

**验收：**

- JVM 测试覆盖 SDP 帧、EasyConn 帧、实例名、TXT 字段、P2P 组就绪门槛和错误帧拒绝。
- 在至少一组已系统蓝牙配对的测试设备上，CarWith 能完成 `BUILD_NET_FINISH`；10930 收到真实请求后，mcarplay 日志记录 `accepted`，且可观察到 mDNS 记录与监听端口。
- 停止后 10930、RFCOMM、JmDNS 和 P2P 组均释放；再次启动可复现，且无端口占用或残留前台服务。
- 全流程不启动未导出 Activity、不伪造 UID/包名、不 root、不跳过签名校验。
- 真实 8240 媒体流仍须按阶段 0B 单独验收；本阶段通过不等于 CarPlay 实车可用。

#### 离线可靠性补强（2026-10-25）

- 已实现：P2P 身份持续轮询、身份/IP/SSID 变化重新发布、失去组主身份立即撤销公告；发布和回收在串行后台线程执行，过期回调不能恢复旧会话。
- 已实现：10930 每轮监听独立持有客户端，停止时关闭未完成请求并清空 `accepted`；更换绑定失败也关闭旧监听，最多同时处理 8 个客户端。
- 已实现：mDNS 部分启动失败按逆序回收注册对象和组播锁；P2P 仅移除本轮创建或正在创建的组，等待回调或 2 秒超时后再关闭通道，复用已有组时不主动移组。
- 离线测试覆盖：真实 loopback 请求、坏帧拒绝、占用端口失败、20 次连续重启、未完成请求回收，以及 P2P 就绪/变化/丢失、异步移组和 mDNS 故障回滚。单测使用临时端口，生产仍固定 10930。
- 待设备验收：亿连发现真实可达、冷启动稳定性，以及百度 CarLife 主线的真实 8240 视频；两条链分别记录结果。

#### Pad 回归与主线修正（2026-10-25 19:20–19:35）

- 最新 APK 已安装到解锁的 PA2353 / Android 14。修复权限回调仅检查本次请求结果的问题：仅补授 `NEARBY_WIFI_DEVICES`、蓝牙/通知已授权时，服务自动启动，已在 Pad 系统授权弹窗上复现并验证修复。
- Pad 确实建立 P2P group owner 和 `192.168.49.1`；公开 API 返回的本机 MAC 为隐私占位值，状态明确显示 `mac-redacted`，mDNS/10930 没有发布，未标记成功。
- 两次应用内停止均收到系统移组成功回调，P2P IPv4 和前台服务消失；该结果只覆盖 Pad 本轮建组/清理。

### 下一优先项：百度 CarLife 无线入口与 8240

1. 小米接入后记录系统、CarWith/CarLife 版本、当前会话和七口状态，先保留已有会话证据。
2. 从 CarWith 的公开设置进入百度 CarLife WLAN 直连；该用户流程内部以 `startType=4`、`carlife_wireless_type=Baidu_wlan` 启动服务。继续复用已有 Pad 接收端完成阶段 0A 的冷启动复测。
3. 核对小米 Mix 组件是否实际启动 SPP/SPP_PLUS。通用 CarLife SDK 中的监听受 `ConnectClient.startTcpListener()` 条件限制；没有设备监听证据前，不默认存在可连接的蓝牙串口，也不新增一个相同 UUID 的手机监听器来冒充原厂行为。
4. 在受控、单客户端条件下由 mcarplay 取得 8240 媒体；若需要实现副机 CarLife HU 建链，应依据百度的 8 字节命令帧及无线 protobuf 消息，独立实现，不能复用亿连的 16 字节 JSON 发现帧。

**验收仍按 0A/0B：** 原厂虚拟显示持续活动，mcarplay 自行收到并严格鉴权解密至少 30 秒可解码、随页面变化的 H.264。蓝牙配对、P2P 建组或亿连 10930 单项通过均不替代此门槛。

## 阶段 2：视频进入 CarPlay

**实施：**

1. 将 `CarLifeVideoParser` 的 Annex B 访问单元接入 `SessionController → VideoSender`，生成正确的 `avcC`、长度前缀和关键帧时序。
2. 优先直通协商分辨率；只有车机拒绝 profile/level、尺寸或裁剪时才启用 MediaCodec 解码/重编码，并记录决策依据。
3. 把车机 `forceKeyFrame` 映射到 CarLife 视频控制，处理断开、重连和首帧超时。

**验收：**

- 在至少一台真实无线 CarPlay 车机上显示实际 CarWith 页面；页面切换可见，导航、音乐等应用操作能反映到画面。
- 从第一个可显示关键帧到车机出现画面不超过 5 秒；连续 10 分钟无黑屏、停帧或非预期断连。
- 断开后重新连接能再次出画；记录车机型号、固件、分辨率、帧率、首帧耗时及是否重编码。

## 阶段 3：触控与音频

**触控实施：** 将 CarPlay HID 坐标映射到 CarWith 画布，经 9340 发送按下、移动、抬起；断线和退出必须释放触点。

**触控验收：** 5×5 网格点击误差不超过画布短边的 3%；连续 100 次点击/滑动无粘连；返回、导航和媒体控制可用。

**音频实施：** 解析 9240 媒体、9241 TTS、9242 语音通道的真实封装，统一为带时间戳 PCM 或可验证编码后接入 CarPlay 音频；保留导航提示优先级。未解析出真实样本前，不把 `AudioPlaybackCapture` 内录声称为 CarWith 原生音频。

**音频验收：** 音乐和导航提示均从车机扬声器播放；提示打断、恢复和音量变化正常；连续 10 分钟无明显卡顿、重复或失步。车机麦克风上行作为独立后续项。

## 阶段 4：发布门槛

- 将应用名、首页、日志目录和 README 统一为 mcarplay；移除会误导的 OPPO/Car+ 或“自动适配”文案。
- 启动时提示 CarWith/CarLife 版本、蓝牙、Wi-Fi 和所需权限；CarWith 主链路不得暗依赖 Shizuku。
- 补充上游 MIT 许可、第三方组件说明和隐私说明；保护签名密钥、配对数据和日志。
- 发布前在测试机完成 3 次冷启动、30 分钟导航/音乐/触控及断线重连；在真实车机完成相同验证并记录型号与固件。

## 诊断功能后续设备验收

1. 在小米上验证蓝牙关闭、附近设备权限拒绝、原厂组件不可用、配对/SETUP 失败时，界面提示与报告阶段、错误码一致。
2. 正常连接后核对 CarWith 输入、视频实际发送、PCM 输入及音频包计数，不能把写入计数视为接收端已显示或有声。
3. 关闭接收端、手动取消、重启应用后立即导出：区分故障、用户操作和未知中断，并保留最近上一进程证据。
4. 验证关闭“显示日志”后仍可导出，文件在 Download/mcarplay 可见、能分享；检查真实日志脱敏效果和导出耗时。
5. 将实际脱敏文件交给 `python3 tools/analyze_diagnostics.py <报告>`，确认摘要与手机/接收端观察一致；缺证据时补取接收端日志，不臆测根因。

## 验证、证据与完成定义

每次合入执行：

```bash
./gradlew spotlessCheck :app:testStandardDebugUnitTest \
  :app:lintStandardDebug :app:assembleStandardDebug
```

当前网络修复基线：455 项 JVM 测试、Spotless、standard debug lint 和 standard debug 构建通过，其中新增 6 项网络生命周期回归。按用户要求暂不安装修复 APK，设备恢复出画仍未验证。

前轮更新功能离线基线：449 项 JVM 测试（其中 37 项覆盖更新功能）、26 项 Python 测试全部通过；Spotless、standard debug/release lint、standard debug/release 和 remote debug 构建通过。更新专项覆盖标签/频道、可信地址、下载边界、哈希、取消/失败清理，以及包名/版本/签名拒绝；诊断专项覆盖脱敏、会话代际隔离、失败原因保留、媒体计数、历史滚动、导出事务清理，以及实际 TCP/UDP 回环中的观察回调异常隔离。该轮 Gradle 检查使用 `--offline`。测试 APK 为 `dist/mcarplay-debug.apk`，校验值在 `dist/mcarplay-debug.apk.sha256`；未在手机验证该 APK。前轮音乐稳定性观察来自构建 `94ae9756b71327a3cd991c4c9c007c41194a52e0d0acf4c29c8167c11d133dab`，不与后续离线结果合并计算稳定性。

验收证据统一记录：日期、手机型号与系统、CarWith/CarLife 版本、APK 与 H.264 的 SHA-256、完整命令、脱敏时序日志、`ffprobe` 结果和车机型号/固件。认证签名、AES 密钥、配对材料和个人日志不得入仓。

完成顺序为：

1. 0A 通过：受支持的 CarWith 投屏会话稳定建立。
2. 0B 通过：8240 取得连续 30 秒可解码 H.264。
3. 阶段 1–2 通过：应用内无 ADB 连接，CarPlay 实车看到 CarWith 画面。
4. 阶段 3–4 通过：触控、音频、稳定性、合规和发布材料齐全。

任何阶段失败时，先固定错误证据并更新本计划；不得跳过前序门槛、把端口连接当作媒体成功，或把未测车型写成已适配。
