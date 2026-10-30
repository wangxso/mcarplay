# Changelog

Release notes are listed newest first. English is followed by Simplified Chinese.
Test releases are experimental and do not establish real-vehicle compatibility.

## [Unreleased]

### English
- Fix wired TCP ordering, peer-reset shutdown and unsupported-port resets; serialize connection-table teardown, reset framing on a fresh handshake, and gate FunctionFS bulk reads on ENABLE with idle backoff. Stopping while waiting for USB now releases the connection-start gate. Wait for StartSession in one bounded root call, keep the USB label out of the Wi-Fi SSID field, and let each wired page dispose only its own listener.
- Shared wired/wireless media changes: use one monotonic clock translated to the NTP epoch for timing replies and audio sync (avoids DiPlay feedback encoding errors), tolerate transient UDP send failures for up to three seconds, and stamp screen video frame headers with the same NTP-epoch clock as the timing replies, so receivers that schedule frames by header time no longer see them as about 126 years old.
- Add **界面语言 / Language**: the interface can switch between Simplified Chinese (the default, regardless of the system language) and English, covering the main screen, dialogs, car settings, the detection panel, the update page, notifications, the car dashboard and the disclaimer. Android 13 and later also list both languages in the system per-app language settings. Logs, diagnostic reports and protocol fields stay in Chinese.
- After each update, the first launch shows that version's release notes in the chosen language, from the `CHANGELOG.md` entry bundled into the APK; validation lines are omitted, and a fresh install shows the disclaimer instead. The update page also shows only the chosen language's half of the release notes.
- When a target head unit is selected, log once per connection start which Bluetooth services it advertises over SDP and whether the wireless CarPlay iAP2 service (`…caff`) is among them, so a head unit that lacks wireless CarPlay can be told apart from one that is not in its pairing window. An SDP timeout is reported as undetermined, not as missing.
- Add a root-only wired CarPlay bench tool (`WiredGadgetMain`, run through `su` + `app_process`, never from the app): it probes the phone's USB gadget and role-switch support, presents the phone to a head unit as an iPhone over FunctionFS, logs every control request, switches to USB host on the role-switch request and rolls back on timeout, failure or if the phone starts sourcing VBUS; rollback hands USB back through the Gadget HAL (`svc usb resetUsbGadget`). It never writes Type-C `data_role`, which starts a PD role swap; a manual swap made a Xiaomi 17 black-screen and reboot. A USB host iAP2 stream is added but not yet wired into connections. Without root, wired-only head units should use a wired-to-wireless adapter. See `docs/WIRED-CARPLAY.md`; the USB NCM media path is verified against MacPlay, while head-unit role switching remains unverified.
- Add a **USB wired connection** entry to remote debug only: request root, present an iPhone-compatible NCM + FunctionFS usbmux gadget, implement lockdown/carkit and wired iAP2, and send CarWith video/audio over USB NCM AirPlay. MacPlay 1.2.4 and DiPlay 0.2.22 on an Android tablet completed wired handshakes; video, audio decoding and touch reached the receivers, and the user confirmed by ear that sound actually played on both MacPlay and DiPlay. DiPlay mode requests trigger re-enumeration; discovery accepts iAP2 StartSession or USB mDNS, then neighbors/manual addresses. Audio falls back from AAC-LC to PCM. Stop and process exit restore Android USB. Real head-unit role switching and USB-host iAP2 remain unverified; the connection entry is absent from standard.
- Validation: 661 JVM tests and 33 Python tests passed; `spotlessCheck`, standard/remote debug lint and APK builds passed. Device evidence covers the MacPlay and DiPlay benches described in `docs/WIRED-CARPLAY.md`; head-unit role switching, SDP logging, language switching and the release-notes popup still require device verification.

