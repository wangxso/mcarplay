# HyperOS 手机兼容：蓝牙公告恢复与模拟器检查

更新：2026-11-06。目标是让同一项无线 CarPlay 能力公告在不同 Android / HyperOS 环境中可靠发布、退役和恢复。
本轮关注手机端公告及连接前置流程；艾瑞泽 8 能否发现公告、点选后回连、最终音画触控仍需实体手机和车机验收。

## 适配原则与实现

- CarPlay 能力 UUID 和手机侧 iAP2 UUID 跨手机保持一致；不按机型生成或轮换，不以 HyperOS 大版本设置车型白名单。
- 按实际 Android API 处理权限和系统行为。HyperOS 大版本与 Android API 并非一一对应；本机 AVD 只能代表表中的具体组合。
  既有流程在 API 31/32 请求位置权限，在 API 33+ 请求附近 Wi-Fi 权限，蓝牙使用 `BLUETOOTH_CONNECT`；前台服务显式使用 `connectedDevice`。
- `PhoneSdpListener` 将 iAP2 传输与 CarPlay 能力两项公告作为同一组资源管理：重复启动不重复注册，任一监听结束都撤销整组。
- 系统蓝牙关闭时撤销公告并作废旧恢复任务；重新开启后仅恢复用户仍请求的公告，不重新发起媒体会话，不主动连接新的车机。
- 注册暂时失败或 `accept()` 异常可后台恢复，最多三次；连续“注册成功但立即监听失败”也共用该上限，避免忙循环。
  用户显式重新连接、或蓝牙经过关闭再开启，才重新打开恢复窗口。手动停止后，迟到失败/广播/恢复任务不得复活监听。
- 恢复沿用正常 Android RFCOMM/SDP API；不新增 root 依赖，不改设备类别，不自动删配对，不修改手机安全策略。
  原有 Shizuku/手填真实蓝牙地址等后续建链要求仍按实际接收端执行。
- 诊断继续区分 `LOCAL_REGISTRATION_ONLY`、监听存活、车机 iAP2 回连与媒体阶段；本地注册成功不写成车机已识别。

实现位置：

- [公告生命周期](../app/src/main/java/com/mcarplay/app/bt/PhoneSdpListener.kt)
- [RFCOMM 服务与恢复](../app/src/main/java/com/mcarplay/app/bt/Iap2BluetoothServer.kt)
- [系统广播](../app/src/main/java/com/mcarplay/app/bt/BluetoothStateMonitor.kt)、[客户端转交](../app/src/main/java/com/mcarplay/app/bt/Iap2BluetoothClient.kt)
- [会话接线](../app/src/main/java/com/mcarplay/app/SessionController.kt)

## 自动回归

新增 [PhoneSdpListenerTest](../app/src/test/java/com/mcarplay/app/bt/PhoneSdpListenerTest.kt) 11 项，运行实际生产生命周期与两项 SDP 资源容器：

- 重复/并发启动只注册一组；任一监听失效后两项都关闭；已停止的句柄只关闭一次。
- 旧监听的迟到失败不能撤销新公告；用户停止、新轮次和再次关闭蓝牙均使旧恢复令牌失效。
- 第二项公告注册失败时回滚第一项，随后可恢复；持续注册失败与连续立即监听失败都受三次恢复上限约束。

蓝牙包定向回归 67 项通过，0 失败/错误/跳过，包含上述 11 项及既有公告、诊断、出站探测和回连策略测试。
这些 JVM 场景验证本机资源与并发行为，不模拟原厂车机的服务识别或蓝牙射频。

最终完整门禁通过：`spotlessCheck`、standard JVM 测试、`lintStandardDebug`、`assembleStandardDebug` 和过滤的 remote bridge JVM 测试。
standard 共 859 项，remote bridge 共 24 项，均 0 失败/错误/跳过；定向 67 项包含在 standard 中，不重复相加。
lint 为 61 Warning、2 Information、0 Error/Fatal，未修改 baseline。

本轮统一使用同一份 standard debug APK 检查，包名 `com.mcarplay.app`、版本 `0.1.5-debug`，SHA-256：
`80c198f98052eadf7f648a36a8be2510705d6bdbf1f040331c369c01ad126e3e`。
模拟器检查期间未再修改生产源码。

## 本机模拟器矩阵

四个环境均为 Xiaomi 系统分区与 ranchu 模拟器硬件栈组合，不能当作对应手机的完整原厂 ROM。
仅操作明确指定的 `emulator-*`；不操作本机连接的真实 Pixel 5，不清空已有应用数据，不调系统日期，不刷固件。

