# mcarplay：CarWith 转无线 CarPlay 实施计划

更新：2026-11-06。目标是在小米手机上取得 CarWith 的真实画面、声音和触控，再由 mcarplay 充当 CarPlay 手机端，连接支持无线 CarPlay 的车机。

首版完成范围为：手机内手动发起连接、CarWith 画面、车机扬声器声音、车机触控回传。自动重连、车机麦克风、通话和语音上行不作为首版承诺。未在真实车机验证前，不声明任何车型“已适配”。

## 当前基线与结论

- **11 月 6 日 v0.1.6 正式版（本地构建，未推 tag）：** 汇总 PR #42 屏幕归属门控、#44 主音频仲裁、#43 手机端 SDP 生命周期与 #41 CarWith 入口诊断；
  新增「上车自动播放音乐」开关（默认开，关闭后连接窗口内拦截车机自动播放并暂停自行开始的音乐）。
  用户反馈小米 17 Pro Max + DiPlay 组合无问题（未记录构建版本、组件版本与具体验证项）。
  主屏/主音频候选尚未在同一构建上实车验证，WinPlay、macplay、艾瑞泽 8 的回归仍待完成。
  本地 899 项 JVM 测试 + 24 项 remote 调试桥测试、Spotless、standard lint/debug 构建通过；debug APK 为 `0.1.6-debug`（versionCode 1060000）。
  tag 创建与签名发布未执行。

- **11 月 6 日手机端 SDP 生命周期恢复（无新增实车验收）：** 两项 iAP2/CarPlay 公告改为成组管理，任一监听结束均撤销，
  修复旧 `running=true` 阻止重新监听的问题；复用系统蓝牙广播，重开后只恢复用户仍请求的公告。
  旧回调/恢复令牌不能影响新轮次或在用户停止后复活，连续注册/立即监听失败共用最多三次自动恢复预算。
  未按 HyperOS/手机型号更换 UUID，未改变 iAP2、认证、CHERY/RECORD 或媒体策略。
  新增生命周期测试 11 项，蓝牙定向 67 项通过；最新完整 standard 859 项、remote bridge 24 项，均 0 失败/错误/跳过，
  Spotless、standard lint/debug 构建通过。HyperOS 3/API 36 与 HyperOS 4/API 37 的本地注册、后台返回、蓝牙重开恢复和停止后不恢复已实际观察；
  OS1/API 33 混合 AVD 在 Enforcing 下未完成开机，OS2/API 35 启动应用发生 ANR，不能记为通过。
  具体系统版本、APK 指纹、模拟器矩阵与同车换手机验收见 `docs/HYPEROS-PHONE-COMPATIBILITY.md`；仍需四接收端实体回归。

- **11 月 6 日艾瑞泽 8 报告对照与回归补充（无新增实车结果）：** 对照用户提供的固件包、协议分析、88 阶段证据链和 HTML/PDF 报告，
  确认适用于既有 `IC2172 / …387_250619_R` 车机的分层定位。新增 CHERY 合成 iAP2 与诊断时序回归，并补 AirPlay 配对/生命周期负例；
  30 秒仅作车机窗口参考，手机 `BT_LINK` 不等于车机 `TimerThread begin`，失败后新 Start 另算尝试。
  断开后约 20 秒崩溃的直接机制已由历史证据定位为周期回调读取空 server；重复 RECORD / 多次 SessionStart 仍是触发候选，
  已补充同停止逻辑的单变量归因、至少 60 秒独立观察、PID/重启检查及可复现场景至少三轮要求，无新增原始库/崩溃文件复核。
  未修改连接策略；单次 RECORD 候选、音画触控、动态音频和四接收端实车回归仍待完成。
  新增 9 项测试，定向 49 项通过；完整 standard 848 项、remote 桥 24 项通过，均 0 失败/0 跳过，Spotless、standard lint/debug 构建通过。
  测试清单、认证验证边界、报告指纹和实车验收项见 `docs/ARRIZO8-PROTOCOL-REGRESSION.md`。

- **11 月 5 日 v0.1.5-test.1 测试版（待发布）：** 汇总界面重构（PR #35）与 PR #31、#33、#34 的修复，仅通过单元测试、模拟器和一台小米手机检查，DiPlay、WinPlay、macplay、艾瑞泽 8 的实车回归尚未进行。测试通过后计划升到 1.0.0。

- **11 月 5 日手机端界面重构（`feat/ui-redesign`，仅界面与死代码清理，未做真车回归）：** 手机端改为「连接 / 设置 / 诊断」三标签，Apple 风格令牌并跟随系统浅/深色；首页英雄卡去掉大车图。移除了从 CarDeck 继承、现行 CarWith 路径无法到达的虚拟屏桌面（`launcher/`、Dock、Grid、壁纸等），保留「车机显示」设置面板；HID 方向键因此只记日志不再注入。截图来自 Android 模拟器（HyperOS_3 AVD），门禁通过；DiPlay、WinPlay、macplay、艾瑞泽 8 的回归尚未进行，合并前必须补做。详见 `docs/UI-REDESIGN.md`。

