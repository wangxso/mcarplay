# CarWith 公开入口调查（2026-10-25）

## 样本与环境

- 手机：`2509FPN0BC`，HyperOS `OS4.0.4.0.XPBCNXM`，解锁且亮屏。
- CarWith `4.0.20-20260923`：APK SHA-256 `85ddab72dfed8cf532c6348aac1313c64373f42bf49915cee4d9e064e07c95b6`。
- 百度 CarLife 小米组件 `8.8.4`：APK SHA-256 `00df4aaacbbc5837f3a0ef17fc55dfabf038596dd27b652cfb23c9722770a994`。
- 静态检查使用 JADX `1.5.6` 与设备 `dumpsys package`；动态检查只调用 CarWith 导出的设置页，未更改蓝牙配对。

## 公开入口与限制

| 组件 | 证据 | 作用 |
| --- | --- | --- |
| `UCarScreenSettingsActivity` | Manifest `exported=true`；设备上可用显式 `am start` 打开 | 用户可进入 CarWith 的 CarLife 连接设置 |
| `CarLifeConnectActivity`、`CarLifeWirelessConnectActivity`、`CastingActivity` | Manifest `exported=false` | 只能由 CarWith 自身的 UI 流程启动 |
| `CarlifeAdbActivity` | Manifest `exported=true`；`onCreate()` 启动 `CarlinkService`，`startType=3` | 调试入口；已实测 10 秒投屏失败并清理 |
| `CarlinkService` | Manifest 要求 `miui.carlink.permission.START_CARLINKSERVICE`；设备声明为 `signature|privileged` | 第三方 APK 不能直接启动正常服务 |
| `CarlifeConnService` | Manifest `exported=true`；`i()` 检查 Binder 调用 UID 对应包是否为 `com.baidu.carlife.xiaomi` 或 `com.baidu.carlife` | 第三方 APK 不能直接调用 `prepareCast()` |

JADX 中的用户路径为 `UCarScreenSettingsActivity` 的 `carlife_connect` 项进入 `CarLifeConnectActivity`，再选无线模式。热点、车辆热点、WLAN 直连的正常流程在启动前检查蓝牙连接及网络状态，之后由 CarWith 内部以 `startType=4` 启动服务。静态代码说明设计意图，不等于当前设备已建立投屏。

10 秒定时器也能从静态代码定位：`CarlifeAdbActivity.c()` 以 `startType=3` 启动 `CarlinkService`；该分支的回调调用 `z(null, 10)`，再由 `CarlinkStateMachine.g1(10)` 安排失败消息。`CarlifeConnService.prepareCast()` 收到有效 Surface 后才通知 `CarlinkService` 的投屏状态机，因此单纯启动七口监听不能代替这一步。

## 本次设备观察

通过导出的设置页依次选择 `CarLife连接 → 无线连接 → 热点连接`，页面显示“当前未连接任何车机蓝牙，前往蓝牙设置”。当时 `ss -ltn` 中没有 `7240/8240/9240/9241/9242/9340/9440` 监听；临时 UI XML 已删除。

用户随后确认 `Test-HU` 是用于模拟车机的 Android Pad（PA2353，Android 14）。只读 `dumpsys bluetooth_manager` 显示它已经与小米配对，但 10:36–10:42 间多次经典蓝牙 ACL 连接成功后约 5–13 秒以 `Local Terminated Connection` 断开。Pad 公布 `0x110A`（A2DP Source）、`0x1112`（HSP Audio Gateway）、`0x111F`（HFP Audio Gateway），没有公布 `0x110B`（A2DP Sink）或 `0x111E`（HFP）。UUID 含义依据 [AOSP BluetoothUuid](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-leanback-release/android-35/android/bluetooth/BluetoothUuid.java)。它表现为普通 Android 设备的音频角色，不能靠配对自动建立车载音频连接。

Pad 前台运行 `dev.h3bridge.hu` 0.3.2，内置百度 CarLife 接收端 SDK。其 SDK 使用 `00001101` 的 RFCOMM 串口连接已配对小米；运行日志重复 `BluetoothDeviceDiscover connect device exception ... read ret: -1`。这是接收端主动连接手机的串口失败，不是视频或 CarPlay 握手失败。10:48 新增 debug 专用 `BtAnchorService`：两机各运行一个服务，以独立 UUID 建立空载 RFCOMM 连接。两端都记录 `RFCOMM anchor connected`，小米 `Test-HU` 的 ACL 变为 `BR/EDR:Y`；当时七个 CarLife 端口仍未监听。这个辅助服务只用于排查 Pad 模拟环境，不进入 release APK，也不作为真实车机验收证据。

10:55 用户通过 CarWith 的 WLAN 直连页面连接 Pad；七个 CarLife TCP 通道均与 Pad 的 `192.168.49.1` 建立连接，`CarlinkService`、`CarlifeConnService` 保持运行。`dumpsys display` 出现 CarWith 的 1280×720 虚拟显示。Pad 实际显示 CarWith 地图和音乐界面（含位置的截图仅留在 `/tmp`，不入仓）。Pad 接收端的脱敏指标显示：10:55:36 传输与协议建立，10:55:38 首帧；10:55:41–10:58:11 的 150 秒内解码帧数由 71 增至 3077，`OMX.qcom.video.decoder.avc` 保持 `RUNNING`、丢帧和解码错误均为 0。安全摘要保存于 `/tmp/mcarplay-0a-pad-evidence.json`。这证明一次正常 Pad 仿真流程能建立真实 CarWith 投屏；尚未完成阶段 0A 要求的 3 次冷启动/至少 2 次成功，也未证明 mcarplay 自己能读取视频。

CarLife `ConnectManager.AcceptThread` 收到同名通道的第二个客户端时会调用 `resetConnectManager()`。因此不能在 Pad 正在投屏时运行 `--attach` 探针，否则会破坏已建立的会话。10:55:41 的在线激活返回 `errno=10004`，同一日志标记 `mIgnoreActiviteFail=true`，且 Pad 随后持续出画；这次激活返回没有阻断当前视频，原因仍需按协议独立分析。

测试辅助链路由 debug APK 提供；`Test-HU` 必须先在两机系统蓝牙中完成配对。已安装且授予 `BLUETOOTH_CONNECT` 后，可复现或清理：

```bash
adb -s PAD_SERIAL shell am start-foreground-service \
  -n com.mcarplay.app/.debug.BtAnchorService \
  -a com.mcarplay.app.debug.BT_ANCHOR_SERVER
adb -s 192.0.2.10:37123 shell am start-foreground-service \
  -n com.mcarplay.app/.debug.BtAnchorService \
  -a com.mcarplay.app.debug.BT_ANCHOR_CLIENT --es peerName Test-HU
# 测试结束时，在两机分别运行：
adb -s PAD_SERIAL shell am stopservice \
  -n com.mcarplay.app/.debug.BtAnchorService
adb -s 192.0.2.10:37123 shell am stopservice \
  -n com.mcarplay.app/.debug.BtAnchorService
```

新增的探针命令：

```bash
.venv/bin/python tools/carlife_probe.py 192.0.2.10:37123 \
  --attach --seconds 35 --full --video \
  --save-h264 /tmp/mcarplay-carwith.h264
```

本次返回码为 `3`，提示七路监听均不存在；检查发生在启动 Activity 与建立 ADB 转发之前。未生成 H.264。探针成功返回还要求签名鉴权、AES 就绪，以及 SPS/PPS、IDR、至少 30 个普通帧、30 秒时间跨度且相邻时间戳间隔不超过 2 秒；最终仍需 `ffmpeg` 解码和画面变化验证。当前结果只证明没有可附着的 CarLife 会话，不能证明正常流程在配对车机后仍会失败。

APK 现默认附着已有 CarWith 会话：首页“设置”打开上述导出设置页，“自检”只等待七口，正式链路也不再自动启动 ADB 调试入口。构建和本地测试已通过；此改动没有提供新的投屏成功证据。

## 下一项实机验证

先保留本轮成功会话证据，再在受控断开 Pad 接收端后由 mcarplay 单独附着 CarLife 通道；若服务随 Pad 断开而停止，需让 mcarplay 实现等效的公开无线 HU 建链流程，不能同时作为第二客户端抢占通道。阶段 0A 还需 3 次冷启动/至少 2 次成功；阶段 0B 必须由 mcarplay 在 8240 获得 30 秒可解码、随页面变化的 H.264。模拟 Pad 的成功仍需真实车机复测。

## 副机 HU 模拟实现（2026-10-25）

**19:22 纠正：** 以下 `_EasyConn`/10930 实现属于亿连发现诊断，不能作为百度 CarLife 无线建链实现。首页已改名，具体源代码依据与本轮设备结果见文末。

APK 新增首页“副机 HU 模拟”，把无线发现链拆成五个可分别验收的状态：

1. `ExternalHuSdpBridge` 监听 CarWith 的 RFCOMM UUID；
2. `ExternalHuP2pCoordinator` 用 autonomous group 接口建组，只采用系统返回的真实名称、MAC、SSID 和本机 P2P IPv4；组和连接信息均确认组主后才就绪；
3. `EasyConnMdnsPublisher` 持有 `MulticastLock`，绑定该 P2P IPv4 并通过 JmDNS 发布 `_EasyConn._tcp.local.`；
4. `CarWithDiscoveryResponder` 只在同一地址监听 10930；
5. 收到 `0x70000010` 后返回 `0x70000011 {"status":"true"}`，状态才记为 `accepted`。

这一步解决的是 CarWith 的无线发现/建网入口，不是媒体桥接。单部手机的蓝牙适配器不能同时作为自己的 RFCOMM 对端，因此测试必须由 Pad 或另一台手机扮演 HU；该拓扑也不证明真实 CarPlay 车机兼容。当前尚未在设备上完成这一新入口的实际 CarWith 会话，10930 未收到请求前不得标记为通过。

本地验证新增 `CarWithDiscoveryResponderTest` 的 loopback 覆盖：完整请求返回 `0x70000011 {"status":"true"}`，错误命令和带载荷请求不标记成功，停止后可重新绑定 10930。全量 JVM 测试 308 项、CarLife 65 项通过，Spotless、lint 和 APK 构建通过；APK SHA-256 为 `2ea731c3693386c5d9d692ee25090645554f1b2b2f2114c41004b55dad188595`。

2026-10-25 12:03 再次尝试覆盖安装 Pad 失败，系统返回 `INSTALL_FAILED_ABORTED: User rejected permissions`；Pad 仍为 10:48 的旧版 APK。小米测试机 `192.0.2.10:37123` 离线，Pad 停在锁屏，因此本轮没有新增设备侧 SDP、P2P、mDNS 或 10930 证据，阶段 1A 仍未验收。

## 离线生命周期验证（2026-10-25）

本轮增加 P2P 就绪变化与异步清理测试、mDNS 注册/组播锁故障回滚测试，并扩展真实 loopback socket 验证。10930 停止时关闭未完成请求、清空成功状态，旧监听线程不能影响重启后的实例；网络变化或组主丢失时撤销 SDP 公告并回收发现服务。mDNS 发布与回收改在串行后台线程执行，P2P 移组等待系统回调或 2 秒超时后关闭通道。复用的已有 P2P 组不由本轮主动删除。

完整离线检查通过：`./gradlew --offline spotlessCheck :app:testStandardDebugUnitTest :app:lintStandardDebug :app:assembleStandardDebug`，329 项 JVM 测试（CarLife 86 项），0 失败/0 跳过；Python 5 项通过，lint 0 error/0 warning。debug 专用蓝牙诊断服务增加 `android.permission.DUMP` 调用限制。最新 APK SHA-256 为 `f751bb700818947ab4cec80bb6b10b420fe8fe871da8cd8898da71e0952c2471`，Android Debug 签名校验通过。

本轮没有操作测试设备。mDNS 注册/组播锁与 P2P 清理使用注入的测试实现，不能证明无线多播可达或系统实际移组；socket 测试使用本机临时端口，不能证明设备上的固定 10930 可达。历史笔记对 RFCOMM 发起方及服务运行端描述不一致，设备恢复后须先核对该分工，再执行阶段 1A、0B 的验收。

## 协议分支纠正与 Pad 回归（2026-10-25 19:20–19:35）

本轮使用普通 shell 读取此前 JADX 1.5.6 的反编译结果；CarWith 与 CarLife APK 的 SHA-256 均与“样本与环境”一致。REA Android provider 因缺少 `REA_JADX_MCP_JAR` 返回 `capability_unavailable`，未产生新的 REA 分析证据，也未修改工具配置。

| 分支 | 静态依据 | 结论与边界 |
| --- | --- | --- |
| 亿连 | `net.easyconn.carman.sdk_communication.SDP.SdpBluetoothUtil.l()` 调用 `createRfcommSocketToServiceRecord(EBT_UUID)` 和 `connect()`；`BluetoothSdpConnectRunnable.run()` 调用该方法 | 该客户端主动连接 HU；现有 RFCOMM 监听、`_EasyConn` 与 10930 属于这一分支。 |
| 百度 CarLife | `BluetoothClient.ListenThread.run()` 调用 `listenUsingRfcommWithServiceRecord()` 和 `accept()`，UUID 为 `00001101-0000-1000-8000-00805F9B34FB` / `a45bc7e5-bb50-4949-9de1-f78299cf6d78` | SDK 内存在手机监听路径；`ConnectClient.startTcpListener()` 只在 `isCarLifeApp()` 且隐私同意时调用它，小米 Mix 的实际运行仍需设备核对。 |
| CarWith 公开 CarLife WLAN 入口 | `CarLifeWlanConnectActivity.j1()` 设置 `startType=4`、`carlife_wireless_type=Baidu_wlan` | 继续沿该用户流程验证阶段 0A/0B；不能用 EasyConn 应答替代百度流程。 |

百度 `BluetoothWirlessHelper` 使用 CarLife 命令帧和 protobuf：`MSG_WIRLESS_INFO_REQUEST/RESPONSE` 为 `0x100001/0x108002`，`TARGETINFO_REQUEST/RESPONSE` 为 `0x100004/0x108005`。这与亿连的 16 字节小端 JSON 帧不同。以上是静态观察，未观察到小米本轮执行这些消息。

Pad 已成功覆盖安装最新版。首次测试复现“只补授 Wi-Fi 权限后服务未启动”：回调错误地把本次结果表中不存在的、先前已授权的权限当作拒绝。改为检查系统当前授权后，19:32:37 仅 `NEARBY_WIFI_DEVICES` 返回 `true` 即自动启动前台服务，未第二次点击启动。

设备日志与网络状态确认：P2P group owner 建立，`p2p0` 为 `192.168.49.1/24`；公开 API 给出的本机 MAC 为隐私占位值，新状态为 `mac-redacted`，因此没有发布 mDNS/10930。19:29:18 和 19:34:31 两次点击停止后约 0.3 秒均收到“本轮 P2P 组已移除”，随后 IPv4 和前台服务消失。没有请求特权 MAC 权限，也没有把 ADB 读到的地址注入应用。

本轮构建、330 项 JVM 测试（CarLife 87 项）及 lint 通过，零失败/零跳过/零 lint warning；APK SHA-256 为 `cd774c62450ee91485ca26a820c5561c3a953bda63aecd52369e541d4f5c4618`。首页将辅助入口标为“亿连发现诊断”，应用标题/版本页脚使用 mcarplay 名称。小米旧 ADB 地址与 Bonjour 广告中的 45381 均返回连接拒绝，双设备协议和媒体验证尚未开始。

## 单机出流与旧判断修正（2026-10-25 19:41–20:27）

用户提供新无线调试端口后，小米恢复连接。19:47 的公开 WLAN 流程再次让 Pad 显示真实 CarWith 视频；本轮未启动 `BtAnchorService`，约 3 分钟解码 3516 帧、丢帧为 0。退出 Pad 接收端后约 0.15 秒七口及虚拟显示均消失，不能把这轮会话直接交给另一个消费者。探针现检查已有 `ESTAB` 通道，拒绝第二消费者。

### 真实握手差异

源码依据仍为前述哈希匹配的 JADX 样本。Pad 原厂接收 SDK 的 `receiver.d.a.a` 在收到 MD_INFO 后发送 HU_INFO 和 `2.0;<nonce>`，不使用旧的 `CarLife_1.0.0`。手机 `PrivateKeyManager.encryptContentProcess()` 的 2.0 分支绑定 `brand + model + sdk + nonce`。本轮还通过 REA/Hopper 观察手机 `libencryption.so`（SHA-256 `09f3b9dc61010b6720cbb0a3fd964b11c43ca5c31382d190dbe86a63254c612e`）：

- `getVerifyResult`，地址 `0x10f40`，Evidence `ev_5e3ac36335947dc1ff32d1ddacf05819345a98fe7f38d96649f9dd217805f253`；
- `std::string::insert` / MD5 构造与输出调用，Evidence `ev_631edc16f5bd2294b12c28bf771e8efc85dcbf0afd03d9586f875286f86e266b`、`ev_57c3fd816557aff175f855b4ae7c4646fa2d5110e9935096e1bc25ac7fec0643`；
- 小写十六进制输出，Evidence `ev_1e3b9402e26f9c7a30fad1a6f6ce1c1f0076a4df6a9a56ada279c9bcdbe15b69`。

该分支比较 `MD5("CARLIFE" + brand + model + sdk + nonce)`，不是 RSA 身份签名。Hopper 不能提供完整函数体范围，其伪代码属于静态重建；随后手机真实响应通过独立 Kotlin/Python 实现的校验，补充了运行时证据。mcarplay 每轮使用 128 位随机 nonce，不记录证明原文，不因校验失败降级或发送成功。

另一处阻塞是 `CarlifeStatisticsInfo` 的 proto2 必填字段：旧实现只有 1–4，缺少 5（connectCount）、6（connectSuccessCount）、7（connectTime），手机无法解析。现发送 1 次尝试、0 次成功、0 时长，未借用 Pad 的渠道凭据或认证身份。`FeatureConfigManager.configSyncDone()` 在 AES 就绪后才派发渠道准入，因此只能在手机证明通过后开始特性/RSA/AES 协商，再等待解密后的 `MD_AUTH_RESULT=true` 才初始化视频。原先把准入放在特性响应前会死锁。

### 已完成的运行验证

| 运行 | 结果 |
| --- | --- |
| 20:10 探针，单客户端 | 40 秒，780 包，20 IDR / 760 非 IDR，7,028,590 字节，SPS/PPS 完整，最大时间戳间隔 1 秒，退出码 0 |
| 20:13 探针，9340 点击应用列表 | 40 秒，437 包，11 IDR / 426 非 IDR，3,515,953 字节；解码画面从启动页变为 CarWith 应用列表，退出码 0 |
| 20:17 APK 内点击“自检” | 普通 Intent 启动 CarWith；1280×720，超过 43 秒 / 841 帧，14 个回环 ESTAB 端点（七对连接），无外部 HU 消费者 |
| 20:25 最新 APK 严格冷启动第 1 轮 | 强制结束 CarWith / CarLife 后，由应用按钮重启；超过 102 秒 / 2041 帧，未出现 10 秒失败 |

两份探针 H.264 均由 `ffprobe` 识别为 Baseline 1280×720，`ffmpeg -v error -i <file> -f null -` 完整解码无错误。Annex B 文件自身不保留传输层时间戳，其 `r_frame_rate` 不用于实际帧率或时长结论；40 秒来自通道时间戳与墙钟。原始流和含位置的截图仅存于 `/tmp`。APK 运行时 Pad 接收端关闭、两机蓝牙辅助服务均未运行，`dumpsys media_projection` 为 `null`。

**修正早期结论：** `CarlifeAdbActivity` 的类名不意味着 APK 要调用 ADB。它是已导出的普通 Activity；应用用标准 Intent 启动，并由 CarWith 自己检查开关、隐私同意和权限。修复握手后，编码器 Surface 及时建立，早期的 10 秒失败不再复现。默认策略已恢复为 `EXPORTED_ENTRY`，仍保留 `ATTACH_EXISTING` 供受控诊断。未启动非导出组件、未伪造 UID，也未使用系统授权或录屏替代视频。

最新版 APK SHA-256 `3e70adc9e0257aa22f26a75b7b01efa42d9700461da5fa7adaab7ed844250f4d`；338 项 JVM 测试（CarLife 95 项）、12 项 Python 测试及 lint 通过。严格 3 次冷启动、10 次启停和无线 CarPlay 接收端验收继续记录。用户已指定 DiPlay，已从其官方 v0.2.21 发布下载并安装到 Pad，SHA-256 `916c0708beb1022d4ea9678ff6a46dfb6d234f707837a2cbd80d615e8e2bb2e3`，APK 签名校验通过；安装本身不代表 CarPlay 已连通。

## 音频桥接修正与当前卡点（2026-10-25 22:10）

旧实现把 9240/9241/9242 的完整媒体帧交给 AES 解密；CarLife 组件源码和运行时都证明只有 12 字节头后的 body 需要解密。修正 `CarLifeSession.handleAudio()` 后，新会话不再出现连续 `MEDIA 音频解密失败`，并新增 `CarLifeAudioParserTest` 覆盖 Init/PCM/畸形帧。最新 APK SHA-256 `2a148f8308b610158f304f6802190b760d6bbb4db06aa226ff8c7712492f6c2f`；348 项 JVM 测试、lint 与构建通过。

当时正式 CarPlay + 开启声音的实测显示 AirPlay 音频通道已协商成功：AudioSender 选择 t102/media/48000Hz AAC-LC，存活 130 秒并写入车机记忆，但只发出 0 包。该观察窗口没有 `MEDIA 音频初始化` 或 `AUDIO: CarWith 原生 PCM 接入`，系统 RemoteSubmix 输入出现 `mute`。当时据此推测 `AppRecorder.AudioCapture` 未激活。**这一根因推断已由下一节持续 MEDIA 帧和小米混合音频源码修正；不能把该窗口的静音推广为原厂未发送音频。**

**下一步优先级：**

1. 用 DiPlay/真车进入 CarWith 的“应用投屏”或可触发 `AudioCapture.startAudioCapture(MediaProjection)` 的原厂流程，确认 9240 INIT/DATA 与 44.1kHz capture 出流；mcarplay 不能伪造投屏状态。
2. 等到 9240 有真实 PCM 后，验证 `CarLifeAudioBridge` 到 48kHz AirPlay 通路；需要实现 44.1k→48k 重采样，避免直接把 44.1k PCM 当 48k 发送。
3. 只有音频 INIT + PCM 连续入队 + 车机有声，才可标记阶段 3 的原生音频通过；`AudioRecordData[mute]` 或零包不算成功。

## 小米原生音乐音频打通（2026-10-25 23:51–10-26 00:07）

23:51 复查时，原 APK 正持续报告 `MEDIA 音频解密失败`，同时视频仍约 20fps。原厂 `com.baidu.carlife.xiaomi` 8.8.4 APK 的 SHA-256 为 `00df4aaacbbc5837f3a0ef17fc55dfabf038596dd27b652cfb23c9722770a994`。本轮使用已有 JADX 输出核对以下静态路径；REA Android provider 缺少 `REA_JADX_MCP_JAR`，因此本轮这些源码观察来自本地 JADX，不标为新增 REA Evidence。

- `RemoteMediaAudioProcessor.onReceiveMediaData()` 调用 `CarLifeAudioManager.onReceiveMedia()`，再到 `PcmHoldHelper.onWriteAudioPcm()`。
- `PcmHoldHelper.sendMusicInitialParameterPackage()` 通过 `PCMPackageHead.setMusicAudioTrackParameter()` 加密 INIT body；`sendMusicPCMData()` 则直接复制 PCM body，没有 AES。12 字节头始终明文。
- `NaviAudioManager.write()` 仍显式加密 TTS body，因此明文规则仅用于小米组件的 MEDIA_DATA；解密失败不会重试明文。
- `EncryptSetupManager.exec()` 由手机发 MD_RSA_REQUEST、HU 回公钥，手机回加密种子，HU ACK 后手机 READY。现有握手方向正确，无需主动发送 MD 请求。MD_INFO 的 sdk 字段实际为 `29`，与内部 `sdk.build.xiaomi` 不同；音频规则依据本会话明确使用的小米组件选择。

实现补齐：每个音频通道独立保存格式；PCM 携带采样率/声道数；有状态整数时钟的线性重采样把 44.1kHz 双声道转换为 48kHz，单声道复制为双声道；外部音频发送器按 1024 样本组织 AAC 帧，初始化编码器后编码并按样本时钟发送 RTP。队列有界，停止时可唤醒等待线程。TTS/VOICE 已有提示优先选择，真实提示打断与恢复尚待验收；线性重采样的音质尚未进行频响测量。

实测时间均为北京时间。00:00:46.682 收到 `MEDIA 音频初始化 44100Hz 2ch 16bit`，随后 `原生 PCM 接入 #1 ... nonzero=true dropped=0`。DiPlay 随即完成 AAC 解密、解码并写入扬声器 AudioTrack。用户明确确认“能听到，音调和速度正常”。至 00:07:23.277（约 6 分 36 秒），DiPlay `outputBuffersTotal=18538`、`totalWrittenFrames=18982912`、`playbackHeadFrames=18968515`，`dropped=0`、`underruns=+0`、`writeErrors=0`、`rebuffers=0`；观察到的认证失败为 0，视频约 20fps、recoveries=0。自然换曲后仍继续出声。本轮尚未完成完整 10 分钟或暂停恢复专项；按用户要求收尾。

稳定性观察构建 SHA-256 为 `94ae9756b71327a3cd991c4c9c007c41194a52e0d0acf4c29c8167c11d133dab`。最终构建另修正原生音频无需录屏授权的提示/启动分支，并为会话中补启声音补发 RECORD；SHA-256 为 `5f47a15ca07e33bb05b2479432655380c7eacb6afba6455a377e0a51477bd099`。362 项 JVM 测试、12 项 Python 测试、格式检查、lint 和构建通过。后续仍需导航提示、暂停恢复、10 分钟专项和真实车机验收。

最终构建已覆盖安装到小米。安装后的自动重连遇到 DiPlay 旧会话 `pair-verify M2 缺少 publicKey`；重启 DiPlay 后重新出视频。新版音频行显示“使用 CarWith 原生声音 · 无需额外录屏授权”，点击“开启”后显示“已开启”，未弹出录屏授权；48kHz AAC-LC SETUP 和补发 RECORD 均成功。这次重连只作安装冒烟检查，不与前轮时长合并计算稳定性。

## 意外退出排查与心跳修复（2026-10-26）

覆盖安装前保存手机 crash buffer、`dumpsys activity exit-info com.mcarplay.app`、应用日志、DropBox 与 Pad 对应记录；原始记录包含设备信息，仅留在本机临时目录，不纳入源码包。

- 小米仍为 `2509FPN0BC / OS4.0.4.0.XPBCNXM`，CarWith `4.0.20-20260923`、CarLife `8.8.4`，接收端为 `PA2353 / Android 14 / DiPlay 0.2.21`。
- 手机 crash buffer 反复出现 `FATAL EXCEPTION: carlife-video-heartbeat`，堆栈均为停止时 `Thread.sleep()` 抛出的 `InterruptedException`。原全局未捕获处理器吞掉异常，因此 FATAL 日志不等于进程实际退出；00:08 后同一手机进程持续存活，系统退出记录未显示对应的崩溃或 ANR，DropBox 没有本应用崩溃条目。
- Pad 的 00:24:44 退出记录明确为 `LOW_MEMORY`，描述 `single-cleaner`，PSS 206MB、RSS 347MB。这是接收端被系统清理的证据，不能归因于手机 APK 崩溃，也不足以定位 DiPlay 内存泄漏。
- 00:38:10 手机隧道和事件连接收到 `Connection reset`，接着视频 `Broken pipe`；Pad 00:38:11 仍输出约 20fps 视频统计，随后记录 `shutdown reason=DiPlay disconnect terminateProcess=false` 并释放无线栈。此时没有接收端崩溃记录；仅凭现有时序无法确定最初的断开触发原因，不能把之前的低内存事件直接套用到这次拆链。
- 原视频结束回调没有停止 CarLife 源，CarWith 在断开后仍继续生成帧。本轮补齐本轮源、所有权和原生音频桥清理，并以视频发送器身份隔离旧轮回调，避免影响新连接。

