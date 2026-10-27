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
