# 有线 CarPlay（root 实验）

更新：2026-10-30。本文记录「手机通过 USB 线直连只支持有线 CarPlay 的车机」的调研结论、已实现的台架工具与后续步骤。
**2026-10-30 已在 Mac 台架上走通完整有线 CarPlay 路径**：MacPlay 经系统 usbmuxd 发现台架、通过 lockdown
启动 `com.apple.carkit.service`、在有线 iAP2 上完成 MFi 认证并下发 `CarPlayStartSession`，随后 CarWith
音视频经 USB NCM 上的 AirPlay 持续播放，触控事件回传。真实车机发起 USB 角色切换仍未验证，也没有实体车机
证据。同日 01:15 起，安卓平板上的 DiPlay 0.2.22 以 USB 主机身份走通同一路径：它不发布 mDNS，地址取自其
iAP2 `CarPlayStartSession`，并要求 CarPlay 模式重枚举（`0x52`，`wIndex=4`）。本功能不代表任何手机或车机已支持，
也不进入 `standard` 的连接流程。

## 结论

- **免 root 不可行。** 车机先以 USB 主机身份枚举手机，只有看到 Apple VID（`0x05AC`）才会进入 CarPlay 流程。
  普通 App 改不了手机的 USB 设备描述符（由厂商 init 脚本写入 configfs），小米手机对车机始终是小米 VID。
- **只支持有线的车机，推荐方案是有线转无线盒子**（Carlinkit 一类）：盒子对车机冒充 iPhone，对手机是普通无线
  CarPlay 接收端，mcarplay 现有的蓝牙 iAP2 + Wi-Fi/AirPlay 链路即可使用。实测前同样不声明支持。
- **root 后原生有线可以尝试**，但关键一步（手机切成 USB 主机）取决于具体机型的 Type-C 控制器与内核。
- **USB 媒体链路可以先脱离车机验证。** root 台架把 `g2` 临时改成 CDC-NCM，手机只经 `usb0` 连到 Mac 上的
  MacPlay AirPlay 接收端口；这证明视频不必占用 Wi-Fi，但没有证明车机的 iPhone 枚举或角色切换流程可用。

## 有线 CarPlay 的五个环节

| 环节 | 内容 | 本分支状态 |
| --- | --- | --- |
| 1. 冒充 iPhone | configfs gadget 用 Apple VID/PID 枚举；FunctionFS 接收发给设备本身的厂商控制请求（`FUNCTIONFS_ALL_CTRL_RECIP`） | NCM + FunctionFS usbmux 复合台架被 macOS usbmuxd 接受，lockdown 配对/TLS 会话与 carkit 服务在 MacPlay 上可用；未接车机 |
| 2. 角色切换 | 车机请求 iPhone 改当 USB 主机，自己转为 USB 设备 | 已实现状态机与节点探测（`usb/WiredSwitchMachine`、`usb/UsbRoleNodes`；不自动写 Type-C 节点），未实机 |
| 3. iAP2 走 USB | 车机 iAP2 接口的 bulk IN/OUT 承载与蓝牙相同的链路层字节流 | Mac 台架经 usbmux 上的 carkit 通道完成识别、MFi 认证与 `CarPlayStartSession`；车机 USB 主机侧 `UsbHostDuplexStream` 尚未接入 |
| 4. 网络 | AirPlay 跑在车机的 CDC-NCM 网卡上（IPv4 和/或 IPv6，经 mDNS 或显式地址发现） | Mac 台架已验证链路本地 IPv6；IPv4/IPv6 候选、邻居与手动地址降级已有离线测试；真实车机 NCM 未验证 |
| 5. 媒体 | 与无线相同的 AirPlay 视频/音频/触控 | 有线会话下视频、音频持续播放，MacPlay 触控事件到达手机；车机未验证 |

MFi 认证只需车机向手机证明自己，手机侧不需要 Apple 密钥，与现有无线路径一致。

## 台架工具：`WiredGadgetMain`

以 root 经 `app_process` 运行（不在 App 进程里，避免 App 的 SELinux 域访问 functionfs）。需要 Magisk/KernelSU 等 root。

```bash
adb shell su -c 'CLASSPATH=$(pm path com.mcarplay.app | cut -d: -f2) app_process /system/bin com.mcarplay.app.usb.root.WiredGadgetMain probe'
```

| 子命令 | 作用 |
| --- | --- |
| `probe` | 阶段 0：列出 UDC、configfs gadget、functionfs/`cdc_ncm` 支持、可写的角色切换节点和 Type-C 端口角色 |
| `plan` | 打印将要执行的 configfs 命令，不做任何改动 |
| `up [--timeout 秒] [--pid 0x12a8]` | 阶段 1–2：停掉 Android gadget、冒充 iPhone、记录车机所有控制请求；收到角色切换请求后切主机并列出车机暴露的接口 |
| `down [--usb-config <进入前的值>]` | 回滚：角色节点写回设备值、拆除 gadget；进入前的 `sys.usb.config` 有意义时写回，再 `svc usb resetUsbGadget` 让 Gadget HAL 重建 |