心跳现在捕获预期中断、恢复中断标志并正常返回。新增 `CarLifeSessionTest` 直接驱动实际心跳线程：连续 20 次在睡眠中停止不进入未捕获处理器，且停止后晚启动的线程立即结束。全量 364 项 JVM 测试、格式检查、standard debug lint 与构建通过。

修复版（含 mcarplay 品牌与 Mi 风格新图标）SHA-256 为 `c8eb9511c0220f6cce8bc4437f7335ba24ddd0fa9cfdb1e28fc9a46e3f72b14d`，00:49 覆盖安装成功，用户自行接受新版免责声明后完成以下回归：

1. 00:53:14 起向 DiPlay 发送真实 CarWith 视频，1920×1140、约 20fps。00:54:49 有意通过 `am force-stop com.shihab.diplay` 关闭接收端，模拟接收端进程丢失，不计为自然崩溃。
2. 手机 00:54:49.115 收到视频连接重置，00:54:49.118 已记录七路连接关闭，随后恢复蓝牙探测。继续观察 10 秒，没有新增 CarLife 视频帧，旧实现断开后持续出流的问题未复现。
3. 重新打开 DiPlay 后，00:55:11.253 再次收到首帧。00:56:13 在手机应用内点击并确认断开，七路通道正常关闭，保活服务停止。
4. 00:56:24.778 再次出画，至 00:57:31 已收到 1321 帧，界面显示约 20fps。安装后的同一手机进程持续存活，按该进程过滤的 crash buffer 为 0 行，未新增心跳 FATAL。

本轮仅覆盖安装冒烟、接收端丢失清理和重连、应用内手动断开后重连；没有重做音乐听感、导航提示或完整 10 分钟专项，不代表真实车机稳定性验收完成。364 项 JVM 测试、12 项 Python 测试、Spotless、standard debug/release lint 与 standard debug/release、remote debug 构建通过。

## 同局域网连接失败与失效网络绑定（2026-10-26 晚间）

本轮为用户授权的无线 ADB 排查。手机为 `2509FPN0BC / OS4.0.4.0.XPBCNXM / Android 17 (SDK 37)`，CarWith `4.0.20-20260923`、小米 CarLife 已更新到 `8.8.5 (274)`。已安装 mcarplay `standard/release 0.1.1 (1010000)`，APK SHA-256 为 `e456b0f17a653c538abb38bc32ae7d2186b8ba669775be6a0be02932bec0c894`。接收端为 `PA2353 / Android 14 / DiPlay 0.2.21`，使用 `Existing Wi-Fi / Same LAN`；两端实际连接同一路由器。原始双端日志和报告仅保存在本机私有临时目录，不入仓、不上传。