- **11 月 5 日相关项目调研：** 对照 CarPToCarL `70db71e`、AuDiCar AMCar `ce35062`（均为 MIT）和 hypercar
  `739c6b0`（GPL-3.0，只参考思路）。它们修过的 IDR 起流、晚到 `0x4301`、前台请求和音频 SETUP 字段，mcarplay 已覆盖。
  同日已实现两项，单元测试通过，未上车验证：
  - 有序断开时补发 `0xEA01` StopEASession（参数照抄 `0xEA00`，后台发送后等 200 ms 再关链路；`0xEA03` 日志记录参数形状）；
  - 网关兜底在所有默认路由中优先选 IPv4，剔除未指定地址。

  待跟进：
  - 上述两项的实机回归（DiPlay、WinPlay、macplay、艾瑞泽 8），以及在奥迪 MIB3 上确认 `0xEA01` 的效果；
  - 定位「返回车机」命令后，对比 phone→HU `requestUI` 与 `modesChanged`；
  - 查 VW4131 报告中推流时的 ctrl 邀请；
  - 60 fps / 大画布、语音键、GPS 与附着入口作为实验开关。

  外部实车结果不作为本项目证据。详见 `docs/RELATED-PROJECTS-2026-11-05.md`。

- **11 月 4 日远程版 CarWith 系统日志导出：** 调试页增加本机导出/分享与固定桥命令
  `export_carwith_system_logs`，采集最近的 CarWith/CarLife/CTA 与系统启动记录、服务及监听状态。
  支持 Shizuku，或用户通过电脑 ADB 授予 READ_LOGS / DUMP 后在前台确认系统日志访问；无需 root。
  未授权时显示操作命令；输出限量、超时和失败明确标记，独立脱敏保存到下载目录，内容不自动上传。
  仅修改 remote 源集，不改变 CarWith 入口或媒体协议。用法与限制见 `docs/DIAGNOSTICS.md`。


- **11 月 5 日新增：主页「车型适配」手动选择（离线通过，未上车验证）。** 选项：自动（默认）/ 通用 / 奇瑞艾瑞泽 8 / 大众 / WinPlay / DiPlay / 奥迪经典 MIB3，
  存 `settings` 的 `compat_vehicle_preset`，经 `VehiclePresetRuntime` 在 `ReceiverCompatibility.resolve` 的自动结果上叠加已有适配开关
  （规则标签 `manual.*` 进 `COMPAT:` 日志）。只复用已登记的例外规则，不新增未验证的车型行为；下次连接生效。
- **11 月 4 日待解决：CarWith「返回车机」无效。** 投屏中点 CarWith 界面上的「返回车机」按钮，车机屏幕没有切回车机原生界面，
  仍停在 CarPlay 画面（用户反馈，车型与版本待补）。原因尚未确认。此前 `CarLifeCommandRouter` 对未识别的 7240 命令走
  `else -> Result()`，既不处理也不记日志，所以现有报告看不出这个按钮发了哪条 CarLife 命令；AirPlay 侧 `releaseScreen` 只在车机点 CarPlay 图标后
  「先让出、再拿回」时短暂使用（`SessionController` 的 `pendingScreenRetake`），手机这边没有把主屏持续交还车机的路径。已加诊断：未识别命令记为
  `CARLIFE: 未处理命令 kind=0x… len=… fields=[…] 第N次`（只记 kind、长度和 protobuf 字段形状，0..255 的 varint 记数值，不记字符串等内容；
  每种 kind 前 3 次及第 2ⁿ 次记录，最多跟踪 64 种）。下一步：用含此日志的构建复现，记下点按钮的时刻，确认对应命令，再映射为把主屏交还车机的
  `modesChanged`（参照真 iPhone 点 OEM 图标返回车机的序列）。
  **11 月 5 日进展（离线通过，未上车验证）：** DiPlay（比亚迪，srcvers 950.7.1）支持报告里，点「返回车机」后约 110 ms 出现
  `CARLIFE: 未处理命令 kind=0x10021 len=0 fields=[]`，多次点按一一对应；该命令含义未公开，仅凭时序推断。已把 `0x10021`
  映射为 `ModeArbiter.onUserReturnedToCar()`（主屏、主音频报归车机，takeConstraint 放宽），经 `enqueueModesAssert` 发 `modesChanged`；
  之后点 CarPlay 图标（`requestUI`）仍可拿回并重建流。待上车确认车机是否真的切回原生界面、音频是否交还。
  上车确认前不要宣称「返回车机」可用。

- **11 月 4 日 v0.1.4 正式版：** 用户决定在 WinPlay 台架回归通过后直接发布，汇总 PR #21、#22（含 #25）、#24、#27、#29、#30。合并构建只在 WinPlay 上回归（约 4 分钟画面、触控正常，最后几秒播放的音乐有声）；DiPlay、macplay、大众帕萨特 2025 与奇瑞艾瑞泽 8 未在此构建上复测，不新增车型支持结论。证据见 `docs/CARWITH-ENTRY-FINDINGS.md`。

