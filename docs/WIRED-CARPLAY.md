# 有线 CarPlay（root 实验）

更新：2026-10-29。本文记录「手机通过 USB 线直连只支持有线 CarPlay 的车机」的调研结论、已实现的台架工具与后续步骤。
**目前没有任何实机证据**：本功能不代表任何手机或车机已支持，也不进入 `standard` 的连接流程。

## 结论

- **免 root 不可行。** 车机先以 USB 主机身份枚举手机，只有看到 Apple VID（`0x05AC`）才会进入 CarPlay 流程。
  普通 App 改不了手机的 USB 设备描述符（由厂商 init 脚本写入 configfs），小米手机对车机始终是小米 VID。
- **只支持有线的车机，推荐方案是有线转无线盒子**（Carlinkit 一类）：盒子对车机冒充 iPhone，对手机是普通无线
  CarPlay 接收端，mcarplay 现有的蓝牙 iAP2 + Wi-Fi/AirPlay 链路即可使用。实测前同样不声明支持。
- **root 后原生有线可以尝试**，但关键一步（手机切成 USB 主机）取决于具体机型的 Type-C 控制器与内核。

## 有线 CarPlay 的五个环节

| 环节 | 内容 | 本分支状态 |
| --- | --- | --- |
| 1. 冒充 iPhone | configfs gadget 用 Apple VID/PID 枚举；FunctionFS 接收发给设备本身的厂商控制请求（`FUNCTIONFS_ALL_CTRL_RECIP`） | 已实现（`usb/GadgetPlan`、`usb/FunctionFs`），未实机 |
| 2. 角色切换 | 车机请求 iPhone 改当 USB 主机，自己转为 USB 设备 | 已实现状态机与节点探测（`usb/WiredSwitchMachine`、`usb/UsbRoleNodes`；不自动写 Type-C 节点），未实机 |
| 3. iAP2 走 USB | 车机 iAP2 接口的 bulk IN/OUT 承载与蓝牙相同的链路层字节流 | 已实现 `UsbHostDuplexStream`，尚未接入会话 |
| 4. 网络 | AirPlay 跑在车机的 CDC-NCM 网卡上（IPv6 链路本地 + mDNS） | 未开始 |
| 5. 媒体 | 与无线相同的 AirPlay 视频/音频/触控 | 复用现有代码，未开始联调 |

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

## 未核实事项

以下来自公开逆向资料或经验值，均需台架或实车核对后再修正代码与本文：

- 角色切换请求编号 `bRequest 0x51`（`AppleControlRequests`）。充电请求 `0x40` 已在 2026-10-29 Windows 空跑中证实。
- iPhone PID `0x12A8`、`bcdDevice`、iAP 接口类别 `0xFF/0xF0`，以及车机是否要求多配置（PTP、usbmux 等）的完整 iPhone 描述符。
- FunctionFS 上零长度 `Os.write` 能否 STALL（零长度 `Os.read` 应答 OUT 请求已在空跑中未报错）。
- 在 A 转 C 线（无 PD）上写数据角色节点能否真正切到主机。
- 有线模式下 iAP2 会话是否省略 Wi-Fi 凭据交换（`0x5702/0x5703`），以及车机在 NCM 上广播的服务。

## 后续步骤

1. 阶段 3：App 侧用 `UsbManager` 打开车机设备，按 `WiredAccessoryInterfaces.match` 选接口，经 `UsbHostDuplexStream`
   接入 `Iap2LinkChannel`；`Iap2SenderSession` 增加有线模式，跳过 Wi-Fi 交换。
2. 阶段 4：root 下拉起 `cdc_ncm` 网卡并配置策略路由，使 App 套接字能连 `fe80::…%usbX`；在该网卡上自行做 mDNS；
   `NetworkBinding` 增加按网卡绑定的实现。
3. 阶段 5：与无线共用 AirPlay 媒体链路联调。入口只放在 `remote` 或新的 root flavor，绝不进入 `standard`。