### 简体中文
- 修复有线 TCP 数据与 ACK 顺序、对端 RST 退出和未知端口拒绝；串行化连接表清理，重新握手清空旧 framing，FunctionFS 仅在 ENABLE 后读端点并在空闲时退避。等待 USB 网卡期间停止会释放连接启动标志。StartSession 改为单次、有时限的 root 等待；USB 标签不再回填 Wi-Fi SSID，页面只注销自己注册的监听。
- 有线/无线共用媒体链路：计时应答与音频同步统一使用平移到 NTP 纪元的单调时钟，避免 DiPlay 反馈编码异常；UDP 发送短暂失败有最多 3 秒宽限；屏幕视频帧头时间戳改用与计时应答相同的 NTP 纪元时钟，按帧头时间调度的接收端不再把画面当成约 126 年前的数据。
- 新增 **界面语言**：界面可在简体中文（默认，不跟随系统语言）和英文之间切换，覆盖首页、各类弹窗、车机设置、检测面板、更新页、通知、车机仪表盘和免责声明；Android 13 及以上也会出现在系统「应用语言」设置中。日志、诊断报告和协议字段保持中文。
- 每次更新后首次打开，按所选语言弹出该版本的更新内容，来源为打包进 APK 的 `CHANGELOG.md` 条目，不显示验证条目；全新安装则先显示免责声明。更新页的发行说明也只显示所选语言那一半。
- 指定目标车机时，每次启动连接记录一次它通过 SDP 声明的蓝牙服务，以及其中是否有无线 CarPlay 的 iAP2 服务（`…caff`），用于区分「车机不支持无线 CarPlay」与「支持但未进入配对窗口」。SDP 超时记为「未确定」，不记为没有。
- 新增仅限 root 的有线 CarPlay 台架工具（`WiredGadgetMain`，经 `su` + `app_process` 运行，不由 App 调用）：探测手机 USB gadget 与角色切换能力，通过 FunctionFS 让手机对车机呈现为 iPhone，记录全部控制请求，收到角色切换请求后切为 USB 主机；超时、失败或手机开始向 VBUS 供电时回滚，并通过 Gadget HAL（`svc usb resetUsbGadget`）交还 USB。不会写 Type-C `data_role`（会发起 PD 角色互换；手动互换曾导致小米 17 黑屏重启）。另新增 USB 主机 iAP2 流，尚未接入连接流程。免 root 时，只支持有线的车机建议使用有线转无线盒子。详见 `docs/WIRED-CARPLAY.md`；USB NCM 媒体路径已在 MacPlay 上验证，真实车机的角色切换仍未验证。
- remote debug 包新增 **USB 有线连接** 入口：申请 root，以兼容 iPhone 的 NCM + FunctionFS usbmux 身份枚举，实现 lockdown/carkit 和有线 iAP2，并在 USB NCM 上经 AirPlay 发送 CarWith 音视频。MacPlay 1.2.4 和安卓平板上的 DiPlay 0.2.22 已完成有线握手，接收端收到视频、音频解码和触控，MacPlay 与 DiPlay 的实际出声均已获用户听感确认。DiPlay 模式请求会触发重枚举；发现支持 iAP2 StartSession 或 USB mDNS，再降级到邻居表/手动地址，音频支持 AAC-LC 到 PCM 降级。停止和进程退出恢复 Android USB。真实车机角色切换与 USB 主机侧 iAP2 仍未验证，入口不进入 standard。
- 验证：661 项 JVM 测试、33 项 Python 测试、`spotlessCheck`、standard/remote debug lint 和 APK 构建通过。设备证据覆盖 `docs/WIRED-CARPLAY.md` 中的 MacPlay 和 DiPlay 台架；真实车机角色切换、SDP 日志、语言切换与更新内容弹窗仍需设备验证。

## [v0.1.3-test.3] - 2026-10-29

> Experimental prerelease: lets phones without Shizuku supply their real Bluetooth address; the new address flow has not been verified on device and this does not establish real-car compatibility.
> 实验性预发布：未使用 Shizuku 的手机也能提供真实蓝牙地址；新的地址流程尚未实机验证，不代表实车兼容。

### English
- Add **本机蓝牙地址** to the connection checklist: receivers such as WinPlay check the phone's real Bluetooth address, which Android hides from ordinary apps. A live Shizuku (or system settings) read wins and is now saved for later sessions without Shizuku; otherwise a manually entered address is used. Entries are validated (placeholder, random, multicast and malformed addresses are rejected), and logs and the support report state the source (`bluetooth.identitySource`) without the address.
- When Shizuku is not ready and no address has been entered or saved, **启动连接** now asks for the phone's real Bluetooth address first, explains where to find it, and continues the connection after it is saved. **仍然连接** keeps the previous behavior.
- README: native video at the receiver's aspect ratio, touch on DiPlay and WinPlay 1.2.5, media keys, the 25-minute WinPlay run, and WinPlay as a bench receiver.
- Validation: 546 JVM tests, 26 Python tests, `spotlessCheck`, `lintStandardDebug`, `lintStandardRelease`, `assembleStandardRelease` and `assembleRemoteDebug` passed. The manual address flow and the new prompt are not yet verified on device.