- **11 月 4 日入口兼容代码提交候选：** 正式会话与自检改用 `COMPATIBLE`，先附着已有七口，再按公开入口、确认 UID 0 的旧版服务、已导出的原厂设置页回退。普通 Shizuku shell 不具备旧版签名权限；失败和取消不误报服务成功。不改认证或视频协议。新增 UID 与取消回归，最终整理构建尚待重跑完整 AVD 矩阵和真机验收。

- **11 月 4 日 OS1–OS4 CarWith 兼容矩阵：** 在同一批 50 个公开 CarWith 构建上，OS1 / Android 13 有 37 个持续出流，
  OS2 / Android 15 有 38 个，OS3 / Android 16 有 39 个；其余失败集中在 `2.3.0` 及更早的 11 个构建（七路或
  `VIDEO_INIT_DONE` 可成功，但编码器 0 帧）、OS1 上的 `3.8.2` CarWith Surface 空指针崩溃，以及
  `4.0.20-20261009` 的 OS3-only 门禁。OS4 / Android 17 按本轮要求不测全局最低版，只从系统初始 CarWith
  `4.0.16-20260907` 开始测该初始版及其后取得的 4 个构建：4 个持续出流，
  `4.0.20-20261009` 因 APK 内固定 `SDK_INT == 36` 而只能用于 OS3。OS4 的有效结果是在接受 mcarplay 首次免责声明后重跑所得；
  此前免责声明未处理时产生的 `listener_timeout` 不计兼容性。OS1–OS3 矩阵使用 SHA-256 `42a827a792ef843a92d9d10e7d58ae56b8b148a0c13750ae0dba8ce0d2908aba`
  的 mcarplay 构建；OS4 又在当前完整构建 `dff6481f484e04ec6c9ba89c7209b91b7998fd0221f6a249cc89d148f2562b7c`
  上复测同一组 5 项，结论不变。CarLife 固定为 `8.6.8/260`。这些均为源端 AVD
  证据，不能证明接收端解码、声音、触控或实体 Qualcomm vendor/kernel 等价性。完整矩阵和逐项限制见
  `docs/CARWITH-ENTRY-FINDINGS.md`。

- **11 月 3 日 CarWith 2.x / 3.x 矩阵（37 个构建，已完成）：** 在 OS1 混合 AVD 上逐版测得 18 个构建在当前日期下
  由 mcarplay 公开入口完成七路、RSA/AES、手机渠道准入与 `VIDEO_INIT_DONE 1280x720@30`，访问单元持续增长 20–24 秒；
  9 个内测包因内置截止日期已过而主动退出，把隔离 AVD 时钟调进有效期后这 9 项全部出流；`3.8.2-20260117` 在收到
  1–4 个访问单元后崩溃，已定位为 `CastController.displayConnectedInNormalCast` 在 SDK<34 分支先于
  `mSurface = surface` 赋值调用 `mSurface.setFrameRate()`，3.8.3 改为传入的 `surface` 后不再崩溃；14 个 2.0–3.0 构建
  没有 mcarplay 当前调用的 `CarlifeAdbActivity`，用 root 强制启动内部服务的对照探针显示 2.3.1 起开始出流、
  2.3.0 及更早零视频的原因是 CarLife 编码器拿不到输入帧（`DQoutput: 0 success out of ~95 tries`），
  3.0.1／3.0.2 的 H.264 经 ffprobe 确认为 1280×720 且可完整解码。另把 `CarLifeDiagnostics` 临时切到已有的
  `ATTACH_EXISTING` 策略实测：mcarplay 自己的代码在 CarWith 2.3.1 上完成七路、RSA、ENCRYPT_READY、渠道准入与
  `VIDEO_INIT_DONE 1280x720@30`，40 秒收到 613 个访问单元（测试后已还原该文件）。因此旧版出流不需要改协议代码，
  缺的是入口策略——旧版没有 `CarlifeAdbActivity`，而 `ATTACH_EXISTING` 在仓库里已定义但没有调用方。
  详见 `docs/CARWITH-ENTRY-FINDINGS.md`。

- **11 月 3 日 HyperOS 1 混合 AVD 排查：** 以小米 10S `OS1.0.4.0.TGACNXM` 的系统分区和 Android 13 模拟器 vendor/kernel 构建 6 GiB root AVD，SELinux 为 permissive。原厂 CarWith `2.0.1-20231207` 缺少 mcarplay 当前调用的 `CarlifeAdbActivity`，自检返回 `ENTRY=MISSING`；另用 root 强制启动内部服务并由 Python 探针连接，七路及 RSA/AES、手机渠道准入通过，收到视频初始化响应，但 0 个视频包。覆盖安装 CarWith `4.0.4-20260522` 后，mcarplay 自检完成七路、RSA/AES、准入和 `VIDEO_INIT_DONE 1280x720@30`，持续 301 秒收到 4,481 个访问单元及 71,549,028 字节；虚拟屏处于运行状态。模拟器 Wi-Fi 已关闭，混合 AVD 与实机不等价，结果不能证明真实手机、车机音频及触控兼容。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。

