# CarWith 4.0.4 出流入口核对（2026-11-02）

## 结论与范围

用户提供的 `com.miui.carlink404.apk` 是 CarWith `4.0.4-20260522`（104000004），
SHA-256 为 `a20bd237534d82df08d561974365dc7da66e36899d3e64cc1b137cac0b084605`。
清单声明 minSdk 29、targetSdk 33。

本轮静态核对确认：mcarplay 使用的公开入口仍存在，名称、导出状态和启动方式匹配。
不能仅按 CarWith 版本号解释用户“无法出流”，也没有证据把 Android 13 定为根因。
此前同版本在另一台手机搭配小米 CarLife 8.8.5 时已有独立源端出流证据；
当前用户的 Android 13 + CarLife 8.6.8 组合已确认独立自检失败，待新版诊断报告定位。

没有执行或安装第三方 APK，没有修改其权限、授权状态、包名或签名，也没有绕过入口检查。
静态核对阶段未改变连接协议；前一轮的 remote 蓝牙诊断包已经包含现有 CarWith 出流自检。

后续用户明确确认独立自检也失败，并提供截图：界面为“等待 CarWith 七口监听”，
`1280×720 frames=0 key=0 bytes=0 span=0s`。这将源端问题收敛到当前等待七路连接的阶段；
截图不能确定具体失败端口、已经等待多久，或 CarWith 内部因何未启动完整会话。
界面的 1280×720 此时仍是默认请求参数，不是视频初始化或实际编码已成功的证据。
已针对这一阶段补入 remote `diag2` 诊断：现有七路连接进度、入口请求结果、固定组件/权限环境，
以及只读 `get_carlife_diagnostics`。不额外连接端口、不改授权和协议；仍需该用户复测。

## APK 中确认的启动路径

1. 清单将 `com.miui.carlink.castfwk.usb.CarlifeAdbActivity` 声明为 enabled/exported。
   它的 `onCreate()` 检查 CarWith 开关、功能开关、已有连接状态以及首次授权。
   CarWith 自己处理授权和 CarLife 声明，完成后生成 `startType=3` 并请求内部服务。
   mcarplay 通过普通显式 Intent 打开该入口，与这条路径相符；名称含 Adb 不代表调用方需要 ADB。
2. `com.miui.carlink.castfwk.CarlinkService.onStartCommand()` 的 `startType=3`
   分支进入 CarLife USB/ADB 兼容路径，经过初始化与组件检查后调用 `c2.f.J()`。
3. `n2.j.l()`（反编译标注 `MarketManager`）检查 `com.baidu.carlife.xiaomi`
   是否存在，以及其 `versionCode >= 220`。用户报告中的 CarLife 8.6.8 / 260
   能通过这一个版本门槛；这不证明它的服务、握手或编码输出正常。
4. `c2.f.J()` 请求另一个包内的
   `com.baidu.carlife.service.CarlifeConnectService`，携带固定的
   `bindPackageName=com.miui.carlink`、
   `bindClassName=com.xiaomi.ucar.carlife.CarlifeConnService`、
   `bindVersion=8.1.1`、`manufacturer=xiaomi` 和 `usb_type=12`。
   其中 `bindVersion` 是入口传递的协议字段，不能当作已安装 CarLife 的版本号。
5. CarWith 内的 `com.xiaomi.ucar.carlife.CarlifeConnService` 实现
   `ICarLifeCastManager` 回调；`prepareCast()` 接收配置，随后才有后续显示与媒体生命周期。
   仅有该 APK 不能确认用户另一个 CarLife 8.6.8 包如何处理启动参数、监听和协议。

以上均为静态代码观察，不能证明用户当时执行了这些分支或完成了权限确认。
相关开关、授权、已有会话和系统权限的实际取值仍未知。

## 与当前项目和用户报告对照

- `CarLifeLauncher` 打开上述已导出入口；`CarLifeSession` 随后等待本机七路端口，
  再执行鉴权和视频初始化。成功返回 `startActivity()` 不能证明 CarWith 实际创建了入口或服务。
- 正式 CarPlay 路径在 AirPlay `RECORD` 后才启动 CarWith 源。
  用户先前两份报告停在蓝牙阶段，没有 `self_test`、公开入口请求、七路连接、
  鉴权或视频初始化记录，因此那些报告不能定位本次所称的独立出流故障。
- [2026-10-28 的设备记录](CARWITH-ENTRY-FINDINGS.md)已包含同一 CarWith 版本在
  小米 14 / Android 16 / HyperOS OS3.0.307、CarLife 8.8.5 / 274 上的自检：
  七路与 AES 正常、`VIDEO_INIT_DONE 1280×720@30`，18 秒收到 180 帧。
  这是另一组环境的源端证据，不代表当前 Android 13 / CarLife 8.6.8 或该车机已验证。

