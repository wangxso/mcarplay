# OcarPlay 0.8.51 与小米 CarWith：APK 分析及无线 ADB 实测

分析日期：2026-10-24。样本为 `OcarPlay-0.8.51.apk`，SHA-256：`657add675cb2075b47bcc7a68363ad63ea97e2881b812a3f1e192aa6aae73507`。

## 结论

OcarPlay 是运行在 OPPO / 一加等手机上的双协议桥接器。它让原厂 Car+ 把手机车载界面送给一个本机模拟的百度 CarLife 车机端，再把视频、音频和触摸转换到真实车机的无线 CarPlay 会话。真实车机看到的是 CarPlay 手机端协议；显示的内容仍由安卓手机的 Car+ 生成，并非 iOS 的 CarPlay 界面。

```text
原厂 Car+ / CarLife 组件
  → 本机 127.0.0.1 的 CarLife 七路 TCP 通道
  → OcarPlay：视频/音频/触摸桥接与必要的画面适配
  → 蓝牙 iAP2 握手 + 车机 WLAN + CarPlay/AirPlay 媒体会话
  → 无线 CarPlay 车机
车机触摸 → CarPlay HID → OcarPlay → 本机 CarLife TOUCH 通道 → Car+
```

## APK 内的主要证据

以下为 `jadx 1.5.6` 对安装包的**静态**观察，不代表实车运行验证。APK 只有一个 DEX，没有打包 `.so`；JADX 报 6 个方法反编译错误，因此个别实现细节可能不完整。

| 环节 | 静态证据 | 含义 |
| --- | --- | --- |
| 依赖原厂组件 | Manifest 查询 `com.oplus.ocar` 和 `com.baidu.carlife.oppo`；`u` 检查这两个包、Car+ Activity/CarLife Service 和原厂签名 | 依赖原生 Car+ 与配套 CarLife 组件，不能直接在小米手机安装后使用 |
| 启动安卓车载界面 | `MainActivity` 启动 `com.oplus.ocar.CarlifeActivity` | 界面由原厂 Car+ 生成 |
| 接收 Car+ 输出 | `t4.y()` 连接 `127.0.0.1`；`r6.a` 声明 CMD 7240、VIDEO 8240、MEDIA 9240、TTS 9241、VR 9242、TOUCH 9340、DATA 9440 | 在同一部手机上模拟 CarLife 车机协议端；媒体源来自本机通道 |
| 处理画面和声音 | `WirelessCarPlayService.g2()` 创建 `t4`；视频能压缩码流直通，尺寸/帧率不合时 `f` 用 Android `MediaCodec` 和 EGL 适配；PCM 可转成 CarPlay 接受的音频 | 不是普通录屏或镜像应用 |
| 模拟 CarPlay 手机端 | `h5` / `r0` 处理蓝牙 RFCOMM 与 iAP2；`a3` 处理 iAP2 认证、Wi-Fi 配置和 CarPlay 启动；`j` 在车机 WLAN 搜索 `_airplay._tcp`；`i0` 处理配对、RTSP、媒体、HID | 真实车机使用原有无线 CarPlay 入口，无需安装车机 APK |
| 回传触摸 | `WirelessCarPlayService.c.r()` 把 CarPlay 输入交给 `t4.T()`，通过 CarLife TOUCH 通道发送 | 车机触控可操作安卓界面 |