- **11 月 3 日 HyperOS 3 模拟器出流与 OS1 边界：** Apple Silicon AVD 上通过 KernelSU 普通系统应用模块解决 CarWith 的 CTA 白名单拒绝，并在 `vendor.perfservice` 运行状态下完成 CarWith `4.0.4-20260522` → 小米 CarLife `8.6.8/260` 的七路连接、RSA/AES、准入、`VIDEO_INIT_DONE 1280x720@30` 与连续 H.264。实跑约 142 秒累计 2,001 个访问单元、51 个关键帧、31,378,042 字节，CarWith 与 CarLife 进程未被 LMKD 杀死；这排除了 OS3 / Android 16 AVD 的入口、CTA、七口监听和视频源到达阻塞。自检未保存连续 Annex-B，也没有 CarPlay 接收端、有效音频或触控证据。默认 2.5 GiB 内存在 CTA 较慢时曾杀死 CarWith，重复测试建议 6 GiB。公开 HyperOS-AVD 目前只有 OS3/A16 与 OS4/A17，不提供 OS1/A13；普通 AOSP A13 不能替代小米安全中心与 CARWITH 系统集成。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。


- **11 月 4 日候选整合：** 合并远程大众手动断开、配对复用、主屏回切修复；保留奇瑞单次 RECORD 与连接代次保护。断开先暂停媒体并保留屏幕 socket，待 TEARDOWN 后关闭；停止状态仍允许屏幕流和会话 TEARDOWN，新增实际 TCP 回归。候选仍待实车复测。

- **11 月 3 日艾瑞泽 8 断开崩溃候选（待实车验证）：** 原厂库和历史 tombstone 确认，Finalize 后周期回调仍访问已清零的 server 指针；20 秒来自原厂空闲超时。原厂重复 SessionStart 会覆盖旧定时器句柄，重复 RECORD 为首要待验证诱因，此前仅按日志条数将其排除的结论撤回。对 `CHERY / Tiggo / srcvers 320.17.7` 增加每连接至多一次 RECORD；并发、超时及旧世代受保护，首次激活失败不再启动媒体。停止时阻止新业务请求、停止本代心跳并保留屏幕连接至 TEARDOWN，同时保护迟到音频/资源声明。候选尚未在车上验证，且同时改变了停止行为；须检查声音并观察断开后至少 60 秒，不能据此宣称崩溃已修复或单独证明 RECORD 的因果。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。

- **11 月 3 日候选离线验证：** Spotless、standard/remote 两变体各 751 项 JVM 测试（0 失败 / 0 跳过）、两变体 lint 与 debug APK 构建通过。回环测试覆盖并发 RECORD、应答超时/迟到、重连、停止期间 iAP 通知、旧世代音频和资源请求，以及屏幕 socket 的有序收尾。该轮测试使用空的 remote 调试桥预设；随后按用户要求使用本机私有预设重新构建 remote APK，已核对预设写入，首次使用仍需确认。上述结果均不替代实车音画触控和断开后复测。

- **11 月 3 日大众 VW4211 连续重连与主屏回切修复候选（待实车复测）：** 报告 `9acfb6ca…` / APK `a029717a…` 显示，两次手动断开后的屏幕流与会话 TEARDOWN 均获 200，但约半秒后 ACL 广播立即自动拉起额外连接。第三次有效手动连接在 22:12:00 的 pair-setup M3 等待期间关闭控制通道，随后 AirPlay 服务消失；22:13:39.022–22:14:14.197 窗口是该故障的后续，未进入媒体协商。当前增加 30 秒手动断开冷却、旧链路清理完成后才允许再启动，并保存已签名验证的接收端身份，后续连接优先 pair-verify；仅明确的认证拒绝才重新 pair-setup。回切方面，diag3 已重发参数集/IDR 仍无触控，因此在真实主屏让出后的 requestUI 改为仅重建屏幕流（TEARDOWN → SETUP → RECORD），保留原 CarWith、音频及控制会话。定向回归 29 项通过；完整门禁结果见 `docs/CARWITH-ENTRY-FINDINGS.md`。这些是发送端修复候选，不能据此认定车机服务关闭的内部原因或实车问题已解决。

- **11 月 2 日艾瑞泽 8 断开崩溃排查小结：** 车机侧 TCP 采样显示会话结束 1 s 内已无到手机的连接，停止序列与 iPhone 一致，+20 s 崩溃依旧。崩溃后车机 CarPlay 约 5 s 自动重启并可回连。按逐步对照（findings「真 iPhone 连接全过程」）仍有三处未试差异：起步模式先留给车机、主音频按需建拆、重连只走 pair-verify。

- **11 月 2 日艾瑞泽 8 MFi 挑战固定 32B（23:36 实车通过：蓝牙/隧道各一次 0.5 s 内通过，点选到出画约 12 s）：** 车机为 MFi Auth 3.0 芯片，非 32B 挑战会让芯片挂起约 35 s（I2C `error -110`），是当晚连接慢、点了没反应、需重启中控的主因；长度记忆还曾把 32B 误记为被拒。CHERY 车机蓝牙与隧道两路改为只发一次 32B、不学习。断开后 20 s 的原厂崩溃与此无关，仍未定位。