remote 包的包名是 `com.mcarplay.app.rd`，`pm path` 要用它；类名不变。用了旧包会因找不到类直接 `Aborted`（退出码 134）。
在 Windows PowerShell 里需要把 `$` 转义，否则 PowerShell 会先展开：

```powershell
adb shell "su -c 'CLASSPATH=`$(pm path com.mcarplay.app.rd | cut -d: -f2) app_process /system/bin com.mcarplay.app.usb.root.WiredGadgetMain probe'"
```

`up` 会断开 USB adb，请先 `adb tcpip` 切到无线 adb。超时、出错或进程被终止都会自动回滚；成功时手机保持主机模式，
用完执行 `down`。输出以 `WIRED:` 开头；USB 序列号每次随机，不输出本机标识。车机返回的设备名称等信息写进
`CARWITH-ENTRY-FINDINGS.md` 前需按惯例脱敏。

### 各阶段通过标准

1. `probe` 至少列出一个角色切换节点，且 `functionfs 内核支持=true`。没有节点时本机基本不可行。
2. `up` 日志出现 `kind=ROLE_SWITCH` 的 SETUP：车机已把手机当成 iPhone。若只看到其他厂商请求，记录全部编号。
3. `up` 以「车机设备 … 接口 …」结束，列出厂商类 iAP 接口与 `class=02 sub=0d` 的 NCM 接口。

### 测试台建议

先用支持有线 CarPlay 的安卓后装车机（ZLINK、AutoKit 等方案）当测试台：便宜、可看日志，USB 口通常支持角色切换。
描述符参考：在 Linux 上对真 iPhone 或有线转无线盒子执行 `lsusb -v`；只做研究，不在仓库中保存或分发其固件和抓包。

## 安全

- 手机切主机后**供电角色必须保持接收端**：两端同时向 VBUS 供电可能损坏硬件。现有节点只写数据角色；
  `up` 切主机后会读回所有 Type-C 端口的 `power_role`，一旦出现 `[source]` 立即写回设备值并回滚。
  这只是软件读回，首次台架仍请串接 USB 电流表观察。
- 任何失败路径都只回滚一次并恢复 Android 的 USB 配置（`WiredSwitchMachineTest` 覆盖）；`down` 可随时手动恢复。
- **不要写 Type-C `power_role`/`data_role`**：写入会向对端发起 PD 角色互换，小米 17 曾因此黑屏重启（见台架记录）。
  `up`/`down` 只自动写 `usb_role`、高通 `ssusb/mode` 与 dwc3 debugfs 节点。
- 手机与对端经 PD 协商成主机兼供电方时（例如两台都是 USB-C 设备直连），不要强行改角色；换用把手机当设备的对端。

## 台架记录

### 2026-10-29 小米 17（2509FPN0BC，`popsicle`）`probe`

root（`uid=0`），无线 adb，USB 口未接线。HyperOS、CarWith 版本未记录；尚未运行 `up`，未接车机。

- `sys.usb.config=none`，`sys.usb.controller=a600000.dwc3`；UDC 只有 `a600000.dwc3`；configfs 有 `g1`、`g2` 两个 gadget。
  据此修正：准备阶段解绑任何占着 UDC 的 gadget，回滚改用 `svc usb resetUsbGadget`，不再写默认 `mtp,adb`。
- 增强版 `probe`（`23ef683`）确认由 Gadget HAL 管理：`init.svc.vendor.usbgadget-hal=running`；`g1` 绑定 UDC，
  `idVendor=0x18d1`，`b.1` 只挂 `function0`；`g2` 未绑定、无函数；`svc usb getFunctions` 为空（未接线、仅充电）。
  `up` 要解绑的就是 `g1`，回滚依赖 HAL 重建它。
- functionfs 内核支持：是；`cdc_ncm` 与 `usbnet` 主机驱动已加载（阶段 4 的内核前提满足）。
- 角色节点三个：`/sys/class/usb_role/a600000.ssusb-role-switch/role`（当前 `none`）、
  `/sys/class/typec/port0/data_role`（`[host] device`）、`/sys/devices/platform/soc/a600000.ssusb/mode`（`none`）。
  这些节点能否写入、写入后能否在 A 转 C 线上真正切到主机，要到 `up` 时才知道。
- `typec port0`：`power_role=source [sink]`，`power_operation_mode=default`。

结论：阶段 0 的软件前提齐备，不能据此推断本机支持有线 CarPlay。

### 2026-10-29 小米 17 `up` 空跑（Windows 电脑作 USB 主机）

同一台手机经无线 adb 运行 `up --timeout 30`，USB 线接 Windows 电脑（装有 Apple 设备驱动），不接车机。

- gadget 建立、ep0 写入描述符、绑定 UDC 均成功：`FFS BIND` → `FFS ENABLE`（主机已选配置）。
- Windows 枚举为 `VID_05AC&PID_12A8`：「Apple Mobile Device USB Composite Device」，接口 `MI_00` 为
  「Apple Mobile Device USB Device」，实例 ID 带 `RESTORE_MODE`。这说明 Apple 驱动认了 VID/PID，但接口布局与真 iPhone
  不同（真机还有 PTP、usbmux 等接口），描述符仍需对照实机修正。
- 收到 `SETUP bmRequestType=0x40 bRequest=0x40 wValue=0x0064 wIndex=0 wLength=0`：**Apple 充电请求 `0x40` 得到证实**
  （来自 Windows 的 Apple 驱动，100 mA）。用零长度 `Os.read` 应答，没有报错。
- 电脑不会发角色切换请求；30 秒后 `GADGET_UP 超时` 并回滚，进程正常退出。

- 回滚后用户确认 USB 已恢复：USB adb 连接重新出现，`probe` 中 `g1` 的 `UDC` 回到 `a600000.dwc3`。
  `svc usb resetUsbGadget` 在这台 Gadget HAL 机型上能把 USB 交还系统。

尚未验证：`0x51` 角色切换、切主机与 VBUS，需要接车机。

### 2026-10-29 事故：手动 PD 角色互换后小米 17 黑屏重启

接线：小米 17 经 USB-C 直连 vivo Pad Air（`2d95:6001`，以 USB 设备身份只暴露 adb 接口），由 Mac 经无线 adb 操作。
PD 协商结果是手机为主机兼供电方：`data_role=[host]`、`power_role=[source]`、`power_operation_mode=usb_power_delivery`，
UDC 列表为空。为了让 Pad 当主机，**手动**（不在 `WiredGadgetMain` 里）依次执行：

1. `echo sink > /sys/class/typec/port0/power_role`，返回 0；
2. 2 秒后 `echo device > /sys/class/typec/port0/data_role`，返回 0。

随后无线 adb 掉线、再连超时，手机黑屏；用户拔掉 USB 后手机重启。没有运行 `up`，没有建立 iPhone gadget。
无法区分是供电角色互换还是数据角色互换触发的，也没有拿到重启前的内核日志。

处置：`up`/`down` 不再自动写 Type-C `data_role`（`UsbRoleNode.Source.autoWritable`），`probe` 仍只读显示；
任何人都不要在这台手机上手写 Type-C `power_role`/`data_role`。另外，普通安卓平板即使角色换对，
其 App 也难以完成「先当主机、再切成 USB 设备」，测试台应使用真正支持有线 CarPlay 的后装车机。

### 2026-10-29 Apple CarPlay Simulator 不能作测试台

在 Mac（macOS 26.6.2，Xcode 27.1）上安装 Additional Tools for Xcode 27 中的 CarPlay Simulator（Apple 签名
`com.apple.CarPlaySimulator`），只做了静态检查和启动，没有连上手机：

- 设备来源是 `MobileDevice.framework`：通过 `AMDeviceNotification` 发现设备，用 `AMDeviceConnect`/`StartSession`/
  `PairWithCallback` 建立 lockdown 会话，并读取 `kAMDBluetoothAddressKey`、`kAMDDeviceClassKey` 等属性；
  不是 iPhone/iPad 的设备直接忽略。有线模式在 lockdown 服务上跑 iAP2（`iAPUSBLockdownConnection`），
  不发车机的角色切换请求；无线模式用从 MobileDevice 读到的蓝牙地址去连，同样以设备先被 Mac 识别为前提。
- 因此它既测不了有线路径，也测不了无线路径：安卓手机不实现 usbmux/lockdown，不会出现在它的设备列表里。
  启动后没有主窗口（只有设置窗口），菜单栏也没有可见入口，与「没有已连接设备」一致。

### 2026-10-29 小米 17 `up` 接 Mac（MacPlay 1.2.4 有线模式等待中）

MacPlay（`Roylyl/MacPlay` 1.2.4，ad-hoc 签名）选「有线CarPlay」并启动接收；手机 USB 直连 Mac，
`probe` 确认 `data_role=host [device]`、`power_role=source [sink]`（PD）。adb 先切到 Wi-Fi 再跑 `up --timeout 60`。

- macOS 枚举为「Apple Inc. / iPhone」（`idVendor=1452`、`idProduct=4776`），选中配置 1，分配 1000 mA。
- 收到两条厂商请求：
  - `bmRequestType=0xc0 bRequest=0x45 wLength=4`：设备到主机的厂商读取，按现有策略 STALL。按 libimobiledevice
    usbmuxd 的命名这是 Apple 的「查询 USB 模式」请求（未在 Apple 文档核实），真 iPhone 会回 4 字节当前模式。
  - `bmRequestType=0x40 bRequest=0x40 wValue=1000 wIndex=500`：充电请求，与 Windows（`wValue=100`）编号相同、电流不同。
- 60 秒内无角色切换请求，`GADGET_UP 超时` 回滚；USB adb（`sys.usb.config=adb`）与 macOS 中的小米设备随即恢复。
- MacPlay 有线模式经 usbmuxd 打开 iPhone 的 CarPlay 服务（源码日志 `usbmuxd carkit up`），本 gadget 没有 usbmux 接口，
  无法被它识别：用户确认 MacPlay 全程停在等待 iPhone 连接。它本身也不发车机的角色切换请求，所以不能替代车机测试第 2 步。

### 2026-10-29 19:50 MacPlay 再测：创建 gadget 失败，未出画

代码基线为 PR [#14](https://github.com/wangxso/mcarplay/pull/14) 的 `1818920`；实际运行手机中已安装的
`com.mcarplay.app.rd`（`0.1.3-rd-debug`）台架工具，未重新安装 APK，本轮未校验已安装 APK 与该提交逐字节一致。
手机为小米 17（`2509FPN0BC`），HyperOS `OS4.0.4.0.XPBCNXM`，CarWith `4.0.20-20260923`（104000020），
小米 CarLife `8.8.5`（274）；USB 直连 Mac，接收端为 MacPlay `1.2.4`（41），无实体车机或车机固件。

1. 先连通手机现有的无线 adb。`probe` 确认 root、FunctionFS、`cdc_ncm`/`usbnet` 均可用，`g1` 绑定 UDC，
   `g2` 未绑定；手机数据角色为设备，供电角色为 sink。
2. 在 MacPlay 选择「有线CarPlay」并点击「启动接收」，通过无线 adb 执行 `up --timeout 30`。
3. 本轮未进入 30 秒等待阶段：创建 `/config/usb_gadget/mcarplay` 立即返回 `Out of memory`，工具报告建立 gadget
   失败并回滚，退出码 1。内核同时报告 `cannot create duplicate filename '/devices/virtual/android_usb/android2'`，
   `kobject_add_internal failed for android2 with -EEXIST`，调用栈含 `gadgets_make`/`configfs_mkdir`。
   当时 `MemAvailable` 约 6 GB；这些日志指向 Android gadget 节点重名，不能仅按 shell 文案判定物理内存不足。
   节点重名是否由前几轮创建/删除遗留导致，尚未独立复现。
4. MacPlay 最后显示「连接启动超时」「未检测到USB连接的iPhone」，无画面；未建立媒体会话，声音、触控未测。
   本轮连 Apple VID/PID 枚举都未完成，不能与上一轮已枚举、等待角色切换超时混为一谈。
5. 回滚后 USB adb 重新出现，`sys.usb.config=adb`，`g1/UDC=a600000.dwc3`；手机仍为设备兼 sink。
   全程未手写 Type-C `power_role`/`data_role`。

按用户要求核对 MacPlay **公开源码**（`fb132c0796f5aa425ca1a453dd9d394a9ecfacbc`）：

- [`watch_usbmuxd`](https://github.com/Roylyl/MacPlay/blob/fb132c0796f5aa425ca1a453dd9d394a9ecfacbc/native/livi-helperd/bin/livi-helperd/src/wired.rs#L157)
  枚举系统 usbmuxd 的 USB 设备，打开 carkit 后才启动 iAP2 会话。
- [`usbmuxd::open_provider`](https://github.com/Roylyl/MacPlay/blob/fb132c0796f5aa425ca1a453dd9d394a9ecfacbc/native/livi-helperd/crates/iap2-wired/src/usbmuxd.rs#L104)
  连接 lockdown，要求 `ProductType` 以 `iPhone` 开头，读取已有信任记录并启动会话，再打开
  `com.apple.carkit.service`。这些是协议与会话要求，不是仅改 USB VID/PID 就能满足。

结论：本轮未出画。即使修复 gadget 重名，现有工具仍缺少 MacPlay 所需的 usbmux/lockdown/carkit 接入；
PR #14 的 USB 主机 iAP2 尚未接入 App，NCM 与媒体联调也未完成。MacPlay 可以作为后续专门适配的目标，
但当前不能直接验证真实车机的角色切换路径，也不能据此声明手机不具备有线 CarPlay 的可能性。

### 2026-10-29 20:14 USB NCM 媒体台架出画

本轮复用同一台小米 17 和 MacPlay 1.2.4，但不再尝试冒充 iPhone 或等待角色切换。`tools/wired_ncm_bench.py`
只借用一个空闲的 `g2` gadget，将其临时配置为 Linux USB CDC-NCM（`1d6b:0104`，不冒充 Apple），
快照并在退出时恢复原属性。脚本只在 `usb0` 上添加指向 Mac 链路本地地址的独立 IPv6 路由和策略规则，
不改 Wi-Fi 默认路由，也不写 Type-C `data_role`/`power_role`。

1. MacPlay 继续选择「有线CarPlay」并启动其 AirPlay 接收器。台架通过独立 root 守护进程维持 NCM、目标路由和
   remote debug 媒体入口，使 CarWith 启动造成无线 adb 短暂掉线时媒体会话仍可继续。
2. App 以 root 提供的网卡 `ifindex` 和显式 `fe80::…%usb0` 地址连接 MacPlay `:17000`，跳过 Wi-Fi 入网、
   ctrl 广播、mDNS 与邀请等待。该入口只存在于 `remoteDebug`，并由 `android.permission.DUMP` 限制调用。
3. MacPlay 日志在约 8 秒后收到视频数据，显示 `first-decoded-frame width=1280 height=720`，窗口打开；
   用户确认窗口内的 CarWith 界面正常。手机日志同期显示 CarWith `1280x720@30`，并明确记录
   `TIMING: timesync 应答 → fe80::…%usb0`。
4. 稳定观察约 180 秒：MacPlay 累计 `videoPackets=3183`，手机累计发送至 CarWith 帧 #3241。
   macOS `lsof` 显示 `:17000` 的连接来自 USB 网卡 IPv6 地址，`netstat -I` 在同一 USB 网卡上有连续流量；
   无线 adb 只用于控制，不承载媒体。
5. 台架到期后自动恢复 `g2`、删除专用路由并复位 Android USB。复核 `sys.usb.config=adb`、
   `g1/UDC=a600000.dwc3`、`g2/idVendor=0x0000`，且手机端状态目录和 `usb0` 均已消失。

这项成功验证的是「CarWith 视频经 USB NCM 到 AirPlay 接收端」的物理媒体路径。MacPlay 仍把该连接当作显式
AirPlay 接收器，而不是手机列表中的 iPhone；iOS 的 usbmux/lockdown/carkit 服务、真实车机对 Android 手机发起的
USB 角色切换、USB 主机侧 iAP2，以及音频/触控均未由该次运行验证；音频在后续运行中补验。原始日志含有设备标识且位于被忽略的 `captures/`，
不进入仓库。

### 2026-10-29 20:50 remoteDebug 一键连接出画

本轮把台架入口接进 App：主界面的「USB 有线连接」卡片只在存在 `UsbWiredConnectActivity` 的 `remote` 包显示；
`standard` manifest 没有该 Activity，因此正式连接流程不可见、不可调用。点击后依次自动请求 root、安装固定脚本、
配置 `g2`/`usb0`、等待 IPv4/IPv6 地址、通过绑定到 USB 网卡的 mDNS 解析 MacPlay `_airplay._tcp`，再启动现有
USB AirPlay 媒体链路并返回主界面。自动发现失败时依次尝试 USB 邻居地址和入口中的手动地址；失败页和
「停止并恢复 USB」都保留在入口内。

回滚时机：App 进程退出（root 脚本按 PID 监视）、会话结束（推流结束或建立失败时 App 自动断开并回滚）
或用户停止。root 脚本另有 24 小时兜底期限，只防 App 存活但失控；早期版本的 30 分钟期限会在推流中途
拆掉 gadget，已取消。命令行工具 `tools/wired_ncm_bench.py` 仍按 `--seconds`（10–1800）限时。

设备为小米 17（`2509FPN0BC`），HyperOS `OS4.0.4.0.XPBCNXM`，CarWith `4.0.20-20260923`，小米 CarLife
`8.8.5`（274），root 管理器为 KernelSU；接收端为 MacPlay `1.2.4`（41）。手机 USB 直连 Mac，无线 adb
只负责观察，不承载媒体。

1. remote 一键 root 请求成功并返回 `uid=0` 后，KernelSU 的 `su -c` 对多行轮询参数报
   `no closing quote`，而 Android 17 的 `ping6 -I usb0 ff02::1` 对 NCM 接口返回 `sendmsg: EINVAL`。实现改为：root 调用一律使用
   `/system/bin/su` 等绝对路径；网卡和 IPv4/IPv6 等待放进固定脚本；App 用 JmDNS 绑定该地址查询
   `_airplay._tcp.local.`，不依赖机型的 all-nodes ping 行为。
2. 点击入口后约 15 秒，mDNS 同时发现 MacPlay `:17000` 和系统 AirPlay 接收器 `:7000`，App 仅选择前者。
   macOS 上 `:17000` 随即出现来自 USB 网卡的已建立 TCP 连接。
3. MacPlay 状态变为「CarPlay已连接 首帧已解码，CarPlay画面窗口已打开」；手机日志显示
   `VIDEO_INIT_DONE 1280x720@30`、首个关键帧及连续 `CarWith 帧` 发送，时序应答明确带 `%usb0`。
   本轮连续出画约 99 秒，手机累计发送到 CarWith 帧 #1961。
4. 在主界面确认「断开连接」后，七路 CarLife 通道与外部视频推流停止；`sys.usb.config` 恢复 `adb`，
   `usb0`、root 台架状态目录和 MacPlay TCP 连接均消失，USB adb 重新出现。

这次成功仍是 USB NCM + AirPlay 媒体台架，不是车机枚举：MacPlay 没有把手机识别为 USB iPhone，也没有证明
真实车机角色切换、usbmux/lockdown/carkit、USB 主机侧 iAP2、音频或触控可用；音频在后续运行中补验。原始地址、MAC、SSID 和完整日志
不进入仓库。

### 2026-10-29 21:46 USB NCM 音视频与地址/音频降级

设备仍为小米 17（`2509FPN0BC`），HyperOS `OS4.0.4.0.XPBCNXM`，CarWith `4.0.20-20260923`，小米 CarLife
`8.8.5`（274），KernelSU；接收端为 MacPlay `1.2.4`（41）。无线 adb 仅用于控制与观察，媒体继续走 USB NCM。

1. mDNS 同时发现系统 AirPlay `:7000` 与 MacPlay `:17000`。前者被名称和端口规则拒绝，后者解析为 USB 链路本地
   IPv6；macOS 的 `/info` 日志记录 `family=IPv6`，手机时序应答带 `%usb0`。链路本地 IPv6 候选会排除
   `TENTATIVE`/`DADFAILED` 地址；实机 `/proc/net/if_inet6` 曾同时出现 `0xc4`（含 tentative）和可用地址，
   旧筛选可能选中前者，本轮已修正。
2. remote 入口默认开启声音。音频协商先选 `type=102/media/AAC-LC 48kHz 2ch`，再以 `type=100` 的
   media/compatibility/default PCM 档作为会话内替换路径。CarWith 原生 MEDIA 输入为 44.1kHz/2ch/16-bit，
   手机将其重采样到 48kHz 后编码为 AAC-LC；首个 PCM 帧 `nonzero=true`，丢帧为 0。
3. 连续观察约 550 秒：MacPlay 在 540 秒统计点收到并解密 24,219 个音频包，向播放器转发 24,219 个，解码
   24,207 个缓冲；
   `invalid=0`、认证错误 0、拒绝 0、管线错误 0，RTP 抖动丢包/迟到/重复均为 0，队列缓冲为 0ms。手机侧同时
   持续递增外部 PCM 包计数。公开源码中该统计探针位于 `volume` 之前，后续链路为
   `audioconvert → audioresample → queue → osxaudiosink`，因此这证明音频已进入 CoreAudio 输出管线。
4. 本轮 MacPlay 选择“播放 iPhone 音频”，媒体音量为 100%，输出为系统默认的 MacBook Air 扬声器；但 macOS
   系统输出同时处于静音，音量仅 13%。所以本轮没有把“解码成功”记成“用户听到声音”，也没有从手机或 MacPlay
   统计推断扬声器实际出声。随后用户取消系统静音并再次播放 CarWith 音乐，确认实际听到声音且无异常。
5. 本轮之后又安装包含双栈 mDNS 回退、邻居/手动替换和网卡清理的新 remote APK，复测约 6 分钟。触发 CarWith
   音乐后，MacPlay 在 390 秒统计点为音频 7,918 包、解密/转发各 7,918、解码 7,906 个缓冲；认证、管线与 RTP
   抖动丢包均为 0。主动执行台架 `down` 后 `usb0` 消失、状态目录删除、`g1` 重新绑定 UDC 且 USB adb 恢复；
   另一次 `am force-stop` 模拟进程退出，也由守护脚本完成同类回滚。观察时 macOS 仍为静音；随后用户取消静音
   并确认音乐正常播放，实际听感已补验。

网络替换路径按以下顺序执行：USB 网卡绑定的 IPv6 mDNS → IPv4 mDNS → USB 邻居表 → 入口中的手动地址；每个
候选地址建立独立的 IPv4/IPv6 策略路由，策略规则创建失败会使该候选失败，而不会把只有路由表条目误报为可用。
mDNS 同时给出 A/AAAA 时由现有连接器逐个尝试，单个地址连接超时后继续下一个。回滚会清空本台架网卡地址，避免
重启后台架复用上一轮地址。Mac 台架本次实际走 IPv6；这不能说明真实车机必须使用 IPv6，NCM 主机实现也可能只给
IPv4 或同时给两类地址，仍需以实体车机日志为准。

MacPlay 同时显示“未检测到USB连接的iPhone”，这也是当前实现下的准确结果。其公开源码的
[`refreshUSB`](https://github.com/Roylyl/MacPlay/blob/fb132c0796f5aa425ca1a453dd9d394a9ecfacbc/native/App/MacPlay.swift#L654-L697)
只枚举 USB Product Name 含 `iPhone` 的 `IOUSBHostDevice`。未启用兼容身份时，台架是 Linux CDC-NCM
`1d6b:0104`、产品名 `mcarplay USB media bench`，因此不会出现在该列表。有线会话辅助程序还会经系统 usbmuxd 打开 lockdown 的
`com.apple.carkit.service`，见
[`watch_usbmuxd`](https://github.com/Roylyl/MacPlay/blob/fb132c0796f5aa425ca1a453dd9d394a9ecfacbc/native/livi-helperd/bin/livi-helperd/src/wired.rs#L157-L240)
和
[`open_provider`](https://github.com/Roylyl/MacPlay/blob/fb132c0796f5aa425ca1a453dd9d394a9ecfacbc/native/livi-helperd/crates/iap2-wired/src/usbmuxd.rs#L104-L135)。
当前默认兼容模式会同时声明 `05ac:12a8`、`Apple Inc.` 和 `iPhone`，所以已经满足上述设备列表筛选；但它仍只实现
USB 物理网卡和 AirPlay 媒体，没有 Apple usbmux、lockdown 或 carkit 服务。这种“列出 iPhone”与“完成有线 iPhone
会话”是两个不同层次，不能因为列表检测成功就声明后者可用。

### iPhone 兼容 NCM 身份实测（22:27）

remote debug 入口新增“优先使用 iPhone 兼容 USB 身份”开关，默认开启；关闭后直接使用 Linux NCM。开启时台架
保留同一个 `ncm.mcarplay_bench` 功能，只把复合设备的 VID/PID 和字符串改为 `05ac:12a8`、`Apple Inc.` 与
`iPhone`，并使用应用首次运行时生成、之后稳定复用的随机 40 位十六进制伪序列号；该值不读取或复用手机硬件标识。
若 30 秒内没有枚举出 NCM 网卡，连接器会执行完整回滚，再以原来的 `1d6b:0104` 身份重试。

1. 小米 17 成功申请 root，5 秒内枚举出 `usb0`。macOS IORegistry 显示 `USB Product Name=iPhone`、
   `idVendor=1452`（`0x05ac`）、`idProduct=4776`（`0x12a8`）、`USB Vendor Name=Apple Inc.`，同时 NCM
   数据接口仍为活动状态。
2. MacPlay `:17000` 的 AirPlay 控制、认证、音频 SETUP 和视频 SETUP 均成功；手机记录首帧
   `1280x720` 并持续向 `usb0` 发送媒体。兼容身份没有破坏已经验收的 NCM 音视频路径。
3. MacPlay 的 3 秒 USB 刷新按公开源码会接受这个 Product Name；其有线辅助进程仍尝试 usbmux/lockdown，
   因台架没有相应接口而不会建立第二条控制会话。本次媒体成功来自既有 AirPlay/NCM 路径，与完整 USB-iPhone
   会话无关。
4. 实测 `am force-stop` 和入口内“停止并恢复 USB”两条回滚路径：`sys.usb.config=adb`、
   `g1/UDC=a600000.dwc3`、台架状态目录删除、USB adb 返回，macOS 设备名恢复为 `Xiaomi 17 Pro Max`。

### 2026-10-30 00:30–00:55 MacPlay 完整有线会话、冻结与音频断流修复

设备为小米 17（`2509FPN0BC`），HyperOS `OS4.0.4.0.XPBCNXM`，CarWith `4.0.20-20260923`，小米 CarLife
`8.8.5`，KernelSU；接收端 MacPlay `1.2.4`（41），选择“有线CarPlay”。无线 adb 只用于控制与观察。台架以
`tools/wired_ncm_bench.py up --identity iphone-ui` 建立 NCM + FunctionFS usbmux 复合 gadget，再在 MacPlay
点击“启动接收”，收到 `CarPlayStartSession` 后用 adb 启动 `UsbMediaBenchActivity`。

1. 控制链路：MacPlay 日志依次为 `usbmuxd carkit up`、`wired: identification accepted`、`wired: MFi auth succeeded`、
   `CarPlayStartSession sent`；手机端记录 secure lockdown `StartService com.apple.carkit.service`、有线 iAP2
   就绪，并收到端口 `17000` 与 Mac 链路本地 IPv6。此前的 usbmux 断开源于纯 ACK 晚于业务响应写出；ACK 改为先写
   后唤醒读者，之后未再出现 duplicate packet 或 bulk 读 `kIOReturnAborted`。
2. 首次有线会话只发出 3 帧视频，38.7 秒后 MacPlay `heartbeat timeout idleMs=15006` 断开。手机日志在
   `00:31:14` 到 `00:32:17` 之间完全空白，`dumpsys greezer` 显示 CarWith 启动高德时执行 `LM FZ`，冻结了
   本应用 uid，3 秒后的 `LM timeout THAW` 唯独漏掉该 uid，统计为 `frozen=63s`。前台服务
   （`connectedDevice`）不能阻止此冻结；百度 CarLife 在云端白名单中，不受影响。
3. 台架脚本现于 `up` 时执行 `cmd greezer unmonitor`、加入 deviceidle 白名单，并在主循环每秒检查
   `/sys/fs/cgroup/apps/uid_<uid>/pid_*/cgroup.events`，冻结时先用 `cmd greezer thuid` 解冻，失败再写
   `cgroup.freeze=0`；`restore` 只撤销本台架添加的项。复测中仅 unmonitor 与白名单仍挡不住启动后约 5 秒内的
   `quick freeze`，守护在 0.6 秒内逐次解冻，此后不再冻结。
4. 音频在播放后 4 秒出现 `ENETUNREACH`，音频发送器随即停止，视频不受影响。原因是脚本每秒对 peer 先删后加
   策略规则；`usb0` 不属于 netd 网络，规则空窗内未缓存路由的 UDP `sendto` 会失败。规则改为已存在即跳过；
   `AudioSender` 的音频包与同步包改为 3 秒宽限，宽限内丢包继续，持续失败才停止。旧日志 `22:09:55` 的同一
   错误也解释了此前无线 NCM 长测中音频包数停止增长的现象。
5. 修复后复测（attempt `ABC35495`）：240 秒时视频 4,480 包、音频 9,914 包，约 20 fps 与 47 包/秒；
   音频解密 9,914、`invalid=0`、认证错误 0、RTP 抖动丢包 0；MacPlay 触控事件到达手机。

Python 台架等待 macOS 枚举的上限由 15 秒改为 45 秒；新伪序列号首次枚举曾耗时约 25 秒。`up` 中首个 peer 路由
失败不再触发回滚，交给主循环重试。上述结果只覆盖 Mac 台架；真实车机的角色切换、USB 主机侧 iAP2 与网络仍需实测。

### 2026-10-30 01:03–01:22 DiPlay（安卓平板作 USB 主机）有线会话

手机同上（小米 17，KernelSU），安装 remoteDebug 构建，从「USB 有线连接」入口启动；接收端为 vivo Pad Air
（`PA2353`，Android 14）上的 DiPlay `0.2.22`（41）有线模式，手机经 USB 直插平板，平板作 USB 主机。无线 adb
只用于控制与观察。DiPlay 源码公开，以下判断均对照其 `transport/` 与 `airplay/` 实现。

1. 01:03 首轮在 usbmux、lockdown 配对、carkit 与 iAP2 链路 SYN 之后失败，DiPlay 报
   `USBMUX write transferred -1 of 36 bytes`。DiPlay 读线程同步写 TCP ACK（2 秒超时），我们的读线程又可能卡在
   FunctionFS bulk IN 写（主机读走前不返回），两侧互等。出站帧改为队列加独立写线程，读线程不再写 bulk IN。
2. 同轮 DiPlay 随后发 `0x52`（`wIndex=4`，CarPlay 模式）并等待重枚举，我们回 `0x01` 且不重枚举，它停在
   `WaitingForReenumeration`。现对 `wIndex=4` 回 `0x00`，300ms 后经 `/sys/class/udc/<udc>/soft_connect` 断开、
   800ms 后重连，失败时解绑再绑定 configfs `UDC`；15 秒内不重复。MacPlay/usbmuxd 的 `wIndex=3` 仍回 `0x01`，行为不变。
3. DiPlay 不发布 mDNS，地址在 iAP2 `0x4301 CarPlayStartSession` 中给出（无 scope 的链路本地 IPv6、端口 7000、
   publicKey 与 deviceIdentifier）。usbmux 守护进程把它原子写入台架状态目录的 `carplay-start`，脚本以
   `start-session` 子命令读出；连接器在 iPhone 兼容身份下最多等 45 秒，StartSession 与 mDNS 谁先到用谁，最后回退
   USB 邻居表。解码时去掉 scope、按白名单过滤字段值。
4. 01:15 复测：DiPlay 记录 `wired iap2 identification accepted`、`authentication accepted`、`tx=0x4301`；soft_connect
   重枚举后约 1.7 秒恢复。AirPlay 经 USB NCM 建立，视频约 20 fps、`recoveries=0`，AAC-LC 48kHz 解码播放，触控回帧
   一次 9ms；平板截图显示 CarWith 桌面（地图、Dock、媒体卡片与滚动歌词）。会话约 3 分钟未断。
5. 同轮 DiPlay 每次 `POST /feedback` 均回 500：其 bplist 编码器把同步后的 NTP `timestamp` 作为 BigInteger
   编码，长度不足 8 字节时越界。根因在我们的计时时钟直接取 `System.nanoTime()`，NTP 秒数只有开机以来的值；
   现使用 1900 纪元 NTP。`TimingServer` 现以进程内固定偏移把单调时钟平移到 NTP 纪元，音频同步包共用同一时钟。
   01:21 复测 `/feedback` 全部 200（音频开始前为 87 字节；开始后含 streamConnectionID、sampleTime 与 timestamp），整段 0 个 500、0 个异常。

该结果只覆盖安卓平板模拟的 USB 主机；真实车机的角色切换、USB 主机侧 iAP2 与车机网卡仍需实测。

## 未核实事项

以下来自公开逆向资料或经验值，均需台架或实车核对后再修正代码与本文：

- 角色切换请求编号 `bRequest 0x51`（`AppleControlRequests`）。充电请求 `0x40` 已在 2026-10-29 Windows 空跑中证实。
- iPhone PID `0x12A8`、`bcdDevice`、iAP 接口类别 `0xFF/0xF0`，以及车机是否要求多配置（PTP、usbmux 等）的完整 iPhone 描述符。
- FunctionFS 上零长度 `Os.write` 能否 STALL（零长度 `Os.read` 应答 OUT 请求已在空跑中未报错）。
- 在 A 转 C 线（无 PD）上写数据角色节点能否真正切到主机。
- 有线模式下 iAP2 会话是否省略 Wi-Fi 凭据交换（`0x5702/0x5703`），以及车机在 NCM 上广播的服务。
- 真实车机扬声器的音频、触控和导航提示；MacPlay 上的实际音乐输出已由用户确认。

## 后续步骤

1. 产品化 Mac 台架路径：remote 入口内置 usbmux 复合身份与 Greezer 守护，自动完成从 `CarPlayStartSession`
   到媒体桥的衔接，无需手动 `am start`。
2. 阶段 3：App 侧用 `UsbManager` 打开车机设备，按 `WiredAccessoryInterfaces.match` 选接口，经 `UsbHostDuplexStream`
   接入 `Iap2LinkChannel`；`Iap2SenderSession` 增加有线模式，跳过 Wi-Fi 交换。
3. 阶段 4：把现有 NCM 台架接到真实车机的 USB 主机 iAP2 会话之后；验证车机暴露的网卡、IPv4/IPv6 地址与 mDNS。
   remoteDebug 已用 JmDNS 按 USB 网卡地址自动发现 MacPlay，但仍需确认真实车机服务名和网络命名空间行为。
4. 阶段 5：与无线共用 AirPlay 媒体链路并由车机验证视频、音频和触控；MacPlay 实际听感已确认。
   USB 媒体入口只放在 `remote` 或新的
   root flavor，绝不进入 `standard`。