### 简体中文
- 「连接准备」新增 **本机蓝牙地址**：WinPlay 等接收端会核对手机真实蓝牙地址，而 Android 不让普通应用读取。Shizuku（或系统设置）实时读取优先，并会保存下来供之后没有 Shizuku 时使用；否则使用手动填写的地址。填写会校验格式（拒绝占位、随机、组播及格式错误的地址），日志与诊断报告只记录来源（`bluetooth.identitySource`），不记录地址本身。
- Shizuku 未就绪、且没有手动填写或保存过地址时，点 **启动连接** 会先提示填写本机真实蓝牙地址并说明查找位置，保存后自动继续连接；选 **仍然连接** 则与以前相同。
- README：更新原生视频按接收端宽高比编码、DiPlay 与 WinPlay 1.2.5 触控、媒体键、WinPlay 约 25 分钟运行，以及 WinPlay 桌面测试说明。
- 验证：546 项 JVM 测试、26 项 Python 测试、`spotlessCheck`、`lintStandardDebug`、`lintStandardRelease`、`assembleStandardRelease` 和 `assembleRemoteDebug` 均通过。手动地址流程与新提示尚未实机验证。

Full changes / 完整变更: [v0.1.3-test.2...v0.1.3-test.3](https://github.com/wangxso/mcarplay/compare/v0.1.3-test.2...v0.1.3-test.3)

## [v0.1.3-test.2] - 2026-10-27

> Experimental prerelease: validated on WinPlay 1.2.5 with a Xiaomi phone (release gate met); this does not establish real-car compatibility.
> 实验性预发布：已在 WinPlay 1.2.5 与小米手机上实机验证（满足发布门槛）；不代表实车兼容。

### English
- Fix no CarWith sound when Bluetooth auto-start races a manual start: the auto-start path no longer turns off sound when native CarWith audio is available. Log the first frame of each CarLife audio kind and count frames the parser drops, so a 0-packet session shows whether CarWith sent anything.
- Add **检测当前车机**: collect connection evidence, confirm picture/sound/touch, and atomically save device-specific settings locally. Try one explicit Bluetooth-stage alternative; limit fallback-port and `/info` method probes; reuse confirmed audio preferences only for matching capabilities. Cancellation preserves previous settings, unknown results stay unverified, and no model list or automatic upload is added.
- Treat WinPlay firmware 1.2.5 like 1.2.4: check the real Bluetooth identity before SETUP and keep the network session when WinPlay releases Bluetooth after network authentication. Other WinPlay versions stay conservative.
- Forward receiver next/previous media keys to CarWith, and request a CarWith encode size that keeps the receiver's aspect ratio.
- Add two opt-in compatibility fallbacks in **车机设置 → 显示**, both off by default: retry with an unencrypted session after an ordinary pairing failure, and try the PCM 48 kHz audio baseline when the receiver lists no usable media format.
- Restyle the detection and update entries, the update page and the detection panel to match the app's dark settings style.
- The remote debug flavor logs phone, HyperOS, CarWith and CarLife versions when it connects to the debug server.
- Device validation (2026-10-27, Xiaomi 2509FPN0BC, HyperOS OS4.0, CarWith 4.0.20, Xiaomi CarLife 8.8.5): on WinPlay 1.2.5 with default settings (both fallbacks off, auto-connect on), picture, touch, music and navigation prompts reached the receiver; a session ran about 25 minutes after Bluetooth release; sessions reconnected after a manual disconnect and after manual network loss. Short WinPlay 1.2.4 and DiPlay runs also delivered picture and sound. The detection wizard's interactive flow and the new WinPlay 1.2.5 policy rule were not exercised on device. Details: `docs/CARWITH-ENTRY-FINDINGS.md`.
- Validation: 543 JVM tests, 26 Python tests, `spotlessCheck`, `lintStandardRelease` and `assembleStandardRelease` passed; `lintRemoteDebug` and `assembleRemoteDebug` passed earlier on this branch.

### 简体中文
- 修复蓝牙自动启动与手动启动相互覆盖导致 CarWith 无声：CarWith 原生音频可用时，自动启动不再关闭声音。CarLife 各音频帧类型首帧入日志，并统计解析器丢弃的帧，0 包会话可据此判断 CarWith 是否发送过数据。
- 新增 **检测当前车机**：收集连接证据，由用户确认画面／声音／触控后，按设备原子保存本地配置。蓝牙阶段可显式对照一次，端口及 `/info` 方法探测有界；音频偏好仅在能力匹配时复用。取消保留旧配置，未知结果保持未验证，无需车型列表，不自动上传。
- WinPlay 固件 1.2.5 与 1.2.4 采用相同处理：SETUP 前检查真实蓝牙身份，网络认证后 WinPlay 释放蓝牙时保留网络会话；其他 WinPlay 版本仍按保守策略。
- 接收端的上一曲／下一曲转发给 CarWith；向 CarWith 请求与接收端宽高比一致的编码尺寸。
- 在 **车机设置 → 显示** 新增两个兼容兜底开关，均默认关闭：普通配对失败后改用明文会话重试；接收端未声明可用的媒体音频格式时按 PCM 48kHz 基线尝试。
- 「检测当前车机」「检查更新」入口、更新页和检测面板改为与应用一致的深色设置风格。
- remote 调试版本连上调试服务器时自动记录手机、HyperOS、CarWith 与 CarLife 版本。
- 实机验证（2026-10-27，小米 2509FPN0BC、HyperOS OS4.0、CarWith 4.0.20、小米 CarLife 8.8.5）：WinPlay 1.2.5 默认设置（两个兜底开关关闭、「上车自动连接」开启）下，画面、触控、音乐和导航提示到达接收端；蓝牙释放后会话持续约 25 分钟；手动断开及手动断网后均能重连。WinPlay 1.2.4 与 DiPlay 短时实测亦出画面和声音。检测向导的实机交互及新的 WinPlay 1.2.5 策略规则未经实机验证。详见 `docs/CARWITH-ENTRY-FINDINGS.md`。
- 验证：543 项 JVM 测试、26 项 Python 测试、`spotlessCheck`、`lintStandardRelease` 和 `assembleStandardRelease` 均通过；`lintRemoteDebug` 与 `assembleRemoteDebug` 已在本分支此前通过。

Full changes / 完整变更: [v0.1.3-test.1...v0.1.3-test.2](https://github.com/wangxso/mcarplay/compare/v0.1.3-test.1...v0.1.3-test.2)

## [v0.1.3-test.1] - 2026-10-27

> Experimental prerelease: receiver policy and Wi-Fi/CarWith fixes passed offline checks; receiver-specific behavior and real-car media stability still need device validation.
> 实验性预发布：接收端策略及 Wi-Fi/CarWith 修复已通过离线检查；接收端专项行为和真实车机媒体稳定性仍待实机验证。

### English
- Add one receiver compatibility policy based on Bluetooth identity/firmware/EA, mDNS features/srcvers, AirPlay `/info` capabilities and current-session state. Pairing, Bluetooth handoff, recovery exceptions, audio selection and compatibility memory now follow the same endpoint evidence; unknown receivers remain conservative.
- Keep active Wi-Fi video sessions alive when a receiver releases Bluetooth after network handoff, and report automatic Bluetooth loss separately from a user disconnect.
- Stabilize same-LAN Wi-Fi and CarWith setup by refreshing stale network bindings and respecting system network-picker and join lifecycles.
- Advance the Android base version to `0.1.3` (`versionCode=1030000`); tagged builds identify `v0.1.3-test.1`.
- Validation: 493 JVM tests, 26 Python tests, `spotlessCheck`, `lintStandardRelease` and `assembleStandardRelease` passed. No new vehicle-compatibility claim is made; receiver behavior and full media regression still need device validation.

### 简体中文
- 新增统一接收端兼容策略，依据蓝牙身份/固件/EA、mDNS features/srcvers、AirPlay `/info` 能力及本轮会话状态，统一决定配对、蓝牙交接、恢复例外、音频选择和兼容记忆；未知接收端继续采用保守策略。
- 接收端完成网络交接并释放蓝牙时，保留仍在运行的 Wi-Fi 视频会话；诊断中区分蓝牙自动断开与用户主动断开。
- 通过刷新过期网络绑定并正确处理系统选网框和入网生命周期，提升同局域网 Wi-Fi 与 CarWith 启动流程的稳定性。
- Android 基础版本升级为 `0.1.3`（`versionCode=1030000`）；标签构建记录 `v0.1.3-test.1`。
- 验证：493 项 JVM 测试、26 项 Python 测试、`spotlessCheck`、`lintStandardRelease` 和 `assembleStandardRelease` 均通过。不新增实车兼容声明；接收端行为及完整媒体回归仍待实机验证。

Full changes / 完整变更: [v0.1.2-test.1...v0.1.3-test.1](https://github.com/wangxso/mcarplay/compare/v0.1.2-test.1...v0.1.3-test.1)

## [v0.1.2-test.1] - 2026-10-26

> Experimental prerelease: GitHub updates have passed offline validation;
> on-device installation, permissions and post-upgrade connection regression remain pending.
> 实验性预发布：GitHub 更新已通过离线验证；手机端安装、权限和升级后连接回归仍待完成。

### English
- Add a main-screen GitHub update flow with release notes, stable/preview channels, cancellable APK downloads and explicit Android installer confirmation.
- Verify download size/SHA-256, package/version, Android requirements and the installed signing certificate; refuse downgrades and standard APK updates for the remote flavor.
- Record release tags in tagged builds to compare preview iterations; explain when older/local builds lack enough metadata to compare the same base version.
- Advance the Android base version to `0.1.2` (`versionCode=1020000`) and record `v0.1.2-test.1` in the tagged APK. In-place upgrades require the same signing key.
- Document update behavior and device acceptance checks. On-device installation and connection regression remain pending.
- Offline validation: 449 JVM tests (37 update tests), 26 Python tests, formatting, standard debug/release lint and standard debug/release plus remote debug APK builds.

### 简体中文
- 首页新增 GitHub 更新入口：更新说明、正式/预发布频道、可取消 APK 下载，最后由用户在 Android 系统安装器确认。
- 检查大小/SHA-256、包名/版本、Android 要求及当前签名证书，阻止降级和 remote 版安装标准发行 APK。
- 标签构建记录发行标签以比较预发布先后；旧版/本地包缺少同基础版本比较信息时明确说明。
- Android 基础版本升级为 `0.1.2`（`versionCode=1020000`），标签 APK 记录 `v0.1.2-test.1`。覆盖升级须使用同一签名密钥。
- 补充更新机制与设备验收文档；手机端安装及连接回归仍待完成。
- 离线验证：449 项 JVM 测试（其中 37 项更新测试）、26 项 Python 测试、格式、standard debug/release lint、standard debug/release 与 remote debug APK 构建。

Full changes / 完整变更: [v0.1.1-test.2...v0.1.2-test.1](https://github.com/wangxso/mcarplay/compare/v0.1.1-test.2...v0.1.2-test.1)

## [v0.1.1-test.2] - 2026-10-26

> Experimental prerelease: diagnostics fixes have passed offline checks only;
> on-device permission prompts, concurrent self-test and Bluetooth-drop reporting remain to be verified.
> 实验性预发布：诊断修复仅完成离线验证，实机权限弹窗、自检与连接并行及蓝牙断开报告仍待确认。

### English
- Diagnostics: a Bluetooth link drop during Wi-Fi join, or an exception after it, is recorded as `BT_LINK_CLOSED` instead of a user stop; exceptions keep the stage-specific code (`SETUP_FAILED`, `CARWITH_FAILED`, …) instead of `CONNECTION_INCOMPLETE`.
- Diagnostics: CarWith self-tests use their own timeline (`track=self_test`), so a self-test and a CarPlay attempt no longer cancel each other's records.
- Diagnostics: showing a permission dialog is no longer recorded as a failed attempt; only a denial is (new code `WIFI_PERMISSION` for Android 13+).
- Diagnostics: a video stream that ends after frames were sent is recorded as a normal stop, not a failure.
- Reports: known names are replaced only as whole words, and a `name` label inside an identifier such as `serviceName=` is no longer treated as a name.
- Diagnostic records are written on a background thread, outside the timeline lock, so video, audio and UI threads no longer wait on disk writes.
- Validation: 412 JVM tests, 26 Python tests, formatting, Android lint and local APK builds passed. No new vehicle-compatibility claim is made.

### 简体中文
- 诊断：加入热点期间蓝牙链路断开（或随后发生异常）记为 `BT_LINK_CLOSED`，不再记成用户停止；异常保留阶段错误码（`SETUP_FAILED`、`CARWITH_FAILED` 等），不再统一记为 `CONNECTION_INCOMPLETE`。
- 诊断：CarWith 自检使用独立时间线（`track=self_test`），自检与 CarPlay 连接不再互相顶掉记录。
- 诊断：弹出权限对话框不再记为连接失败，只有拒绝授权才记录（Android 13+ 新增错误码 `WIFI_PERMISSION`）。
- 诊断：已发出视频帧后视频连接结束记为正常结束，不再算作失败。
- 报告：已知名称只按完整单词替换；`serviceName=` 这类标识符里的 `name` 不再被当作名称。
- 诊断记录改在后台线程、时间线锁外写盘，视频、音频和界面线程不再等待磁盘写入。
- 验证：412 项 JVM 测试、26 项 Python 测试、格式、Android lint 和本地 APK 构建通过；不新增实车适配声明。

Full changes / 完整变更: [v0.1.1-test.1...v0.1.1-test.2](https://github.com/wangxso/mcarplay/compare/v0.1.1-test.1...v0.1.1-test.2)

## [v0.1.1-test.1] - 2026-10-26

> Experimental prerelease: diagnostics changes have passed offline checks only;
> on-device export, permission interactions and Xiaomi connection regression remain pending.
> 实验性预发布：诊断改动仅完成离线验证，实机导出、权限交互及小米连接回归仍待完成。

### English
- Add connection-stage IDs, error codes/advice and a main-screen redacted support-report export button.
- Preserve bounded previous-process logs, critical JSONL events, uncaught stacks and app-only system exit evidence.
- Distinguish native media input from transport writes; include format and memory samples without per-frame disk logging.
- Include APK/signing fingerprints, component versions, permissions and network snapshots; redact common credentials, wire dumps and identifiers.
- Add an offline analyzer and regression tests. No automatic upload is introduced.
- CI: share one build-setup action, cache pip, skip documentation-only changes and add APK size/SHA-256 job summaries.
- Signed workflow: reject a wrong keystore password or alias before building, upload reports on failure, and update an existing release when re-run.
- Validation: 399 JVM tests, 26 Python tests, formatting, Android lint and local APK builds passed. No new vehicle-compatibility claim is made.
- Android app version advances to `0.1.1` (`versionCode=1010000`); GitHub tag `v0.1.1-test.1` identifies the prerelease. Signed upgrades require the same signing certificate; local debug builds use a different certificate.
- Diagnostics are evidence, not guaranteed root-cause attribution. Review redacted reports before sharing; no automatic upload is introduced.

### 简体中文
- 新增连接阶段/会话编号、错误码与处置提示，首页可直接导出脱敏诊断报告。
- 有界保留最近进程日志、关键 JSONL 事件、堆栈及本应用系统退出证据。
- 区分原生媒体输入和实际发送计数，补充格式与内存采样，避免逐帧写盘。
- 报告包含 APK/签名指纹、组件版本、权限和网络快照，处理常见凭据、原始报文和标识信息。
- 新增离线分析器和回归测试，不会自动上传日志。
- CI：两个工作流共用构建环境配置，缓存 pip，仅改文档时跳过构建，并在运行摘要中显示 APK 大小与 SHA-256。
- 签名工作流：构建前即校验 keystore 密码与别名，失败时上传报告，重跑时更新已存在的 Release 而非报错。
- 验证：399 项 JVM 测试、26 项 Python 测试、格式、Android lint 和本地 APK 构建通过；不新增实车适配声明。
- Android 应用版本升级为 `0.1.1`（`versionCode=1010000`），GitHub 标签为 `v0.1.1-test.1`。正式签名覆盖更新需使用同一证书；本地 debug 包使用不同证书。
- 诊断报告只表明已记录的事实，不能保证还原所有根因；分享前仍须检查脱敏结果，不会自动上传。

Full changes / 完整变更: [v0.1.0-test.4...v0.1.1-test.1](https://github.com/wangxso/mcarplay/compare/v0.1.0-test.4...v0.1.1-test.1)

## [v0.1.0-test.4] - 2026-10-26

### English

**Added**
- README badges for Android CI, the latest release, release downloads, Android 12+, MIT licensing, experimental status and GitHub stars.
- A versioned bilingual changelog, linked from both READMEs and included in source upload bundles.

**Changed**
- Tagged releases now use the matching changelog entry as their GitHub release notes, instead of only generating a commit link.
- Missing, empty or duplicate release-note entries stop publication before signing.
- The discovery responder's port-release assertion now allows bounded kernel shutdown latency, avoiding an immediate-connect race seen on hosted CI runners.

**Validation and scope**
- No application runtime changes compared with `v0.1.0-test.3`; this is a documentation and release-process update.
- The existing 364 JVM tests, Android lint, formatting checks and APK build are retained as release gates; release-note extraction has dedicated Python tests.
- The APK still reports Android version `0.1.0`, as in the previous test release. The GitHub tag identifies this prerelease iteration.
- APK, SHA-256 checksums and signing-certificate information are published as release assets. Keep the previous signing key for in-place updates.
- Xiaomi-to-DiPlay video, music and touch have bench-test evidence. Real vehicles, navigation announcements, pause/resume and extended stability still require validation.

### 简体中文

**新增**
- 中英文 README 徽章：构建状态、最新版本、Release 下载量、Android 12+、MIT 许可证、实验状态和 Stars。
- 按版本维护的中英文 changelog；两份 README 均提供入口，源码上传包也包含更新日志。

**调整**
- 标签发布直接读取对应版本的 changelog 作为 GitHub Release 正文，不再只有自动生成的提交链接。
- 对应更新说明缺失、为空或重复时，在签名前终止发布。
- 发现服务的端口释放断言改为有界等待内核完成关闭，避免托管 CI 上立即重连探测产生的竞态误报。

**验证与范围**
- 相比 `v0.1.0-test.3` 没有应用运行逻辑变化，本次为文档与发布流程更新。
- 保留 364 项 JVM 测试、Android lint、格式检查和 APK 构建门槛；为更新说明提取新增 Python 专项测试。
- APK 在 Android 中仍显示 `0.1.0`，与上一测试版一致；GitHub 标签区分本轮预发布。
- Release 附带 APK、SHA-256 校验文件和签名证书信息；覆盖更新需沿用上一版签名密钥。
- 小米到 DiPlay 的画面、音乐和触控已有测试证据；实车、导航提示、暂停恢复和长时间稳定性仍待验证。

Full changes / 完整变更: [v0.1.0-test.3...v0.1.0-test.4](https://github.com/wangxso/mcarplay/compare/v0.1.0-test.3...v0.1.0-test.4)

## [v0.1.0-test.3] - 2026-10-26

### English
- Fixed uncaught heartbeat interruption during normal CarLife shutdown, with a 20-cycle regression test.
- Stopped the session's CarLife source and native audio bridge when video streaming ends; guarded stale video callbacks from cleaning up a replacement session.
- Documented Xiaomi/DiPlay disconnect evidence and intentional receiver-loss, manual-disconnect and reconnect checks; no new heartbeat FATAL was observed in that device regression.
- Shipped mcarplay branding, the Mi-style launcher icon and the existing CarWith video/audio/touch bridge.
- Local verification: 364 JVM tests and 12 Python tests passed, alongside formatting, lint and build checks. The signed GitHub APK workflow completed successfully.

### 简体中文
- 修复 CarLife 正常停止时心跳线程中断产生未捕获异常的问题，新增连续 20 次启停回归。
- 视频结束时停止本轮 CarLife 源和原生音频桥；隔离旧视频回调，避免清理替代会话。
- 补充小米与 DiPlay 断连证据，以及接收端丢失、手动断开和重连记录；设备回归未新增心跳 FATAL。
- 包含 mcarplay 品牌、Mi 风格启动图标和已有 CarWith 视频、音频、触控桥接。
- 本地验证：364 项 JVM 测试、12 项 Python 测试、格式检查、lint 和构建通过；GitHub 签名 APK 流程成功。

[v0.1.0-test.4]: https://github.com/wangxso/mcarplay/releases/tag/v0.1.0-test.4
[v0.1.1-test.1]: https://github.com/wangxso/mcarplay/releases/tag/v0.1.1-test.1
[v0.1.0-test.3]: https://github.com/wangxso/mcarplay/releases/tag/v0.1.0-test.3