## 下一次复现所需证据

先停止 CarPlay 连接和其他 CarLife 会话，保持手机解锁、mcarplay 在前台。
通过 CarWith 自己的设置确认开关并完成正常授权，再在 mcarplay 点 **CarWith 出流自检**。
自检独立于车机和 CarPlay，无需先建立蓝牙连接。约 30 秒后停止自检并导出脱敏报告。

| 自检最后到达的证据 | 后续检查 |
| --- | --- |
| 公开入口请求，七路未就绪 | 入口是否实际启动、CarWith 授权/开关、已有会话、后台活动启动限制、CarLife 服务启动 |
| 七路已连接，鉴权未完成 | CarLife 8.6.8 的握手、加密和渠道准入结果；需要该次实际报告 |
| `VIDEO_INIT_DONE`，没有视频帧 | CarWith 显示/编码器生命周期与 CarLife 视频通道；仅本 APK 静态分析不足以定位 |
| 自检已有源帧，正式连接仍失败 | 分别检查 CarPlay 建链、IDR 入流门控与视频发送/接收，不再把它记为未建立源端出流 |

正式会话的 `sourceVideoFrames` 在 IDR 入流门控后计数，零本身也不能证明从未收到视频字节。
不得用版本升级、自检启动或端口建立直接替代“收到可解析源帧”的验收。

## 2026-11-06 非 root 入口阶段探针（代码已实现，待真机复测）

公开入口的 `startActivity()` 返回仍只记为 `REQUESTED`，不能证明 CarWith 接收了 Intent。
为解决普通手机用户只能提供 mcarplay 报告、无法再操作手机取日志的问题，新增了复用现有
「通知使用权」的只读探针：

- 只观察 `com.miui.carlink` 的通知是否存在、是否带 `FLAG_FOREGROUND_SERVICE`、活动数量、
  移除次数和单调时钟；标题、正文、进度、通知 key 均不入报告。
- `access=CONNECTED` 表示通知监听器已实际绑定；`UNKNOWN`/`DISCONNECTED` 时明确保持未知，
  不推断 CarWith 未启动。`notification.accessEnabled` 只表示导出时授权列表里有本组件，
  不代表监听器已绑定或通知已送达。
- 入口等待超时现在区分：已观察到 CarWith 通知但七口未就绪
  (`CARWITH_LISTENERS_PENDING`)、监听可用但未观察到通知
  (`CARWITH_ENTRY_NOT_OBSERVED`)、监听未绑定无法判断
  (`CARWITH_ENTRY_STATUS_UNKNOWN`)；后台请求仍由 `CARWITH_ENTRY_BLOCKED` 表示。
- 七口全部连上后，若命令通道收到零帧，结束码为 `CARWITH_PROTOCOL_SILENT`，并在报告中
  单列 `commandFrames`。这条只证明命令通道在这一轮没有帧，不代表七口从未监听。
- 通知探针不会启动 CarWith、额外连接端口或申请新权限；没有通知使用权时也必须能完成
  原有连接流程，结论降级为未知。

该改动只提高可观测性，不修复 CarWith 4.0.4 / Android 13 / CarLife 8.6.8 的原始故障。
仍需在故障手机上复现、导出报告，并按实际出现的 `CARWITH_*` 码决定下一步。

## 分析证据与限制

- REA 归档完整清单 Evidence：
  `ev_aa1dece8a5fcb9784e2d0373a7ff4bd4535494c7ba49aa7071863bca871b3960`；
  摘要 Evidence：`ev_6373cb44a3c8309832ffaf3da1694769f5eaddf7b4dbb85c2883d63922b2eb64`。
  归档含 4,932 个文件项，无已记录的内容完整性矛盾；这不是签名验证或运行证据。
- REA 的 Android 代码接口因缺少 `REA_JADX_MCP_JAR` 未执行。
  上述方法体结论来自本机现有 **JADX CLI 1.5.6**，清单来自 Android Build Tools 35.0.0 的 aapt2，
  不将这些 CLI 结果冒充 REA Android Evidence。
- 有限检查范围为上述入口、服务管理器、权限页、CarlinkService、CarLife 组件检查/桥接类及回调服务。
  反编译正文、归档 Evidence 和逐文件 SHA-256 仅保留于本机临时分析目录，未纳入源码；
  没有对整个 APK、CarLife 8.6.8 或手机 ROM 做完整行为验证。