- **11 月 2 日艾瑞泽 8 配对类型与 auth-setup（实车 23:11 复测）：** CHERY 车机改发 `X-Apple-HKP: 0` 并在 SETUP 前做会话内 MFi auth-setup，车机日志与 iPhone 一致（`type 0`、`MFiSAP_Exchange` 0.47 s），断开后 20 s 仍崩溃。可见差异仅剩辅音频流。

- **11 月 2 日艾瑞泽 8 来源名与 speechMode（实车 22:57 复测）：** 修正 bplist 负整数编码（`speechMode=-1` 曾被读成 255），CHERY 车机加 `X-Apple-Client-Name`/`X-Apple-ProtocolVersion`；车机已记 `From=iPhone`、`speech (none)`，但断开后 20 s 仍崩溃。剩余差异：会话内 MFi auth-setup、辅音频流。

- **11 月 2 日艾瑞泽 8 崩溃线索：type-130（离线通过，未上车验证）：** 每次 mcarplay 会话结束后车机 +10 s 记 `Idle for 10 seconds`、+20 s 崩溃；真 iPhone 会话结束后两者皆无。只有 mcarplay 在会话开始时请求车机不支持的 type-130 流。CHERY 车机改为不 SETUP iAP 隧道；22:44 实车复测后仍崩溃，假设被否定（修改保留，与 iPhone 一致）。剩余差异：会话来源名为空、无会话内 MFi auth-setup、无辅音频流、speech 模式为 `?`。

- **11 月 2 日艾瑞泽 8 偶发配对失败与修改（离线通过，未上车验证）：** 中控重启后需在车机蓝牙设置点手机才会发起 CarPlay；一次 pair-setup 被车机判 `bad setup code`，重点后通过。SRP 客户端改为 A、S 恒满长，B 不满长时提交证明前重取，消除补零约定差异。另：系统 WLAN 保存车机热点时手机走无互联网的热点。

- **11 月 2 日艾瑞泽 8 行车实测（20:58–21:27，SETUP macAddress 修改包）：** 车机记下的自动重连地址已是手机在热点上的真实 MAC；会话行车中持续约 28 min，用户断开后车机记 `noErr`，但断开后约 20 s 原厂 `_PerformPeriodTasks` 仍崩溃；重启后的进程上 13 s 的 `kConnectionErr` 会话结束后约 20 s 再次崩溃。蓝牙释放、有序断开、真实 MAC 三项均已与 iPhone 一致，崩溃诱因仍未定位；手机端日志未取得。

- **11 月 2 日艾瑞泽 8 修改包实车复测（20:44）与 SETUP macAddress 修改（未上车验证）：** `disableBluetooth` 生效（车机关蓝牙、会话保持），有序断开后车机记 `noErr` 并按正常路径收尾，但断开后约 20 s 原厂 `_PerformPeriodTasks` 仍崩溃。车机把 SETUP 的占位 `macAddress` 记为自动重连地址，真 iPhone 填真实 Wi-Fi 地址；现对 CHERY 车机改填本机在车机热点上的 Wi-Fi 地址（接口硬件地址 / sysfs / Shizuku / EUI-64 依次尝试，失败保留占位）。需上车验证断开后是否仍崩溃。

- **11 月 2 日艾瑞泽 8 真 iPhone 对照与「照 iPhone 做」三项修改（未上车验证）：** 同一辆艾瑞泽 8（固件 `…387_250619_R`）接车机 ADB 抓 logcat。真 iPhone 四次断开（关无线局域网的有序断开、直接离开热点）车机均未崩溃；有序断开为 `0x4E0D` 不可用 → `TEARDOWN` 屏幕流 → `TEARDOWN` 会话，车机记正常断开。iPhone 每次会话开始约 0.1–0.6 s 发 `disableBluetooth`，车机随即关闭自身蓝牙。小米 `2509FPN0BC` 用 mcarplay 连接正常出画，用户断开后 20.4 s 车机原厂 `_PerformPeriodTasks+640` 空指针崩溃（与 10-31 相同），重启后的进程在下一次会话中 `SIGILL` 再崩，CarPlay 停摆至重启中控。另见车机读 MFi 芯片 I2C 挂起导致的认证卡住，真 iPhone 也遇到过。分支 `fix/chery-inbound-wait`：CHERY 车机在 SETUP 后发 `disableBluetooth` 且其后蓝牙断开不收会话；用户断开时按 iPhone 顺序后台拆会话，车机不确认则先离开热点；已知成功的 MFi 挑战长度等待 40 s。需上车验证会话保持、断开后不崩溃与直接重连。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。

- **11 月 2 日艾瑞泽 8 雅蓝牙复测与「等车机回连」修改（未上车验证）：** 同一用户 `M2102J2SC` / HyperOS OS1.0 用 remote `0.1.3-rd-diag3-debug` 复测三轮，均停在蓝牙阶段。车机 SDP 实时查询无 `…caff`、有 `ec884348…`（与 10-31 那台相同），手机主动连入 34 次全部在 43–127 ms 内 `READ_FAILED`，车机始终未回连，配对还被删除过一次。新分支 `fix/chery-inbound-wait`：这类快速失败后改为只等车机回连（带 `ec884348…` 首次即转，否则连续 3 次；每 60 s 仍试一次出站），并在界面提示用户在车机上点选本机；修正指定目标未配对时的「没有已配对设备」误报。需完整重启中控、两侧删除配对后由车机点选手机复测。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。