| 环境 | 本机 Android / API | 启动与运行时核对 | 应用检查 |
| --- | --- | --- | --- |
| HyperOS 1 / Thyme | Android 13 / 33，OS1.0 / V816.0.4.0.TGACNXM | Enforcing；追加观察 150 s 后仍无 `sys.boot_completed`，zygote 为 `restarting`、package 服务不存在 | 未到应用安装/运行条件，未验证 |
| HyperOS 2 / Fuxi | Android 15 / 35，OS2.0.215.0.VMCCNXM | 已启动，`sys.boot_completed=1`，Enforcing | 覆盖安装成功；启动后空白，系统退出记录为启动阶段 ANR；连接、公告与恢复未覆盖 |
| HyperOS 3 | Android 16 / 36，OS3.0.2.0.WMCCNXM | 已启动，`sys.boot_completed=1`，Enforcing | 首页/连接前置提示、两项本地 SDP 注册、后台返回、蓝牙重开后重新注册、确认停止后不再恢复均观察到 |
| HyperOS 4 | Android 17 / 37，OS4.0.24.0.XFRCNXM | 已启动，`sys.boot_completed=1`，Enforcing | 同一 APK 安装、首页、连接前置提示、两项本地 SDP 注册、后台返回、蓝牙重开后重新注册、确认停止后不再恢复均观察到 |

本轮没有验证 API 31/32/34；不能把 API 35 或 HyperOS 2 标签写成 Android 14 验证。
旧镜像的 permissive 启动参数被自动审批拒绝后，本轮移除该参数、保持 Enforcing，未切换安全模式。
既有 AVD 启动器会初始化其自带的 KernelSU/兼容模块，不能称为未经修改的原厂手机环境。
HyperOS 1 在包管理服务出现前即反复重启 zygote，截图命令也超时，未进入 APK 安装条件；这不是应用测试通过或应用不兼容的证据。
HyperOS 2 的三份本轮系统退出记录均为 `reason=6 (ANR)`、`failed to complete startup`；检查的 crash buffer 没有本应用崩溃栈，
尚未取得可归因的启动线程栈，不能断言是本次 SDP 修改、内存或某项权限导致。
HyperOS 2 的 shell 输入另受 `INJECT_EVENTS` 限制；本轮没有为此授予特权或降低系统安全限制。
临时亮屏检查后的 `stay_on_while_plugged_in` 实测仍为原值 `0`。

检查按实际界面和阶段记录；只有能启动且有当前界面、日志的环境才继续连接操作。
HyperOS 3 / API 36 与 HyperOS 4 / API 37 的实际观察相同：

- 覆盖安装、首页、声音选择和缺少真实蓝牙地址的提示可用；通过界面选择“仍然连接”，未填写伪造地址或改动车型预设。
  HyperOS 4 还实际经过电池优化提示，选择“仍然连接”后进入同一连接流程。
- 初始 iAP2 与 CarPlay 两项 `SDP_REGISTER outcome=OK evidence=LOCAL_REGISTRATION_ONLY`，界面进入等待车机蓝牙接入。
- 退到桌面再返回应用，仍保持本轮等待状态；关闭蓝牙收到 `STATE_TURNING_OFF / OFF`，重新开启收到 `TURNING_ON / ON`，
  随后两项公告重新注册，出现“已恢复本机 iAP2/CarPlay 公告（不代表车机已识别）”日志。
- 点“取消连接”并在二次对话框确认，界面显示 `USER_STOP`、日志显示监听停止；再次关闭/开启蓝牙并观察约 4 s，
  新增 SDP 注册与恢复消息均为 0，应用进程仍存活。

AVD 保留原有应用数据及已授予的权限，本轮未覆盖全新安装免责声明、逐项拒绝后再授权、长时间锁屏/后台或省电清理。
没有实体接收端、配对或真实无线链路，本地公告成功不等于车机发现；本轮没有 iAP2 回连、热点、邀请、音画触控通过证据。
连接动画会使 `uiautomator dump` 返回 `could not get idle state`，必须查看实际截图和当前日志，不能复用上一次导出的 XML。
HyperOS 4 同时暴露主屏与副屏，截屏不带 display id 会给出“结果不保证跨次一致”的警告；本轮按 SurfaceFlinger 主屏 ID
`4619827259835644672` 固定取证，避免把副屏画面当成应用界面。
原始截图、UI 树和日志只保存在忽略的 `dist/` 中，不入库。

## 实体手机与同车验收

同一车机和固件、同一 APK、相同配对与操作步骤，只更换手机；记录手机型号、Android API、HyperOS 完整版本、CarWith/CarLife 版本。
每台分别确认：

1. 本地两项 `SDP_REGISTER outcome=OK`、监听存活，并在车机端实际观察到支持无线 CarPlay。
2. 车机设备列表点选手机后 `incomingAccepted` 增长，完成 iAP2 并交付热点凭据。
3. Wi-Fi/ctrl 邀请、AirPlay 配对/SETUP/RECORD、画面/声音/触控逐层验收。
4. 蓝牙重开、锁屏/后台、手动停止、重连分别记录；停止后不会被旧任务重新发布。
5. 艾瑞泽 8 断开后独立观察至少 60 秒并检查原厂 PID/重启/tombstone，按 [协议回归矩阵](ARRIZO8-PROTOCOL-REGRESSION.md) 判定。

模拟器结果不能直接升级为 HyperOS 全版本或某个手机型号已适配。连接行为变更合并前仍需 DiPlay、WinPlay、macplay 和艾瑞泽 8 的设备回归。