1. 手机历史自检曾收到真实 CarWith 视频，报告中最近两轮为 12 / 98 帧；不能因此推断正式 CarPlay 链路也已经出画。
2. 23:28:45 手机获得应用专属网络句柄 `111`；23:28:46 TCP 接通，随后 pair-setup、pair-verify、SETUP 和主屏流协商成功，23:28:47 RECORD 成功。接收端声明主屏 `2800×1840@30`。
3. RECORD 后应用请求 CarWith 公开入口，但本机 `7240` 连续返回 `ECONNREFUSED`，七路连接未建立，源视频和已发送视频均为 0。23:28:53 会话结束。现有证据仅定位到原厂监听未就绪，尚不足以归因于 CarLife 8.8.5 更新、屏幕分辨率或某个原厂权限。
4. 23:29 起手机反复认定“网络已在位”而跳过入网；实际所有新 TCP 均报 `ENONET`。报告 `network.active=WIFI validated=true`、`network.bound=unknown`，系统网络列表仅有当前 Wi-Fi `112`，原句柄 `111` 已不存在。DiPlay 同期蓝牙鉴权成功、已发送启动请求，但等待首个 TCP 超时。本轮两端退出记录未提供对应崩溃证据。
5. 源码的 `AtomicReference.compareAndSet(network, null)` 按引用而非网络值匹配，可能漏掉来自另一 `Network` 实例的断开通知；读取当前绑定也从未检查系统是否仍认识该句柄。Android 明确说明绑定网络失效后，新套接字也会继续失败，必须清除绑定：[bindProcessToNetwork](https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network))。
6. 同局域网捷径通过同步 `getNetworkCapabilities()` 读取 SSID，但该 API 会脱敏位置相关字段。临时授予前台位置权限的对照没有恢复连接；正确方式还需要带 `FLAG_INCLUDE_LOCATION_INFO` 的网络回调。参考 [NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback#FLAG_INCLUDE_LOCATION_INFO)。对照结束已撤回精确/大致位置权限，并恢复原权限标志。

修复将请求所有权、绑定、失效清理集中到同一同步状态机；按网络值匹配断开事件，拒绝旧请求回调，绑定失败不报成功，系统已失效的网络不再复用。SSID 改由被动回调读取；用户手动连接时可明确确认接收端处于当前同局域网，从而不依赖位置权限核对 SSID，不自动把任意 Wi-Fi 当作目标网络。

新增 6 项网络生命周期回归，覆盖同值不同实例断开、漏通知失效、旧请求污染、绑定失败、其他网络断开及注销后晚回调。全量 455 项 JVM 测试、Spotless、standard debug lint/构建通过。修复版尚未正式签名覆盖安装；CarWith 首帧、接收端显示/音频/触控与重连仍待验证，不将离线通过记作连接恢复或实车验收。

## RECORD 后 7240 未监听的根因（2026-10-27）

复查 2026-10-26 23:28 的失败轮次（环境同上一节：`2509FPN0BC / OS4.0.4.0.XPBCNXM / SDK 37`，CarWith `4.0.20-20260923`，CarLife `8.8.5`，mcarplay `standard/release 0.1.1`）。手机 main 缓冲已轮转，以下来自 events 缓冲与 app-ops，原始记录不入仓。

1. 23:26:12、23:26:16 mcarplay `MainActivity` 在前台时请求入口：events 有 `wm_create_activity … CarlifeAdbActivity`，随后 `CarlinkService` 以 `PROC_STATE_TOP` 启动，入口有效。
2. 23:28:11 起应用打开系统 WLAN 设置，前台依次为设置 `SubSettings` 和网络请求对话框；23:28:46 对话框关闭后前台回到设置而非 mcarplay，直到 23:29:45。
3. 23:28:47 RECORD 后应用请求入口，但 23:28:40–23:29:45 之间没有任何 `CarlifeAdbActivity` 创建，也没有 `CarlinkService` 启动；`7240` 因此持续 `ECONNREFUSED`。CarWith 没有收到启动请求，不是原厂监听自身异常，与 CarLife 8.8.5 更新、分辨率无关。
4. `cmd appops get com.mcarplay.app 10021` 显示 MIUI「后台弹出界面」为 `ignore`，最近一次拒绝约在 23:49。该字段只保留最后一次拒绝，23:28 的拒绝已被覆盖；但足以证明系统正在静默拒绝本应用的后台界面启动，即使已授予 `SYSTEM_ALERT_WINDOW`。`startActivity()` 被拒时不抛异常，原日志因此仍写“已请求”。
5. 附带观察：空闲的 `CarlinkService` 在 `ACTIVITY_STARTER` 启动约 40 秒后自行 `STOP_SERVICE`（23:25:37、23:27:05），提前打开入口需考虑这个时限。

修复：请求入口时记录应用是否位于前台（`IMPORTANCE_FOREGROUND`）；若在后台发起且七口等待超时，日志与报告给出新诊断码 `CARWITH_ENTRY_BLOCKED`，提示允许「后台弹出界面」或保持前台后重连。原因仍作为推断呈现，不伪装成已确认的系统拒绝。459 项 JVM 测试、Spotless、standard debug lint 与构建通过；尚未在设备上授予该权限复测，修复版亦未安装。

后续修复（同日）：用户点「启动连接」时应用必在前台，此时即打开 CarWith 入口并完成 CarLife 握手（预热），帧在 RECORD 前丢弃；RECORD 后直接接入该会话，不再在后台打开入口。接入点由 `CarLifeJoinGate` 丢帧到下一个 IDR，IDR 未内联 SPS/PPS 时补上解析器缓存的参数集，并请求一次关键帧。每轮配对起步的退役只停已接入上一轮推流的源，预热源保留；用户断开时一并停止。上车自动连接（后台）及推流结束后的重连仍走 RECORD 后请求入口的旧路径，可能报 `CARWITH_ENTRY_BLOCKED`。463 项 JVM 测试、Spotless、standard debug lint 与构建通过；未安装到设备，预热期 CarWith 界面弹出是否干扰系统 WLAN 加入确认框、接入后首帧与音频仍需实机验证。

**预热撤回（同日 00:39–00:47 实测）：** debug 构建 `0.1.2-debug`（SHA-256 `ee60c689400e1859c3de6e2efaad80fda6be233a2634f3ccda3542586328d19e`）全新安装，「后台弹出界面」保持拒绝，DiPlay `0.2.21` 同局域网模式。00:41:27.975 点「启动连接」时前台打开入口成功，15ms 内七口连通，握手与视频（1920×1140、约 20fps）正常；CarWith/高德界面在虚拟屏 21/22，未遮挡主屏。但 CarWith 投屏期间（`wifi_p2p_carlife_connect`）系统 Wi‑Fi 扫描全部 `Scan aborted (reason -7)`，含 `cmd wifi start-scan`；系统入网选择框无网络可选，应用 5 秒后重发请求又撤销了该框（提示“该应用已取消选择设备的请求”），00:42:52 入网失败，未到 RECORD。00:46:14 停止 CarWith 后，00:46:16 起扫描立即恢复。更早 00:38:40 起的扫描失败也与当时的 CarWith 会话重叠；但 00:17–00:30 CarlinkService 运行期间扫描正常，具体触发条件（服务运行还是实际投屏）尚未区分。

结论：入网必须在 CarWith 投屏之前完成，不能在前台预热。已撤回预热，恢复 RECORD 后打开入口；保留 `CarLifeJoinGate` 与旧轮回调隔离。连接前若读取到 MIUI op 10021 为拒绝，首页提示并提供直达权限页的按钮，用户也可选择仍然连接。另记一个独立问题：扫描不可用时“首窗 5 秒重发”会撤销系统选择框，用户来不及选择，入网逻辑尚未修改。

**更正：扫描失败的根因是 mcarplay 自己的 Wi‑Fi 低延迟锁（同日 00:52–01:07）。** 上文“CarWith 投屏导致扫描失败”的结论有混杂：当时停止 CarWith 的同时也断开了 mcarplay。后续对照：

1. 授权提示正常出现，用户经直达入口把「后台弹出界面」设为允许；这一轮未预热，仍卡在入网。
2. 蓝牙连上 `wangpad` 后，CarWith 因 10-25 测试保存的 CarLife 记录自行 `startAutoConnect`（`wifi_p2p_carlife_connect`）；用户删除该记录及另一条 CarLife 记录后不再自启。但 00:52:59–00:54:37 CarWith 已停、mcarplay 仍在连接时扫描依旧全部失败。
3. 每 5 秒一次的 `cmd wifi start-scan` 对照：mcarplay 空闲时 60 次中 58 次成功；01:03:43 点「启动连接」后 01:03:44 起连续失败，早于蓝牙连入（01:04:11）和入网请求（01:04:12）。
4. `dumpsys wifi` 显示 `CarPlayService` 持有 `WIFI_MODE_FULL_LOW_LATENCY`（`CarPlaySender:wifi`，uid 10392 `isFg=true`）。mcarplay 在前台时扫描失败（01:06:40），按 Home 后 `isFg=false`，01:06:49、01:06:54、01:06:59 扫描立即成功。低延迟模式只在持锁应用前台时生效；系统入网选择框浮在 mcarplay 之上，本应用仍算前台。10-26 23:28 那轮应用在系统 WLAN 设置之后，因此能正常扫描、入网。

修复：低延迟锁移出保活服务的启动流程，改为视频发送端启动（入网之后）才持有，推流结束、每轮退役和服务销毁时释放。代价是配对/SETUP 期间不再持有该锁。预热的撤回原本基于错误归因；授权「后台弹出界面」后 RECORD 后打开入口的路径可用，暂不恢复预热。

**修复后复测（同日 01:10）：** 构建 SHA-256 前缀 `8d8445986ef1036e`，「后台弹出界面」已允许，CarWith 的 `wangpad` 等 CarLife 记录已删除，接收端 DiPlay `0.2.21` 同局域网。01:10:31 发出入网请求，选择框可正常选择网络；首窗 5 秒重发又弹出一次框，01:10:40.201 入网成功。01:10:40.771 RECORD 完成，01:10:40.784 `CarlifeAdbActivity` 创建；当时 mcarplay 在前台，故本轮未验证后台启动入口。7240 首次尝试未就绪，01:10:41.191 七路连通，01:10:41.797 首个关键帧发出（1920×1140，约 20fps）。01:10:50 起车机触摸事件到达，01:10:53 原生 MEDIA PCM 44.1kHz→48kHz 接入（nonzero=true、dropped=0）。用户确认连接成功。尚未覆盖：后台打开入口、推流期间低延迟锁对稳定性的长时影响、重连、真实车机。

**后台打开入口复测（同日 01:24–01:25）：** 构建 SHA-256 前缀 `279b953032143712`（另含“系统选择框在等用户时不按 5 秒首窗撤回”）。测前用 `cmd wifi network-requests-remove-user-approved-access-points com.mcarplay.app` 清除系统对本应用入网的记忆授权。用户点「启动连接」后于 01:25:00 回到桌面；01:25:01 系统选择框出现在桌面之上，01:25:05 用户选择，01:25:05.878 入网。01:25:06.592 RECORD 完成，应用日志标记“当前不在前台”；01:25:06.604 `CarlifeAdbActivity` 仍被创建，01:25:07.027 七路连通，01:25:07.342 首个关键帧发出。这证明允许「后台弹出界面」后，应用在后台时也能打开 CarWith 入口。另：上一轮（01:17）系统记住授权后不再弹选择框，3 秒入网。本轮用户在请求发出前已离开应用、且约 4 秒内完成选择，“等待选择框”逻辑未被触发，仍待首次入网且停留超过 5 秒的场景验证。467 项 JVM 测试、Spotless、standard debug lint 与构建通过。

**选择框保持复测（同日 01:31–01:32）：** 清除系统入网记忆授权后，用户停留在 mcarplay 内点「启动连接」。01:31:51.724 系统选择框出现；01:31:56.717 应用记录“系统选择框正等待用户选择——继续等待，不撤回重发”，未再撤回框。期间系统扫描正常（01:31:53、01:32:13 成功）。DiPlay 01:32:23.376 记录 `first TCP timeout`（自 01:31:51 起约 30 秒）后断开蓝牙重建；手机 01:32:21 收到链路断开，结束本轮并撤回请求，框于 01:32:24.966 关闭。下一轮 01:32:29 再弹框，01:32:37 用户选择后入网并 RECORD 成功。结论：5 秒首窗不再撤掉等待中的选择框；框的最长停留受接收端约 30 秒的首 TCP 超时限制（真车预算更短），超过即本轮作废。

## WinPlay 1.2.4 蓝牙交接触发自动断开（2026-10-27）

本轮来自用户提供的脱敏日志和开关对照，接收端为 Windows 上的 WinPlay `V1.2.4`。本轮未提供手机型号、HyperOS、CarWith、CarLife、Windows 版本及 APK 校验值，不能沿用上一轮 DiPlay 环境或作为完整兼容性验收。

1. 初次日志中 AirPlay pair-setup / pair-verify 成功，但会话 SETUP 返回 403；mcarplay 仍使用占位蓝牙 MAC，Shizuku 未运行。WinPlay V1.2.4 的 `run.ts` 按所选手机蓝牙地址过滤，`cpStack.ts` 在 SETUP 的 `deviceID` 不匹配时返回 403。按真实蓝牙身份路径复测后，Shizuku 显示就绪并进入视频发送。
2. 11:00:36.410 已发送第一个关键帧（18399B），11:00:38.705 原生源累计 41 帧、1920×1140；11:00:38.733 发送第 41 帧关键帧，随后约 0.23 秒关闭七路、iAP 隧道、事件通道、蓝牙组件和保活服务。此前 feedback 持续返回 200、timing 和视频 keepAlive 正常；粘贴片段没有先于收尾的网络写入失败证据。发送成功不等于接收端已完成解码。
3. 末尾的「已手动断开」来自 `SessionController.disconnect()` 的统一文案；用户确认没有点击取消/断开。关闭「设置 → 显示 → 上车自动连接」后，用户确认可以连接。该开关也控制 ACL 断开自动收尾，开关对照将问题定位到这条自动路径。
4. 源码调用链为 `CarBluetoothReceiver` 收到目标设备 `ACL_DISCONNECTED` → `CarPlayService.ACTION_AUTO_STOP_SESSION` → `SessionController.disconnect()`。原路径不检查仍在运行的 Wi-Fi 视频，并把诊断写成 `USER_STOP`。WinPlay V1.2.4 在网络 iAP 的 MFi 认证通过后关闭旧配件并结束原生蓝牙桥接：[`run.ts`（标签 V1.2.4）](https://github.com/Roylyl/WinPlay/blob/V1.2.4/src/engine/run.ts#L81-L93)。这一正常蓝牙交接可触发上述误停；尚无完整双端日志给出该次 ACL 事件的精确时刻。

修复：自动收尾命令执行时，若当前 Wi-Fi 视频发送端已启动则保留会话，首帧尚未到达也同样保护；媒体通道继续按既有结束流程清理。视频未启动时仍自动收尾，诊断使用 `BT_LINK_CLOSED`，最终提示区分自动与手动。新增实际 TCP 回环回归，模拟首帧前和首帧后的蓝牙释放，验证后续帧仍能传输；同时覆盖重复 ACL 命令、发送端停止后和没有发送端时的自动结束分支。修复包尚未在用户设备复测，音频、触控及长时稳定性未由本轮日志确认；临时规避为关闭「上车自动连接」后手动启动。

离线验证：新增 2 项服务生命周期回归，全量 475 项 JVM 测试、Spotless、standard debug lint 与 standard debug 构建通过（`--offline`）。本轮没有修改 Python 工具，未重复运行其测试。

## WinPlay 无声的两处原因与 WinPlay / DiPlay 出声复测（2026-10-27 18:26–19:24）

本轮日志来自 remote 调试桥（NAS 上的 `cardeck-bridge`，设备日志 `3c88630b.jsonl`），只在本机临时目录阅读，未纳入仓库。手机环境由新增的 `ENV:` 行自动上报（19:22:41，remote 包 SHA-256 `c6f099b01cdd84eefe2cec591a0f6a808edcdd014498fbd46648fe03746f8006`）：Xiaomi `2509FPN0BC`，Android 17（SDK 37），HyperOS `OS4.0`（ROM `OS4.0.4.0.XPBCNXM`），CarWith `4.0.20-20260923`（104000020），小米 CarLife `8.8.5`（274）。WinPlay 复测早于这一行约 27 分钟、使用上一版 remote 包（`daf5a9428ed20b968d794cce880987754a397d220ba7e563245514aeeeb42ec2`），同一台手机；期间未观察到 CarWith/CarLife 升级，但该时刻的版本并非由日志直接记录。WinPlay 由兼容规则识别为 `winplay.1.2.4`，Windows 版本未记录；DiPlay 运行在 macOS 主机上，其版本号未在日志中记录。

1. **第二次会话（18:36）被自动启动覆盖为「仅推视频」。** 18:36:01.909 用户选择开启声音；18:36:02.047 蓝牙 ACL 触发 `ACTION_AUTO_START_SESSION`，`CarPlayService` 以 `!CarLifeLauncher.isAvailable(app) && acquireProjectionSilently()` 重写 `audioEnabledForSession`，CarWith 可用时恒为 false，18:36:03.298 记录「本次连接未开启车机侧声音」。原生音频不需要投屏授权，已改为 `isAvailable || acquireProjectionSilently()`。
2. **第一次会话（18:26–18:35）协商成功但 0 包。** t102/media/48kHz AAC-LC SETUP 与补发 RECORD 成功，WinPlay 持续回报 `type=102 sampleRate=48000`，约 9 分钟内没有 `MEDIA 音频初始化`/`原生 PCM 接入`，结束时 `共发 0 包`。用户确认期间在播放。旧 `CarLifeAudioParser` 对无有效 INIT 的 PCM、非法 INIT 字段和未知 kind 一律静默返回，日志无法区分「CarWith 未发送」与「发送后被丢弃」，**该次根因未确定**。已在 `CarLifeSession.handleAudio()` 增加每通道每种 kind 的首帧记录、未解析帧计数（首次与每 2000 次）及 INIT 字段摘要。
3. **WinPlay 复测（18:55，修复包）：** 18:55:32.034 协商 t102/media/48kHz AAC-LC；18:55:40.097 首次收到 MEDIA `kind=0x30001`（16B），随即 `MEDIA 音频初始化 44100Hz 2ch 16bit`，40.106 首次收到 `kind=0x30006`（7056B），40.114 `原生 PCM 接入 #1 nonzero=true dropped=0`；18:56:02 累计 1032 包、552KB，无「未解析」行。用户确认画面与声音正常。
4. **DiPlay 复测（19:23，ENV 包）：** 19:23:13.688 协商同一档位；CarWith 返回 `VIDEO_INIT_DONE 1920x1080@30`（请求 1920×1260）；19:23:18.638 收到 MEDIA INIT 44.1kHz/2ch/16bit 与首个 DATA，19:23:43 累计 1180 包、631KB。用户确认画面与声音正常。

两次复测 INIT 均在七路握手后数秒内到达并先于 DATA，未出现静默丢帧。观察窗口均不足 1 分钟（以日志统计行计），没有覆盖暂停/恢复、导航提示、触控和重连；**不满足 `CHANGELOG.md` 中 WinPlay 1.2.4 发布门槛（10 分钟、触控、断开重连），门槛继续有效。**

离线验证：新增 3 项 JVM 测试，全量 543 项、Spotless、standard debug lint、remote debug lint 与构建通过。

## WinPlay 1.2.5 长时观察（2026-10-27 约 19:28–19:53）

接收端版本 WinPlay `1.2.5`（x64）由用户事后提供的「关于」页截图确认，页面同时显示「当前已是最新版本」、状态为未连接；截图晚于本轮测试，按同一安装记录。日志不记录 WinPlay 版本，兼容策略行中的 `winplay.1.2.4` 只是规则名。本轮连接建立早于日志观察窗口起点；事后补读完整日志，19:27:51 的 iAP2 `0x1d01` 自报 `WinPlay/Roylyl` 固件 `1.2.5`，与截图一致。同日 18:26–18:55 的 WinPlay 会话自报固件 `1.2.4`，前文 WinPlay 无声与复测两节对应的是 1.2.4。手机与组件版本同上一节 `ENV:` 行（remote 包 `c6f099b0…`）。一开始曾把本轮误判为 19:23 DiPlay 会话的延续；按音频包计数反推（19:30:21 累计 6820 包，约 47 包/秒）可知音频流约在 19:28 才启动，是一次新会话。

- **声音：** 19:30:21 至 19:53:22 每 30 秒稳定新增约 1407 包（≈48000/1024 包/秒），19:53:22 累计 71488 包、38257KB，观察结束时仍在推流；19:51 RTP 序号从 65535 回绕到 0，属正常 16 位回绕。全程没有「音频帧未解析」。
- **导航语音：** 19:30:19 首次收到 TTS `kind=0x40001`（16B）、`TTS 音频初始化 16000Hz 1ch 16bit` 与 `kind=0x40003` 数据；19:30–19:31 观察到多轮初始化/数据/停止（`0x40002`）。音乐与提示分走 9240/9241。
- **触控：** 19:28:50–19:47:33 记录多次 `EVENT: 触摸` 按下/抬起。
- **画面：** 用户确认正常；本窗口筛选未统计视频帧。

结论：WinPlay 1.2.5 上画面、声音、导航提示和触控均到达接收端，会话至少持续约 25 分钟未断开。**本轮没有执行断开后重连**；且 `CHANGELOG.md` 的发布门槛写明 WinPlay 1.2.4，1.2.5 结果能否替代由维护者决定，门槛暂不改动。**同日维护者决定将门槛改为 WinPlay 1.2.5**（截图显示其为当前最新版本）；本轮已观察到画面、声音和触控并持续约 25 分钟；仍缺断开后重连一次，「上车自动连接」与两个兜底开关的状态也未从日志确认。

## WinPlay 1.2.5 断开与重连（2026-10-27 20:23–20:26）

同一台手机、同一组件版本（20:18:59 `ENV:` 行与上一节一致）。接收端 WinPlay 1.2.5 运行在 Windows 主机 `DESKTOP-…`，与手机同处家庭 Wi-Fi（接收端地址为局域网私有地址），兼容策略识别为 `winplay.unverified-firmware`（`bluetooth=KEEP_DURING_BRINGUP`），即策略尚未把 1.2.5 列为已核对固件；视频运行时的蓝牙丢失保护使会话不受影响。之后核对 WinPlay `13ccb04` 源码与本轮日志，已把固件 `1.2.5` 登记为与 1.2.4 相同的网络交接画像（`winplay.1.2.5.network-handoff`），该新规则本身尚未在实机上复测。每次会话均由「上车自动启动」拉起，RECORD 后约 3–5 秒 WinPlay 释放蓝牙，日志为「兼容策略保留已接入的网络会话」。

| 开始 | 触发 | 结果 | 结束 |
|---|---|---|---|
| 20:23:07 | 蓝牙自动启动 | 出画面，蓝牙释放后会话保留 | 20:23:23 用户手动断开手机网络（`Software caused connection abort`、`sendto EPERM`） |
| 20:23:24 | 断网后约 1 秒自动重连 | 出画面，持续约 87 秒 | 20:24:53 用户点断开 |
| 20:25:12 | 手动断开后蓝牙自动重连 | 出画面 | 20:25:19 用户点断开 |
| 20:25:26 | 自动重连 | 出画面 | 20:25:32 用户手动断开 Wi-Fi（「绑定的 WiFi 网络已断开」） |
| 20:25:50 | 断网后自动重连 | 出画面；20:25:56 MEDIA INIT 44.1kHz/2ch/16bit 与 PCM，推出 278 包 | 20:26:03 用户点断开 |

两次网络中断由用户确认为手动操作，用于验证断网后的恢复。前四次会话 `共发 0 包` 与 CarWith 当时未播放一致（只有播放时才出现 MEDIA 帧）；声音在重连后的第五次会话得到确认。

门槛对照（`CHANGELOG.md` WinPlay 1.2.5 清单）：手机发起连接、画面/触控/声音到达、蓝牙释放后会话持续 10 分钟以上（上节约 25 分钟）、断开后重连、「上车自动连接」开启——均有日志证据；两个兼容兜底开关不在日志中，用户确认测试时均为关闭。**WinPlay 1.2.5 发布门槛满足。** 检测向导的实机交互仍未验证。

## DiPlay 实拍与关闭接收端（2026-10-27 22:08–22:12）

同一台手机、同一组件版本（22:07:06 `ENV:` 行），mcarplay 为 `v0.1.3-test.2` 同代码的 remote 构建。接收端 DiPlay 运行在 Android Pad 上，iAP2 自报固件 `0.1.0`，策略命中 `diplay.wire-0.1.0.bt-retire-retry`。

- 22:08:10 连上 DiPlay；CarWith 请求 1920×1260，`VIDEO_INIT_DONE` 返回 1920×1080@30。用户提供的 22:08 实拍照片显示 Pad 上 DiPlay 窗口完整呈现 CarWith 网易云音乐界面（左侧 Dock、状态栏与内容区正常，迷你播放器处于暂停）。照片只作本记录的观察依据，未纳入仓库。
- 22:12:02 开始播放后收到 `MEDIA 音频初始化 44100Hz 2ch 16bit` 与 PCM（`nonzero=true dropped=0`），4 秒内推出 196 包。
- 22:12:06 接收端 `Connection reset`，会话结束。用户确认此时在 Pad 多任务界面手动关闭了 DiPlay，属于预期结束，不是音频引起的拆链。

本节只补充 DiPlay 画面实拍证据；DiPlay 的触控、长时运行与重连仍未验证（19:23 那次 DiPlay 记录只有约 25 秒带声音的观察，19:28 起的长时会话是 WinPlay 1.2.5）。

## 小米 14 + 小米 SU7 Max 首次实车尝试（2026-10-28 19:44–20:03）

手机：Xiaomi `23127PN0CC`，Android 16（SDK 36），HyperOS `OS3.0`（ROM `OS3.0.307.0.WNCCNXM`），CarWith `4.0.4-20260522`（104000004），未安装 Shizuku；mcarplay 为 `v0.1.3-test.2` 同代码的 remote 构建（SHA-256 `5fe698e8…`）。车机：小米 SU7 Max（蓝牙名），车机软件版本、Tier-1 与 CarPlay 实现均未记录。证据为用户导出的两份脱敏诊断报告与调试服务器日志。

1. 19:46–19:52 共 8 次连接/自检均在预检 1–11ms 内以 `CARWITH_FAILED` 结束：手机未安装小米 CarLife 组件（`carlife.version=unavailable:NameNotFoundException`），`CarLifeLauncher.isAvailable()` 要求 CarWith 与 CarLife 两个包同时存在。
2. 安装小米 CarLife `8.8.5`（274）后，19:59:45 的 CarWith 自检约 3 秒打开入口、七路连接与 AES 握手成功，`VIDEO_INIT_DONE 1280x720@30`，18 秒收到 180 帧：CarWith 4.0.4 + CarLife 8.8.5 + HyperOS OS3.0 可以出流（仅源端，未到接收端）。
3. 20:00:29 与 20:01:49 两次以 SU7 为目标的 CarPlay 连接：ACL 多次连上/断开，连接前 HFP/A2DP 为「未连接」；出站 iAP2 RFCOMM（`…caff`）13 次失败（「车机未就绪或不在无线配对窗口」），车机也未回连，没有 iAP2 `0x1d01`、mDNS `_airplay`、`/info` 或 `COMPAT:` 记录。用户约 77 秒与 2 分钟后取消。
4. 同时存在两个与本次失败无关但会影响后续的条件：Shizuku 未安装，ctrl 广播使用占位蓝牙地址；「后台弹出界面」未允许。

结论：本轮不能判定 SU7 是否提供无线 CarPlay 接收端——指定目标时连接跳过 SDP 闸门，日志没有记录车机声明的服务。已在 wangxso/mcarplay#12 为指定目标增加一次 SDP 服务记录；下次实测应同时尝试在车机上进入 CarPlay/手机互联的添加设备界面，并填写本机真实蓝牙地址（`v0.1.3-test.3`）。不据此声明 SU7 不兼容。

## 「TTS/VOICE 提示优先」的修正（2026-10-30，代码核对，无新设备证据）

前文（原生音频接入一节）记录的「TTS/VOICE 已有提示优先选择」，实际实现是 `CarLifeAudioBridge` 在任何 TTS/VOICE PCM 到达后 500ms 内丢弃全部 MEDIA PCM：音乐被切断而非压低，丢掉的音乐样本也不会补回。该行为已改为手机侧混音与音乐 ducking，方案、参数与台架验证步骤见 `docs/AUDIO-DUCKING.md`。本节只是代码层面的更正；新行为尚无任何接收端或车机的听感证据。

## WinPlay 1.2.5：main 首次导航提示后音乐静音，PR #15 混音版本保持出声（2026-10-30 台架）

环境：小米 17 Pro Max（2509FPN0BC），Android 17 / SDK 37，HyperOS OS4.0.4.0.XPBCNXM，CarWith v4.0.20-20260923，小米 CarLife v8.8.5；接收端 WinPlay 1.2.5（Roylyl，Windows 主机），手机以仅应用网络加入接收端热点。音频档位 t102/media AAC-LC 48kHz，延迟 250ms。两个版本都是 `remote` 渠道包（`com.mcarplay.app.rd`），经 adb 安装。

- **main（09a231d），19:52 会话，用户反馈「先有声后没了」：** 音频首包 19:52:14。19:52:47 / 19:52:54 两次导航提示后，已发音频比墙钟落后 0.64s；19:53:26 再一次提示后落后 1.04s，此后保持（稳态速率 1407 包 / 30s，正好 48kHz）。WinPlay `/feedback` 的 `sampleTime` 从首包起按 48kHz 自走，不跟随我们每秒的同步包，19:54 起领先我们的发送时间线约 0.77s。原因：`CarLifeAudioBridge` 在每个 TTS 包后 500ms 内丢弃 MEDIA PCM，而 RTP 时间戳按样本计数，每次提示都在时间线上留下空洞；累计亏空超过 250ms 缓冲后，之后的每个包都迟到，**整个会话剩余时间无声**，手机日志却一直显示正常推流。
- **PR #15（d04885e），20:02 会话，用户确认「这个版本可以」：** 首包 20:02:31.55；20:02:40.5–20:02:57.8 的提示记录了 `提示音开始，音乐压低到 -15.0dB` / `提示音结束，音乐淡回原音量`。20:02:53 已发 1020 包 = 21.76s，墙钟 21.75s；20:03:23 已发 2427 包 = 51.78s，墙钟 51.76s：提示前后没有亏空。我们的时间线稳定领先 WinPlay 播放位置约 0.28s（≈250ms 延迟），方向正常。
- **同一会话暂停/恢复，用户确认「恢复后有声」：** MEDIA STOP 20:04:13.563，MEDIA INIT 20:04:21.896（约 8.3s 暂停），该 30s 窗口只发 984 包（少 9.0s）。恢复后我们的时间线比 WinPlay `sampleTime` 落后 8.73s 且保持不变，但声音正常，20:04:27 / 20:04:36 / 20:05:11 的提示也正常压低。说明 `sampleTime` 是自走计数而不是播放锚点，「接收端领先」本身不等于无声。修正后的假设（未验证）：短于 WinPlay 空闲重置阈值、又长于 250ms 缓冲的空洞，会让后续包逐个迟到被丢，接收端始终看不到空闲、不会重新锚定；数秒的暂停则让它重置并在下一个包重新锚定。main 每次提示后的 500ms 丢弃正落在这个区间。

结论：在这一台 WinPlay 1.2.5 上，PR #15 的混音修复了 main 「首次导航提示后整段静音」的问题；提示期间音乐压低 -15dB、结束后恢复，用户听感确认可用。仅此一台接收端和一台手机，DiPlay、MacPlay 与真实车机未验证。尚未解决（本节记录时）：短时上游停顿（CarWith 卡顿、Wi-Fi 抖动）仍可能在 RTP 时间线上留下空洞——随后由 PR #17 的发送端断流补静音处理，验收见下一节；WinPlay 的空闲重置阈值未知；两次运行都出现 AAC 自检「本机解码器没解出样本」但实际有声，属于误报。台架后用户提出把 -15dB 做成「车机设置」里可调，已在同一 PR 加入（未另行实机验证）。

## PR #17 外部音频断流补静音台架验收（2026-10-30 20:26–20:48，UTC+8）

本轮安装 PR #17 提交 `6ab4ec86a4df34d08c2a114a81e230a2a5ee7c02` 的 remote debug 包，
版本 `0.1.3-rd-debug`；APK SHA-256 为 `cf301622bbe077b83366abb09db01a64f24574a59966c0b2ab08e56c21123736`，
已通过无线 ADB 核对手机安装的 `base.apk` 与构建包哈希一致。构建前完整 gate 与 remote debug 构建通过，
578 项 JVM 测试、0 失败（本次复用 Gradle 未变化的测试结果）。

手机：Xiaomi `2509FPN0BC`，Android 17（SDK 37），HyperOS `OS4.0` / `OS4.0.4.0.XPBCNXM`，
CarWith `4.0.20-20260923`（104000020），小米 CarLife `8.8.5`（274）。接收端为 WinPlay，
策略命中 `winplay.1.2.5.network-handoff`；Windows 版本未记录。三次会话均选择 t102/media AAC-LC 48kHz、
250ms 延迟；CarWith MEDIA 输入为 44.1kHz 双声道 16bit，由应用重采样。证据为既有远程调试桥的只读脱敏日志与用户反馈；
日志仅保存在本机私有临时目录，未纳入仓库。无线 ADB 在验收结束时已不可连接，未更改手机调试设置。

操作为连接 WinPlay、开启声音并播放音乐，期间触发导航提示、暂停恢复、换曲，再手动断开重连。
用户给出的窗口为 20:26–20:48，并确认上述操作后「均正常，未发现静音或延迟增长」。
具体暂停/换曲次数与每次恢复耗时未单独记录，不按建议清单自动计为完成十次。

| 会话 | 音频发送源启动 | 首次 MEDIA INIT | 手动停止 | 发包总数 |
| --- | --- | --- | --- | --- |
| 1 | 20:26:49.178 | 20:27:09.505 | 20:41:08.609 | 39332 |
| 2 | 20:41:19.863 | 20:41:24.888 | 20:42:54.446 | 4197 |
| 3 | 20:43:08.018 | 20:44:06.802 | 20:48:47.785 | 13170 |

- **实际触发断流补静音：** 首轮共记录 12 次 TTS 初始化。前五次断流恢复摘要分别记录补 20、18、18、20、22 帧，
  对应约 384–469ms；20:31:50–20:33:19 的 MEDIA STOP/INIT 窗口中，累计补静音由 218 增至 2826 帧。
  20:35:19 起累计为 2846 帧（约 60.715 秒），后续不再增加；全部推流统计的跳过帧数均为 0。
  断流摘要按代码限频，不能由五条摘要推断只有五次断流。
- **发送端时间轴：** 首轮 20:27:19.197 为 454 包、ts=464896，20:40:49.586 为 38441 包、ts=39363584。
  两点墙钟差 810.389 秒，RTP 样本差 / 48000 为 810.389333 秒，端点差约 0.33ms；
  所有推流统计均满足 ts=包数×1024、seq=包数模65536。这是发送端统计的一致性，不能代替接收端逐包到达或播放时刻测量。
- **首帧前不补静音：** 第三轮 20:43:08 启动发送源，20:44:06.806 仍为 0 包、补静音 0 帧；
  MEDIA 到达后开始发包，20:44:36.822 为 1407 包。
- **重连与其他媒体：** 日志记录两次手动断开后的重新启动、MEDIA INIT 与持续发包；最终 20:48:47 为手动停止。
  各轮均有 CarWith 视频发送，首轮有 34 条触摸事件记录；用户此次明确反馈的是声音恢复与延迟，未另作图像质量或触控准确度测量。

结论：**PR #17 在 WinPlay 1.2.5 台架上的音频断流恢复验收通过，用户已认可本次验收。**
听感反馈与发送端日志共同支持断流后可恢复声音、时间轴没有在已观测断流中累计落后。
总窗口约 22 分钟分为三次会话，首轮音频约 14 分钟；建议清单中的单次连续 15 分钟及第三次重连尚未完成，
属于补充检查，不作为本次音频修复的验收阻塞项，也不将未做的项目记为通过。
本轮没有修改前版本的同期对照或 WinPlay 解码/播放日志，不确认「接收端丢弃迟到包」的具体根因，
也不新增其他接收端或实车兼容性结论。


## 奇瑞艾瑞泽 8（2022 款）：无线识别与首次音画触控（2026-10-31）

手机：小米 17 Pro Max（`2509FPN0BC`），Android 17 / SDK 37，HyperOS
`OS4.0.4.0.XPBCNXM`，CarWith `4.0.20-20260923`（104000020），小米 CarLife
`8.8.5`（274）。应用为已发布的 standard/release `v0.1.3-test.4`（`783fd63`），
APK SHA-256 `689daa7899c76ee040fc8313a339aa687e6d6550bd4df42f3c17ad56b1302bf9`。
车机由用户确认为 2022 款奇瑞艾瑞泽 8、有无线 CarPlay；ADB 显示 Renesas `G6SH-r8a7795`、
Android 9 / SDK 28，固件 `M1E_IC2172/82_03_21.387_250619_R`（2025-06-19）。
车机 iAP2 自报 `Cloudrive / Tiggo / CHERY`、硬件 `HWS002`；`Tiggo` 是协议字段，
不据此改写用户提供的车型。原始报告、双端日志、APK 和截图仅保存在本机私有临时目录。

### 识别阶段

- 11:36–12:19 的多次连接只到蓝牙阶段；指定目标的 SDP 返回七个服务，其中无
  `00000000-deca-fade-deca-deafdecacaff`，但有 `ec884348-cd41-40a2-9727-575d50bf1fd3`。
  旧日志的“无线 CarPlay iAP2 服务=无”只检查前者，不能判定车机不支持无线 CarPlay。
  12:18 先启动应用再配对也未成功，不能把启动顺序当作已验证修复。
- 使用手机 root + `app_process` 临时注册 120 秒的 CarPlay 手机能力 UUID
  `2d8d2466-e14d-451c-88bc-7301abea291a`，保持应用既有的 `…cafe` iAP2 监听。
  12:27:58 车机日志仍判定该手机 `isSupportCarplay():false`，12:28:48 配对后变为 `true`；
  用户确认出现“支持无线 CarPlay 连接”，当次尚未连上。这项临时 root 操作是诊断手段，
  不计为普通应用自主发布能力的验收。
- 车机 `WirelessCarplayManager.connectBT` 的静态 DEX 中另有
  `00000000-cade-defa-deca-deafdecacafe`。第二个临时探针同时公告该入口与能力 UUID，
  未取得这个入口的首包，不能断言它是成功路径；不将其未经验证地接入 iAP2。

### 成功会话及结束

- **12:36:32.127** 应用既有 `…cafe` 监听收到车机主动回连，iAP2 链路同步和识别成功；
  车机声明可发送 `0xaa06 / 0x5703`，可接收 `0x4e09 / 0x4e0b / 0x4e0c / 0x4e0d / 0x4e0e / 0x5702`。
- **12:36:34.345** 收到 `0x5703` 热点凭据；车机未声明 `0x4301`，应用经 mDNS 发现 AirPlay，
  不额外等待该消息。**12:36:42.371** RECORD 成功，**12:36:43.069** CarWith 返回
  `1920×720@30`，**12:36:43.113** 发出首个视频关键帧；截图显示导航和音乐卡片。
  用户明确确认“已连上，画面声音触控正常”，并确认手机与车机之间没有 USB 数据线。
- 音频采用 `t102/media AAC-LC 44100Hz / 250ms`，结束累计发送 7171 包 / 4154KB。
  首次音频同步 UDP 在启用一秒后返回 `ECONNREFUSED`；虽然本次有声，仍是待查现象。
  event 隧道的控制协商也曾五轮无应答；不能仅凭媒体成功认定所有控制流程正确。
- **12:39:52** 网络/蓝牙/视频链路结束，用户确认当时手动断开或切换网络。
  本次激活到结束约 190 秒，不计为自发断连，也不计为十分钟稳定性验收。
- **12:40:07** 车机原厂 CarPlay 进程在 `libAirPlay_R14_vehicle.so`
  `_PerformPeriodTasks+640` 空指针崩溃；随后有第二次 iAP2/RECORD，12:40:30 用户手动停止，
  **12:40:50** 再次出现同位置崩溃。首次断开早于崩溃约 15 秒；现有证据只能确认断开后的
  清理异常，未证明具体触发字段或根因。原厂库 SHA-256 为
  `9e24ea4577c7eac52d0799bd88647a1a8bea561a179e54bafa7afe7c45b5438d`。

结论：**这一组手机、固件和应用有一次真实无线 CarPlay 音画触控成功证据。**
不能据此声明 2022 艾瑞泽 8 全系支持、无需辅助诊断即可冷启动，或长时稳定性通过。
应用修复现将能力公告与既有 iAP2 监听一同注册/撤销，注册失败回收已打开记录；能力入口不承载协议流。
普通 APK 上该公告的识别、冷启动、断开后重连和十分钟运行仍待复测；断开后的原厂 AirPlay 崩溃另行跟进。


本轮按用户要求将修复候选覆盖安装到 `remoteDebug`（`com.mcarplay.app.rd`，`0.1.3-rd-debug`），
APK SHA-256 `d4772ea9d2457f169ba39802538feff4e19075b59453d8b21ab8c6424ec45847`，
已核对手机 `base.apk` 哈希一致。迁移此次选定车机与本机蓝牙身份，停止 standard 进程以避免双实例竞争；
临时诊断公告已到期，移除手机上的探针 JAR。后续仅读取 remote 日志。
601 项 JVM 测试、Spotless、standard/remote debug lint 和构建通过；安装本身不计为此候选的实车重连验收。


### remote 修复候选复测：车机崩溃残留与手动触发（2026-10-31 12:48–12:58）

仍为上一节哈希 `d4772ea9…` 的 remoteDebug APK，未重新构建。12:48:03、12:49:05
均记录“监听 iAP2 并发布无线 CarPlay 手机能力”；用户反馈“连接不上，无反应”，车机设置仍显示手机支持
无线 CarPlay。12:49 检查车机：`pidof` 无 CarPlay 进程，服务记录 `app=null`，在两次原厂崩溃后尚未恢复，
不是由本次报告推导为手机公告失败。

- **12:50:05** 通过 ADB 显式重新启动车机 CarPlay 服务/界面，进程恢复。**12:50:06**
  remote 收到主动回连，链路同步、`0x1d01`、`0xaa01` 正常；随后 32/20/16B 挑战均无应答，
  也未取得 `0x5703`，本轮失败。没有通过伪造认证应答把它计为成功。
- 原厂日志停在 `Accessory_AuthenticationResponseMessage sem_wait`；此前初始化记录
  `iAP2ReadCertificateDataInit old key = 45`，没有看到对应的锁释放。
  对原厂 `libiAP_vehicle.so`（SHA-256 `0510472c3bdfbaa916fd33d40aeec6725cd3a0ccbf6ca15b1dbacc97c016305a`）
  的静态分析：`iAP2ReadCertificateDataInit`（`0x77088`）通过 `SVPShmem::Get/At` 复用共享内存，
  新建分支才初始化信号量并启动证书读取线程；`Accessory_AuthenticationResponseMessage`（`0x777c8`）
  会在共享内存中的信号量上等待。结合进程此前崩溃，推断存在遗留共享锁；没有直接修改共享内存、
  认证芯片或信号量，也不将“之前的进程持锁崩溃”当作已动态证明的锁持有者身份。
- 用户确认已停车并明确授权 ADB 重启中控。重启后 CarPlay 进程恢复，remote 再次建立蓝牙链路，
  **12:55:26.352** 取得 `0x5703`。手机首次为 remote 请求该车机网络时出现系统 Wi-Fi 选择确认。
  当前保留的手机快照没有覆盖完整的认证应答帧，不能据此补计 `0xaa03` 的次数或长度。
- 用户随后确认“手动点击蓝牙可以”。**12:58** 车机前台是原厂 CarPlay 界面，截图显示实际 CarWith
  导航、音乐卡片和随时间变化的页面。临时探针已移除，本轮能力公告来自修复 APK；用户在车机端手动点蓝牙触发。
  本轮没有再次逐项询问声音和触控，也没有复测断开后的重连。

结论：修复 APK 有一次“重启车机以恢复原厂模块 → remote 启动连接 → 车机手动点手机”的无线出画证据。
**手机单侧启动即可自动触发车机尚未验证，断开后的原厂 AirPlay 崩溃也未修复。**
临时重启恢复不等于崩溃根因已解决；继续保留此限制，不把这次观察扩展为完整冷启动/重连验收。


### remote 修复候选：约两小时使用及锁屏后重连（2026-10-31，用户验收反馈；后续日志补证见下节）

沿用本轮小米 `2509FPN0BC`、CarWith/CarLife、2022 款奇瑞艾瑞泽 8 与上述固件环境，
本轮已安装的 remoteDebug 修复候选 APK 为 `d4772ea9…`（代码 `6417263`）。
12:58 已有车机显示实际 CarWith 页面证据；用户原计划使用一小时，之后反馈实际“完成了大概两个小时无异常”，
唯一退出是“由于我手机手动锁屏导致了退出”。14:50（UTC+8）收到本轮结束反馈；
这不是精确的会话结束时刻，约两小时是用户估计的使用窗口，含一次锁屏退出，不计为单次不间断两小时。

针对该次退出，用户进一步明确确认：**直接重新连接成功，无需重启车机。**
本轮没有提供锁屏和重连的精确时间、恢复耗时及操作次数，也未逐项统计导航提示、暂停/换曲或触控次数。
不把先前建议的专项清单自动全部记为完成。

本轮结束后手机及车机 ADB 均不在线，仅有另一台 Pad；mDNS 也未发现该手机，
当时约两小时窗口的 remote 日志尚未取回，结束时未再次核对安装包哈希；随后已通过 remote 只读 API 补取日志，见下节。
以下结论以此前安装/截图证据及此次用户反馈为依据，不补计帧数、音频包数、精确连续时长或日志级断开根因。

- **本配置的用户侧长时使用验收通过：** 约两小时使用窗口内，用户未反馈其他异常或自发断连。
- **锁屏退出后的直接重连通过：** 用户确认直接重新连接成功，不需要重启中控；仅覆盖本次退出场景。
- **锁屏兼容性仍有问题：** 主动锁屏是明确触发的退出，单独记为待修问题，不归为无操作时的自发断连，
  也不因用户主动操作就将其视为功能正常。具体是 CarWith 生命周期、网络释放或系统后台限制，仍待日志定位。
- 手机单侧自动触发、完整冷启动、异常断网后的恢复与此前原厂 AirPlay 崩溃，均不由本次直接重连结果推定已解决。

本次补齐这一台手机与这一版车机固件的长时实际使用证据；不推广为所有年份/固件的车型兼容承诺。


### 长时窗口 remote 日志核对（2026-10-31，UTC+8）

用户提供 remote 服务地址及只读凭证后，通过受鉴权的只读日志 API 取得同一手机
`2509FPN0BC` / `0.1.3-rd-debug` 的 7 页、12,069 条脱敏记录，时间范围
12:47:46.491–14:52:33.877。分页游标已耗尽；序号 1–12072 中缺 12028、12033、12034，
均位于两轮媒体结束后的重试阶段；本次两轮媒体范围内没有序号缺口。该检查证明取得服务器已有记录，
不等于逐包网络抓取或所有内部事件都有日志。服务地址、凭证、设备标识和原始响应未纳入仓库。
本机脱敏记录副本 SHA-256：`7d6e75fe391850c384b24a1196a9406a1f730d0092e314316be8313bcbdf62a2`。

| 会话 | RECORD 激活 | 首个 CarWith 视频帧发送 | 媒体退出 | 激活至退出 |
| --- | --- | --- | --- | --- |
| 1 | 12:57:25.464 | 12:57:26.234 | 13:05:24.749 | 7 分 59.285 秒 |
| 2 | 13:05:49.760 | 13:05:50.513 | 14:44:49.647 | 98 分 59.887 秒 |

两轮激活时长合计 **106 分 59.172 秒**，整体出画观察窗口约 107 分 24 秒，
与用户“大概两个小时”的使用估计对应；不将其改写为精确两小时或单次不间断两小时。
用户已进一步确认 **13:05:24 对应手动锁屏退出，14:44:49 对应结束测试或离车**。
前一次退出到再次激活约 25.011 秒、到首帧发送约 25.764 秒。重连中存在旧轮退役和一次未完成的中间尝试，
以实际首帧作为恢复证据；未发生新的手机进程启动，用户确认无需重启车机。
补取日志也补齐了重启后的认证证据：12:55:25.658 首次发出 32B 挑战，12:55:26.180
收到 `0xaa03`（68 字节签名），随后 12:55:26.352 收到 `0x5703`；这是早先有限快照未覆盖的内容。


- **画面：** 两轮 `VIDEO_INIT_DONE` 均为 1920×720@30；实际持续推流统计通常约 19–20fps。
  长会话最后一条视频统计在 14:44:31.924，为 117601 帧、2941 个关键帧、899296218 字节，
  对应 5921 秒源端观察。14:44:49.647 才记录媒体退出，末尾约 18 秒没有新的帧统计；
  不据会话时长保证每一秒均有出帧。用户反馈使用时未发现黑屏、卡顿或其他异常。
- **音频：** 两轮为 t102/media AAC-LC 44.1kHz、250ms；首轮结束共发 18242 包 / 10569KB，
  长会话结束共发 254942 包 / 147707KB。长会话有 292 条 TTS 初始化记录，首轮有 13 条，
  不等同于 305 次独立导航提示。14:03:10.841 MEDIA STOP、14:03:13.573 MEDIA INIT，
  停/启间隔约 2.732 秒，之后持续发送。该序列支持音乐停/启后恢复，但不凭日志推定具体按了哪个按钮。
- **时间轴与断流补静音：** 长会话 197 条音频推流统计均满足 `ts=包数×1024`、`seq=包数模65536`。
  从 13:06:19.945（1112 包，ts=1138688）到 14:44:22.280（254443 包，ts=260549632），
  墙钟 5882.335 秒，RTP 样本时长 5882.334331 秒，两端差约 -0.67ms，未见发送端时间轴累计落后。
  稳态统计累计补静音 456 帧（约 10.588 秒），跳过帧均为 0。两次退出后分别记录跳过 165、678 帧；
  这发生在锁屏/结束测试的清理阶段，不把它们隐藏成全程零跳帧，也不解释为运行期间的听感异常。
  这些是发送端一致性证据，不能替代实际扬声器延迟测量；用户“无异常”的听感反馈单独保留。
- **触控与控制：** 首轮有 33 条触摸事件记录，长会话无新的触摸事件记录，不能据此计为两小时持续触控测试。
  长会话 14:03:14.088 蓝牙控制链路结束，但 14:03:14.115 明确记录“蓝牙已断开，兼容策略保留已接入的网络会话”，
  当时已发送 68424 帧；其后持续出帧、音频和 feedback，未形成新的媒体会话。
  因此约 99 分钟是媒体会话时长，不代表蓝牙控制链路全程保持。

  两轮音频同步 UDP 各在启动后约一秒报 `ECONNREFUSED`；event 控制协商各五轮无应答后进入 DEAD，
  但媒体仍持续；之前已记录的这两项协议问题没有因长时出画而消失。
- **结束与清理：** 14:44:49 发生 event/video socket 终止、音频停止；随后车机热点释放，
  14:52:33 用户取消仍在重试的连接。结束清理有一条 `AAC 编码异常 - codec is released already`，
  不是稳态运行期间的音频失败。该 remote 日志不含同期车机进程崩溃记录，不能证明原厂崩溃已修复。

结论：**这组手机/固件的手动连接、长时媒体使用和锁屏后直接重连验收，有日志与用户反馈共同支持。**
最长媒体会话约 99 分钟，满足本轮长时使用检查；锁屏仍会引起退出，须单独修复。
自动触发、完整冷启动和此前原厂 AirPlay 异常断开崩溃仍不属于已解决范围。


### 用户补充的实车实拍与环境索引（2026-10-31）

用户提供两张 3072×4096 原图，均显示同一辆实体车的中控实际呈现 CarWith 页面，
作为既有日志、ADB 截图及用户反馈之外的实拍证据。原图保存在本地被 Git 忽略的
`reference/vehicle-evidence/2026-10-31-chery/`，不随源码提交。

| 本地原图 | 可见证据 | SHA-256 |
| --- | --- | --- |
| `chery-display-1237.jpg` | 车内实体中控、屏幕时钟 12:37、地图和音乐卡片；对应首次 standard 发布版配合临时识别公告的成功窗口 | `66deba3d1da20557d59ba4b79034ef1df0b24b9a5b276d430862f4d6511c6798` |
| `chery-display-1305.jpg` | 车内实体中控、屏幕时钟 13:05、车道级导航、音乐、天气和转向提示；对应 remote 修复候选测试窗口 | `131e0ed3c79b44289b7a5ca90b1d557ee6d08df3fefa8705bfc13b743dc5abc5` |

第二张可见时间只有分钟，不能仅凭图片判断它在 13:05:24 锁屏之前还是 13:05:50 恢复之后。
两张图片不单独证明声音、触控、精确时长或断开原因；这些结论分别由用户反馈和前节日志支持。

README 使用对应用户实拍的 **AI 辅助脱敏裁图**。处理时移除了车窗、道路、内饰、手机支架和
原始 EXIF/GPS，并模糊地图、目的地、街道和天气地点文字；原始照片仍只保存在被 Git 忽略的
本地目录。这两张图是派生证据，不是未处理截图；AI 处理可能改变局部像素，因此像素级、文字级
和时间结论仍以原始照片与日志为准。

| README 脱敏图 | 来源 | SHA-256 |
| --- | --- | --- |
| `docs/screenshots/chery-arrizo8-2026-10-31-1237-redacted.png` | `chery-display-1237.jpg` 的中控屏裁图与地点脱敏 | `a7a18377e21a52d595c26305225414dbb0fc45903d794b979b6a0f9ef22f2241` |
| `docs/screenshots/chery-arrizo8-2026-10-31-1305-redacted.png` | `chery-display-1305.jpg` 的中控屏裁图与地点脱敏 | `389cb44278c062729623343aa1190f330403bcbf9fbb1a2ef8ab18a1133ed9c0` |

本轮收集到的环境汇总如下，型号/版本来自此前 ADB、支持报告、iAP2 自报及用户车型确认：

| 项目 | 已记录值 |
| --- | --- |
| 车型 | 奇瑞艾瑞泽 8，2022 款；用户确认原厂无线 CarPlay |
| 车机设备 | Renesas `G6SH-r8a7795`，product `g6sh_m1e` |
| 车机系统 | Android 9 / SDK 28 |
| 车机固件 | `M1E_IC2172/82_03_21.387_250619_R`，构建号 387，构建日期 2025-06-19 |
| iAP2 自报 | `Cloudrive / Tiggo / CHERY`，硬件 `HWS002`；协议型号 `Tiggo` 不替代用户确认的车型 |
| 实测投屏 | 1920×720，协商 30fps，实际持续推流统计通常约 19–20fps |
| 手机 | 小米 17 Pro Max，`2509FPN0BC`，Android 17 / SDK 37 |
| HyperOS | `OS4.0.4.0.XPBCNXM` |
| CarWith / 小米 CarLife | `4.0.20-20260923`（104000020）/ `8.8.5`（274） |
| 长时测试应用 | remoteDebug `0.1.3-rd-debug`，代码 `6417263`；已安装 APK SHA-256 `d4772ea9d2457f169ba39802538feff4e19075b59453d8b21ab8c6424ec45847` |
| 连接步骤 | 手机启动连接，车机蓝牙列表手动点该手机；手机到车机为无线连接 |
| 结果范围 | 实车音画触控已有证据；两轮媒体累计约 107 分钟、最长约 99 分钟；锁屏退出后约 26 秒直接恢复出画 |

本索引只覆盖这一台车机/固件和这一组手机组件，不补推未采集的车机 MCU、蓝牙芯片或其他车型版本。

### 方向盘媒体键与锁屏冻结定位（2026-10-31 16:43–16:45，UTC+8，本机 ADB 日志）

同一辆奇瑞艾瑞泽 8（用户确认；iAP2 与 `/info` 仍自报 `CHERY / Tiggo`，`sourceVersion=320.17.7`）、
同一台小米 `2509FPN0BC`、HyperOS `OS4.0.4.0.XPBCNXM`、CarWith `4.0.20-20260923`、小米 CarLife `8.8.5`。
测试包为 remoteDebug `0.1.3-rd-debug`，基于 `aeccca7` 加分支 `feat/steering-media-keys` 未提交改动构建，
APK SHA-256 `7bd1f9bee36e3b276fdf8565b778f0a21cd5957e7b5763fb716ed428ee047107`；与正式版 `com.mcarplay.app` 并存安装，
省电策略为安装默认值，两个包都不在 deviceidle 白名单。证据为手机 `main_debug.log` 与同期 logcat，经 ADB 取回，原文不入库。

- **方向盘键位：** 接收端 `/info` hidDevices[0] `Buttons` 描述符为位图（`81 02`）：
  `05 0c 09 01 a1 01 05 0c 09 b6 09 b5 09 b0 09 b1 15 00 25 01 95 04 75 01 81 02 95 01 75 04 81 01 c0`，
  即 bit0=上一首、bit1=下一首、bit2=播放、bit3=暂停。main 旧实现只读首字节且非 Array 时把值直接当 Usage ID，
  这台车的「下一首」`0x02` 会解成无键。本包 16:43:54–16:44:36 记录 8 次按键（下一首 6、上一首 2），每次按下
  只触发一次，分别作为 CarWith 硬按键 16/15 发出；首次「下一首」后约 1 秒 CarWith 媒体音频开始推流。
  曲目是否实际切换、播放/暂停键未在日志出现，待用户反馈，不计为完成验收。
- **锁屏退出时序：** 16:43:47 RECORD、16:43:48 首帧。16:44:39.833 息屏且锁屏（安全锁，电池优化未豁免）。
  其后 CarWith 视频与音频照常发送至 16:44:49；iAP2 虚拟链路 16:44:42 因包未确认判 DEAD，媒体不受影响。
  **16:44:49.17–16:44:54.05 本进程在应用日志与 logcat 中均无任何输出**，同期 Wi‑Fi 发送字节由每秒约 370KB 降到几百字节。
  16:44:54.05 屏幕重新点亮同一毫秒，视频/事件通道 `Software caused connection abort`、keepAlive `sendto EPERM`、
  蓝牙 RFCOMM 与调试桥同时断开，会话结束（约 67 秒）。
- **结论：** 本次锁屏退出不是 CarWith 停流，而与 HyperOS 在息屏约 9 秒后冻结 mcarplay 并切断其网络的特征一致；
  前台服务与 CPU 唤醒锁未能阻止。logcat 未见直接写明冻结的条目，因此记为高度吻合的推断，
  待「省电策略：无限制」后锁屏复测证实或推翻。

### 大众帕萨特 2025 款：有 ctrl 邀请却因无热点凭据拆链（2026-10-31 22:06–23:02，UTC+8，用户提供的脱敏诊断报告）

手机 Xiaomi/Redmi `M098FE`，Android 17（SDK 37，`CP2A.260605.016`），HyperOS 版本未在报告中；CarWith
`4.0.23-20261016`（104000023）、小米 CarLife `8.8.5`（274）；mcarplay `0.1.3` standard/release，APK SHA-256
`a40bcb5deb57ddacee9482f3ec3235fc19715f360aba3f2f6bf200e72d894ffe`。接收端 iAP2 `0x1D01` 自报厂商为大众（画像键
`volkswagen_volkswagen_ag`），型号字段为空；用户确认车型为大众帕萨特 2025 款，车机固件版本未知。报告 ID `db17225a…`、`a3c956f0…`，原文不入库。

- **蓝牙阶段正常：** 指定目标后 RFCOMM 连入 `…caff`，识别与 MFi 认证走完（`0xaa01`/`0xaa03` → `0xaa05`）。
  车机发送清单 `0xaa06 0x5703`、可收清单 `0x4e0c 0x4e0d 0x4e0e 0x5702`，不声明 `0x6800`/`0x4301`。
  SDP 只缓存了 A2DP 接收/AVRCP/HFP/0x1133/0x180F 与一个厂商 UUID，**没有 `…caff`**，RFCOMM 却能连上。
- **卡点：** `0x5702` 发出并重发、问候重发，车机都只回链路 ACK，约 20 秒内没有 `0x5703`；回退 `0x4300` 后也没有
  `0x4301`，会话以「未拿到车机热点凭据」拆链，三轮相同。同一时段车机经 WiFi 向手机 ctrl 端口发了 5 次
  `GET /ctrl-int/1/connect`（22:10:29、22:12:20、22:12:44、22:13:05、22:13:35，同一 receiver ID），说明手机已在车机网络上、
  AirPlay 窗口已开；旧实现只给邀请计数，Phase 1 仍按「无线索」拆链，邀请无人接。报告期间进程被上滑清理 5 次，
  进程内缓存的热点 SSID 每次随之丢失。
- **第二份报告：** 目标车机未指定，`BLUETOOTH_SCAN`/`RECORD_AUDIO` 被重置为未授权；自动模式的 SDP 闸门以「未发现
  iAP2 服务」跳过了全部 6 个候选。结合上一条，大众车机在自动模式下会被同样跳过。该时段无车机蓝牙连接与 ctrl 邀请记录，
  不能确认车机当时在场。
- **代码修正（未上车验证）：** 无 `0x5703`/`0x4301` 但已收到 ctrl 邀请时不再拆链，返回空端点；主控按手机当前 WiFi 绑定后
  接邀请（SSID 不可读时用占位标签，不写入热点缓存）。自动模式下，设备类别为车载音频、曾成功建立 `…caff` RFCOMM，或 SDP 同时有
  HFP 免提、A2DP 接收与 MAP 通知（0x1133，即帕萨特的组合）的设备不再被 SDP 结论否决，跳过日志附设备类别。是否能出画需同一车机复测，复测前不计为大众支持证据。
- **测试包复测（23:50，报告 `4100bdbd…`，remote/debug APK `5c5840f1…`）：** 指定目标后 RFCOMM 与认证照旧完成，
  `0x5703` 照旧不到。用户在系统 WLAN 手动加入车机热点后，ctrl 的 mDNS 于 23:50:33 注册成功，**80 ms 后**车机即发第 1 次
  邀请，23:50:55 第 2 次。但 Phase 1 仍在等 `0x5703`，要到约 90 秒的会话期限才进兜底；用户在 55 秒时导出，兜底尚未执行。
  据此再改：等 `0x5703` 期间每 500 ms 检查邀请，一到即转入兜底（模拟帕萨特的回环测试 3.3 秒返回空端点）。
- **出画失败（23:51–23:59，报告 `ca18677a…`，同一测试包）：** 首次 AirPlay pair-setup 完成后，车机此后每轮都**立即回
  `0x5703`**，应用专属网络自动入网成功——热点凭据只在首次配对时缺失。`/info`：Toppower `VW4211`、`sourceVersion=320.17.3`、
  屏 1280×720、`maxFPS=40`、3 个 HID。两轮会话（23:52:16、23:59:00）屏幕流协商与 RECORD 成功、CarWith 帧持续发送，但：
  1. CarWith 不采纳请求的 1280×720，返回 1920×1140 并被原样透传；车机首帧后约每 300 ms 发一次 `forceKeyFrame`（每轮数百次），
     画面始终未解出。转给 CarWith 的重置编码器请求无效（关键帧仍约每 120 帧一个），旧日志「已强制编码 I 帧」不实。
     同日 20:28 CarWith 对同样请求返回过 1280×720，22:54 起固定 1920×1140，原因未定。
  2. 用户每点一次原生菜单的 CarPlay 图标，车机发一条无参数的 `requestUI`；事件层照常回 RTSP 200，但旧实现不做业务处理、不回 `modesChanged`（录屏中图标为绿、点击无反应）。
  3. 7 次连入中 5 次在初始 SETUP 后被车机关闭控制通道：均为未等邀请、凭 `_airplay._tcp` 直接连入，邀请随后 0.3–0.6 s 才到；
     成功的 2 次距上一次邀请 7 s / 13 s，另一次失败用的是 42 s 前的旧邀请。失败后未重试。
- **对应修改（未上车验证）：** `requestUI` 回 `modesChanged` 并请求关键帧；连入前等 20 s 内的新鲜邀请，`_airplay._tcp` 直连
  仅在 3 s 宽限后作为旧机型兜底，初始 SETUP 后控制通道被关时等下一次邀请自动重连（最多 2 次）；CarWith 尺寸与车机流不符
  且 2 s 内 ≥4 次 `forceKeyFrame` 时，改为手机端硬解 → GL 拉伸 → 车机流尺寸编码（帧率不超 `maxFPS`），对透传正常的接收端
  不触发。`forceKeyFrame` 日志改为限流并如实区分「转给 CarWith」与「本机编码 I 帧」。
- **审查后补充（2026-11-01）：** `modesChanged` 与转码准备移出车机事件读取线程（串行执行、合并密集请求、丢弃会话
  更替后的迟到任务）；转码改用单调时间戳（CarLife 时间戳以秒计，同秒多帧同值）并以 `KEY_MAX_FPS_TO_ENCODER`
  实际限帧；切换转码后丢弃透传线程残留的旧格式帧；邀请时效按当前时刻评估。20 s / 3 s 为单车现场观察得出的
  兼容阈值，不是协议常量。资源仲裁模型、能力声明审计与真机编解码测试留作后续。
- **二次审查补充（2026-11-01）：** 转码解码器起步时，IDR 缺的 SPS/PPS 从 CarLife 解析器缓存补（入流门控只在首次接入时补齐，
  中途切换后的 IDR 不保证内联 PPS，否则会持续黑屏）；转码初始化期间会话停止时，未发布的编码器 / EGL 由初始化方释放；
  CarWith 格式变化按源尺寸判断，不再与转码后固定的流尺寸比较（否则每帧都记一次格式变化并同步写日志）。
- **三次审查补充（2026-11-01）：** 断开时先关屏幕数据 socket 再取转码发布锁，避免「写线程阻塞在 socket、
  转码发布方等写锁、stop 等发布锁」三方卡住断开按钮；转码解码器在停止后才启动完时由创建方释放，不再发布；自动模式
  候选列表在「已缓存 …caff 的设备」之外保留 SDP 闸门放行的例外，离线的旧接收端不再挡住不声明 …caff 的车机。
  转码输出也计入诊断时间线的已发帧数 / 字节（此前切换转码后时间线停更，5 s 后会误报视频停滞）。
- **四次审查补充（2026-11-01）：** 转码解码输入丢帧（输入缓冲繁忙、超出缓冲、入队异常）后视为断流：后续 P 帧不再送解码器，
  等 CarWith 下一个 IDR 再续，等待期间每秒最多向 CarWith 要一次关键帧（`VIDEO_ENCODER_RESET`）。转码后车机的
  `forceKeyFrame` 只作用于手机端编码器，修不好源解码器。
- **第九版测试包复测（2026-11-01 21:44–21:47，报告 `a2a917d6…`，remote/debug APK `1576852d…`）：** 手机与 CarWith/CarLife
  版本同上。连接侧修改在车上生效：
  1. 指定目标后按「车载音频类别」越过 SDP 结论连入 `…caff`；
  2. `.rd` 包与车机重新完整走 pair-setup，`0x5703` 仍未到，收到 ctrl 邀请后 0.3 s 内转入兜底并接邀请；
  3. 初始 SETUP 一次成功，未再被关控制通道；
  4. 用户点 CarPlay 图标约 20 次，每次都回 `modesChanged`（RTSP 200）。

  **仍未出画（推断）：** 这次 CarWith 返回 1280×720，与车机流一致，按旧条件不转码。CarWith 帧与发出帧都是 1067、约 19 fps、
  关键帧约每 40 帧一个，但车机整轮约 0.9 次/秒要关键帧（70 s 内 60 次以上），无任何触控上报，用户反复点图标。
  由此可见，透传解不出画不只由尺寸不符引起。旧的「2 s 内 4 次」阈值在这个频率下即使尺寸不符也不会触发。另外，
  RECORD 后约 1 s 车机断开蓝牙，4.5 s 后重连，上车自动连接在会话进行中再次进入 `startConnection`，把邀请时刻、
  重连次数与要帧计数中途清零。
- **对应修改（未上车验证）：** 10 s 内车机 `forceKeyFrame` 达 6 次即判为持续解码失败，不论尺寸都切换手机端转码；
  原有的尺寸不符 + 密集触发保留。`requestUI` 后我们自己补的 I 帧不计入风暴。会话进行中再次启动连接时不再清零上述计数。
  诊断新增：
  - CarWith 与手机编码器的 SPS/PPS 特征：profile、约束位、level、POC 类型、参考帧数、VUI 帧率、`max_num_reorder_frames`、
    CABAC、8×8 变换；
  - 透传码流统计：I/P/B 片数、每帧片数、帧内 SPS/PPS、AUD、SEI、首个 IDR 的 NAL 组成；
  - 每次要帧距上一个已发 IDR 的毫秒数。

  统计在前 300 帧、切换转码时和会话结束时各写一次运行日志。阈值同样来自单车观察，不是协议常量。
- **诊断补全（2026-11-01，未上车）：** 为区分以下几类原因，再补一批日志：车机解不了码流、车机已断开数据口、链路拥塞、
  时钟异常、资源仲裁没切到 CarPlay。新增内容：
  - avcC 与配置头字节；
  - 帧大小 / IDR 间隔 / 发送停顿 / 慢写 / 队满丢帧；
  - CarWith 对要帧的 IDR 响应时延；
  - 转码管线输入→解码→渲染计数与编解码器输出格式；
  - 屏幕数据口回传与关闭；
  - 车机时钟漂移与跳变；
  - 车机命令按类型计数及首次出现时的结构；
  - `modesChanged` 应答与完整 `/info modes`；
  - 入流门控的重新接入；
  - 每分钟的 Wi-Fi 信号/频段/速率、温控与电量。

  码流统计改为前 300 帧后每 30 s 记一次，避免中途熄火时拿不到结束汇总。
- **再次被拒（2026-11-01 22:07–22:08，用户粘贴的脱敏运行日志片段，片段中无 APK 指纹）：** 指定目标连入，这次车机立即回 `0x5703`
  （.rd 包已配对过），手机本就连在车机热点上。ctrl 广播时序：
  - 59.067 开始等邀请，59.895 广播确认可见；
  - 01.087 按「开始等待后 2 s」强制重公告，注销了刚可见 1.2 s 的广播；
  - 02.052 广播再次可见；
  - 02.097 3 s 宽限（同样从开始等待算）到期，未等邀请直连 `_airplay._tcp`；
  - 初始 SETUP 后车机关闭控制通道。

  此前成功的几轮，邀请都在广播可见后 0.1–0.3 s 到达。另外，读线程退出后 `/info` 在死连接上另起读线程并清掉关闭标记，
  白等 10 s 超时才进入按邀请重连。片段在重连等待处结束，重连结果未知。
- **对应修改（未上车验证）：** 宽限期改从广播确认可见（`onServiceRegistered`）或重公告时刻起算。广播已可见时，可见满 3 s
  仍无邀请才补一次公告；从未确认可见时，仍在 2 s 后补。补公告后再可见满 3 s 才走 mDNS 直连。控制通道读线程已退出时，
  后续 RTSP 请求立即失败。
- **首次出画与触控（2026-11-01 22:45–22:48，报告 `b9c1dae6…`，remote/debug APK `2922543a…`，手机仍为 `M098FE`）：**
  22:24–22:45 多轮车机都不回 `0x5703`。22:45:53 手机在车机热点上、ctrl 广播可见约 100 ms 后车机发来邀请，按邀请连入。
  - **22:45:54 一轮：** `/info modes` 中主屏 `transferPriority/takeConstraint=500/500`，即车机以 userInitiated 持有主屏。
    透传 Baseline 1280×720（SPS profile 66、level 31→补丁 40、无 B 帧、单 slice、无 CABAC），车机约每秒要一次关键帧。
    用户点 CarPlay 图标 7 次，每次都回「手机持有」的 `modesChanged`，均无效。持续要帧触发同尺寸转码（手机编码器
    High 100 + CABAC）后车机仍要帧，13 s 后关闭屏幕数据口。
  - **22:46:22 一轮：** 车机重连并回 `0x5703`，`modes` 中主屏为 `100/100`（niceToHave）。同样的 Baseline 透传，
    **零次要帧，车机发来 832 条触控报文（点击与滑动），用户确认进入了 CarPlay 页面**。22:47:45 用户回原车主界面，
    车机发 `changeModes`（take 主屏 500/500）；手机仍回「手机持有」，之后点图标 6 次都回不到 CarPlay，用户反馈一致。
  - **结论：** 无画面与要帧风暴是资源仲裁问题，不是码流问题。车机以 userInitiated 持有主屏时，手机宣称持有会被无视；
    手机必须先如实承认车机持有，在用户点图标（`requestUI`）时再拿回。另见：用户断开时事件通道未关闭，22:48:05
    新一轮 RECORD 后车机把新旧两路一起 reset；车机首个 timesync 请求与后续请求的时钟基准不同。
- **对应修改（未上车验证）：**
  - 新增手机侧资源仲裁状态：`/info modes` 中 userInitiated 的 take 记为车机持有，`changeModes` 的 take/borrow/
    untake/unborrow 按请求更新，`requestUI` 时拿回主屏（约束 never 时不抢）；`modesChanged` 按当前归属上报。
  - 撤销同尺寸持续要帧即转码（只记日志）；转码编码器改为请求 Baseline。
  - 断开时一并关闭事件通道与计时服务。
  - timesync 漂移遇超过 1 小时的跳变时换基准。
  - 之前把设备名「17 Pro Max」判断为换了手机，这是错的：报告显示手机仍为 `M098FE`，该名称是手机向车机报告的设备名。
- **拿回主屏生效，开启声音断链（2026-11-01 23:01–23:03，报告 `d6111d57…`，remote/debug APK `b1d17eee…`，手机仍为 `M098FE`）：**
  - **23:01:52 一轮：** `/info modes` 主屏 500/500，手机如实回「主屏=车机」。车机先要关键帧 20 次（同尺寸 1280×720 透传，
    只记日志不转码）。23:02:00 用户点 CarPlay 图标（`requestUI`），手机回「主屏=手机」，**0.5 s 后开始收到触控**（共 10 条），
    此后不再要帧；CarWith 开始播放音乐。资源仲裁的拿回路径在车上生效。
  - 23:02:31 用户在手机上点「开启声音」：音频 SETUP 在主线程发出，`AUDIO: 通道启动异常 - null`（按钮在主线程直接调用，推断为
    没有 message 的 `NetworkOnMainThreadException`；新版日志会带异常类名）。抛出时报文已加密、计数已前移，0.6 s 后车机关闭控制通道并 reset 事件通道与屏幕数据口，会话结束。
  - **23:02:42 重连一轮：** 等邀请 4 s 超时（广播可见才 2.6 s），邀请在开始等待 6 s 后才到；mDNS 一直开着，清缓存后不再重报
    `_airplay._tcp`，最终走网关兜底连入。`modes` 主屏 100/100，手机报「主屏=手机」，但车机停在原车界面持续要帧；
    23:02:53 点图标时手机状态没变，重发同样的 `modesChanged`，车机无反应。用户反馈车机断电重启后再连能进入，退回主界面后回不去。
- **对应修改（未上车验证）：** 手机上点「开启声音」改走后台线程，主线程上的 RTSP 请求在加密前拒绝；`requestUI` 时若主屏本就归手机，
  先报一次让出、200 ms 后再报拿回；等邀请上限 4 s → 9 s（覆盖可见 3 s、重公告、再宽限 3 s）；mDNS 清缓存时停掉并重开发现。
- **回原车主界面后切不回（2026-11-01 23:08–23:23，报告 `4e27999a…`；23:08/23:11 两轮为 APK `b1d17eee…`，23:22 一轮为 `9e97beb9…`，手机仍为 `M098FE`）：**
  - 三轮现象一致：开场 `/info` 主屏 500/500，手机回「主屏=车机」，车机持续要关键帧；用户点图标（`requestUI`），手机回「主屏=手机」，
    随即有触控、要帧停止（23:11 一轮触控 1325 条）。用户回原车主界面时车机发 `changeModes`（take 主屏 500/500），手机回「主屏=车机」。
    之后再点图标，手机回「主屏=手机」，**车机既无触控也不再要关键帧**，停在原车界面。23:22 一轮（新包）对后续点击改为先报让出、200 ms 后
    再报拿回，共 10 次，同样无反应。
  - 23:22:49 那次 `requestUI` 带 `url`（12 字符），其余不带参数；带不带都能在开场拿回，与能否二次进入无关。
  - CarWith 透传码流只有首个 IDR 内联 SPS/PPS，参数集仅在开流时随 VideoConfig 发送一次。
  - 新包上 4 s 等邀请、音频补启主线程两处问题未再出现：23:22 一轮收到邀请 0 ms 即连入，音频随连接启动。
- **对应修改（未上车验证）：** 每次 `requestUI` 拿回主屏后，在下一个 CarWith IDR 前重发 VideoConfig，并用解析器缓存给该 IDR 内联 SPS/PPS
  （假设：车机回原车界面时拆掉了解码器，再进入时只等参数集）。拿回 3 s 后记一行「车机触控 +N、要关键帧 +M」，用于区分仲裁未生效与解码失败。

## 奇瑞艾瑞泽 8 雅（2022 款）：手机主动连入全部快速失败、车机未回连（2026-11-02 18:32–18:36，UTC+8，remote 实时日志）

手机小米 `M2102J2SC`，Android 13（SDK 33），HyperOS `OS1.0`，ROM `V816.0.4.0.TGACNXM`；CarWith `4.0.4-20260522`（104000004），
小米 CarLife `8.6.8`（260）；应用 remote/debug `0.1.3-rd-diag3-debug`。车型由用户确认为 2022 款艾瑞泽 8 雅，用户报车机版本
「2.0.4」，与 10-31 那台的固件 `M1E_IC2172/82_03_21.387_250619_R` 是否同一版本无法对应。证据为调试桥实时日志，经只读 API 脱敏
（设备名/地址为占位符），原文不入库。

- **三轮均停在蓝牙阶段**（各约 57 s、39 s、98 s 后由用户取消），没有 iAP2、热点凭据或媒体。每轮都先在本机注册 `…cafe` iAP2 监听与
  CarPlay 能力 UUID 并开始监听；第三轮注册（18:35:15）早于配对完成（18:35:21），顺序与 10-31 成功路径一致。
- **车机不提供 `…caff`：** 18:34:37 SDP 实时查询完成（295 ms），8 项服务为 SPP、A2DP 接收、AVRCP、HFP、0x1133（MAP 通知）、
  `4de17a00-52cb-11e6-bdf4-0800200c9a66`、`ec884348-cd41-40a2-9727-575d50bf1fd3` 及一项 0x0000，与 10-31 那台同样有 `ec884348…` 而无 `…caff`。
- **手机主动连入 34 次全部失败**（第二轮 6 次、第三轮 28 次），都在 43–127 ms 内 `IOException reason=READ_FAILED readRet=-1`；
  如此之快与 SDP 找不到服务一致，重试不会改变结果。
- **车机始终没有回连：** 三轮 `incomingAccepted=0`。日志无法反映用户是否在车机上点选了手机；10-31 的成功均为车机回连，且需用户在车机
  蓝牙列表手动点手机。
- **配对不稳定：** 第一轮 `BONDING→NONE`；第二轮 18:34:37 配对完成，18:34:59 断开后到 18:35:15 第三轮开始时目标已不在已配对列表，
  随后重新配对。应用代码无 `removeBond`，由用户还是车机删除未定。
- 当时目标未配对，日志在「目标不在已配对列表」后又写「没有已配对的蓝牙设备」，而手机上另有 7 台已配对设备，属误导。

**对应修改（未上车验证）：** 对未声明 `…caff` 的车机，出站在 1 s 内 `READ_FAILED` 后改为等车机回连：带 `ec884348…` 时首次即转，
否则连续 3 次；之后每 60 s 才试一次出站，不再每 3 s 一次，也不做配置文件断开对照。声明 `…caff` 的车机、较慢或其它原因的失败不受影响；
帕萨特这类首连成功的车机不会进入此路径。转入时日志与界面提示「请在车机的蓝牙或 CarPlay 设备列表中点选本机」；指定目标未配对时不再
误报「没有已配对的蓝牙设备」。是否因此连上，需同一车机在完整重启中控、两侧删除配对、先启动 mcarplay、再由车机点选手机后复测。

- **修改包复测（18:53–18:56，remote 日志；测试包 SHA-256 `249f5b4e…`，日志版本串仍为 `0.1.3-rd-diag3-debug`）：** 新逻辑按预期生效——
  第二、三轮各只在配对后连一次，75 ms / 174 ms `READ_FAILED` 即转入等待并给出提示，第三轮此后只在 60 s 后探测一次（18:56:53，75 ms 失败），
  不再每 3 s 重试。**车机仍未回连**（`incomingAccepted=0`），能力记录均在配对前注册。配对依旧不稳：第一轮 `BONDING` 持续约 35 s 后随 ACL
  断开回到 `NONE`；第二轮配对成功，用户取消、车机蓝牙断开后，第三轮开始时目标又不在已配对列表。日志不能说明用户是否在车机上点选了手机、
  车机是否显示支持无线 CarPlay，也不能说明配对由谁删除。

## 奇瑞艾瑞泽 8（2022 款，10-31 同一辆）：删除配对后首轮出画，用户断开后车机认证与链路无应答（2026-11-02 19:18–19:22，UTC+8，remote 实时日志）

手机小米 `2509FPN0BC`，Android 17（SDK 37），HyperOS `OS4.0.4.0.XPBCNXM`，CarWith `4.0.23-20261016`（104000023），小米 CarLife `8.8.5`（274）；
应用 remote/debug `0.1.3-rd-diag3-debug`（`fix/chery-inbound-wait` 测试包）。用户反馈此前删除了车机蓝牙配对，「只在开始连上一次」。

- **第一轮成功：** 19:18:53 车机回连 `…cafe`，MFi 32B 应答、`0x5703`、加入热点、AirPlay 配对/SETUP/RECORD 均完成，19:19:11 发出 CarWith 首帧，
  约 9 s 内发送 170 帧。19:19:20 以 `USER_STOP` 结束（手机断开按钮或调试桥 `stop_connection`，二者在设备日志中无法区分）。
- **第二轮（19:21:23 车机回连）：** 链路同步与 `0x1d01` 正常，32/20/16B 三次 MFi 挑战车机都只回链路 ACK、不回 `0xaa03`，之后 `0x5702` 无 `0x5703`，
  19:21:53 车机关闭 RFCOMM。与 10-31 12:50 原厂崩溃后「认证共享锁」的现象一致。
- **第三轮（19:22:15 车机回连）：** 手机每 500 ms 发 iAP2 链路 SYN，车机 28 s 内无任何应答，「链路协商超时」。
- 本轮没有任何车机 ADB 操作；10-31 起对车机的操作仅为重启 CarPlay 服务与经用户授权的中控重启，没有持久改动。
- 另见：车机回连会话进行中，出站客户端仍按本轮候选列表走到该车机并尝试一次 `…caff`（19:19:12、19:21:39），因为
  `BtSessionGate` 只在每轮列表开始时检查。该尝试 100 ms 内失败，是否影响车机未知。

## 奇瑞艾瑞泽 8（2022 款，10-31 同一辆）：真 iPhone 无线 CarPlay 的断开时序（2026-11-02 19:49–19:52，UTC+8，车机 ADB logcat）

车机 `G6SH_r8a7795`，Android 9，固件 `M1E_IC2172/82_03_21.387_250619_R`，19:44 刚重启；车机无 root，只取 logcat（main/system/crash），
原文含定位与设备序列号，仅存本机临时目录，不入库。手机为用户提供的真 iPhone（型号与 iOS 版本未记录）。用户操作：未在 iPhone 上忽略此车时
连不上，忽略后重新配对；连上后先关 iPhone 蓝牙（会话不受影响），再关无线局域网（会话断开）；之后重新打开两者即自动重连。

### 真 iPhone 连接全过程（逐步，19:49:38–19:51:44，车机 ADB logcat）

以下按时间顺序列出同一次会话（会话时长 106 s、`noErr` 结束）在车机日志中的每一步；「+」为距用户在车机蓝牙设置点选 iPhone 的秒数。
地址、热点名与口令已略去。末列为 mcarplay 在 2026-11-02 23:36 复测（APK `a029717a…`）中的对应表现。

| 步骤 | 时刻 | 车机日志证据 | 说明 | mcarplay 现状 |
|---|---|---|---|---|
| 1 点选 | 38.073（+0） | `connectWirelessCarplay: iPhone` → 38.441 `FlcBsmSockConnect` | 车机发起蓝牙 SPP 连向手机的 iAP2 服务；手机不主动连车机 | 相同（手机主动连入在该车上 45 ms 内 `READ_FAILED`，改为等车机回连） |
| 2 iAP2 识别 | 44.422–44.522（+6.4） | `Device_StartIdentificationMessage` → `Accessory_IdentificationInformationMessage`（含 `BluetoothTransportComponent`、`WirelessCarPlayTransportComponent`）→ `Device_IdentificationAcceptedMessage` | 点选到识别约 6 s，为车机侧链路建立耗时 | 相同 |
| 3 蓝牙 MFi 认证 | 44.588–45.399（+7.3） | 证书 608 B → `Accessory_AuthenticationResponseMessage sem_wait` 44.875 → `sem_post` 45.307（0.43 s）→ `iAP AUTHENTICATION_PASS` | 一次挑战即通过；车机芯片为 MFi Auth 3.0，摘要 32 字节 | 修改后相同：只发一次 32 B，0.55 s 收到签名（此前先发 20/16 B，芯片挂起约 35 s） |
| 4 热点凭据 | 45.404 | `Device_RequestAccessoryWiFiConfigurationInformationMessgae` → `Accessory_WiFiConfigurationInformationMessgae`（SSID、口令、`securityType = 2`、`channel = 149`） | iPhone 认证后立即请求车机热点信息（0x5702 → 0x5703） | 相同 |
| 5 车机起 AirPlay | 45.458 | `Registering Bonjour _airplay._tcp. port 7000`、`AirPlay servers started` | 车机在发出凭据的同时开 AirPlay 服务 | 相同 |
| 6 入网 | 49.262（+11.2） | `OnApLinkedStaJoin` | 拿到凭据约 3.9 s 后加入热点 | 相同；手机系统 WLAN 保存过该热点时会以系统身份入网并成为默认网络（无互联网） |
| 7 车机连手机 ctrl | 入网后 | 车机以 `hostName=iPhone.local.` 连 iPhone 的 `_carplay-ctrl` | 车机按手机广播的 ctrl 服务回连 | 相同（手机广播 `_carplay-ctrl._tcp`） |
| 8 配对 | 53.009–53.266（+15.2） | `Control pair-setup HK, type 0` ×3、`Control pair-verify 1/2` | 车机重启后首连做完整 pair-setup（M1/M3/M5）再 pair-verify；19:55、19:57 重连只走 pair-verify | 修改后配对类型相同（`X-Apple-HKP: 0`；原为 `type 2`）；**不同**：mcarplay 每次会话都做完整 pair-setup |
| 9 会话内 MFi | 53.276–53.717 | `MFi _requestProcessAuthSetup` → `MFiSAP_Exchange start/end`（0.44 s） | pair-verify 后、会话开始前做 `/auth-setup` | 修改后相同（0.47–0.51 s，证书 608 B、签名 64 B） |
| 10 会话开始 | 53.789–53.910（+15.8） | `AirPlay session started: From=iPhone … T=WiFi C=?`、`HID started`、`link_BindPort iap2_wificarplay_port` | 只记一次 `session started` | 修改后 `From=iPhone`；补发 RECORD 时可能记两次（重复启动是待验证的崩溃诱因） |
| 11 初始模式 | 53.969 | `Modes changed: screen accessory …, mainAudio accessory …, speech n/a (none)` | iPhone 起步时屏幕与主音频都留给车机 | **不同**：mcarplay 不经此阶段，约 2 s 后直接 `screen controller / mainAudio controller` |
| 12 网络 iAP2 识别 | 54.028 | `field_put_VehicleInformationComponent`、`field_put_LocationInformationComponent` | 经 AirPlay 内的 iAP2 链路再做一次识别 | 相同（23:36 为 39.254）；部分会话该链路未到 NORMAL |
| 13 车机关蓝牙 | 54.041–54.130（+16.0） | `CarplayService_DisableBluetooth`、`CarPlayConnected and setBtDisable`；54.574 ACL 断开 | iPhone 在会话开始约 0.13 s 后发 `disableBluetooth`，会话改走纯 Wi-Fi | 修改后相同 |
| 14 隧道 MFi 认证 | 54.197–54.752（+16.7） | 证书 608 B → `sem_wait` 54.230 → `sem_post` 54.677（0.45 s）→ `Option_WifiCarplay_Wifi AUTHENTICATION_PASS 2` | 网络 iAP2 一次挑战即通过 | 修改后相同（只发一次 32 B，0.48 s；此前先发 20 B，挂起约 37 s 并排队） |
| 15 接管屏幕与主音频 | 55.366–55.549（+17.5） | `Modes changed: screen controller …`（55.366）→ `mainAudio controller`（55.539）→ `ScreenStreamStart`、`video stream status … 0` | 会话开始约 1.5 s 后才接管并出画 | **不同**：mcarplay 的屏幕流在会话开始前（39.063）就已启动 |
| 16 主音频按需 | 58.066（+20.0） | `Main audio setting up AAC-LC/44100/2 for media` | 有媒体播放时才建主音频流 | **不同**：mcarplay 会话开始 0.1 s 即建主音频并全程保持 |
| 17 辅音频（提示音） | 19:50:07.663–13.579 | `Alt audio setting up AAC-ELD/48000/1` → `[duer]Teardown` → `Alt audio torn down` | 约 6 s 的提示音单独建流、单独拆流；19:55、19:57 两次会话没有 | 不建辅音频（与崩溃无关，见下） |
| 18 暂停时拆主音频 | 19:50:30.502 / 19:50:50.311 | `[duer]Teardown` → `Main audio torn down`；20 s 后 `Main audio setting up …` | 媒体停止即拆流，恢复播放再建；会话与屏幕流保持 | **不同**：mcarplay 不中途拆主音频 |
| 19 断开 | 19:51:40.141–.530 | iAP2 `wirelessCarPlayStatus = 0`（0x4E0D）→ .270 第一条 `[duer]Teardown`（屏幕流）→ .494 第二条（整会话，间隔约 220 ms）→ .501 `session ended … noErr` → .530 `AirPlay servers stopped` | 先告知无线不可用，再拆屏幕流，最后拆会话 | 修改后相同（链路未到 NORMAL 时 0x4E0D 无法发送） |
| 20 收尾 | 41.113–44.110 | `CarPlayDisconnected and setBtEnable`；41.346 `OnApLinkedStaLeave`（会话结束后 0.85 s）；44.102 蓝牙适配器 `10->11` 重新开启 | iPhone 立即离开热点；车机恢复蓝牙 | 手机按设置可能留在热点上；离开与否都会崩溃 |
| 21 结束后 | 19:51:41 起 | 无 `Idle for`、无崩溃 | — | **不同**：mcarplay 每次会话结束 +10 s `### Idle for 10 seconds`、+20 s `_PerformPeriodTasks+640` SIGSEGV，原因未定位 |

同日 19:55、19:57 两次 iPhone 重连不再点选：两者都开时 iAP2 报可用（状态 1）→ 入热点 → 车机连 `iPhone.local` → 会话开始，且只走 pair-verify（见下文）。

- **连接（19:49:4x–19:49:55）：** 车机经蓝牙完成 iAP2 后，iPhone 加入车机热点，车机以 `hostName=iPhone.local.` 连 iPhone 的 ctrl，随后
  pair-setup/pair-verify、AirPlay MFiSAP，19:49:53.910 `AirPlay session started: From=iPhone … T=WiFi`，19:49:55 屏幕接收启动。
- **会话中按流拆音频：** 音频结束时 iPhone 单独拆流——19:50:13 `[duer]Teardown` → `Alt audio torn down`，19:50:30 `[duer]Teardown` →
  `Main audio torn down`，会话与屏幕流保持。
- **断开时序（19:51:40）：**
  1. **.141** iAP2 先到（车机蓝牙此时已关，见下，应经 AirPlay 内的 iAP2 隧道）：`SVP_iAP_WirelessCarPlayUpdateCB wirelessCarPlayStatus = 0` → `WirelessCarPlayUpdateStatusCallBack Available=0`，
     即 `0x4E0D WirelessCarPlayUpdate` 置为不可用。
  2. **.270–.475** 第一条 `[duer]Teardown`：只拆屏幕流（`ScreenStreamStop` → `_ScreenTearDown` → `Screen thread exit`）。
  3. **.494** 第二条 `[duer]Teardown`（距上一条约 220 ms）：`HID stopping`、`Main audio torn down`，即整会话拆除。
  4. **.510–.958** 车机自行收尾：连接状态 `Normal_Disconnected(5)` → `StopWirelessCarPlay` → 注销 `_airplay._tcp`、`AirPlay servers stopped`
     → `SVP_iAP_DeactivateDevice`。
  5. **41.345** iPhone 离开车机热点（`AP-STA-DISCONNECTED`）；41.113 车机设置记 `CarPlayDisconnected and setBtEnable`，19:51:44 车机蓝牙适配器
     状态变化。
- **会话期间车机关闭自身蓝牙：** 19:49:54.574（会话开始约 0.7 s 后）iPhone ACL 以 `Adapter State: BLE_TURNING_OFF` 断开；会话结束后
  `CarPlayDisconnected and setBtEnable`，19:51:44 适配器重新开启，蓝牙服务进程号随之变化（19:57 再次变化）。因此关 iPhone 蓝牙不影响会话。
- **配对身份：** 19:49:45 车机记 `Created AirPlay Pairing Identity`（重启后新建；同日 19:19 mcarplay 读到的 `/info pi` 与之不同），首连走
  pair-setup；19:55、19:57 重连只走 pair-verify。用户需先在 iPhone 上忽略此车才能连上，与车机身份更换一致（推断）。
- **只开蓝牙、未开 Wi-Fi（19:54:13–19:54:52）：** iAP2 报 `wirelessCarPlayStatus = 0`，车机状态 6 后停服务，约 39 s 后 ACL 断开。
- **两者都开即自动重连（19:55:14–19:55:28）：** iAP2 报可用（状态 1）→ iPhone 入热点 → 车机连 `iPhone.local` → 会话开始。
- **无拆除请求的断开（19:56:59）：** iPhone 直接离开热点（`AP-STA-DISCONNECTED`），此前无 `0x4E0D` 与 `TEARDOWN`；车机 0.5 s 后由
  `WIFI_AP_STA_LEAVE` 触发 `StopWirelessCarPlay`，先停 AirPlay 服务再拆会话（伴随一条 `kTimeoutErr`），状态 5。19:57:17 起自动重连，19:57:29 会话开始。
  用户此次的具体操作未记录。
- **没有崩溃：** 19:44–19:57 全程 crash 缓冲无记录，匹配 `tombstone` 的只有 SELinux 审计噪声，CarPlay 进程 PID 3652 不变。
- **两种 iPhone 断开对车机都是「先停服务/有序拆除」：** 要么 iPhone 主动 `0x4E0D` + 两次 `TEARDOWN`，要么 iPhone 离开热点由车机自己收尾。
  mcarplay 现在是关闭全部 TCP 后才释放热点，车机先看到连接被断而手机仍在热点上，这条路径 iPhone 两次都没有走过。
- **与 mcarplay 的差异：** mcarplay 断开时不发 `0x4E0D` 不可用、不发 `TEARDOWN`，同时关闭全部 TCP 与蓝牙 RFCOMM；10-31 与今日的崩溃/认证无应答
  均发生在这种断开之后。车机日志只显示 `Teardown` 及所拆部分，不含请求体；按流拆除的请求体格式（`streams` 列表）为按 AirPlay 惯例的推断。
- **认证卡住的直接原因是车机 MFi 芯片 I2C 读不返回（20:00–20:05，同一抓包，另含车机 `dmesg`）：** 车机 iAP2 应答挑战时先取共享信号量，
  再经 `/dev/i2c-4` 写挑战并轮询读取芯片（日志 `i2c_read 133` → 正常约 0.4 s 后到 `143/149/158` 并 `sem_post`）。
  - 19:55:29 **真 iPhone** 的 Wi-Fi 隧道 iAP2 认证（线程 7209）同样停在 `i2c_read 133` 无后续；iPhone 会话不受影响，19:57 重连 BT 认证正常。
  - 20:00:47 iPhone 直接离开热点（无 `TEARDOWN`），车机自行收尾，会话结束原因 `kConnectionErr`。
  - 20:02:23 用户在车机上点选 mcarplay 手机（蓝牙地址与随后 AirPlay 会话 `D=` 相同），车机主动连其 `…cafe`；20:02:26 认证线程 8488 取锁、写挑战后停在
    `i2c_read 133` 不再返回且未释放；车机对后续挑战无处理，20:02:38 手机端关闭 RFCOMM。**此前本次开机内 mcarplay 没有任何连接或断开，车机也没有崩溃。**
  - 20:03:27 再次点选：BT 认证 0.6 s 通过，20:03:38 AirPlay 会话开始，20:03:38.95 车机收到视频；隧道认证（线程 8821）先后两次各等 35 s 超时，
    20:04:48 第三次通过（`Option_WifiCarplay_Wifi AUTHENTICATION_PASS`）。
  - 车机 `dmesg`（20:02:26 之前的条目已被审计日志挤出）记 `i2c-rcar e66d8000.i2c: error -110`（即 `i2c-4`，20:04:00.9）及 `i2c-6` 多次 -110。
  - 结论：「认证锁」是车机读 MFi 芯片挂起、持锁线程不返回所致，真 iPhone 也会碰到；10-31「崩溃后遗留锁」只是其中一种情形的推断，需修正为此。
    车机内部等待约 35 s 超时；手机端在此期间重发的挑战会排队，各再耗 35 s。触发条件未知。
- **mcarplay 断开后复现原厂崩溃（20:11，同一抓包）：** 20:03 的 mcarplay 会话运行约 461 s 后，用户在 mcarplay 点断开：
  20:11:18.558 车机屏幕流先停、18.589 车机读到 mcarplay 关闭蓝牙 RFCOMM（`bt socket closed`）、18.614 会话以 `kConnectionErr` 结束、
  18.643 手机离开热点、18.732 车机才停 AirPlay 服务；19.263 手机以另一 MAC（系统 Wi-Fi 已保存的热点）0.6 s 后重新入网。
  **20:11:39.040（断开后 20.4 s）`SIGSEGV`，`libAirPlay_R14_vehicle.so (_PerformPeriodTasks+640)`，空指针 0xac**——与 10-31 完全相同，
  库 SHA-256 仍为 `9e24ea45…`。CarPlay 进程由 3652 重启为 9935。真 iPhone 当日四次断开（两次有序、两次直接离开热点）均未触发。
- **重启后的进程再次崩溃：** 20:11:51 用户在车机上点选 mcarplay 手机，BT 认证通过，20:11:58 会话开始、20:11:59.95 收到视频；
  **20:12:02.2 `SIGILL`（ILL_ILLOPC）于 Binder 线程的 JIT 代码（`dalvik-jit-code-cache` ← `Binder.execTransact`）**，20:12:03.6 进程退出，
  之后未再拉起（20:13 `pidof` 为空）。这就是用户看到的「出画几秒退出」；20:12:06 手机从系统 Wi-Fi 身份（`.168`）切回 mcarplay 专属网络（`.25`）
  发生在崩溃之后。SIGILL 的原因无直接证据，与 10-31「崩溃后原厂模块不稳定、需重启中控」一致。
- **iPhone 会话开始即发 `disableBluetooth`：** 三次 iPhone 会话分别在 19:49:54.041、19:55:28.893、19:57:29.584（会话开始后约 0.1–0.6 s）
  触发车机 `_AirPlayHandleSessionControl` → `CarplayService_DisableBluetooth Dev_BtAddr=<iPhone 蓝牙地址>`，车机随即
  `CarPlayConnected and setBtDisable` 关闭整个蓝牙适配器，断开后 `setBtEnable` 恢复。车机 `libcarplayserver_R14_vehicle.so` 中该会话控制的类型串为
  `disableBluetooth`、参数键 `deviceID`（静态字符串）。mcarplay 不发此命令，故其会话期间车机蓝牙与 iAP2 RFCOMM 保持，断开时车机多出一路
  蓝牙链路关闭；这是否为崩溃诱因未证实。iPhone 在哪条通道、以何种请求体发送，日志未记录，需按 AirPlay 会话命令惯例实现后上车验证。
- **对应修改（`fix/chery-inbound-wait`，未上车验证）：**
  1. 车机 iAP2/`/info` 自报厂商 CHERY 时（策略规则 `chery.disable-bluetooth`），初始 SETUP 与事件通道建立后、`modesChanged` 前经控制通道
     `POST /command` 发 `{type: disableBluetooth, params: {deviceID: <本机蓝牙地址小写>}}`；发出前即置位，本连接内蓝牙断开（RFCOMM 关闭或 ACL 断开）
     按预期处理、不收会话。其它接收端不发。
  2. 用户断开且会话已激活时，后台按 iPhone 顺序：网络 iAP2 控制会话已建立则先发 `0x4E0D`=0；停本地推流；约 150 ms 后 `TEARDOWN` 屏幕流
     （`{streams: [{type: 110}]}`）；约 150 ms 后 `TEARDOWN` 会话（空体），各等应答最多 1.5 s；之后执行原收尾。会话 `TEARDOWN` 未得 200 时先释放车机热点
     再收尾（模仿 iPhone 直接离网）。期间再点断开或发起新连接立即收尾。车机掉线等非用户断开不变。
  3. MFi 挑战（蓝牙与网络隧道两路）：该车机记忆中成功过的长度等待窗口延长到 40 s（车机内部约 35 s 超时），静默过的仍缩短，未知长度照旧。
  需同一车机验证：会话开始后车机是否关蓝牙且会话保持；断开后 30 s 内车机是否不再崩溃、能否直接重连。
- **修改包实车复测（2026-11-02 20:43–20:46，UTC+8，车机 ADB logcat；remote/debug APK `b42cbef3…`，手机小米 `2509FPN0BC`）：** 中控 20:39 重启后车机
  CarPlay 正常。20:44:18 车机点选 mcarplay 手机，BT 认证通过，20:44:31.773 会话开始，**20:44:31.929 车机执行 `CarplayService_DisableBluetooth`
  并 `setBtDisable`**，蓝牙适配器关闭，会话保持（用户确认车机蓝牙已关）。20:45:09.927 屏幕流 `[duer]Teardown`、20:45:10.221 会话 `[duer]Teardown`，
  **会话结束原因 `noErr`**（此前为 `kConnectionErr`），随后车机停 AirPlay 服务、手机离开热点、车机 `setBtEnable`，与真 iPhone 有序断开的车机侧序列一致。
  **但 20:45:30.293（断开后约 20 s）仍 `SIGSEGV`，`_PerformPeriodTasks+640`、空指针 0xac**，CarPlay 进程重启。可排除蓝牙 iAP2 链路与断开顺序为唯一诱因。
  仍存差异：① 车机记 `mAutoReConnectMacAddress = AA:BB:CC:DD:EE…`，即 mcarplay SETUP `macAddress` 的占位值，真 iPhone 为其真实 Wi-Fi 地址；
  ② 网络 iAP2 认证再次停在车机 MFi 芯片读取（`sem_wait` 无后续），`0x4E0D` 不可用未送达车机；③ 手机系统 Wi-Fi 仍保存车机热点，离开 0.7 s 后以另一 MAC 重新入网。
- **对应修改（未上车验证）：** CHERY 车机（规则 `chery.real-wifi-mac`）的会话 SETUP `macAddress` 改填本机在车机热点上的 Wi-Fi 地址：取控制连接本地地址所在接口，
  依次用接口硬件地址、sysfs 直读、Shizuku 读 sysfs、EUI-64 链路本地 IPv6 反推；都失败时保留占位并记日志。其它接收端不变。
- **SETUP `macAddress` 修改包行车实测（2026-11-02 20:57–22:01，UTC+8，车机 ADB logcat；remote/debug APK `0e8378f5…`，手机小米 `2509FPN0BC`；
  中控 20:51 重启，固件不变）：** 只有车机侧日志，手机端日志未取得。
  - 20:58:15 手机以系统 Wi-Fi 已保存热点的身份（`1e:9a:…`）入网，随后会话开始；车机 `mAutoReConnectMacAddress = 1E:9A:…`，与热点终端 MAC 一致，
    **占位值问题已消除**。会话在行车中持续 **1661 s**（约 28 min），视频 `Glitches 7%, 3 total`。
  - 21:26:05 用户断开：屏幕流 `[duer]Teardown` → 会话 `[duer]Teardown`，**`AirPlay session ended … Reason=0 noErr`**，车机报 `Normal_Disconnected`、停
    AirPlay 服务；21:26:16 手机离开热点。**21:26:25.79（断开后约 20 s）仍 `SIGSEGV`，`_PerformPeriodTasks+640`、空指针 0xac**，进程 5892 → 10255。
    因此 SETUP `macAddress` 不是崩溃诱因；蓝牙释放、有序断开、真实 MAC 三项均已与 iPhone 一致仍崩溃。
  - 21:26:32 手机重新入网，21:26:41 新进程上 pair-setup/pair-verify 通过、会话开始并再次 `DisableBluetooth`（`Dev_BtAddr` 为 mcarplay 手机）；
    21:26:54 会话以 **`kConnectionErr`（Dur=13 s）** 结束，结束前车机认证线程在连续 `i2c_read`，`[duer]Teardown` 未出现（非有序断开，发起方未知）。
    **21:27:14（结束后约 20 s）重启后的进程 10255 同样 `_PerformPeriodTasks+640` 崩溃。** 即该崩溃在 noErr 与 kConnectionErr 两种结束后都会出现，固定约 20 s。
  - 22:00:53 一个新 MAC（`a6:5e:…`）入网，抓包至 22:01:52 未见 AirPlay 会话；ADB 随即断开。
- **中控重启后需在车机上点手机、偶发 pair-setup `bad setup code`（2026-11-02 22:02–22:17，UTC+8，车机 ADB logcat；APK `0e8378f5…`）：**
  - 22:02 中控重启后手机蓝牙（通话/音乐）自动连上，但车机没有发起 CarPlay（无 `connectWirelessCarplay`、手机未入热点）；此前崩溃时车机已清空
    `mAutoReConnectMacAddress`。22:15:28 用户在车机蓝牙设置点手机后车机才连 `…cafe`。
  - 22:15:30 iAP2 认证通过、22:15:32 入网，22:15:40 pair-setup 只走到 M3：**`### Pair-setup server bad setup code`**，随后 pair-verify 报
    `unknown peer`；车机此后未再收到配对请求，22:16:00 `WIRELESS_CONNECT_TIMEOUT` 收尾。此前所有抓包里同一固定码 `3939` 的 pair-setup 均成功，
    22:16:49 第三次点选又一次通过（M1/M3/M5 + verify），会话开始并 `DisableBluetooth`。
  - 判断：SRP 证明对 A、B、S 的补零/去零编码与车机约定不一致时，只在其中某值首字节为 0（各约 1/256）时暴露，与「偶发一次、重试即好」吻合；
    车机具体用哪种编码无直接证据。
- **对应修改（离线通过，未上车验证）：** SRP 客户端重选私钥，直到 A 与 S 均为满 384 字节（补零与否编码相同）；接收端 B 首字节为 0 时
  在提交证明前重发 M1 换 B（最多 3 轮）。模拟接收端新增逐项反向的编码约定，40 轮全部通过；关闭重选时用例失败。
- **用户反馈（22:19）：** 连上后手机走车机热点 Wi-Fi、无互联网。抓包中会话入网 MAC 一直是系统已保存热点的身份（`1e:9a:…`），即手机系统 Wi-Fi
  连着该热点，而不是 mcarplay 的应用专属网络。
- **崩溃前的「Idle」与 type-130（2026-11-02，同一车机，对照当日真 iPhone 抓包）：** mcarplay 每次会话结束（20:11、20:45、21:26、22:27、22:28，
  `noErr` 与 `kConnectionErr` 都有）后，车机 +10 s 记 `### Idle for 10 seconds`，+20 s 在 dispatch 工作线程 `_PerformPeriodTasks+640` SIGSEGV
  （22:28:51 一次直接记 `Idle for 20 seconds` 后同毫秒崩溃）。当日真 iPhone 三次会话结束（19:51 `noErr`；19:57、20:00 iPhone 先离热点、`kConnectionErr`）
  都没有 `Idle` 也没有崩溃。会话开始时只有 mcarplay 触发车机 `### Unsupported stream type: 130`（20:03、20:11、22:16），iPhone 从不请求该流类型。
  推断（未证实）：车机对不支持的 type-130 SETUP 留下了未清理的状态，会话结束后其空闲检查在 20 s 时访问已释放对象。
- **对应修改（离线通过，未上车验证）：** CHERY 车机（规则 `chery.no-iap-tunnel`）不再 SETUP type-130 iAP 隧道；其它接收端不变。需验证会话开始不再出现
  `Unsupported stream type`，结束后无 `Idle for` 与崩溃，触摸照常。
- **不建 type-130 的修改包实车复测（2026-11-02 22:40–22:46，UTC+8；APK `ce466185…`，车机 ADB logcat + 调试桥手机日志）：** 中控 22:39 重启。
  22:44:08 会话开始，手机记「不 SETUP iAP 隧道」，车机**未再报** `Unsupported stream type`，`DisableBluetooth` 生效，视频与音频启动。
  22:45:03 用户断开：手机按 iPhone 顺序发 `0x4E0D`（本次经隧道送达）→ 屏幕流 `TEARDOWN` 200 → 会话 `TEARDOWN` 200，随后关闭控制连接；车机记 `noErr`。
  **22:45:24（+21 s）仍 `_PerformPeriodTasks+640` SIGSEGV**（只在 crash 缓冲区，本次 main 缓冲区无 `Idle for` 行）。**type-130 假设被否定。**
- **同一车机 iPhone 与 mcarplay 会话的车机 AirPlay 日志差异（行模板去数字后比对）：** 只有 iPhone 有：`session started: From=iPhone`（mcarplay 为
  `From=`，空）、`MFi _requestProcessAuthSetup` / `MFiSAP_Exchange`（会话内 auth-setup）、`Alt audio setting up AAC-ELD`（辅音频流）、
  modes 中 `speech n/a (none)`（mcarplay 为 `(?)`）。这些是剩余的可见差异，与崩溃的关系未知。
- **其它观察：** 中控重启后车机立即回连手机 `…cafe`（22:40:20），手机 22:40:23 才开始监听，车机等满 60 s 超时；车机 CarPlay 进程崩溃后曾卡在
  `mConnectStatus = 1`（22:29:38 起），之后点选一律 `return!`，需重启中控。
- **来源名与 speechMode 修改包实车复测（2026-11-02 22:57–22:58，UTC+8；APK `5909a4db…`）：** 发现 mcarplay 的 bplist 把负整数写成 1 字节（`speechMode=-1` → 0xFF），
  车机按无符号读成 255；改为负数一律 8 字节。CHERY 车机控制通道请求加 `X-Apple-Client-Name: iPhone`、`X-Apple-ProtocolVersion: 1`（车机 AirPlay 库含这两个头名）。
  复测中车机记 **`session started: From=iPhone`** 与 **`speech n/a (none)`**，两项与真 iPhone 一致。22:58:15 有序断开、`noErr`；
  **22:58:35（+20 s）仍 `_PerformPeriodTasks+640` SIGSEGV**。来源名与 speechMode 均非崩溃诱因。
  会话结束统计中 `Time Sync` 的 max/avg offset 为 INT_MAX 在 iPhone 会话里同样出现，不是差异。
  剩余可见差异只有：会话内 MFi `auth-setup`（`MFiSAP_Exchange`）与辅音频流（`Alt audio setting up AAC-ELD`）。
- **配对类型与会话内 auth-setup 修改包实车复测（2026-11-02 23:04–23:12，UTC+8；APK `b18cc3db…`）：** CHERY 车机 pair-setup/pair-verify 改发 `X-Apple-HKP: 0`
  （车机对 iPhone 记 `pair-setup HK, type 0`，对 mcarplay 原 `2` 记 `type 2`），pair-verify 后、SETUP 前发 `POST /auth-setup`（0x01 + X25519 公钥）。
  23:04:58 第一次点选时车机 iAP2 认证停在 `Accessory_AuthenticationResponseMessage sem_wait` 两分钟以上，需重启中控。重启后 23:11:22 车机记
  **`pair-setup HK, type 0`** 与 **`MFiSAP_Exchange start/end`（0.47 s，iPhone 为 0.44 s）**，会话正常。23:12:08 有序断开、`noErr`；
  **23:12:29（+20 s）车机记 `### Idle for 20 seconds` 后同毫秒 `_PerformPeriodTasks+640` SIGSEGV**。配对类型与 auth-setup 均非诱因。
  真 iPhone 会话结束后约 0.8 s 即离开热点、无崩溃；mcarplay 离开热点与否（20:11 立即离开、21:26 11 s 后离开）都崩溃，故「断开后手机需继续应答计时」不成立。
  车机日志中与 iPhone 的可见差异只剩辅音频流（`Alt audio setting up AAC-ELD`）。
- **MFi 挑战长度与车机芯片挂起（2026-11-02 全天车机日志 + 调试桥手机日志）：** 车机为 MFi Auth 3.0 芯片（iPhone auth-setup 时车机记
  `Using MFi Auth v.3 chip`、`inDigestLen = 32`）。真 iPhone 每路认证一次、约 0.45 s 通过。mcarplay 的网络隧道按基线先发 20B
  （`TUNNEL_MFI_BASELINE = 20/32/16`），车机认证线程 `Accessory_AuthenticationResponseMessage sem_wait` 约 35–37 s 才返回，其后 32B/16B 排队
  （20:03、20:44、20:59、22:44、22:57、23:11）；蓝牙路径的长度记忆一度把 32B 记为「被拒」，23:20:50 蓝牙首发 20B 后车机认证线程挂死、dmesg 记
  `i2c-rcar e66d8000.i2c: error -110`，车机 30 s 后超时。22:42、23:04 等「点了没反应 / 需重启中控」均在非 32B 挑战之后。
- **对应修改（离线通过，隧道部分上车中）：** CHERY 车机（规则 `chery.mfi-32-only`）蓝牙与隧道两路 MFi 挑战都只发一次 32B、等 40 s、不读写长度记忆。
- **崩溃与挂起认证无关（更正）：** 曾观察到 22:45、22:58、23:12 三次会话结束时都有未完成的隧道认证，但 20:11 与 21:26 两次崩溃前隧道认证早已通过
  （20:04:48、20:59:37 `AUTHENTICATION_PASS`），会话结束时无挂起，仍在 +20 s 崩溃；故「挂起认证导致崩溃」不成立。
- **断开后车机侧 TCP 与停止序列（2026-11-02 23:27–23:30，车机 `/proc/net/tcp*` 每秒采样）：** 会话中车机持有手机 3 条 TCP（`7000` 控制、事件、屏幕数据）。
  23:29:23 会话 `noErr` 结束，两条流连接即刻关闭，控制连接 23:29:24 进入车机侧 `TIME_WAIT`；**会话结束 1 s 内已无任何到手机的 TCP 连接**，
  但 23:29:33 仍记 `Idle for 10 seconds`、23:29:43 `Idle for 20 seconds` 并 SIGSEGV。车机停止序列（`StopWirelessCarPlay` → `DeactiveCarPlay` →
  `carplayserver_stop` → `AirPlay servers stopped` → `CFRunLoopRun end` → `DeactiveWirelessCarPlay end`）与真 iPhone 19:51:40 逐行一致，
  仅 `DeactiveWirelessCarPlay linkid` 为 1（iPhone 为 2）。同一会话车机记两次 `session started`（第二次 `C=AAC-LC`，对应补发 RECORD），
  但 20:03、20:44、22:44、22:57 只记一次的会话同样崩溃；仅凭 started 日志条数不能排除重复 RECORD，见 11 月 3 日补充。
- **当晚结论：** 已对齐并在车上验证的差异：蓝牙释放、有序断开、真实 MAC、type-130、来源名、speechMode、HKP、auth-setup、MFi 32B；
  断开后 +20 s 的 `_PerformPeriodTasks` 崩溃仍在。车机 CarPlay 进程崩溃后约 5 s 自动重启并能回连。
  **更正：** 按上文「真 iPhone 连接全过程」逐步对照，仍有三处行为差异未试：① iPhone 起步时屏幕与主音频都留给车机（`accessory`），约 1.5 s 后才接管；
  ② 主音频只在媒体播放时建立、暂停即拆（会话结束时未必有主音频流），mcarplay 全程保持；③ iPhone 重连只走 pair-verify，mcarplay 每次完整 pair-setup。
- **MFi 32B 固定修改包实车复测（2026-11-02 23:36–23:40，UTC+8；APK `a029717a…`，车机 ADB logcat + 调试桥手机日志）：** 23:36:25 车机点选，
  蓝牙 0xAA02 32B（一次、窗口 40 s）0.55 s 收到 0xAA03，车机芯片读取约 0.43 s；auth-setup 511 ms；23:36:38 会话开始，隧道 0xAA02 32B 一次、0.48 s 收到签名，
  车机记 `Option_WifiCarplay_Wifi AUTHENTICATION_PASS`。点选到出画约 12 s，全程无芯片挂起。会话 204 s（触摸 40 次），23:40:02 有序断开 `noErr`；
  **23:40:22（+20 s）仍 `Idle for 20 seconds` 后 SIGSEGV**。本会话网络 iAP2 控制会话未建立（车机 SYN marker 达 #200、链路 DEAD），0x4E0D 未发；
  而 22:45 等会话链路为 NORMAL、0x4E0D 已送达同样崩溃，二者无关。

### 原厂断开崩溃的静态分析与单次 RECORD 候选（2026-11-03，未上车）

对与上文车机一致的 ARM64 `libAirPlay_R14_vehicle.so`（SHA-256 `9e24ea4577c7eac52d0799bd88647a1a8bea561a179e54bafa7afe7c45b5438d`）
做静态分析，并复核 13 份历史崩溃记录；未运行或修改原厂库，也未操作车机。

- `_PerformPeriodTasks+640` 的 `0x3a420` 从 `[x8+0xac]` 读取字段；13 份崩溃的 `x8=0`。同一函数先从 `session+0x10` 取 server 引用，
  Finalize 会清零该引用。server 的 `+0xac` 默认值为 20 秒，会话将其换算为空闲阈值；两份现场计时寄存器分别约 20.001 / 20.002 秒。
  23:29 日志在 Finalize 后仍出现 Idle 10 / 20 秒并崩溃，说明周期回调未随会话彻底退役。
- 原厂 SessionStart 每次成功都会新建定时器并覆盖 `session+0xd8`，TEARDOWN/Finalize 只能取消当前句柄；若同一 session 成功 Start 多次，
  较早的定时器可能遗留。23:27 会话有两次 started 后崩溃；其他崩溃会话只见一次 started，缺少同一 session 的实际 Start 和定时器计数。
  **更正上文：重复 RECORD 未被排除，是优先验证的触发候选；不能声称已证明全部现场均由它触发。**
- 候选在 `manufacturer=CHERY`、`model=Tiggo`、AirPlay `srcvers=320.17.7` 同时匹配时，同一连接只发一次 RECORD；不同车机维持旧行为。
  并发调用共享结果，超时/拒绝后不在原连接重发；首次 RECORD 未确认则终止本轮。`srcvers` 是栈版本，并非完整车机固件标识。
- 有序停止时禁止新业务请求，终止本代心跳，屏幕推流停止接收新帧但数据 socket 暂留至 TEARDOWN；允许关闭所需的 iAP 通知。
  音频初始化与资源声明按连接代次过滤迟到工作。候选同时包含这些收尾调整，若实车不再崩溃，仍需单变量对照才能归因于单次 RECORD。
- **待实车验证：** 从干净的车机 CarPlay 进程起步，核对策略命中及单次 RECORD，检查画面、声音、触控；手动断开、动态开启声音与异常掉网后各观察至少 60 秒。
  理想情况下记录同一 session 的 Start、timer 创建/取消与 Finalize。尚未验证此候选解决原厂崩溃或避免音频回归。

## 2026-11-03：VW4211 手动断开后的重连、配对窗口关闭与主屏回切

- **环境与证据：** 用户提供的本地报告 `9acfb6ca…`，生成于 2026-11-03 22:14:15（Asia/Shanghai）；当前进程 `35328f0c`，
  `0.1.3-rd-diag3-debug` / APK `a029717a…`。手机小米 `M098FE`，CarWith `4.0.23-20261016` / `104000023`，
  小米 CarLife `8.8.5` / `274`；HyperOS 版本未在本次证据摘要中确认。接收端 `VW4211` / `fv=5739` / `sourceVersion=320.17.3`。
  原始报告含隐私数据，仅在本地读取，不入仓；本节不保留网络名、地址、凭据、密钥或原始协议载荷。
- **复现与用户反馈：** 建立连接、返回原车主界面、再次点 CarPlay 图标；再重复手动断开与连接。用户反馈断开本身不再导致车机异常，
  但三次后无法连接，回到原车主界面后仍无法再次进入。以下日志只证明对应的控制与媒体行为，不能代替用户确认画面、声音和触控。
- **手动断开确有自动抢连：** 22:10:23.184 手动停止，屏幕流与会话 TEARDOWN 分别在 `.284`、`.453` 获 200，
  `.701` 已由 ACL 广播自动重启连接；22:11:30.933 手动停止，22:11:31.044 / `.216` 两次 TEARDOWN 获 200，
  `.556` 再次自动重启。两次额外会话均在数秒内被取消，不能把它们当作成功重连。
- **服务消失早于用户指定的末段：** 第三次有效手动连接始于 22:11:57；22:12:00.233 收到 ctrl 邀请，`.281` 建立 AirPlay TCP，
  `.426` 第一条 pair-setup 获 200 与 SRP 参数；`.555` 控制读取关闭，`.557` 第二条 pair-setup（M3 / CSeq=2）等待失败，
  `.660` mDNS 报服务消失。后续同一 AirPlay 端口持续拒绝连接。
  22:13:39.022–22:14:14.197 窗口有 11 次 `ECONNREFUSED`，蓝牙/iAP2 与应用 Wi-Fi 绑定已完成，但没有新 ctrl 邀请或 AirPlay 发现，
  未取得源视频、未发送视频；22:14:12.640 用户取消。现有证据不能判定接收端进程崩溃、内部资源耗尽或是哪条命令直接触发服务关闭。
- **参数集补发已不足以恢复主屏：** 22:09:45 首次 requestUI 后 3 s 有 17 条触控；22:09:52.077 车机以 500/500 take 主屏。
  后续 requestUI/modesChanged 仍获 200，22:09:55.182–`.196` 已重发 VideoConfig 及内联 SPS/PPS 的 IDR，
  但回切检查为触控 +0、要帧 +0；视频连接仍持续写入，首轮总计 2185 帧。不能继续将这次问题归结为未补发 SPS/PPS。

**当前修复候选（尚未上车验证）：**

1. 手动断开记录接收端级别的 30 s 冷却；ACL 广播、前台服务队列和控制器入口均拦截自动启动，手动连接可解除冷却。
   退出在后台发送屏幕流与会话 TEARDOWN，再释放蓝牙/Wi-Fi；新连接等旧清理结束，避免旧清理拆掉新链路。
2. 首次完整配对后，仅保存经过签名及 pair-verify 验证的接收端身份，按控制器与稳定接收端身份隔离；后续直接 pair-verify，
   避免每次连接都重新注册。仅 TLV authentication=2 允许在新 TCP 上重新注册一次；EOF、403、busy 等保留信任。
   补齐各阶段 TLV 状态/错误及签名检查，用户取消单独记录，避免误记为配对超时。重复注册与服务消失的因果关系仍需接收端日志验证。
3. 真实的会话中主屏让出后，再次 requestUI 先报告归属，然后关闭旧屏幕数据流并发送仅屏幕 TEARDOWN，
   使用同一 display UUID、新的 streamConnectionID/密钥/数据口重新 SETUP/RECORD，重新等待 CarWith IDR。
   首次进入不重建；主音频归属不变，CarWith 源与音频通道保持运行。旧视频结束回调与迟到回切任务按发送器/会话身份隔离，
   取消不会被误当作恢复失败而断开新会话；重建标记直到成功才清除。

**本地验证（2026-11-04）：** 新增 21 项回归，定向 29 项通过，包含连续四轮加密握手只注册一次、信任明确拒绝后修复、EOF/403/busy 保留信任、
错误状态/签名拒绝、接收端缓存隔离、主屏约束/重复 requestUI、手动断开冷却以及真实 TCP 的屏幕重建/完整 TEARDOWN。
最终门禁通过：`spotlessCheck`、`:app:testStandardDebugUnitTest`（113 个测试类、691 项，失败/错误/跳过均为 0）、
`:app:lintStandardDebug`、`:app:assembleStandardDebug`、`:app:assembleRemoteDebug`。
本地 standard/debug APK SHA-256 为 `3af26831de0d58bc3be7fa3f54c79f715e459b9839cb340588c5eb0e1963ca02`，
remote/debug（`0.1.3-rd-debug`）为 `e693eb819783b05111bfa3516060660e0657a5a01cb2b1a65a0f2b91114323ac`；未安装、未发布。
实车仍需连续断开重连至少四轮、确认手动停止后不抢连，
并多次验证「原车主界面 → CarPlay」的实际画面/声音/触控；现有拒连残留状态能否恢复需单独复测。

## HyperOS 3 AVD 与 CarLife 8.6.8 出流排查（2026-11-03）

本轮仅操作 Mac 上的 `HyperOS_3` ARM64 模拟器，未操作同时在线的实体 Pad 或车机。目标是把 CarWith 的七路本地
CarLife 源端从零帧推进到持续 H.264，并区分 AVD 系统集成问题与 mcarplay 协议问题；OS3 的结果不能直接外推为
OS1 或 OS2 兼容性结论。

| 项目 | 本轮最终环境 |
| --- | --- |
| 系统 | HyperOS `OS3.0.2.0.WMCCNXM`，Android 16 / SDK 36，`sdk_gphone64_arm64` |
| 内存 | AVD 默认 2,531,864 KiB，另有约 1.9 GiB swap |
| mcarplay | standard/debug `0.1.3-debug`，`com.mcarplay.app` |
| CarWith | `4.0.4-20260522` / `104000004`，`com.miui.carlink`；通过普通系统应用模块变为 updated system app |
| 小米 CarLife | `8.6.8` / `260`，`com.baidu.carlife.xiaomi`，用户应用 |
| Root / 模块 | KernelSU `3.3.0`，`meta-overlayfs 1.3.1`，模块 `carwith-system` |
| CarWith APK SHA-256 | `a20bd237534d82df08d561974365dc7da66e36899d3e64cc1b137cac0b084605` |
| CarLife APK SHA-256 | `ab7b001a7b3eba95819da6808b29e2038629b30f2bbe3a363256e34ff7cbd4ee` |

测试前已保存 AVD 快照 `before-carwith-system`。CarWith APK 通过 KernelSU 模块暂存到 `/system/app/CarWith`，没有放进
`priv-app`；重启后 `dumpsys package com.miui.carlink` 同时列出 `/system/app/CarWith` 与数据版覆盖，实际包包含
`SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA UPDATED_SYSTEM_APP`。数据版 `codePath` 仍位于 `/data/app` 是正常覆盖关系。

### 阻塞一：Qualcomm 性能服务调用使应用启动 ANR

- 11:25:09–11:25:34，mcarplay 前台自检记录 `entry=REQUESTED`、`background=false`，25 秒内连接尝试 63 次，
  7240 始终 `ECONNREFUSED`，`allConnectedOnce=false`，视频、关键帧与字节数均为 0。
  `REQUESTED` 仅表示启动请求已提交；摘要中的初始 `state=CONNECTED` 也不能替代七路连通或媒体证据。
- 系统将跨应用启动转给安全中心的 `ConfirmStartActivity`，但安全中心自身启动 ANR。直接由 ADB 请求 CarWith 导出入口也启动 ANR；
  11:41 在亮屏、解锁状态复测仍因 `failed to complete startup` 被终止，因此不能只归因于锁屏或后台启动限制。
- 两份 ANR 都显示主线程等待 `ActivityThreadImpl.waitHandlerThread()`；`ActivityHelper` 线程则同步阻塞在
  `IPerfManager$Stub$Proxy.setClientBinder()` → `Performance.connectPerfServiceLocked()` → `BoostFramework` 初始化。
  对该镜像的 `QPerformance.jar` 做静态检查，确认调用的是 `vendor.perfservice`。
- 利用模拟器已有 root 停止再启动性能服务后，CarWith 入口恢复。最终 `service list` 含
  `vendor.perfservice: [com.qualcomm.qti.IPerfManager]`，`ps` 中 `perfservice` 存活；该服务与 CarWith 持续共存时完成了下述
  142 秒出流。现有证据足以把它列为启动 ANR 的直接环境阻塞，但尚不足以断言原生服务内部的具体死锁原因。

### 阻塞二：安全中心拒绝 CarWith 的 CTA 授权入口

入口能够运行后，CarWith 原先仍是普通用户应用，11:43、11:51 及 11:58 均出现：

```text
CarlifeAdbActivity: onCreate CarlifeAdbActivity
SystemAppPDA: can't launch this for out of whiteList, com.miui.carlink
PermissionActivity: onActivityResult ...-2
```

对安全中心与 CarWith APK 的静态核对表明，CarWith `PermissionActivity` 请求
`miui.intent.action.SYSTEM_PERMISSION_DECLARE_NEW`；安全中心最终接受的任一入口包括内置 CTA 名单、系统应用/系统 UID，
或该 CTA 权限。普通安装身份未通过检查，`-2` 来自白名单拒绝，并非用户点击了拒绝。

本轮的解决办法是让 CarWith 保持原 APK、原签名和用户数据，通过模块获得系统应用身份；没有修改安全中心白名单，
也没有伪造 CTA 权限。重启后安全中心正常打开 CarWith 隐私授权页，用户点击同意后记录
`SystemAppPDA: user agreed! com.miui.carlink` 与 `PermissionActivity: carlife adb connect`，下一轮自检的
`CARLIFE-DIAG event=ENV` 也变为 `systemApp=true updatedSystemApp=true`、`mcarplay.backgroundStartAllowed=true`。

AVD 只有约 2.5 GiB 物理内存。一次较慢的 CTA 操作期间，`lowmemorykiller` 曾以 `oom_score_adj=701` 杀死
`com.miui.carlink`。这不是 CTA 逻辑失败；重新启动并尽快完成同意即可。成功轮中 CarWith 约占用 287–365 MiB，
142 秒内未被 LMKD 杀死，但系统持续回收了天气、设置等低优先级进程。若要求重复冷启动稳定，建议给该 AVD 分配 6 GiB 内存。

### 七路握手与 H.264 验证

mcarplay 保持前台并点击「CarWith 出流自检」后，12:17:25.114 记录 `entry=REQUESTED background=false`；
12:17:26.361 已在 4 次尝试、1,244 ms 内完成：

```text
openedThisAttempt=7240,8240,9240,9241,9242,9340,9440
allConnectedOnce=true timedOut=false
```

随后完成协议握手与准入：

1. 收到前台请求并回 `GO_TO_FOREGROUND`；
2. 发送设备信息，完成 RSA 公钥请求与加密 AES 种子交换；
3. 收到 `ENCRYPT_READY`，手机渠道准入结果为“通过”；
4. 发送视频初始化请求，收到 `VIDEO_INIT_DONE 1280x720@30`；
5. 首个访问单元为关键帧，随后帧数稳定增长。

到 12:19:48.293 的最后一条周期统计，已连续运行约 142 秒：

```text
视频帧 #2001 1280x720 frames=2001 key=51 bytes=31378042 span=142s
```

同一时间 CarWith、小米 CarLife 与 mcarplay 进程都存活，七个监听端口仍在。该结果排除了：

- Qualcomm 性能服务导致的入口 ANR；
- 安全中心 CTA 白名单拒绝；
- CarWith 七路监听未创建；
- RSA/AES/手机渠道准入失败；
- 1280×720@30 H.264 数据未到达 mcarplay。

自检只统计访问单元，没有保存可直接交给 `ffprobe` 的连续 Annex-B 文件；也没有 CarPlay 接收端，因此本轮不能证明
接收端解码、声音或触控通过。日志里 CarLife 的 VOICE 通道首先收到零字节数据，音频没有形成有效 PCM。

测试结束时通过 `am force-stop com.mcarplay.app` 停止 mcarplay；随后设备侧七口全部消失。完整运行日志保存在本机
git 忽略范围的下载文件中，以上为脱敏摘要。

### 复现与排障顺序

1. 在修改系统盘前保存 AVD 快照；启动 KernelSU 与 `meta-overlayfs`，确认 `service list` 可注册
   `vendor.perfservice` 且进程存活。
2. 把 CarWith 原 APK 放入普通 `/system/app/CarWith` 模块并重启，确认包标志包含 `SYSTEM` 与
   `UPDATED_SYSTEM_APP`；再安装原版小米 CarLife 8.6.8。
3. 保持 mcarplay 在前台，直接点「CarWith 出流自检」。CTA 页出现后迅速同意，避免 2.5 GiB 默认镜像在等待期间触发 LMKD。
4. 用 `adb shell su -c "ss -lnt"` 核对七口。看到 `LISTEN` 后继续看
   `adb shell run-as com.mcarplay.app tail -n 200 files/main_debug.log`，至少等到
   `LISTENERS_CONNECTED allConnectedOnce=true`、`VIDEO_INIT_DONE`，以及 `frames`/`bytes` 持续增长。
5. 若仅有 `REQUESTED` 且入口长时间无 `CarlifeAdbActivity.onCreate`，先查性能服务 ANR；若入口立即退出并出现
   `SystemAppPDA ... whiteList` / `-2`，查 CarWith 系统应用身份与 CTA；若七口已监听但无媒体，才进入 mcarplay
   协议层排查。

### OS1/OS2 边界

在上述 OS3 排查结束时，HyperOS-AVD 公开发布只提供 OS3 / Android 16 与 OS4 / Android 17，没有 OS1 或 Android 13 镜像。
之后另建的 OS1 混合 AVD 排查见下一节。HyperOS 版本号也不能单独推出 Android API：
同一大版本会因机型和固件落在不同 Android 基线，必须记录具体设备、HyperOS 版本、`ro.build.version.sdk`。

普通 Android 13 AOSP AVD 只能验证 APK 的基础 API 兼容性，缺少小米安全中心、CTA、性能服务和对应系统权限，不能代替
OS1 结论。要精确复现 OS1 + Android 13，需要一台对应机型的实机，或为该机型制作包含匹配 kernel/vendor 的可启动系统镜像；
仅把 CarWith、CarLife 和安全中心 APK 推进通用模拟器不构成等价环境。OS2 同样需要对应设备固件实测，当前没有可引用的
OS2 AVD 或视频流证据。该段是当时状态；后续 OS2 / Android 15 完整矩阵见文末的跨系统兼容矩阵。

## HyperOS 1 / Android 13 混合 AVD 排查（2026-11-03）

本轮没有操作在线实体设备或车机。单独构建 `HyperOS_1_Thyme_A13`、端口 `5582`，将小米 10S 固件的
`system`、`system_ext`、`product` 和 `mi_ext` 与 Android 13 AVD 的 ranchu vendor/kernel 组合；为启动 framework
修补了模拟器镜像中的灯光服务等兼容项。设备属性设置为 `thyme` / `M2102J2SC`，不表示硬件为真实小米 10S。
启动后 `sys.boot_completed=1`，可 `adb root`；AVD 配置内存 6144 MiB，`MemTotal=6057224 kB`，SELinux 为 permissive。

| 项目 | 本轮环境与 SHA-256 |
| --- | --- |
| ROM | `OS1.0.4.0.TGACNXM`，Android 13 / SDK 33；Fastboot 包 `5cf9f192f9eee191e5f74307af7785a8df27a0c6f0eb257dbd606688603c0ad7` |
| 原厂 CarWith | `2.0.1-20231207` / `102000001`，系统应用；APK `a3d082b867d15a1a2303a791b9c2522e7298afc3e3c07a838a4a7f6e2a51515c` |
| 小米 CarLife | `8.6.8` / `260`；APK `ab7b001a7b3eba95819da6808b29e2038629b30f2bbe3a363256e34ff7cbd4ee` |
| mcarplay | standard/debug `0.1.3-debug`；APK `ab1517d7607197d3b29e84655aeb0b57aab6f8693c811db7f6ce4427fa00b0c8` |
| 对照 CarWith 更新 | `4.0.4-20260522` / `104000004`，原系统应用上的同签名更新；APK `a20bd237534d82df08d561974365dc7da66e36899d3e64cc1b137cac0b084605` |

### 原厂 2.0.1 入口及受控探针

- mcarplay 前台自检立即报告 `ENTRY=MISSING`、`listeners=not_started`。检查 APK manifest 确认原厂 2.0.1
  没有声明 mcarplay 所调用的 `com.miui.carlink.castfwk.usb.CarlifeAdbActivity`；这是一处已证实的入口差异，
  不能单独推出协议不兼容。
- 为继续观察底层链路，以 root / ADB 强制启动 CarWith 的非导出内部服务并指定 `Baidu_ap`，再使用仓库现有
  Python CarLife 探针连接；这不是 mcarplay 无 root 的普通启动方式，也不是正常无线连接。七个端口 `7240`、
  `8240`、`9240`、`9241`、`9242`、`9340`、`9440` 均连通，探针报告 RSA 验证、AES 和手机渠道准入成功，
  收到视频初始化响应并发送启动消息，随后控制通道关闭；总计 `video packets=0 bytes=0`。
- CarLife 创建了 `c2.android.avc.encoder`（1280×720、15 fps），两次周期统计均为 `DQoutput: 0 success out of
  99 tries`。CarWith 的状态机记录 `MSG_CASTCONTROLLER_CAST_FAILURE`；现有日志未确定失败原因，不能仅据此认定
  虚拟显示从未建立。独立 `screenrecord` 对模拟器主屏的短测产生了可由 `ffprobe` 识别的 H.264、720×1280、2 帧，
  只说明基础屏幕编码能产出数据，不证明 CarLife 的虚拟显示链路或持续出流正常。
- 混合镜像中的 Wi-Fi 和蓝牙服务出现崩溃；探针运行时为抑制 Wi-Fi 反复崩溃而关闭了模拟器 Wi-Fi。该环境缺少原机
  Qualcomm vendor/kernel，且使用 permissive 与 root 强制入口，因此零帧不能归因于 OS1、旧版 CarWith 或 mcarplay
  协议中的某一项，也不能作为实体手机不支持的证据。

### 4.0.4 对照复测：持续收到视频

覆盖安装后 CarWith 仍具有 `SYSTEM UPDATED_SYSTEM_APP` 标志；原厂 2.0.1 系统 APK 保留。首次用户须知等待期间的
25 秒监听超时不能视为该版本无法出流的结论。用户处理须知后，通过 ADB 模拟点击 mcarplay 前台的「CarWith 出流自检」，
16:04:26 的新会话由 mcarplay 自身的应用 Intent 请求 CarWith 4.0.4 公开入口；没有用 root 强制启动 CarWith 内部服务。

- 约 1.2 秒内连通七路：`7240,8240,9240,9241,9242,9340,9440`，记录 `allConnectedOnce=true timedOut=false`。
- 收到 RSA 公钥请求和加密 AES 种子、`ENCRYPT_READY`，手机渠道准入通过，随后记录 `VIDEO_INIT_DONE 1280x720@30`。
- 16:04:27 收到首个视频访问单元并识别为关键帧；到 16:09:28 的周期记录为 `frames=4481 key=113 bytes=71549028
  span=301s`。观察期间 CarWith、CarLife、mcarplay 进程存活，七个监听端口仍在。
- 运行中 `dumpsys display` 记录 CarWith 自有的 1280×720 虚拟屏处于 `ON`。协议初始化返回 30 fps，而 CarLife 的
  `c2.android.avc.encoder` 配置为 20 fps；按上述访问单元和时间粗算约 15 个/秒，不将返回值当作实测帧率。
- 该自检未保存可供独立解码的连续视频文件，VOICE 通道只看到零字节记录，没有有效音频证据。没有 CarPlay 接收端，
  因此不能证明车机解码、声音或触控通过；也不能将 4.0.4 的结果归给原厂 2.0.1。

本轮没有修改 mcarplay 源码或协议代码；没有 CarPlay 接收端、有效音频、触控及真实手机验证。该轮没有 OS2 结果；
后续 OS2 / Android 15 完整矩阵见文末的跨系统兼容矩阵。

## CarWith 2.x / 3.x 版本矩阵（2026-11-03）

在上述同一 OS1 / Android 13 混合 AVD、CarLife `8.6.8/260`、mcarplay `0.1.3-debug` 环境下，测试了 37 个
2.x / 3.x 独立构建。来源为公开历史版本目录与用户从 Telegram 公开附件下载的 APK；逐个用 `apksigner verify`
核对包名与版本，签名证书 SHA-256 均为小米 ROM 原厂 CarWith 的
`c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`。相同版本号不同日期的构建单列。
这些是本轮取得的构建，不代表小米发布过的全部内部版本。

3.1.2 及之后的自检从用户已同意条款的干净 3.1.2 数据快照升级，旧版分别清理测试数据。早期复用 4.0.4 数据
触发的 `SQLiteException: Can't downgrade database from version 6 to 3` 已排除，未计入结果。自检通过指当前实际
日期下 mcarplay 调用公开入口，七路、RSA/AES、手机渠道准入与 `VIDEO_INIT_DONE 1280x720@30` 均成功，且视频
访问单元与字节至少增长 20 秒；帧数是日志中的访问单元计数，不是独立解码或车机显示验收。

| CarWith 构建 | 当前日期下 mcarplay 自检 | 补充对照 |
| --- | --- | --- |
| `2.0.0-20231103` | 缺当前入口 | root 探针：有效期内七路已连、0 视频包 |
| `2.0.1-20231129` | 缺当前入口 | root 探针：有效期内七路已连、0 视频包 |
| `2.0.1-20231207` | 缺当前入口 | root 探针：七路已连、0 视频包 |
| `2.1.0-20240118` | 缺当前入口 | root 探针：有效期内七路已连、0 视频包 |
| `2.1.1-20240131` | 缺当前入口 | root 探针：有效期内七路已连、0 视频包 |
| `2.1.2-20240301` | 缺当前入口 | root 探针：七路已连、0 视频包 |
| `2.2.0-20240322` | 缺当前入口 | root 探针：有效期内七路已连、0 视频包 |
| `2.2.1-20240401` | 缺当前入口 | root 探针：七路已连、0 视频包 |
| `2.2.2-20240429` | 缺当前入口 | root 探针：有效期内七路已连、0 视频包 |
| `2.2.2-20240509` | 缺当前入口 | root 探针：七路已连、0 视频包 |
| `2.3.0-20240606` | 缺当前入口 | root 探针：有效期内七路已连、0 视频包 |
| `2.3.1-20240712` | 缺当前入口 | root 探针：有效期内收到 557 个视频包 |
| `3.0.1-20240722` | 缺当前入口 | root 探针：当前日期下收到 515 个视频包 |
| `3.0.2-20240801` | 缺当前入口 | root 探针：当前日期下收到 568 个视频包 |
| `3.1.2-20240828` | 视频 257 个访问单元 / 21 秒 | — |
| `3.1.4-20240904` | 视频 263 个访问单元 / 21 秒 | — |
| `3.1.5-20240911` | 视频 218 个访问单元 / 20 秒 | — |
| `3.2.0-20241009` | 视频 274 个访问单元 / 21 秒 | — |
| `3.2.1-20241105` | 已过期，当前日期下退出 | 有效期内视频 279 / 21 秒 |
| `3.3.0-20241114` | 视频 244 个访问单元 / 22 秒 | — |
| `3.3.2-20241210` | 视频 343 个访问单元 / 24 秒 | — |
| `3.4.0-20250120` | 视频 317 个访问单元 / 20 秒 | — |
| `3.4.1-20250227` | 视频 368 个访问单元 / 20 秒 | — |
| `3.4.2-20250319` | 已过期，当前日期下退出 | 有效期内视频 346 / 22 秒 |
| `3.4.2-20250402` | 已过期，当前日期下退出 | 有效期内视频 304 / 20 秒 |
| `3.5.0-20250407` | 视频 340 个访问单元 / 22 秒 | — |
| `3.5.2-20250521` | 视频 336 个访问单元 / 24 秒 | — |
| `3.5.3-20250528` | 已过期，当前日期下退出 | 有效期内视频 348 / 22 秒 |
| `3.6.0-20250612` | 视频 330 个访问单元 / 21 秒 | — |
| `3.6.2-20250815` | 已过期，当前日期下退出 | 有效期内视频 268 / 22 秒 |
| `3.7.2-20250923` | 视频 334 个访问单元 / 22 秒 | — |
| `3.8.0-20251113` | 视频 265 个访问单元 / 21 秒 | — |
| `3.8.2-20260117` | 4 个访问单元后 CarWith 崩溃 | 同环境四次复现 |
| `3.8.3-20260203` | 视频 285 个访问单元 / 23 秒 | — |
| `3.8.4-20260302` | 已过期，当前日期下退出 | 有效期内视频 295 / 24 秒 |
| `3.8.4-20260313` | 已过期，当前日期下退出 | 有效期内视频 289 / 24 秒 |
| `3.8.4-20260320` | 已过期，当前日期下退出 | 有效期内视频 296 / 24 秒 |

### 两类补充对照

**过期构建。** 反编译 `UCarApplication` 后可见这些内测包内置了截止日期：到期后应用主动退出，
较新的版本还会弹出「CarWith内测版本已到期，请卸载更新后使用」并跳到应用详情页。已确认的截止日期包括
`2.0.0-20231103`（2023-12-10）、`3.2.1-20241105`（2024-11-22）、`3.4.2-20250319`（2025-04-11）、
`3.4.2-20250402`（2025-04-20）、`3.5.3-20250528`（2025-06-18）、`3.6.2-20250815`（2025-08-24）、
`3.8.4-20260302`（2026-03-17）、`3.8.4-20260313`（2026-03-31）、`3.8.4-20260320`（2026-04-09）。
为区分过期与链路兼容性，只把隔离 AVD 的时钟临时调到构建有效期内，再运行同一自检；这八项在有效期内
全部通过七路、认证、准入与视频初始化，之后恢复当前时间。对照不改变这些构建在真实当前日期下已过期的事实。

**缺入口构建。** 另有 14 个 2.0–3.0 构建没有声明 mcarplay 目前调用的
`com.miui.carlink.castfwk.usb.CarlifeAdbActivity`，自检报告 `ENTRY=MISSING`。为继续观察底层链路，另以
root / ADB 强制启动非导出的 `CarlinkService` 并指定 `Baidu_ap`，用 Python 探针连接七路。这不是 mcarplay 的
普通公开入口，也不是实体手机的正常无线连接，模拟器 Wi-Fi 期间关闭。

- 当前日期下，`2.0.0-20231103`、`2.0.1-20231129`、`2.1.0-20240118`、`2.2.0-20240322`、
  `2.2.2-20240429`、`2.3.0-20240606`、`2.3.1-20240712` 因过期直接退出，未观察到监听；
  `2.0.1-20231207`、`2.1.1-20240131`、`2.1.2-20240301`、`2.2.1-20240401`、`2.2.2-20240509`
  能监听七路并完成认证，但 0 视频包。
- 把时钟调到各构建的有效期后，12 个 2.x 构建全部可以监听七路并完成认证；其中 `2.3.1-20240712`
  收到 557 个视频包（37 秒），`2.3.0-20240606` 及更早的 11 项仍是 0 视频包。
- `3.0.1-20240722` 与 `3.0.2-20240801` 在当前日期下即可收到视频，延长到 37 秒后分别收到 515 和 568 个
  视频包并通过探针自身的完整视频判定。保存的 H.264 经 ffprobe 确认为 1280×720，ffmpeg 解码无错误。

**旧版零视频的原因在设备侧。** 对 `2.2.2-20240509` 和 `2.3.0-20240606` 的 logcat 显示，CarLife 已创建
`c2.android.avc.encoder`（1280×720），但周期统计始终是 `Render: 0, Drop: 0, DQoutput: 0 success out of ~95 tries`，
即编码器从未拿到输入帧；`2.3.0` 的 logcat 中完全没有编码器创建记录。`2.3.1-20240712` 与之后的构建则
编码器持续出帧（配置 15 fps）。分界点是 `2.3.1`。该现象发生在 CarLife 侧取帧阶段，与 mcarplay 的协议实现无关，
但混合 AVD 的虚拟显示链路与实体机不同，仍不能据此断定实体机的旧版同样无画面。

### mcarplay 自身协议在旧版 CarWith 上的验证

前面所有旧版结果都来自仓库外的 Python 探针。为回答「不改 mcarplay 能否把旧版 CarWith 的流引出」，
本轮临时把 `CarLifeDiagnostics` 的 `launchStrategy` 从 `EXPORTED_ENTRY` 改为已有的 `ATTACH_EXISTING`，
重新构建并在模拟器上实测，随后已还原该文件（`git status` 中不再出现该文件）。

在 CarWith `2.3.1-20240712`（时钟调到其有效期内）上，mcarplay 自己的代码完成了完整链路：

```text
[20:03:58.426] CARLIFE: 七路连接完成，开始协议握手        attempts=1 waitMs=4 allConnectedOnce=true
[20:03:58.568] CARLIFE: 收到 RSA 公钥请求
[20:03:58.571] CARLIFE: 收到 ENCRYPT_READY
[20:03:58.572] CARLIFE: 手机渠道准入结果 通过
[20:03:58.624] CARLIFE: VIDEO_INIT_DONE 1280x720@30
[20:04:39.030] CARLIFE-DIAG event=FAILED ... video=1280x720 frames=613 key=41 bytes=1243998 span=40s
```

结束原因是 CarWith 自行退出投屏（`CastingActivity` 结束后 `System.exit`），不是 mcarplay 断开；
mcarplay 侧状态为 `VIDEO_RUNNING`，40 秒内收到 613 个访问单元、41 个关键帧、1,243,998 字节。

结论分层如下：

- **协议层无需修改。** mcarplay 既有的认证、种子交换、渠道准入、视频解析与计数逻辑在旧版 CarWith 上原样工作，
  没有为旧版写任何特例代码。
- **入口层是唯一缺口。** 旧版没有 `CarlifeAdbActivity`，当前自检固定使用 `EXPORTED_ENTRY`，因此直接报
  `ENTRY=MISSING`。`ATTACH_EXISTING` 策略在仓库里已定义但没有任何调用方；要支持旧版，需要给自检或正式会话
  增加一个「先尝试公开入口，缺失则附着」的回退分支，属于入口策略改动，不是协议改动。
- **2.3.0 及更早仍不可用。** 用 `ATTACH_EXISTING` 复测 `2.0.1-20231129`：CarWith 短暂监听了七路后自行退出，
  与 root 探针的零视频结论一致。旧版的零视频是设备侧编码器拿不到输入帧，不是 mcarplay 的问题。

### 3.8.2 崩溃定位

`3.8.2-20260117` 四次均在完成七路、RSA/AES、渠道准入与 `VIDEO_INIT_DONE` 后，只收到 1–4 个访问单元，
随后 CarWith 的 `CarlinkSM` 线程崩溃：

```text
FATAL EXCEPTION: CarlinkSM
java.lang.NullPointerException: Attempt to invoke virtual method 'void android.view.Surface.setFrameRate(float, int)' on a null object reference
    at com.miui.carlink.castfwk.CastController.displayConnectedInNormalCast(CastController.java:157)
    at com.miui.carlink.castfwk.CarlinkStateMachine$i.c(CarlinkStateMachine.java:188)
```

对照反编译代码：`displayConnectedInNormalCast` 在 SDK<34 分支调用 `mSurface.setFrameRate(i14, 0)`，
而调用方 `displayConnected` 中的 `mSurface = surface` 赋值发生在该方法返回之后，首次连接时 `mSurface` 仍为 null。
`3.8.3` 把这一行改为使用传入的 `surface`，同环境自检有持续视频。该关联来自模拟器 logcat 与静态代码比较，
仍需实机确认。

### 边界

本轮未修改 mcarplay 的 Kotlin 或协议源码。AVD 使用 ranchu vendor/kernel、root、SELinux permissive，Wi-Fi 因
混合镜像问题关闭；没有真实手机和车机的画面、声音、触控或长时间连接证据，也没有 OS2 测试结果。
过期对照使用修改过的模拟器时钟，root 探针使用非公开入口，两者都不能直接代表实体机的普通使用路径。
第三方 APK、logcat 与 H.264 文件均留在仓库外。

## CarWith 2.x / 3.x / 4.x 跨系统兼容矩阵（2026-11-04）

本轮把同一批 50 个 CarWith 构建分别放入 OS1–OS3 的独立 AVD，并按用户要求让 OS4 只从该系统的初始 CarWith
`4.0.16-20260907` 开始测，不再向上追测 2.x 或更早的 4.x。全部系统固定使用原签名 CarWith、小米 CarLife
`8.6.8/260`。OS1–OS3 矩阵使用 mcarplay standard/debug `0.1.3-debug`，APK SHA-256 为
`42a827a792ef843a92d9d10e7d58ae56b8b148a0c13750ae0dba8ce0d2908aba`；OS4 最后又在当前完整构建
`dff6481f484e04ec6c9ba89c7209b91b7998fd0221f6a249cc89d148f2562b7c` 上重跑 5 项。CarLife APK SHA-256 为
`ab7b001a7b3eba95819da6808b29e2038629b30f2bbe3a363256e34ff7cbd4ee`。

| 系统 | Android / SDK | 固件 | 测试范围 | Root / SELinux |
| --- | --- | --- | --- | --- |
| OS1 | 13 / 33 | `OS1.0.4.0.TGACNXM`，thyme 系统分区 + Android 13 ranchu vendor/kernel | 50 个构建 | `adb root` / permissive |
| OS2 | 15 / 35 | `OS2.0.215.0.VMCCNXM`，fuxi 系统分区 + Android 15 ranchu vendor/kernel | 50 个构建 | `adb root` / permissive |
| OS3 | 16 / 36 | `OS3.0.2.0.WMCCNXM` | 50 个构建 | KernelSU `3.3.0` / permissive |
| OS4 | 17 / 37 | `OS4.0.24.0.XFRCNXM`，6 GiB、4 核 | 从系统初始版开始的 5 个构建 | KernelSU `3.3.1` / enforcing |

每个成功项均要求同时具备：公开入口/旧版 root 服务的 `ENTRY=REQUESTED`，或附着现有会话的 `ENTRY=ATTACHED`、七口
`7240/8240/9240/9241/9242/9340/9440` 全连、RSA、AES `ENCRYPT_READY`、手机渠道准入通过、
`VIDEO_INIT_DONE 1280x720@30`，并持续至少 20 秒收到 H.264 访问单元、关键帧和非零字节。下表“出流”只代表
mcarplay 收到源端媒体，不代表 CarPlay 接收端已解码或有声音。

| CarWith 构建 | OS1/A13 | OS2/A15 | OS3/A16 | OS4/A17 |
| --- | --- | --- | --- | --- |
| `2.0.0-20231103` | 0帧 | 0帧 | 0帧 | — |
| `2.0.1-20231129` | 0帧 | 0帧 | 0帧 | — |
| `2.0.1-20231207` | 0帧 | 0帧 | 0帧 | — |
| `2.1.0-20240118` | 0帧 | 0帧 | 0帧 | — |
| `2.1.1-20240131` | 0帧 | 0帧 | 0帧 | — |
| `2.1.2-20240301` | 0帧 | 0帧 | 0帧 | — |
| `2.2.0-20240322` | 0帧 | 0帧 | 0帧 | — |
| `2.2.1-20240401` | 0帧 | 0帧 | 0帧 | — |
| `2.2.2-20240429` | 0帧 | 0帧 | 0帧 | — |
| `2.2.2-20240509` | 0帧 | 0帧 | 0帧 | — |
| `2.3.0-20240606` | 0帧 | 0帧 | 0帧 | — |
| `2.3.1-20240712` | 出流 | 出流 | 出流 | — |
| `3.0.1-20240722` | 出流 | 出流 | 出流 | — |
| `3.0.2-20240801` | 出流 | 出流 | 出流 | — |
| `3.1.2-20240828` | 出流 | 出流 | 出流 | — |
| `3.1.4-20240904` | 出流 | 出流 | 出流 | — |
| `3.1.5-20240911` | 出流 | 出流 | 出流 | — |
| `3.2.0-20241009` | 出流 | 出流 | 出流 | — |
| `3.2.1-20241105` | 出流 | 出流 | 出流 | — |
| `3.3.0-20241114` | 出流 | 出流 | 出流 | — |
| `3.3.2-20241210` | 出流 | 出流 | 出流 | — |
| `3.4.0-20250120` | 出流 | 出流 | 出流 | — |
| `3.4.1-20250227` | 出流 | 出流 | 出流 | — |
| `3.4.2-20250319` | 出流 | 出流 | 出流 | — |
| `3.4.2-20250402` | 出流 | 出流 | 出流 | — |
| `3.5.0-20250407` | 出流 | 出流 | 出流 | — |
| `3.5.2-20250521` | 出流 | 出流 | 出流 | — |
| `3.5.3-20250528` | 出流 | 出流 | 出流 | — |
| `3.6.0-20250612` | 出流 | 出流 | 出流 | — |
| `3.6.2-20250815` | 出流 | 出流 | 出流 | — |
| `3.7.2-20250923` | 出流 | 出流 | 出流 | — |
| `3.8.0-20251113` | 出流 | 出流 | 出流 | — |
| `3.8.2-20260117` | CarWith 崩溃 | 出流 | 出流 | — |
| `3.8.3-20260203` | 出流 | 出流 | 出流 | — |
| `3.8.4-20260302` | 出流 | 出流 | 出流 | — |
| `3.8.4-20260313` | 出流 | 出流 | 出流 | — |
| `3.8.4-20260320` | 出流 | 出流 | 出流 | — |
| `4.0.2-20260429` | 出流 | 出流 | 出流 | — |
| `4.0.2-20260515` | 出流 | 出流 | 出流 | — |
| `4.0.2-20260528` | 出流 | 出流 | 出流 | — |
| `4.0.4-20260522` | 出流 | 出流 | 出流 | — |
| `4.0.6-20260603` | 出流 | 出流 | 出流 | — |
| `4.0.9-20260629` | 出流 | 出流 | 出流 | — |
| `4.0.12-20260707` | 出流 | 出流 | 出流 | — |
| `4.0.14-20260801` | 出流 | 出流 | 出流 | — |
| `4.0.16-20260907` | 出流 | 出流 | 出流 | 出流 |
| `4.0.20-20260929` | 出流 | 出流 | 出流 | 出流 |
| `4.0.20-20261009` | OS3 门禁 | OS3 门禁 | 出流 | OS3 门禁 |
| `4.0.21-20260929` | 出流 | 出流 | 出流 | 出流 |
| `4.0.23-20261016` | 出流 | 出流 | 出流 | 出流 |

汇总：OS1 为 37/50 出流，OS2 为 38/50，OS3 为 39/50；OS4 的指定 5 个测试点为 4/5。三个系统都复现了
`2.3.0` 及更早 11 个构建的编码器 0 帧，说明该边界跨越 Android 13–16，且此前的 CarLife 编码器诊断不是 OS1
混合镜像的一次性现象。`3.8.2-20260117` 只在 OS1 崩溃；OS2/OS3 同 APK 持续出流，因此不是 mcarplay 的解密或
视频解析问题。OS2 / OS3 的 3.x、4.x 全部出流（OS2/OS4 的 OS3-only 构建除外）。

### OS4 系统初始版本起点

早期 OS4 的 4 条 `listener_timeout` 是在 mcarplay 首次免责声明尚未接受时产生，不能作为 CarWith 兼容结果。
本轮在 1120×2436 模拟屏上将声明滑至底部并接受，确认主界面
`com.mcarplay.app/.MainActivity` 正常前台后重建测试目录并重跑。有效结果为：

| CarWith 构建 | frames | key | bytes | span |
| --- | ---: | ---: | ---: | ---: |
| `4.0.16-20260907` | 276 | 10 | 4,910,918 | 22 s |
| `4.0.20-20260929` | 336 | 9 | 4,802,876 | 22 s |
| `4.0.20-20261009` | 0 | 0 | 0 | 0 s |
| `4.0.21-20260929` | 338 | 9 | 4,943,798 | 22 s |
| `4.0.23-20261016` | 334 | 9 | 4,902,309 | 22 s |

四个成功项的 `entry_result` 均为 `REQUESTED`，`all_channels/rsa/aes_ready/admitted` 均为 true，
`video_init` 均为 `1280x720@30`。没有 `4.0.20-20261029` 这个测试项；本轮测试的是公开取得的
`4.0.20-20260929` 与 `4.0.20-20261009`。

### `4.0.20-20261009` 是 OS3-only 构建

该 APK 在 OS1、OS2、OS4 均表现为入口请求成功但 CarWith 进程立即退出；OS3 / SDK 36 同一 APK 正常出流。
reproducible 两次且 installed `base.apk` SHA-256 与测试 APK 一致，排除了安装未更新。

反编译该 APK 的 `com.carwith.launcher.UCarApplication` 可见：

```java
public static boolean v() {
    return Build.VERSION.SDK_INT == 36;
}

// onCreate()
if (s(2026, 10, 24)) {
    System.exit(0);
    return;
}
```

`s(...)` 在 `v()` 为 false 时显示“该CarWith测试版本只能用于OS3”并返回 true，随后进程主动 `System.exit(0)`。
OS4 logcat 中对应进程在 `onCreate CarlifeAdbActivity` 之前以 status 0 退出，故 7240 始终
`ECONNREFUSED`。这是 CarWith 自身设置的平台门禁。mcarplay 的协议与入口代码即使继续等待也无法解除；直接启动
CarWith 内部服务仍会先执行同一个 `Application.onCreate`。把它当普通 OS4 CarWith 更新测试会得到假失败。

### 边界与复现

- 50 个构建是当前公开取得的集合，不代表小米发布过的全部内部版本。
- OS1–OS3 使用 ranchu vendor/kernel 并与 Xiaomi system 混合，且 permissive；OS4 虽为 enforcing，仍是 AVD
  vendor/kernel。结果证明相应软件栈可建立源端媒体链路，不证明实体 Qualcomm vendor、真实车机、接收端解码、声音或触控。
- 2.x 旧版没有当前公开入口时，矩阵用 root 启动内部服务后由 mcarplay `ATTACH_EXISTING` 连接；这是受控探针，
  不等价于无 root 的正常用户流程。
- 测试脚本把隔离 AVD 时钟临时调到各内测构建有效期内，并在结束后恢复；这不改变构建在当前真实日期已到期的行为。
- 复现入口为仓库外的 `run_carwith_matrix.py`。有效结果目录分别为：
  `os1-thyme-a13/carwith-matrix/v-final`、`os2-fuxi-a15/carwith-matrix/v-final`、
  `instances/HyperOS_3/carwith-matrix/v-final`、`instances/HyperOS_4/carwith-matrix/system-base-v3`。
  OS4 的 `system-base-v1` 含未接受免责声明造成的无效失败，不作为兼容证据；`system-base-v2` 是较旧 mcarplay 构建，
  已由 `system-base-v3` 的当前构建复测取代。

## 入口兼容代码整理（2026-11-04）

正式会话与独立自检统一使用 `COMPATIBLE`：先连接现有七口，第一次连接失败后请求公开入口；入口缺失或不可调用时，
先确认 `su` 或已授权的 Shizuku 进程 UID 为 0，再启动原厂 `CarlinkService`，否则打开已导出的 CarWith 设置页，
提示用户选择百度 CarLife 无线连接。普通 UID 2000 不会被当作 root；命令返回失败仍报告失败；用户停止后不再发起后续入口。
入口过程在源连接线程中执行，不在 UI/媒体线程等待进程。不打开仅启动监听而不创建 CarWith 虚拟屏的百度 CarLife 首页。

这些变更补齐旧版没有 `CarlifeAdbActivity` 的启动路径，不改变认证、AES、视频解析、第三方 APK、系统权限或内测截止日期。
上述矩阵来自已记录的 AVD 和 APK 构建；本次整理还修正了 UID 检查与取消行为，最终提交构建尚未重新跑完整 AVD 矩阵或实体手机。
回归覆盖已有七口不重启入口、首次连接失败后的重试、取消、入口层级选择、非 root 拒绝和 root/Shizuku 服务结果。

## 车机离线后整夜出站探测与未选目标时的车机识别（2026-11-04，代码修正，未在车上验证）

来源：2026-11-03/04 两晚的 remote 调试日志（脱敏），手机 `2509FPN0BC`，车机为上节奇瑞艾瑞泽 8（蓝牙名 `Cloudrive-*`，SDP 未声明 iAP2 `…caff`，手机主动连入约 100 ms 即 `READ_FAILED`，需车机回连）。

- **现象一**：会话中车机 ACL 已断开，推流终止后日志为「CarPlay 会话结束（推流终止）——恢复蓝牙探测」，此后 `Iap2BluetoothClient` 约每 60 s 主动连车机一次；00:16–10:18 每小时约 55 次 `BT-DIAG … event=CONNECT_END outcome=FAILED reason=READ_FAILED`，只在 Doze 期间中断，连续两晚。会话结束只清除了 `BtSessionGate.sessionActive`，没有任何条件让出站循环在车机离线后停下。
- **修正**：清单接收器 `CarBluetoothReceiver` 记录每台设备最近一次 ACL 广播（`BtAclTracker`，与「上车自动连接」开关无关）。出站循环只在以下情况发起连接（`OutboundProbePolicy`）：该设备 ACL 已连上；用户点「连接」或 ACL 自动拉起后的 3 分钟窗口；推流终止后的 2 分钟宽限。窗口打开之后收到该设备的 ACL 断开即停，但本应用自己主动断开 HFP/A2DP 或发起出站 RFCOMM 期间（及其后 10 s 内）的 ACL 断开不算车机离线，重试照常进行到窗口结束（PR #29 审查补充）；本进程未见过 ACL 广播时，窗口过后以 HFP/A2DP 是否在线兜底（每设备 30 s 复查一次）。暂停期间只读内存状态，不发起蓝牙连接；车机重新上电时 ACL 连上，接收器照常拉起会话。
- **现象二**：未选目标车机时，`isCarDevice` 只按名称关键词（BMW/MINI/AUDI/PORSCHE/MERCEDES/BENZ/VOLVO/CARPLAY）判断，用户日志中 `Cloudrive-023564` 被记为「AUTO: 忽略非车机蓝牙」，另见 `VW BT 7094`、`i8` 等车机名。
- **修正**：判定移到纯函数 `CarDeviceClassifier.isCarDevice`。蓝牙设备类别为音视频大类的车载音响 `0x0420` 或免提 `0x0408` 时直接认作车机；音视频大类其它小类（耳机 `0x0404` 等）与可穿戴大类（手表）直接排除，即使名称含关键词。手机 `0x020C`、未分类 `0x1F00`、电脑及类别未知时仍按关键词兜底（台架接收端常跑在手机/电脑上），关键词新增 `Cloudrive`、`VW BT`。日志中的 `0x0404`、`0x020C`、`0x1F00` 设备在名称不含关键词时仍被忽略；`i8` 只有在系统报告车载/免提类别时才会被识别。

验证范围：JVM 单元测试与完整门禁。**未在车上验证**：车机上电时 ACL 广播能否把暂停中的出站循环恢复、奇瑞以外车机的设备类别、以及手机主动 RFCOMM 连接本身是否会产生短暂 ACL 连上广播，均需下一次实车日志确认。

## WinPlay：main 合入 `feat/vw-passat-2025` 后的台架回归（2026-11-04 15:08–15:12，UTC+8）

本轮安装合并提交 `c9b200f`（main `70fcad3` 合入 `feat/vw-passat-2025` `6e650da`）的 remote debug 包，版本 `0.1.3-rd-debug`
（versionCode 1030000）；构建前完整门禁与 remote debug 构建通过。证据为手机导出的诊断报告（原始未脱敏，只留在本机，未纳入仓库）
与用户反馈「WinPlay 已过」。

手机：Xiaomi `2509FPN0BC`，Android 17（SDK 37），HyperOS `OS4.0.4.0.XPBCNXM`，CarWith `4.0.23-20261016`（104000023），
小米 CarLife `8.8.5`（274）。接收端为 WinPlay，`srcvers=950.7.1`，策略命中 `protocol.probe` 与 `winplay.1.2.4.network-handoff`；
WinPlay 与 Windows 版本未记录。

- **连接**：车机经 iAP2 回连，完整 pair-setup（M2/M4/M6）与 pair-verify 一次通过；15:08:42 开始，15:08:52 RECORD，约 11 s 出首帧。
- **视频**：按 1920×1080@60 配置，实际约 20 fps，连续 3 分 56 秒共 4649 帧；IDR 固定每 40 帧，每帧 1 片。
- **触摸**：16 条触摸报文（`hidSendReport=16`），车机命令解析失败 0 条、非 200 应答 0 条。
- **音频**：t102/media AAC-LC 48 kHz。用户确认只在最后才放歌：CarWith MEDIA（44.1 kHz 双声道，由应用重采样）15:12:42 才到达，
  到会话结束共推 356 包（约 7 s）；本机自检没有解出样本（无法证伪），听感未单独确认。
- **蓝牙**：会话期间出站探测暂停，结束后恢复；已配对的奇瑞车机（`Cloudrive-*`）与非 CarPlay 设备按预期跳过，未干扰本次连接。
- **结束**：15:12:49 接收端先关闭屏幕数据口（EOF），音频 UDP 同时被拒；约 3 s 后用户在手机上点断开，TEARDOWN 时控制通道已被接收端关闭，
  按设计直接离开接收端热点。
- 结束时还记录了「会话在链路协商完成前被车机拆掉（~10s 看门狗形态）——不记音频档位」。WinPlay 加密会话会布防 MIB3 链路，
  但不会完成控制会话，这条守卫因此每次结束都命中，后果只是不记录音频档位；main 与 `feat/vw-passat-2025` 原本如此，不是本次合并引入。

结论：合并版本在这台 WinPlay 台架上的连接、视频与触摸通过，音频只覆盖约 7 s。本次合并**未在 DiPlay、macplay 与奇瑞艾瑞泽 8 上回归**
（用户决定直接合入 main），不对这三台作新的兼容性结论。

## 大众 ID7（VW4131）：推流中的 ctrl 邀请无人响应，约 18 s 后黑屏（2026-11-04，代码修正，未在车上验证）

- **来源：** 外部黑屏诊断（`id7-blackscreen-diagnosis.md`，本仓库未收录）。车机 `VW4131`、AirPlay `sourceVersion=320.17.7`。
  这份诊断记录了 15 次断流，每次断流前都有一次 `GET /ctrl-int/1/connect` 邀请。诊断方的结论是：Cinemo 栈车机在推流中发邀请，
  意思是要重建会话；手机不响应时，车机约 18 s 后自行拆掉会话，表现为黑屏。这是二手结论，本仓库没有原始报告可以复核。
- **原行为：** `CarPlayCtrlAdvertiser` 推流中收到邀请只计数、记日志。邀请只在连接阶段被 `awaitCtrlInvite` 和
  `watchForPendingStartSession` 消费，推流期间没有任何动作。
- **对应修改（离线通过，未上车验证）：**
  - **触发条件：** 车机自报型号符合 `VWxxxx`（ID7 `VW4131`、帕萨特 2025 `VW4211`；帕萨特的厂商字段报 Toppower，所以按型号匹配），
    规则 `vw.streaming-invite-reconnect`。
  - **动作：** 推流中收到邀请时，退役当前会话，按当前端点重走连接（刚收到的邀请算新鲜邀请）。
  - **防抖：** 每次重连后 15 s 内的邀请一律忽略。
  - **其他车机：** 只多记一行日志，行为不变。
- **待验证：** 需要在 ID7（`VW4131`）和帕萨特（`VW4211`）上确认两点：推流中的邀请之后重连能出画；重连后不会被下一次邀请反复打断。

## 2026-11-05：CarWith「返回车机」对应 CarLife 命令 0x10021（推断）

- **来源：** 用户提供的 mcarplay 0.1.5 支持报告（已脱敏），接收端 DiPlay（比亚迪，srcvers 950.7.1）。
- **现象：** 用户点 CarWith 界面右上区域的「返回车机」后，约 110 ms 内日志出现 `CARLIFE: 未处理命令 kind=0x10021 len=0 fields=[]`，
  每次点按一条；此前该命令被忽略，车机仍停在 CarPlay 画面。
- **结论强度：** 仅依据时序相关，未见协议文档；`0x10021` 无载荷。
- **对应修改：** 收到后经 AirPlay `modesChanged` 把主屏与主音频报归车机。**未上车验证**，需确认车机切回原生界面与音频交还。

## 2026-11-05 帕萨特 2025（VW4211）回主界面再进 CarPlay：三个实验策略（待实车）

背景：用户反馈帕萨特回原车主界面后再点 CarPlay 图标「有概率能进去」。最新报告（app 0.1.4，会话 b9fe5a45）里视频流本身无问题
（1874/1874 帧），重建主屏流加参数集补发并不能让接收端解码器稳定重新锁定；`scheduleTakeBackCheck` 此前只记日志，失败后没有动作。

本次在「显示设置」加入四选一实验策略（默认关；**仅当主页「车型适配」手动选了「大众」且在显示设置里选了某个策略时生效**，不按车型自动启用）：

1. 失败重建：拿回主屏 3 s 内车机无触控也无要关键帧时，自动重建一次主屏流（每次点图标最多一次）；
2. 失败断开：连续 2 次无反应就结束本轮（`PASSAT:` 日志），让用户重新连接；
3. 不重建：点图标时不重建主屏流，只重申归属 + 下一个 IDR 补参数集。

**证据状态：无。** 只有单元测试（`PassatReturnPolicyTest`）与构建通过，三者都未上车。「无触控且无要帧」的判据在用户拿回后 3 s 内没碰屏时
也会成立，可能误判，策略 2 尤其如此。请每次只选一个，上车反复「回主界面再进」，导出日志看 `PASSAT:` 与 `SESSION: 拿回主屏` 两类行，
把结果（成功次数/总次数）补记到本节后再下结论。

## Pixel 5 / AOSP 七口恢复（2026-11-06，台架临时方案）

Pixel 5（LineageOS/AOSP Android 16）上，CarWith `3.0.1-20240722` 的七个 CarLife 端口一直没有出现，
mcarplay 反复得到 `7240 ECONNREFUSED`。根因不在 mcarplay 的 TCP 或协议实现，而是三层源端阻塞叠加：

1. CarWith 创建 MIUI 私有窗口类型 `2998`、`2006`，AOSP 以 `permission denied for window type 2998`
   拒绝，CarWith 在监听七口前退出；
2. 绕过正常 UI 直接启动服务时，`ConnectTypeUtil` 仍为 `none`，`CarlinkService.prepareCast()` 不进入
   CarLife Wi-Fi AP 准备路径；
3. mcarplay 切到前台后，`CastingActivity.onStop()` 调用 `e1()`，将连接重置为 `none`、结束投屏并清理任务。

台架上用 Frida 最小 hook 补齐 `wifi_ap_carlife_connect`、跳过两个私有窗口辅助调用，并在 CarLife 会话期间
阻止 `e1()` 和 `q.k("none")` 清链；再由 root 以 `startType=4`、`carlife_wireless_type=Baidu_ap` 启动
`CarlinkService`。处理后七口持续监听，mcarplay 建立七路并持续发送视频。截至 10:54（Asia/Shanghai），源端累计约
`43,821` 帧、`729 MB`、观察约 `4,361` 秒；没有据此声明接收端长时间动态画面、音频、触控或真车兼容通过。

Frida/root 仅为 bench 手段，不是产品修复或无 root 方案；它不是完整的 OS1 移植。完整原因、最小 hook、启动顺序和
验证命令见 [CARWITH-PIXEL5-PORT-RECOVERY-2026-11-06.md](CARWITH-PIXEL5-PORT-RECOVERY-2026-11-06.md)。

## 正常小米：CarWith 入口已请求但七口未出现（2026-11-06 复核 2026-11-04 报告）

复核一份 Xiaomi `M2102J2SC` / Android 13 / CarWith `4.0.4-20260522` / CarLife `8.6.8` /
mcarplay `0.1.4` 的脱敏报告。16:21–17:02 的多轮自检中，除 16:57:44 的一次成功附着外，其余各次都在 25 秒内轮询 `7240` 62 次，均为
`ECONNREFUSED`，状态为 `entry=REQUESTED background=false entryBlockedSuspected=false`。导出时
「后台弹出界面」已允许，CarWith 入口 exported/enabled、功能开关和关键权限也正常，因此不能归因于
Pixel 5 的 AOSP 私有窗口崩溃，也不能仅归因于后台启动限制。

`entry=REQUESTED` 只表示 `startActivity()` 返回，不表示 `CarlifeAdbActivity` 被创建或成功启动
`CarlinkService`；CarWith 4.0.4 的入口还可能因无线开关、非 `none` 的遗留连接状态、CTA/权限页或内部服务失败
而静默结束。报告没有 CarWith 私有偏好、Activity/进程状态、logcat 和入口页面证据，无法进一步定因。

同一报告中 16:57:44 曾附着已有的七口（13 ms、`allConnectedOnce=true`），但发送 `0x18001` 后 8 秒无视频帧，
至 16:59:07 七路全部断开，`frames=0`。这证明七口存在不等于 CarWith 已创建有效的投屏会话。完整分析和下一步
取证命令见 [CARWITH-PIXEL5-PORT-RECOVERY-2026-11-06.md](CARWITH-PIXEL5-PORT-RECOVERY-2026-11-06.md)。

## 大众 VW4131：主音频归车机时三档音频 SETUP 全部 400（2026-11-06）

- **来源：** 用户反馈“无声音”的脱敏报告 `56b663a8-6973-4ba9-88ee-213162a99143`，mcarplay `0.1.5`（standard/release）。
  手机 Xiaomi `2509FPN0BC`，Android 17（SDK 37，build `CP2A.260605.016`），CarWith `4.0.23-20261016`（104000023），
  小米 CarLife `8.6.6`（258）。接收端 `/info` 自报 Volkswagen、model `VW4131`、`sourceVersion=320.17.7`；
  复现步骤、HyperOS 版本和接收端固件版本未提供。
- **画面与触摸：** 18:46 一轮视频持续约 29 秒、发出 1081 帧；18:46:17–18:46:27 收到多条 `hidSendReport`，
  因此本轮不是视频链路整体断开。CarLife 源端只记录到 `TTS`（16 kHz 单声道），未见 `MEDIA` INIT；
  报告中的 `audioChunks` 是 CarLife 输入计数，`audioPacketsSent=0` 表示没有任何音频包写到车机。
- **音频时序：**
  - 18:41:09 与 18:41:19 两轮 `/info` 把主音频报为手机持有；`t102/media/AAC-LC/48k` 的 SETUP 均返回 200，
    但接收端随即关闭控制通道，音频发送器在首包前停止。
  - 18:42:19 起重连后，`/info` 的主屏为主车机持有、主音频为车机借用；18:42:22、18:42:24、18:42:26
    对 `t102/media AAC-LC`、`t100/media PCM`、`t100/compatibility PCM` 的 SETUP 全部返回 `400 Bad Request`。
    18:45:42 一轮只有在主屏被 `requestUI` 拿回后画面正常，主音频仍归车机，三档 SETUP 同样全部 400。
  - 因而本次“无声音”的直接原因是没有建立 AirPlay 音频流，不是包已发送但接收端静音；音频包计数为 0。
- **相关性：** 同一份报告内，主音频归手机的两次 SETUP 为 200，归车机的十次为 400。仓库已记录的真 iPhone 时序也在
  `Main audio setting up` 前先报 `mainAudio controller`。因此高置信度指向主音频资源归属，而不是 AAC/PCM 编码器；
  接收端未返回 400 错误体，不能据此声称已取得完整协议解释。
- **对应修改（离线通过，未上车验证）：** 音频 SETUP 前新增一次 `modesChanged`，只把主音频报归手机，屏幕归属保持不变；
  车机以高于 userInitiated 的 take/unborrow 约束持有或借用主音频时不抢占、保持原归属，避免截断倒车提示音等。
  此时本次不发送 SETUP，等 `changeModes` 的 UNBORROW/UNTAKE 归还主音频后重试；claim 未被确认时不把 SETUP 400 写成
  该车机的格式失败记忆。
  `ModeArbiterTest` 覆盖低约束接管、`unborrowConstraint`/`takeConstraint=1000` 拒绝抢占、归还后只重试一次，以及
  “车机持有屏幕并借用主音频”的报告形态。定向单测与 Spotless 通过。需在同一台 VW4131 上复测
  `AUDIO: 音频流建立前主音频资源已报归手机`、SETUP 状态和实际声音，才能确认修复有效。
## 2026-11-06：艾瑞泽 8 固件/协议报告转为回归矩阵（源码对照，无新增设备验收）

输入为用户提供的 `FIRMWARE_PACKAGE_ANALYSIS.md`、`CARPLAY_PROTOCOL_ANALYSIS.md`、`FIRMWARE_CARPLAY_CHAIN.md`
与 `carplay-protocol-report.html/.pdf`。当前实车 SOC `M1E_IC2172/82_03_21.387_250619_R` 与本仓库历史证据相符，
但报告中的真 iPhone 会话不能当作本轮 MCarPlay 的验证。原始包、库、日志、配对材料未复制进仓库。

- **可用结论：** iAP2 PASS、入网、Bonjour/ctrl、pair-verify、MFi auth-setup、SETUP/RECORD 与音画触控独立验收。
  报告中的 `WirelessCarPlayStatus=1` 是能力更新，Linker `Connect(0)` 是会话事件；都不等同于应用已出画。
- **计时修正：** 30.002 s 的精确起点是车机 `TimerThread begin`；手机 `BT_LINK` 只提供近似对时提示。
  失败例执行 TIMEOUT → Stop/Bonjour 注销 → END，随后上层新一次 Start；不证明同轮无缝重连或上层一定自动重试。
- **实现范围：** 补合成 iAP2 回环、诊断时间线与 AirPlay 配对/停止边界，不改变现有 CHERY 匹配、挑战长度、单次 RECORD 或媒体策略。
  没有原始 plist/TLV/pcap，不能声称新增真实报文回放或 84/88 项逐包验证。
- **断开崩溃结论收敛：** 11 月 6 日用户转述另一边分析，与 11 月 3 日本文件保存的静态分析和 13 份历史 tombstone 摘要一致；
  本次未重新复核原始库或崩溃文件。直接机制为 Finalize 后周期回调仍从 `session+0x10` 取得空 server，
  在 `_PerformPeriodTasks+640` 访问 `0xac`；约 20 s 与 server 空闲阈值吻合，区别于建链阶段的 30 s watchdog。
  蓝牙关闭顺序、type-130、真实 MAC、来源名、speechMode、HKP、auth-setup、MFi 32B、结束原因和 TCP 残留的既有对照未消除崩溃。
  重复 RECORD / 多次 SessionStart 仍是优先触发候选；一条 started 日志不能代替实际 Start/timer 计数。
  回归矩阵补充独立观察至少 60 s、进程重启检查、可复现场景至少三轮及同停止逻辑的 RECORD 单变量归因要求。
- **仍未验收：** 当前候选在车上的音画触控、首次/复用配对、动态音频、断开后 60 s 与直接重连；
  11 月 3 日的原厂断开崩溃候选仍未证实修复，且 RECORD 与停止流程同时改变，候选通过也不能直接归因 RECORD。
  有线仍为“未实测”，不因包内存在 USB 模块改变结论。

完整的报告指纹、现有覆盖、新增用例、88 阶段实车检查点和 DiPlay/WinPlay/macplay/艾瑞泽 8 合并门槛见
[艾瑞泽 8 协议回归矩阵](ARRIZO8-PROTOCOL-REGRESSION.md)。新增 9 项测试，定向 49 项通过；完整 standard 848 项、remote 桥 24 项
通过，均 0 失败/0 跳过，Spotless、standard lint/debug 构建通过。上述离线结果不替代设备证据。

## 2026-11-06：HyperOS 手机端 SDP 生命周期与 AVD 检查（无新增实车验收）

两项手机侧 SDP/RFCOMM 公告改为共享生命周期，修复监听异常退出后 `running` 仍为真导致无法重新发布的问题。
复用蓝牙状态广播，在用户仍请求连接时恢复；用户停止、新轮次与蓝牙再次关闭会作废旧任务，自动恢复最多三次。
UUID、iAP2/认证、CHERY/RECORD 和媒体策略未改变，不按 HyperOS 大版本增加手机白名单。
新增生产生命周期回归 11 项，蓝牙定向 67 项通过；最新完整 standard 859 项、remote bridge 24 项均 0 失败/错误/跳过，
Spotless、standard lint/debug 构建通过。

本轮使用保留原数据的 Xiaomi system + ranchu 混合 AVD，保持 SELinux Enforcing，不代表对应实体手机原厂 ROM：

- HyperOS 3 / Android 16 API 36 / `OS3.0.2.0.WMCCNXM`：实际覆盖安装并进入连接流程，初始与蓝牙重开后两项公告均
  `SDP_REGISTER outcome=OK evidence=LOCAL_REGISTRATION_ONLY`；后台返回保留等待状态，确认用户停止后再次蓝牙重开未重新公告。
- HyperOS 4 / Android 17 API 37 / `OS4.0.24.0.XFRCNXM`：同一 APK 安装并通过首页、声音与电池优化提示进入连接流程；
  初始与蓝牙重开后两项公告注册、后台返回、确认停止后不再恢复均与 HyperOS 3 一致。该实例有主副屏，截屏固定使用主屏 ID。
- HyperOS 2 / Android 15 API 35 / `OS2.0.215.0.VMCCNXM`：覆盖安装成功，启动后空白；本轮三份退出记录均为启动阶段 ANR，
  未取得可归因的线程栈，未覆盖公告/连接，不记为通过。
- HyperOS 1 / Android 13 API 33 / `V816.0.4.0.TGACNXM`：追加观察 150 s 后仍处开机动画，zygote 重启、package 服务不存在，
  未达到安装运行 APK 的条件，不能据此判定真实 HyperOS 1 手机兼容性。

APK 指纹、完整矩阵、截图/日志的证据边界及同车换手机步骤见 [HyperOS 手机兼容记录](HYPEROS-PHONE-COMPATIBILITY.md)。
没有操作当前连接的真实手机、车机或接收端，没有新增车机发现、iAP2 回连、媒体、触控或断开后原厂稳定性验收。

## 2026-11-06 帕萨特 2025 车机持有主屏时仍持续发送视频（通用修复）

- **来源：** 帕萨特 2025 车主反馈，mcarplay `0.1.5` 支持报告 `53afaae1-7c74-402f-9f6d-0cf023fa1d9e`。
  接收端 `VW4211` / `firmwareRevision=5739` / AirPlay `320.17.3`；会话 `0266d30e` 本身已稳定推流 191 秒、发送 6141 帧。
- **现象：** 车主报告「倒车应该断开连接」。日志中 `17:02:22.565` 与 `17:04:34.497` 两次主屏被车机拿走
  （`changeModes`，`transferType=1` TAKE，约束 500），此后仍出现
  `VIDEO: CarWith 帧 ... 已发送`；两段分别持续约 10.3 秒和 14.3 秒。导出时 `sourceVideoFrames=6141`、
  `videoFramesSent=6141`，确认不是只收到 CarWith 帧，而是实际写入了屏幕流。
- **归因边界：** 这份日志没有倒挡事件，只有主屏资源从手机转给车机。报告的 `changeModes` 全是 TAKE，没有 BORROW，
  因此不能据此把倒车和「返回原车主界面」区分开。修复不按品牌或倒挡识别，而以 CarPlay 资源归属为准：
  主屏不归手机时，屏幕视频没有输出意义。
- **对应修改（本提交，未在车上验证）：** `VideoSender` 每帧检查 `ModeArbiter.phoneOwnsScreen`。主屏归车机时
  丢弃全部视频帧，包括 IDR；屏幕流 socket、CarWith、iAP2 与音频不关闭，keepAlive 继续发送。主屏归还后等待新的
  IDR，重发参数集，并每秒重试关键帧请求直到 IDR 到达，之后才恢复全帧率。若暂停期间车机重新拿走主屏，门控重新进入暂停。
  诊断层收到 `SCREEN_OUTPUT` 暂停/恢复事件；主屏让出期间不再把 `lastVideoMsAgo` 增长误报成视频停滞。
- **验证范围：** JVM 状态机测试与本地 TCP 传输测试覆盖「暂停时零视频帧」「归还后 P 帧仍被拦住」「首次关键帧请求失败后重试」
  「新 IDR 后恢复」。
  **未在帕萨特或其他车型上实车验证**；仍需确认倒车影像、恢复延迟、触控以及不同车机归还主屏时的 `requestUI`/`UNTAKE` 时序。
- **VW4131 倒车复核（2026-11-06 18:53，旧构建）：** 大众 ID.7（`VW4131`，AirPlay `320.17.7`）日志在
  `18:53:10.197` 收到主屏 `changeModes`：`transferType=3`（BORROW）、`borrowID=4`、
  `unborrowConstraint=1000`；`18:53:38.382` 自动归还主屏。借用持续约 28.2 秒，期间旧版 `0.1.5` 仍从
  `VIDEO: CarWith 帧 #3721` 持续发到 `#5100`（约 1,379 帧），且没有 `SCREEN_OUTPUT`/`screenSuspended` 字段，
  说明该 APK 尚未包含本修复。该载荷也确认：借用状态下 `requestUI` 必须遵守 `unborrowConstraint`，不能把倒车画面抢回；
  本提交已解析该字段并加入精确载荷回归。同一报告的主音频也以 `transferType=3` 归车机，三档音频 SETUP 均回
  `400 Bad Request`；音频候选现在会先检查 `phoneOwnsAudio`，资源被车机占用时不再轮换格式或写入“格式不兼容”记忆。

## 2026-11-06 小米 17 Pro Max + DiPlay：用户确认无问题

- **来源：** 用户口头反馈「diplay+xiaomi 17 pro max没问题」。
- **已知：** 手机 Xiaomi 17 Pro Max，接收端 DiPlay；用户确认本次连接无问题。
- **未记录：** 具体构建版本、HyperOS/CarWith/小米 CarLife/DiPlay 版本、连接步骤，以及视频、声音、触控各自是否覆盖。
  本条目只记录一次用户确认，不扩展为车机兼容或长期稳定性结论；WinPlay、macplay、艾瑞泽 8 与真车仍待回归。