- **11 月 2 日明文诊断第三版（按用户要求，仅 remote/debug；离线通过）：** 导出摘要、时间线、运行日志和本应用退出记录保留原文，文件带 `-raw` 后缀与明文标记；默认运行日志尾部增至约 4MB。追加存储的蓝牙身份/目标与配对服务缓存、Wi-Fi/网络地址和路由、指定组件的 UID/路径/签名/权限/服务、本进程线程栈及原始系统异常。编译变体策略同时核对 flavor、build type 和 debug 标志，standard 及所有 release 保持脱敏；远程扩展采集器不进入 standard，服务器只读日志 API 的脱敏保持原样。调试页、已有重装续接和导出前告知范围，数据只由既有调试桥与手动分享路径传递。诊断版本为 `0.1.3-rd-diag3-debug`；standard debug、remote debug、remote release 各 695 项 JVM 测试，Spotless、两 debug 变体 lint/构建及 12 项只读日志 API 测试通过。APK 已核对 standard 不含扩展采集器且导出文案仍脱敏，remote/debug 文案为完整未脱敏，签名与用户现有 remote 相同。remote lint 的两项 HardwareIds warning 对应本轮明确要求采集的原始地址，未修改 lint baseline。尚未在该用户手机复测，不声称修复出流故障。

- **11 月 2 日源端诊断第二版（离线通过，待用户复测）：** 用户进一步确认 CarWith 独立出流自检也失败，截图停在“等待 CarWith 七口监听”、源帧为零。已在同一个 remote 诊断包加入原有七路连接的等待时间/失败端口/部分连入进度、入口请求结果、CarWith/CarLife 启用与权限状态、最近一次自检摘要及只读 `get_carlife_diagnostics`；不额外探测端口，不修改原厂授权或媒体协议。新构建参数 `-Pmcarplay.diagnosticsBuild=true` 生成 `0.1.3-rd-diag2-debug`，旧 `btDiagnosticsBuild` 参数保留为别名。standard/remote 各 691 项 JVM 测试、两变体 lint/构建、Spotless 及 12 项只读日志 API 测试通过；签名与用户现有 remote 一致，bridge 与两个远程只读命令均未进入 standard。截图不足以判断具体端口或权限根因，用户设备仍待复测。

- **11 月 2 日 CarWith 4.0.4 APK 静态核对：** 用户提供的 `4.0.4-20260522` / 104000004（SHA-256 `a20bd237…`）保留了已导出的 `CarlifeAdbActivity`，mcarplay 的普通 Intent 入口匹配。该路径自行转为 `startType=3`，再由 CarWith 请求小米 CarLife 服务；组件版本门槛为 versionCode ≥220，用户的 8.6.8 / 260 不会仅因这一门槛被否决。两份旧报告没有覆盖源端自检，无法据此定位所称无流；同版本搭配 8.8.5 在另一台手机已有 180 帧源端证据。未执行 APK、未修改协议，Android 13 / 8.6.8 组合仍待自检报告；详见 `docs/CARWITH-4.0.4-ANALYSIS.md`。

- **11 月 2 日蓝牙建链诊断包（待用户复测）：** 用户提供的小米 `M2102J2SC` / Android 13、CarWith `4.0.4-20260522`、小米 CarLife `8.6.8` 的两份报告，分别为 standard/release 与 remote/debug；车型由用户补充为 2022 款艾瑞泽 8 雅，HyperOS 与车机固件未提供。两份均停在蓝牙阶段，没有 iAP2 成功、车机回连或媒体输入，不能据此判定 Android 13 不兼容。已补本地 SDP 注册/监听、配对/ACL、SDP 结果、RFCOMM 阶段/耗时/固定异常、回连接受/拒绝及取消计数；同轮摘要进入脱敏报告和远程只读命令。异常任意原文不写日志，晚到的旧轮事件不能覆盖新轮。`-Pmcarplay.btDiagnosticsBuild=true` 构建 `0.1.3-rd-btdiag1-debug`（包名仍为 `com.mcarplay.app.rd`），不新增权限或协议兜底。standard/remote 各 686 项 JVM 测试、两变体 lint/构建、Spotless 及 12 项只读日志 API 测试通过；已核对 APK 的远程命令与 bridge 代码未进入 standard，诊断包签名与用户现有 remote 报告一致。尚未在该用户手机/车机验证，不能把本地公告成功视为车机识别成功。用法见 `docs/DIAGNOSTICS.md`。