Apple 的开发资料也说明，无线 CarPlay 使用蓝牙/iAP2 建立连接，随后通过车机的 Wi-Fi 传输音视频和控制数据；新连接流程可直接在 iAP2 中交换 IP 与端口。[Apple WWDC17](https://developer.apple.com/videos/play/wwdc2017/717/)、[Apple WWDC23](https://developer.apple.com/videos/play/wwdc2023/10150/)。

这里不要求安卓手机拥有车机侧的 MFi 认证芯片：车机作为配件提供证书并完成挑战签名，APK 的 `j4` 和 `i0` 有验证车机签名、配对和建立加密会话的路径。它模拟的是 **CarPlay 手机端**，因此车型兼容取决于车机是否接受这套第三方实现。[Apple 配件验证说明](https://support.apple.com/en-ca/guide/security/sec70a4f377d/web)。

## 对宣传文案的限定

APK 内置说明明确列出的当前范围是**无线画面、媒体声音、触控**；车机麦克风、双向通话音频、手机 USB 直连尚未提供。因此“语音控制全都丝滑”不能据此确认，尤其不能理解为车机麦克风已接通。说明还要求用户进入应用选择车机并点击连接；同一次进程内有断线恢复，但不能据此确认“每次上车完全自动连接”。支持奔驰/宝马的具体车型、固件也没有实车证据。

内置说明列出的已核验手机端基线是一加 / ColorOS 原厂 Car+ 与 CarLife 组件 `8.6.8 (260)`，其它版本和车机需要实测。新建连接前还有联网的版本、发布签名和 APK 完整性检查。

## 小米 CarWith：无线 ADB 实测

2026-10-24 通过无线 ADB 检查一部小米手机（型号代码 `2509FPN0BC`，HyperOS `OS4.0`、Android API 37）。设备上有系统应用 `com.miui.carlink`，版本 `4.0.20-20260923`；还有 `com.baidu.carlife.xiaomi`，版本 `8.8.4`（versionCode 273）。两份 APK 只拉取到本机临时目录分析，未写入本仓库。SHA-256 分别为 `85ddab72dfed8cf532c6348aac1313c64373f42bf49915cee4d9e064e07c95b6` 和 `00df4aaacbbc5837f3a0ef17fc55dfabf038596dd27b652cfb23c9722770a994`。

| 证据层级 | 发现 | 结论 |
| --- | --- | --- |
| 小米 CarLife APK 静态 | `CommonParams` 的七个端口与 OcarPlay 的 `r6.a` 全部一致；`ConnectManager.startAcceptThread()` 创建七个监听线程 | 小米组件提供与 OcarPlay 所用 CarLife 接收接口相同的端口布局 |
| 协议版本静态对照 | OcarPlay 的 `t4` 发起 CarLife `4.0` 握手；小米组件的 `CarlifeProtocolVersionInfoManager` 将 `4.0` 列为支持版本，并对主版本 4 启用车机 SDK 路径 | 版本号层面匹配，但尚不能证明后续命令与媒体兼容 |
| 小米 CarWith APK 静态 | `e2.g`（CarlifeProxy）启动百度 `CarlifeConnectService` 时设置 `bindPackageName=com.miui.carlink`、`bindClassName=com.xiaomi.ucar.carlife.CarlifeConnService`；后者的 `prepareCast()` 建立 CarWith 虚拟显示画布 | 百度协议组件与 CarWith 界面通过 Binder 串接，不只是手机上碰巧安装了一个独立的百度应用 |
| Manifest 静态 | 小米版百度 `CarlifeConnectService` 与 CarWith 的 `CarlifeConnService` 均声明 `android:exported="true"` | 独立桥接应用具备调用入口；普通应用身份能否完成整套连接仍待实测 |
| 设备运行 | 打开百度 CarLife 入口后，`ss -ltn` 观察到 `7240/8240/9240/9241/9242/9340/9440` 同时监听 | 七路本机入口在该设备上实际可启动 |
| 设备运行 | 按 CarWith APK 中的参数启动百度连接服务后，`dumpsys activity services` 显示百度进程绑定了 CarWith 的 `CarlifeConnService` | CarWith 接管的连接链路实际能在这台设备上建立到 Binder 阶段 |

**更新后的判断：小米适配有一条明确的 CarLife 路径，优先级高于另做 ICCOA 接收端。** 目标链路是 `CarWith → 百度 CarLife 小米组件 → 本机七路 CarLife 通道 → 独立桥接器 → 无线 CarPlay 车机`。ICCOA 官方另确认 CarWith 支持 ICCOA Carlink，但此方案不必先实现 ICCOA 车机端。[ICCOA 说明](https://cdn.iccoa.cn/news/47.html)、[小米 CarWith 数据共享说明](https://privacy.mi.com/CarWith-share/zh_CN/)。

**尚未验证：** OcarPlay 的 CarLife 握手与小米组件 8.8.4 的媒体加密、命令和音视频格式是否完全兼容；真正收到 CarWith 编码画面、声音和触摸回传；以及具体无线 CarPlay 车机是否接受会话。现有 OcarPlay APK 对 OPPO 包名及原厂签名有硬编码检查，不能直接在小米手机安装后使用。下一阶段应先用独立的本机 CarLife 接收器做协议握手，确认 CarWith 的实际媒体流，再接 CarPlay 发送端。

运行测试结束后已返回手机桌面并停止两次测试启动的进程；复查时这七个端口不再监听，设备状态与测试前一致。未发起蓝牙或车机配对。