- **10 月 31 日艾瑞泽 8 remote 长时使用与直接重连验收通过（日志已补证）：** 同一小米 `2509FPN0BC`、2022 款奇瑞艾瑞泽 8 / 固件 `M1E_IC2172/82_03_21.387_250619_R`，候选 `6417263` / APK `d4772ea9…`。remote 只读 API 的 12,069 条记录确认两轮媒体激活累计 106 分 59 秒，最长一轮 98 分 59 秒；用户确认 13:05 手动锁屏退出，约 26 秒后重新出画，14:44 结束测试，使用中无其他异常。长会话发送端音频时间轴跨 5882.335 秒的端点差约 -0.67ms，有 292 条 TTS 初始化与一次 MEDIA STOP/INIT 后持续发包。锁屏退出仍须修复；自动触发、完整冷启动、同步 UDP/控制协商问题及此前原厂崩溃仍未验证解决。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。

- **10 月 31 日奇瑞艾瑞泽 8 首次实车音画触控：** 小米 `2509FPN0BC` + standard `v0.1.3-test.4`，车机固件 `M1E_IC2172/82_03_21.387_250619_R`。临时 root 公告 CarPlay 手机能力后，车机识别为可用手机；12:36:32 主动回连应用既有 iAP2 服务，12:36:43 发出 1920×720 首帧，用户确认无线连接、画面/声音/触控正常。约 190 秒后由用户主动断网结束。应用已补入随连接生命周期发布能力公告的修复，该小节记录时普通 APK 的冷启动、重连和十分钟稳定性待复测（后续长时与锁屏后重连结果见上条）；原厂 AirPlay 库在断开后出现空指针崩溃，另行跟进。12:48 起 remote 修复包在车机崩溃残留状态下无响应；仅重启 CarPlay 进程后卡在原厂认证共享锁。用户授权重启中控后，12:55 再次取得热点凭据，12:58 通过车机手动点蓝牙出画，已无需临时探针。该时点自动触发、再次断开后重连与原厂崩溃仍未验证解决；锁屏后直接重连后来由用户确认通过。完整边界见 `docs/CARWITH-ENTRY-FINDINGS.md`。

- **v0.1.3-test.4（10 月 30 日）：** 发布修复范围为已合入 main 的 PR #15、#16、#17：导航提示混音与可调压低音量、音量设置原子交接、外部音频断流补静音、发现端口释放测试重试。混音和补静音分别有 WinPlay 1.2.5 听感证据；组合构建及连接中调整设置仍待设备验证。发布 standard 签名 APK，不包含 PR #14 的有线实验；中英文说明见 `CHANGELOG.md` 对应条目，不新增实车兼容性结论。
- **PR #17 外部音频断流补静音验收通过（10 月 30 日 20:26–20:48）：** `6ab4ec8` 的 remote debug 包已在小米 `2509FPN0BC` + WinPlay 1.2.5 台架复测。用户确认导航、暂停恢复、换曲和重连后声音均正常，无静音或延迟增长，并认可本次 PR 验收。日志记录 12 次 TTS 初始化、累计补静音 2846 帧、跳帧 0，以及两次手动断开后的重连。首轮推流统计跨 810.389 秒，RTP 样本时长为 810.389333 秒，未见时间轴累积落后。总体约 22 分钟分为三次会话；建议清单中的单次连续 15 分钟及第三次重连属于补充检查，不作为本次音频修复的验收阻塞项。接收端丢弃迟到包的根因仍未由接收端日志证实，不新增实车支持结论。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **v0.1.3-test.3（10 月 29 日）：** 新增「本机蓝牙地址」：Shizuku 实时读取优先并保存，否则使用手动填写；Shizuku 未就绪且无地址时，启动连接前提示填写。起因是小米 23127PN0CC（HyperOS OS3.0）无 Shizuku 时只能发占位地址。手动地址流程尚未实机验证。
- **v0.1.3-test.2（10 月 27 日）：** WinPlay 1.2.5 发布门槛已满足（默认设置下画面、触控、声音到达，蓝牙释放后持续约 25 分钟，手动断开与断网后均可重连），准备以 `v0.1.3-test.2` 预发布。检测向导实机交互与 WinPlay 1.2.5 新策略规则仍待实机验证。证据见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **发布门槛（10 月 27 日，后续已补齐）：** `v0.1.3-test.1` 之后先在 WinPlay 1.2.5 上完成默认设置音画触控、蓝牙释放后至少 10 分钟运行及断开重连，再继续实车验收。该门槛及后续奇瑞实车证据已记录在 `CHANGELOG.md` 与 `docs/CARWITH-ENTRY-FINDINGS.md`。

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

**当前状态（2026-10-31）：mcarplay 已能独立取得 CarWith 视频/音频，并在 WinPlay/DiPlay 及本轮艾瑞泽 8 实车显示。实车环境、两张用户实拍、长时媒体和锁屏后直接重连均已记录；`v0.1.3` 正式版按这一手动连接范围发布，2026-11-04 的 `v0.1.4` 正式版沿用同一已验证实车范围。锁屏保持连接、异常断开清理、完整冷启动与更完整的方向盘控制转入后续版本。**

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

2026-10-31 用户确认：不等非必须增强，先发布第一个正式版本。首版范围是已由实车、日志和
用户反馈共同支持的手动无线连接、音画触控、长时媒体与锁屏后直接重连；版本按 `v0.1.3`
非 prerelease 标签发布，中英更新说明明确适用设备与未覆盖场景，升级继续使用原有正式签名密钥。

2026-11-04 用户确认：WinPlay 台架回归通过后直接发布 `v0.1.4` 正式版，不等待 DiPlay、macplay 与两台实车复测；
更新说明写明这些未复测范围。

后续版本按以下顺序推进：

1. 锁屏不断连：区分自动息屏与手动锁屏，完成 Wi-Fi/网络、蓝牙和媒体生命周期修复，并以正式签名包覆盖锁屏、恢复和重连回归。
2. 上车自动连接：在更多冷启动与车机蓝牙状态下验证触发、去重、取消和失败恢复，不把本轮手动车机触发成功视为自动连接已实现。
3. 方向盘控制：补齐媒体键、焦点状态和按键映射矩阵，完成多轮换曲、暂停恢复、导航提示及电话打断回归。

进展（2026-10-31，仅代码与单测，未上车）：AirPlay `hidSendReport` 与 iAP2 0x6802 两条媒体键通路改为共用
按描述符展开（Array/位图）、按设备边沿判定（按住只触发一次，长按 1.5 秒连发）和同一出口（上一曲/下一曲交
CarWith 硬按键，其余交手机媒体会话）；补 0xCD 播放/暂停、0xB7 停止，0xB9 按 HID 规范改为随机播放并忽略。
锁屏方面先补只读观测：推流期间的息屏/亮屏/解锁/Doze 与锁屏、电池优化豁免状态写入日志和诊断时间线，
息屏期间结束的会话记录距息屏的毫秒数。同日 16:44 艾瑞泽 8 锁屏复现：CarWith 息屏后照常出帧，
未豁免电池优化的 mcarplay 在锁屏约 9 秒后整进程无输出、亮屏时全部套接字已被系统关闭，与 HyperOS 冻结断网一致；
连接前改为在未豁免时提示一次并打开系统豁免页（HyperOS 4 重定向到单应用省电策略），待「无限制」后锁屏复测验收。

进展（2026-10-31 晚，仅代码与单测，未上车）：大众车机诊断报告显示蓝牙 iAP2 认证完成、车机经 WiFi 发出 ctrl 邀请，
但（首次 AirPlay 配对前）不回 0x5703/0x4301，旧实现因「无热点凭据」拆链。现改为收到邀请即不拆链、按手机当前 WiFi 接邀请；自动模式 SDP 闸门
不再跳过车载音频类别或曾成功建链的设备。详见 `docs/CARWITH-ENTRY-FINDINGS.md`，待同车复测。
同晚续测：帕萨特已能完成 AirPlay 配对、SETUP、RECORD 与持续推流（之后每轮自动拿到 `0x5703`），但车机解不了 CarWith
透传的 1920×1140 流（屏 1280×720）、点 CarPlay 图标发出的 `requestUI` 未做业务处理（只回 RTSP 200）、未等邀请抢先连入被拒。已补 `requestUI`
应答、新鲜邀请门槛与被拒后按邀请重连、解码失败时切换手机端转码；均为代码与单测，待同车复测出画与触控。
11-01 第九版复测：蓝牙 SDP 例外、无 `0x5703` 时接邀请、新鲜邀请门槛与 `requestUI` 应答均在车上生效；但 CarWith 输出与车机同为
1280×720 时车机仍持续要关键帧、无触控，判断仍未出画。已改为持续要帧即转码（不论尺寸），并在日志中记录 CarWith 码流特征与要帧时序，
待同车复测。
11-01 22:46 帕萨特首次出画并可触控（同尺寸 Baseline 透传，主屏 niceToHave 持有时）；回原车主界面后切不回 CarPlay，
根因是资源仲裁：手机始终宣称持有主屏。已改为按车机 modes/changeModes 如实上报归属、requestUI 时拿回，撤销同尺寸转码，
待同车复测「进入 → 回主界面 → 再进入」。
11-01 23:02 复测：车机以 userInitiated 持有主屏时，点图标后拿回主屏随即有触控、要帧停止（拿回生效）。同轮在手机上点「开启声音」
导致会话被车机 reset（主线程发 RTSP 抛异常、加密计数错位）；重连后主屏本就归手机、车机仍停在原车界面，点图标无效。
已改为音频补启走后台线程且主线程拒发 RTSP、点图标时主屏本就归手机则先报让出再拿回、邀请等待放宽到 9 s、mDNS 清缓存时重开发现；
待同车复测。
11-01 23:22 复测（含上述修改）：开场拿回主屏仍有效；回原车主界面后再点图标，先让出再拿回也无效，车机不出画也不要帧。
已改为拿回主屏后重发 VideoConfig 并给下一个 IDR 内联 SPS/PPS，并记录拿回 3 s 内的触控/要帧数，待同车复测。

异常断开/原厂 AirPlay 恢复仍作为稳定性问题持续验证。原始实拍发布使用时先整理其中的位置等个人信息。

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
  :app:lintStandardDebug :app:assembleStandardDebug \
  :app:testRemoteDebugUnitTest --tests 'com.mcarplay.app.bridge.*'
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
