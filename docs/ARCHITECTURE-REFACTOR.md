# 会话编排层重构方案（草案）

基线：`main` @ `ab0cb93`（v0.1.6，文中行号与统计均以此为准，搬移后会漂移）。
状态：**Phase 0 的代码护栏已在 `refactor/session-phase0-guardrails` 完成（只加测试与工具，不改生产代码）；基线实车报告待采集；
Phase 1a 已在 `refactor/session-1a-interfaces` 完成，待 T1 回归后合并；其余尚未开始。** 2026-11-10 对照代码复查后修订过一次，改动见文末「修订记录」。本文仍是讨论稿：第 9 节的问题确认后再定稿、拆 PR。

## 1. 背景与目标

协议层（`carlife/`、`iap2/`、`airplay/`、`bt/`、`net/`、`video/`、`audio/`）的分包和单测覆盖整体健康；问题集中在把它们串起来的
编排层 [`SessionController.kt`](../app/src/main/java/com/mcarplay/app/SessionController.kt)。近几个版本的回归（「连上约 3 秒即断」
「僵尸轮次堵门」「SETUP 后控制通道被关」「旧轮迟到回调停掉新一轮发送端」）都出在**轮次归属与收尾时机**上，而这部分恰好没有
单元测试，只能靠实车回归兜底。

目标（按优先级）：

1. **生命周期显式化**：「一次用户连接」与「一轮端点 → 配对 → 推流」成为两个有类型的对象；三处各自维护的收尾列表合成一份
   按结束原因执行的清单，现在写在注释里的「这里绝不能做 X」由结构和单测守住。
2. **轮次归属只有一种机制**：取代现在并存的 8 种标志/代际/身份比较（见 2.3）。
3. **编排逻辑可单测**：阶段转移、取消、晚到事件、收尾竞态能在 JVM 单测里复现。
4. **车型差异只经 `ReceiverCompatibility.Policy` 进入流程**，流程代码不出现车型名。

非目标：不重写协议实现；不改变任何对车机可见的行为；不迁移到协程；不拆 Gradle 多模块（见 3.5、3.7）；
不顺手修正重构中发现的疑似缺陷（单独开「行为变更」PR）。

## 2. 现状评估

### 2.1 保留的设计

- **按协议边界分包**，底层大多可单测（123 个测试文件、约 1.57 万行，含 `replay/` 协议回放）。
- **兼容策略集中**：[`ReceiverCompatibility.Policy`](../app/src/main/java/com/mcarplay/app/compat/ReceiverCompatibility.kt)
  「协议事实 → 可解释策略」，车机例外以带日期证据的字段登记。重构后要让它成为流程读取车型差异的**唯一**入口。
- **诊断体系独立**：`ConnectionTimeline` / `DiagnosticCode` / `DiagnosticRedactor` 与 `tools/logbot/rules.py` 对齐。
- **会话归进程级单例**（2026-10-11 决策）：Activity 只挂屏，销毁重建不影响管线。这条保留，只是单例变薄。
- **[`CarLifeStateMachine`](../app/src/main/java/com/mcarplay/app/carlife/CarLifeStateMachine.kt) 是现成范本**：
  「socket I/O 与定时器在 `CarLifeSession`，本类只管协议顺序、一次性守卫与转移」。CarPlay 侧照此分离。

### 2.2 问题与数据

| 项 | 数据（基线） |
| --- | --- |
| `SessionController` 体量 | 4216 行；46 个 `@Volatile`、100+ 个可变状态；近 400 次提交中 39 次改动（全仓最高） |
| 会话主流程 | [`onCarPlayEndpoint`](../app/src/main/java/com/mcarplay/app/SessionController.kt:3149) 单个闭包约 980 行，最深缩进 64 列（约 16 层），`Bg.run` 内再嵌 `Bg.run`，内部定义 `newClient()`/`newScreenSender()`/`failure()` |
| 单测覆盖 | 只有尾部纯函数 `pairingFallback` 有测试；另两处测试直接改写单例字段（`currentClient`、`connectCancelled`、`videoSender`） |
| 取消方式 | `connectCancelled` 62 处、`wasCancelled()` 23 处、`ownsConnection()` 17 处、`sessionStarting` 27 处；24 处 latch/sleep/await 阻塞等待，取消只能等它们自己醒来后查标志 |
| 收尾语义混用 | [`retireSession()`](../app/src/main/java/com/mcarplay/app/SessionController.kt:1165) 既是每轮配对起步的重置，也是终态收尾的一部分；注释里记着「绝不能 detach BT」「绝不能 resetAll」 |
| 车型名散落 | 非注释代码行出现车型/接收端名：`SessionController` 132、`AirPlayClient` 65、`AudioSender` 53、`MainActivity` 47 |
| UI 读状态 | 英雄卡靠轮询 `isSessionRunning() \|\| sessionStarting.get()` 与 `videoSender?.framesSentTotal` 推断状态；`ConnectionStage` 只用于诊断展示，不驱动逻辑 |
| 其他大类 | [`AirPlayClient.kt`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt) 3609 行、69 个可变字段（RTSP、配对、info 探测、iAP 隧道、音频协商、屏幕流、event channel 同类）；`MainActivity` 2317 行 |

### 2.3 现存的轮次归属机制

同一个问题（「这个回调/线程还属于当前这一轮吗」）现在有 8 种回答方式，彼此没有统一的先后关系：

| # | 机制 | 位置 | 语义 |
| --- | --- | --- | --- |
| 1 | `sessionStarting: AtomicBoolean` | SessionController | 会话建立互斥；被 6 处外部代码读取（MainActivity、UpdateActivity） |
| 2 | `connectCancelled` | SessionController | 用户取消，**也**被 `onSenderLinkClosed` 用来踢掉配对线程 |
| 3 | `ReceiverDetectionRuntime` epoch | `ownsConnection()` | 端点观测是否属于当前连接 |
| 4 | `AirPlayClient.connectionGeneration` | `isConnectionCurrent` 10 处 | 客户端连接代际 |
| 5 | `currentClient !== client` | 13 处 | 引用身份比较 |
| 6 | `currentCarLifeSession !== session` + `CarLifeSessionRegistry` lease | 3 处 + 注册表 | CarLife 源归属 |
| 7 | `userStopGeneration` + `disconnectCompletion` latch | 断开/重连 | 断开清理完成前不启动新连接 |
| 8 | `BtSessionGate.sessionActive` 全局标志 | 多处置位 | 出站探测静默 |

### 2.4 `SessionController` 职责分布与去向

| 行（约） | 职责 | 去向 |
| --- | --- | --- |
| 94–380 | 常量、`ensure`、日志缓冲、媒体键分发 | 日志缓冲留在门面；媒体键 → `input/` |
| 380–779 | 状态字段、UI 钩子、`ensureSessionObjects` | `ConnectionSession`；UI 钩子 → `SessionUiPort` |
| 779–869 | 正式 CarLife 源与路由 | `session/StreamPipeline` |
| 869–1073 | `startConnection` / `disconnect` | `ConnectionSession.start/close` |
| 1073–1165 | 物理屏断电/恢复 | `service/ScreenPowerController` |
| 1165–1271 | `retireSession`、`maybeStartAudioNow` | 拆成 `Attempt.close()` 与音频模块 |
| 1271–2100、4130–4181 | 车机热点入网（Shizuku shell、specifier、legacy、手动四条路径） | `net/CarWifiJoiner` |
| 2101–2195 | EA 会话收尾、等 ctrl 邀请 | BT 部分 → `ConnectionSession`；邀请 → `net/EndpointResolver` |
| 2195–2486 | 音频通道启动、档位探测、拆链归因 | `audio/AudioChannelStarter` |
| 2486–2526、2768–3011 | modes 重申、主屏让出/拿回、帕萨特回屏、推流中邀请重连 | `airplay/ScreenOwnership` |
| 2526–2768、3011–3149 | 端点解析、mDNS 合并、晚到端点、邀请重试、初始 SETUP 重试 | `net/EndpointResolver` + `AttemptRunner` |
| 3149–3275、4078–4128 | 主流程：前置检查、绑网分派 | `AttemptRunner.run()` |
| 3275–3523 | 配对、降级（BT 退役重试 / 明文 / 加密升级）、SETUP | `AttemptRunner.pair()/setup()` |
| 3523–3717 | event channel 命令分发（hidSendReport / iAPSendMessage / forceKeyFrame / changeModes / requestUI） | `session/EventCommandRouter` |
| 3717–3955 | 屏幕流、发送端、主屏流重建 | `session/StreamPipeline` |
| 3955–4076 | RECORD、CarLife 起流、音频、`finally` 收尾 | `AttemptRunner.stream()` + `Attempt.close()` |

### 2.5 现存的三条收尾路径

收尾步骤目前分三处手工维护，彼此不同步；2026-10-19「会话断了 45 秒后手机屏突然变黑」与 2026-10-23「熄火后 shell 兜底网络遗留」
都是其中一处漏了步骤（附录 B-13、B-14）。

| 步骤 | 每轮重置 [`retireSession()`](../app/src/main/java/com/mcarplay/app/SessionController.kt:1165) | 车机拆流 `VideoSender.onEnded`（[3833](../app/src/main/java/com/mcarplay/app/SessionController.kt:3833) 起） | 终态 [`disconnect()`](../app/src/main/java/com/mcarplay/app/SessionController.kt:972) |
| --- | --- | --- | --- |
| 停视频发送端 | ✓ | 自身已结束 | ✓ |
| 停 CarLife 源并释放 lease | ✓ | ✓ | ✓ |
| 停 CarWith 音频桥 | ✓ | ✓ | ✓ |
| 停 `AudioSender`、清共享密钥 | ✓ | — | ✓ |
| 关 `AirPlayClient` | ✓ | — | ✓（先按 iPhone 顺序 TEARDOWN） |
| `StreamingWifiLock`、`ScreenLockMonitor`、暗幕 | ✓ | ✓ | ✓ |
| 恢复物理屏 | ✓（点亮） | ✓（车机侧断开时直接休眠） | ✓ |
| `BtSessionGate.sessionActive = false` | ✓ | ✓ | ✓（清理完成后） |
| 音频拆链归因 | — | ✓ | — |
| 清 shell 兜底网络 | — | ✓ | ✓ |
| `bluetoothClient.armAfterSessionEnd()` | — | ✓ | — |
| detach BT 控制会话 / `0xEA01` | 注释禁止 | — | ✓ |
| 停 BT/ctrl/发现器、释放 Wi-Fi、停前台服务 | — | — | ✓ |

### 2.6 全局单例

`SessionController` 直接调用 11 个进程级对象，流程代码因此无法在 JVM 单测里替换依赖：

| 对象 | 调用次数 | 用途 |
| --- | --- | --- |
| `ConnectionDiagnostics` | 44 | 时间线阶段、里程碑、错误码 |
| `ReceiverDetectionRuntime` | 20 | 接收端检测、连接 epoch |
| `Bg` | 20 | 后台任务 |
| `ShizukuGate` / `CarInputClient` | 8 / 5 | shell 能力、物理屏电源 |
| `BtSessionGate` | 6 | 出站探测静默 |
| `ScreenDimmer` / `ScreenLockMonitor` / `StreamingWifiLock` | 5 / 1 / 3 | 暗幕、锁屏监测、Wi-Fi 锁 |
| `CarPlayService` / `VehiclePresetRuntime` | 2 / 2 | 前台服务、车型预设 |

另有 `CompatibilityPrefs`、`DisplayPrefs`、`AutoConnectPrefs`、`TargetDevicePrefs` 等经 `Context` 读取的偏好对象。

## 3. 目标结构

### 3.1 分层与依赖方向

```text
MainActivity / CarPlayService / remote bridge
        │  （只经门面）
        ▼
SessionController（object，薄门面：入口转发 + SessionSnapshot 发布 + 日志）
        │
        ▼
session/ ── ConnectionSession ── Attempt(AttemptScope) ── AttemptRunner
        │        │                     ├─ EventCommandRouter
        │        │                     ├─ StreamPipeline（VideoSender / AudioSender / CarLife 桥）
        │        │                     └─ ScreenOwnership
        ▼        ▼
net/CarWifiJoiner  net/EndpointResolver  audio/AudioChannelStarter  airplay/  iap2/  bt/  carlife/  video/
        │
        ▼
compat/（唯一出现接收端家族/车型名的地方）   diagnostics/（被所有层调用，不反向依赖）
```

依赖规则（由 `ArchitectureRulesTest` 守，见 Phase 0）：

- 引擎层 `airplay/ audio/ bt/ carlife/ compat/ diagnostics/ i18n/ iap2/ input/ media/ net/ shizuku/ transport/ util/ video/`
  **不得**引用根包的 `SessionController`/`MainActivity`/`SettingsContentActivity`/`SenderApp`，也不得引用
  `session/ service/ ui/ settings/ update/ legal/`，只通过构造参数里的回调/接口与上层通信。`R`、`BuildConfig` 不受限。
- 基线已有 6 处例外，登记在测试的允许清单里，计划在 Phase 2h 消除：
  - `carlife/CarLifeDiagnostics` → `SessionController`、`service`
  - `carlife/ExternalHuController` → `SessionController`（这两处直接调用 `appendLog/claimCarLifeSession/releaseCarLifeSession`，
    改为注入 `log` 与 `CarLifeSessionRegistry`）
  - `diagnostics/SupportReport` → `SessionController`（读取蓝牙诊断摘要，改为由调用方传入）
  - `diagnostics/btsnoop/BtSnoopActivity` → `ui`（本身是界面，考虑移到 `ui/`）
  - `bt/CarBluetoothReceiver` → `service`（系统广播入口，负责拉起前台服务，可保留并注明）
- 新增顶层包必须在测试里归类为引擎层或编排/界面层，否则测试失败。
- `session/` 内的流程代码只读 `Policy` 字段，不按车型名分支。

### 3.2 生命周期：两级对象、一份收尾清单

| | `ConnectionSession` | `Attempt` |
| --- | --- | --- |
| 对应 | 一次用户连接（点「连接」到「断开」/BT 终态） | 一轮端点 → 绑网 → 配对 → SETUP → 推流 |
| 持有 | BT server/client、ctrl 广播、`_airplay._tcp` 发现器、Wi-Fi 连接器、邀请与要帧计数 | `AirPlayClient`、`VideoSender`、`AudioSender`、CarLife 源与音频桥、事件路由、`StreamingWifiLock`、暗幕/熄屏 |
| 收尾入口 | `close(reason)`：终态 | `close(EndReason)`：每轮 |

**收尾只有一份有序清单**，按结束原因决定执行哪些步骤，取代 2.5 里三处各自维护的列表：

```kotlin
enum class EndReason { ROUND_RESET, RECEIVER_ENDED, TERMINAL }
```

| 步骤 | `ROUND_RESET`（下一轮起步） | `RECEIVER_ENDED`（车机拆流） | `TERMINAL`（用户断开/BT 终态） |
| --- | --- | --- | --- |
| Attempt 内资源（发送端、CarLife、音频桥、锁、暗幕、物理屏） | ✓ | ✓ | ✓ |
| 关 `AirPlayClient`、清密钥 | ✓ | 按基线保持不关（见下） | ✓（先 TEARDOWN） |
| 音频拆链归因、清 shell 兜底网络 | — | ✓ | 只清网络 |
| detach BT / `0xEA01`、停 BT/ctrl/发现器、释放 Wi-Fi、停前台服务 | **不执行** | — | ✓ |

- 第一步只做「合并清单」，每个原因执行的步骤集合与基线逐项相同。2.5 表里看起来像遗漏的差异（例如车机拆流时不停 `AudioSender`）
  先原样保留并在 PR 里列出，是否修正另开「行为变更」PR，不混在重构里。
- `ROUND_RESET` 不 detach BT 的依据：DiPlay 在隧道就绪前看到 RFCOMM 断开即判 bring-up failed（2026-10-16，附录 B-4）。
- 基线注释里的「`retireSession` 不得 `resetAll`」（奥迪 3315，2026-10-23）指向的强制横屏覆盖已随虚拟屏桌面删除，
  代码里只剩注释；搬移时把这条注释改写为历史说明，不再当作现行约束。

**Attempt 内部有两处合法的 BT detach，不属于收尾：**

| 场景 | 基线位置 | 条件 |
| --- | --- | --- |
| 配对降级的「BT 退役重试」 | [3420](../app/src/main/java/com/mcarplay/app/SessionController.kt:3420) | `pairingFallback().useBtRetireHack`：`Policy.allowBluetoothRetireRetry`、本轮尝试过配对、pair-setup 未被拒、未遇 403 |
| 空端点等待超时 | [2725](../app/src/main/java/com/mcarplay/app/SessionController.kt:2725) | 等晚到 0x4301 满 5 分钟且没有新一轮接手 |

因此 `Attempt` 不直接持有 BT 句柄，但可以通过 `ConnectionSession` 提供的两个具名请求触发：
`retireBluetoothForPairingRetry()` 与 `dropBluetoothAfterEndpointTimeout()`。除这两个入口外，流程代码没有其他 detach 途径；
`Attempt.close()` 的实现里不出现它们（由一条针对 `ROUND_RESET` 的单测守住）。

### 3.3 `AttemptScope`：统一归属与取消（接口先行）

Phase 1a 已落地的部分（[`session/AttemptScope.kt`](../app/src/main/java/com/mcarplay/app/session/AttemptScope.kt)）：

```kotlin
interface AttemptScope {
    val id: String                        // 诊断时间线的 attempt id（ConnectionDiagnostics.receiverAttempt()）
    val observation: ReceiverObservation? // 检测侧的 (binding, epoch, session)
    val isCurrent: Boolean                // 本轮仍拥有连接；用户取消后仍为 true
    val isActive: Boolean                 // isCurrent 且未被取消
}
```

归属要两个判断，不是一个：用户取消后，本轮仍负责释放「会话建立中」互斥和记诊断（基线里的 `if (ownsConnection()) sessionStarting.set(false)`），
所以「还拥有连接」和「还该继续推进」必须分开。`LegacyAttemptScope` 直接读现有的 `connectCancelled` 与
`ReceiverDetectionRuntime.isCurrent(observation)`，与原来的 `ownsConnection()`、`wasCancelled()` 逐字等价（含短路顺序）。

后续按需加入的成员（在第一个用到它们的 PR 里加，避免先写出没人调用、语义靠猜的过渡实现）：

```kotlin
    val cancelReason: DiagnosticCode?
    fun cancel(reason: DiagnosticCode)    // 幂等；唤醒本轮所有等待并关闭登记的资源（Phase 3）
    fun checkActive()                     // 已取消则抛 AttemptCancelled（单点 catch，Phase 3/4a）
    fun <T : AutoCloseable> own(resource: T): T   // Phase 3
    fun await(latch: CountDownLatch, timeoutMs: Long): Boolean  // 2c：先用分片轮询实现，Phase 3 改为可打断
    fun sleep(ms: Long)                   // 同上
```

注意：基线里 Wi-Fi 入网等函数只查 `connectCancelled`，不查归属。2c 把它们改成 `scope.isActive` 会让「被新一轮取代」的旧轮也停下，
这是行为变化，要么在 2c 里显式保留只查取消标志的语义，要么另开行为变更 PR，不能在抽取里顺手改。

编号统一：一轮只有一个 id，即诊断时间线的 attempt id；检测侧的 `ReceiverObservation` 挂在 scope 上，不再各自判断。

替换实现后的映射（Phase 3）：

- #1 `sessionStarting` → 「`ConnectionSession.activeAttempt != null`」；外部只读 `SessionSnapshot.phase`。
- #2 `connectCancelled` → `scope.cancel(USER_STOP)` 或 `scope.cancel(BT_LINK_CLOSED)`，原因不再混用一个布尔。
- #3 → `scope.isCurrent` / `scope.isActive`（1a 已完成：`onCarPlayEndpoint` 内的 `ownsConnection()`、`wasCancelled()` 已替换）；#5 → `scope.isActive`。
- #4 `AirPlayClient.connectionGeneration` 保留在客户端内部：同一轮里客户端会被重建（BT 退役重试）或重连，它回答的是
  「这条控制连接还活着吗」。编排层的延时任务同时检查 `scope.isActive` 与该代际，但不再用它判断跨轮归属。
- #6 CarLife lease 保留（它还管诊断自检的互斥），lease 归 Attempt 持有，迟到回调先过 `scope.isActive`。
- #7 `disconnectCompletion` → `ConnectionSession.closed: CountDownLatch`，下一次 `start` 等它。
- #8 `BtSessionGate.sessionActive` 由 `ConnectionSession` 在 Attempt 进入配对与收尾时统一置位。

现在每个阻塞点之后的样板

```kotlin
if (wasCancelled()) { if (ownsConnection()) retireSession(); appendLog("…"); if (ownsConnection()) sessionStarting.set(false); return@run }
```

收敛为 `scope.checkActive()`，在 `AttemptRunner.run()` 顶层一处 `catch (AttemptCancelled)` 统一记日志、记诊断、收尾。

### 3.4 显式状态：只管跨轮决策

一轮内部「绑网 → 发现 → 配对 → SETUP → 推流」的推进由 `AttemptRunner` 的线性代码决定，阶段只作为进度上报。
状态机只负责真正出过竞态的**跨轮决策**，避免出现两个真相源：

```kotlin
sealed interface SessionPhase {
    data object Idle : SessionPhase
    data object Listening : SessionPhase                                  // BT 组件在跑，尚无端点
    data class AwaitingEndpoint(val since: Long) : SessionPhase            // 空端点，等晚到 0x4301 或 ctrl 邀请
    data class Establishing(val attempt: String, val stage: ConnectionStage) : SessionPhase  // stage 仅为进度
    data class Streaming(val attempt: String, val audio: Boolean) : SessionPhase
    data class Stopping(val reason: DiagnosticCode) : SessionPhase
}

sealed interface SessionEvent { /* UserStart(automatic), UserCancel, UserDisconnect(reason), Endpoint, LateEndpoint,
                                   WifiCredentials, SenderLinkClosed, CtrlInvite, WifiLost, AttemptEnded(outcome), StreamEnded */ }

/** 纯函数：只决定下一状态与要执行的动作，不做 I/O。 */
fun transition(phase: SessionPhase, event: SessionEvent, policy: Policy?): Pair<SessionPhase, List<SessionAction>>
```

- 进入 `transition` 的判断：推流中忽略冗余 0x4301 / 0x5703、「已有会话在处理中」、BT 断开时 `KEEP_NETWORK_SESSION` 与
  `END_SESSION`、空端点等待期间的邀请与晚到端点、收尾时补投暂存的晚到 0x4301、推流中邀请是否重连、运行中再次「启动连接」不清计数。
  附录 B 的每条事故对应一条具名测试。
- `ConnectionStage`（诊断）继续由 `AttemptRunner` 在阶段边界写入时间线，`Establishing.stage` 取同一个值。
- UI 只读 `SessionSnapshot(phase, phaseHint, framesSent, audioActive, scalePercent, targetFps)`，
  取代 `isSessionRunning() || sessionStarting.get()` 与直接读 `videoSender`。

### 3.5 线程模型（决策：不引入协程，不引入事件线程）

- 保留「每个 Attempt 一条阻塞线程，按阶段线性执行」的写法：BluetoothSocket、Socket、`requestNetwork` 回调都是阻塞或回调式 API，
  线性代码最好读，也与 `CarLifeSession` 一致。
- 状态由 `SessionStateHolder` 持有：一把锁内同步执行 `transition` 并更新 `SessionSnapshot`，返回的动作在锁外执行；
  阻塞动作交给 Attempt 线程或 `Bg`。不设单独的事件线程。
- 门面方法保持同步语义：`startConnection()`、`disconnect()`、`cancelConnecting()` 现在是 `@Synchronized`，界面在调用后立刻读状态。
  返回前状态必须已经翻转，这是不变量（见第 4 节）。
- 取消靠 `AttemptScope` 关闭登记的 socket/连接器并唤醒等待，不再依赖「等它自己醒来查标志」。
- 项目当前没有 `kotlinx-coroutines` 依赖；迁移协程的收益（结构化取消）已由 `AttemptScope` 覆盖，成本是全量改写阻塞 I/O，不划算。

### 3.6 车型差异规则

- 流程代码（`session/`、`net/CarWifiJoiner`、`audio/AudioChannelStarter`、`airplay/ScreenOwnership`）只能读 `Policy` 字段。
- 现有直接分支（例如 `passatReturnStrategy()`/`applyPassatReturnStrategy`、音频/RECORD 里的车型判断）改为 `Policy` 上的具名字段，
  字段 KDoc 保留日期证据，与现有 `Policy` 写法一致。
- 车型名可以继续出现在注释与日志里（日志文本是 logbot 契约，见 4）。架构测试只检查去掉注释和字符串后的标识符：
  按驼峰/下划线拆词后不得出现 passat、audi、honda、cadillac、diplay、winplay、macplay、chery、arrizo、mib3、id7、vw、cinemo。
  上述流程文件建立前，这条规则没有可扫描的文件。
- 因此 `airplay/ScreenOwnership` 不能直接搬：`PassatReturnStrategy`/`PassatReturnPolicy` 这类带车型名的类型要先改成 `Policy` 上的
  中性字段（例如 `screenReturn: ScreenReturnStrategy`），作为单独的「行为保持」PR，再做抽取（Phase 2e、2f）。

### 3.7 不拆 Gradle 多模块

单 `app` 模块 + 包边界 + 架构测试已经足够；多模块会牵动 `standard`/`remote` flavor、Spotless、lint baseline 与 CI 门禁，
收益只是编译隔离。若将来想把纯 JVM 协议层抽成 `:protocol` 以加速测试，再单独评估。

### 3.8 依赖注入：`SessionEnvironment`

2.6 的全局对象不改实现，只在编排层前面加一层接口，生产实现逐个转发到现有对象，测试提供假实现：

```kotlin
interface SessionEnvironment {
    val diagnostics: SessionDiagnostics   // ConnectionDiagnostics + timeline
    val detection: ReceiverDetectionPort  // ReceiverDetectionRuntime
    val bluetoothGate: BluetoothGatePort  // BtSessionGate
    val screen: ScreenPort                // ScreenDimmer、ScreenLockMonitor、CarInputClient 的物理屏电源
    val shell: ShellPort                  // ShizukuGate、Shell
    val prefs: SessionPrefs               // 各偏好对象与 VehiclePresetRuntime
    fun background(name: String, block: () -> Unit)   // Bg.run
    fun uptimeMillis(): Long
}
```

按需引入：抽哪个模块，就只把它用到的那几个对象收进来，不一次性包完。`WifiCarPlayConnector` 这类直接依赖
`ConnectivityManager` 的具体类同理，抽 `CarWifiJoiner` 时先提取它用到的方法为 `CarWifiLink` 接口。

## 4. 不变量（每个 PR 都必须保持）

1. **日志文本是契约**：[`tools/logbot/rules.py`](../tools/logbot/rules.py) 按日志文本匹配（如 `CARLIFE:` 前缀、
   `VIDEO: 车机关闭了屏幕数据口`、`POWER: 未豁免电池优化`）。搬移时日志前缀与措辞逐字保留；必须改时同 PR 改 rules.py 与其测试。
2. **诊断时间线与日志序列不变**：同一接收端、同一操作序列，`ConnectionTimeline` 的 stage/milestone 顺序与 `DiagnosticCode` 不变；
   行为保持的 PR 还要求按标签分组、归一化后的日志行序列一致（用 Phase 0 的对比脚本，见第 6 节）。
3. **对车机可见的行为不变**：报文顺序、时序常量（`CTRL_INVITE_WAIT_MS`、`SPECIFIER_JOIN_TIMEOUT_MS` 等）、重试次数原样搬移，常量连同注释一起走。
4. **门面 API 不变**：MainActivity、CarPlayService、UpdateActivity、`remote` flavor 的 `BridgeCommands` 调用的公开成员保持签名，
   内部改为转发；`sessionStarting`、`videoSender` 这类直接暴露的可变字段在 Phase 4c 统一换成 `SessionSnapshot` 后再删。
5. **门面方法保持同步语义**：`startConnection()`、`disconnect()`、`cancelConnecting()` 返回前状态已经翻转，界面随后读到的是新状态。
6. 不在视频、音频、UI 线程上做磁盘 I/O；`applicationId`、`run-as` 路径不动；`remote` 代码不进 `standard`。

## 5. 分阶段实施

每个 PR 只做一件事，标题标明类型：

- **行为保持**：对车机和用户不可见；允许新增接口与转发层；日志序列与时间线必须和基准一致。
- **行为变更**：有意改变可见行为（包括修正重构中发现的疑似缺陷），单独开，单独验证。

规模：S 约 200 行以内，M 200–600 行，L 600 行以上（含测试）。回归级别 T1/T2 见第 6 节。

### Phase 0：护栏（不改行为，可随时合入）

已完成（`app/src/test/java/com/mcarplay/app/architecture/`）：

- `ArchitectureRulesTest`：扫描主源码中去掉注释与字符串后的代码，断言 3.1 的依赖方向，并执行 3.6 的车型名规则；
  允许清单登记 6 处现有例外，例外修复后若不删清单条目，测试同样失败；每个顶层包都必须归类。
- `LogContractTest`：解析 `tools/logbot/rules.py`。`LOG_PATTERNS` 的每条规则都要归入「依赖应用措辞」或「只看系统文本」；
  依赖应用措辞的短语（`CARLIFE:`、`VIDEO: 车机关闭了屏幕数据口`、`VIDEO: CarWith 参数集`、`STREAM_END_SCREEN_OFF`、
  `POWER: 未豁免电池优化`、`VIDEO: 转码统计`）必须仍出现在主源码的字符串字面量里；`STAGE_LABELS` 与 `ConnectionStage` 的名称和
  文案一致，`CODE_ADVICE` 覆盖全部 `DiagnosticCode`。
- `KotlinSourceTest`：覆盖上面两项依赖的词法拆分（嵌套块注释、字符串模板、原始字符串、字符字面量、转义引号）。
- 已做反向验证：在 `net/EndpointHosts.kt` 加一行 `import com.mcarplay.app.SessionController`，并把 `VIDEO: 转码统计` 改成别的措辞，
  对应两个测试都失败；验证后已还原。
- 历史事故对照表（附录 B）。
- 对比脚本 `tools/compare_diagnostics.py`（附 `test_compare_diagnostics.py`），用法与判据见第 6 节。`LogContractTest` 同时守住它依赖的
  报告分节标题、报告头时间和进程起点日志行的格式。脚本只用合成报告和本机的自检日志验证过，还没有在真实支持报告上跑过。

待完成：

- **基线实车报告**：按附录 A 在当前 `main` 上跑一遍四台接收端，留存脱敏支持报告（不进仓库）。这也是 `main` 本来就欠的回归。

完成标准：门禁通过；对比脚本可用；基线报告已归档。

### Phase 1：接口先行与收尾合一（行为保持）

改动小，直接针对收尾类回归，放在抽模块之前。

| PR | 内容 | 规模 | 测试 |
| --- | --- | --- | --- |
| 1a ✅ | 定义 `AttemptScope`（`LegacyAttemptScope` 读现有标志）与 `SessionUiPort`（阶段提示、提示、SSID、确认框），`DialogSpec` 移到 `session/`；`onCarPlayEndpoint` 的 36 处归属判断与全文件 54 处界面写入改为经接口，不搬代码，门面字段保留 | M | 等价性表驱动测试；界面端口的转发测试；「界面状态只经端口写入」的源码守护 |
| 1b | 引入 `SessionEnvironment`，先只收 `ConnectionDiagnostics`、`BtSessionGate`、屏幕相关对象（收尾清单要用的那些） | M | 假环境可记录调用顺序 |
| 1c | 收尾合一：`retireSession()`、`VideoSender.onEnded` 的收尾段、`disconnect()` 改为调用同一份 `endAttempt(EndReason)` 清单，各原因的步骤集合与 2.5 的表逐项相同 | M | 三种原因各一条「步骤集合与顺序」测试；`ROUND_RESET` 不触发 BT detach；附录 B-4、B-13、B-14 |

完成标准：收尾清单从 3 处降到 1 处；T2 回归通过。

### Phase 2：行为保持的抽取（削薄 `SessionController`）

每项先加必要的接口缝，再把代码移过去。按耦合从低到高排序。

| PR | 内容 | 规模 | 测试 |
| --- | --- | --- | --- |
| 2a | `session/EventCommandRouter`：3523–3717 的 `when(command["type"])` | M | 五类命令的分发；非当前轮丢弃 |
| 2b | `net/EndpointResolver`：`awaitCtrlInvite`、`resolveEndpointHost`、`mergeBrowserEndpoint`、`enrichEndpointFromBrowser`、`preferEndpoint`、`txtValue` | M | 端点合并优先级、IPv6-only、邀请超时（纯函数部分直接测） |
| 2c | `net/CarWifiJoiner`：1271–2100、4130–4181 与 4078–4128 的分派；先提取 `CarWifiLink` 接口；状态 `boundCarSsid`/`lastCarWifiSsid`/`lastWifiPsk` 归入 `CarWifiState`；这段代码里 58 处对会话标志和 UI 钩子的引用改走 1a 的接口 | L | 四条入网路径的回退顺序、取消、超时；附录 B-2 |
| 2d | `audio/AudioChannelStarter`：`startAudioChannel`、`attributeAudioTeardown`、`audioCandidateKeys`、`maybeStartAudioNow`、`retryAudioAfterClaimReturned` 及 `activeAudio*` 状态 | M | 档位候选顺序、20 s 存活窗口归因、看门狗守卫；附录 B-15 |
| 2e | 帕萨特回屏改为 `Policy` 上的中性字段（不搬代码） | S | `ReceiverCompatibility` 测试覆盖原有各策略 |
| 2f | `airplay/ScreenOwnership`：`ModesAction` 队列、`enqueueModesAssert`、`scheduleModesReclaim`、`scheduleTakeBackCheck`、回屏策略、推流中邀请重连 | M | 合并语义、让出/拿回间隔；附录 B-11、B-17 |
| 2g | `session/StreamPipeline`：正式 CarLife 源与路由、`newScreenSender`、主屏流重建（779–869、3717–3955） | L | 入流门控、旧源迟到回调隔离；附录 B-18 |
| 2h | `service/ScreenPowerController`；消除 3.1 列出的反向依赖 | S | 架构测试允许清单删去对应项 |

完成标准：`SessionController` 的 `@Volatile` 字段从 46 降到 15 以内；全局单例的直接调用只剩在 `SessionEnvironment` 的生产实现里；T2 回归通过。

### Phase 3：统一轮次归属（中风险）

- 用真正的 `AttemptScope` 实现替换 `LegacyAttemptScope`，按 3.3 的映射处理 #1–#8；先让新旧两套并行判断并在不一致时记
  `SCOPE_MISMATCH` 日志，跑一轮实车后再删旧判断。（M）
- 把 24 处阻塞等待改为 `scope.await/scope.sleep`，取消时立即返回。（M）
- 测试：在假环境上覆盖「各阶段中途取消」「配对中 BT 断开」「收尾窗口内晚到 0x4301」「断开后立即重连」「旧 CarLife 源迟到回调」；附录 B-5、B-7。

完成标准：归属机制从 8 种降到 `AttemptScope` 加 CarLife lease 与客户端内部代际；`connectCancelled`、`ownsConnection()`、
`currentClient !== client` 在流程代码中归零；`SCOPE_MISMATCH` 在 T2 回归中未出现。

### Phase 4：显式状态（中高风险，拆三步）

| PR | 内容 | 规模 | 测试 |
| --- | --- | --- | --- |
| 4a | `ConnectionSession` / `Attempt` / `AttemptRunner`：`onCarPlayEndpoint` 拆为 `joinWifi → discover → pair → setup → stream` 五个阶段方法；两处具名 BT detach 请求（3.2） | L | 阶段顺序；附录 B-12、B-16 |
| 4b | `SessionPhase` + `transition` + `SessionStateHolder`，接管 3.4 列出的跨轮决策 | M | 附录 B 中标为 `transition` 的事故逐条成为具名测试 |
| 4c | UI 改读 `SessionSnapshot`；删除 `sessionStarting`、`videoSender` 等对外可变字段；调整两处直接改写单例字段的测试 | M | 快照在门面方法返回前已更新 |

完成标准：`onCarPlayEndpoint` 的最深嵌套从 16 层降到 4 层以内，单个阶段方法不超过约 150 行；附录 B 的事故都有对应测试；
`SessionController` 只剩门面；T2 回归通过且与基准一致。

### Phase 5：拆 `AirPlayClient`（可选）

`AirPlayClient` 保留为门面，内部拆出：`RtspConnection`（`ensureConnected`/`request`/`readLoop`/`dispatchResponse`/解析）、
`PairingFlow`（`runPairing`/`runAuthSetup`/`retryWithPairing`）、`InfoProbe`、`IapTunnel`（`IapTunnelClient`/`runIapTunnel`/`armMib3VirtualLink`）、
`AudioNegotiation`（`runAudioStream`/`audioCandidates`/`probeAndFixVolume`）、`ScreenStreamControl`（`runScreenStream`/`teardownScreenStream`/`record`）。
现有 `AirPlay*Test` 是这一步的主要护栏。是否做，视 Phase 4 之后的维护情况再定。

### Phase 6：`MainActivity`（低优先级）

把连接前置检查链（`ensureBluetoothReady` → `ensureWifiLocationReady` → … → `continueAfterBackgroundStartCheck`）收成
`ui/ConnectPrecheckFlow`，Shizuku 与残留显示状态收成独立控制器，沿用现有 `ui/*Controller` 的写法。

## 6. 回归方案

回归分级，不再要求每个 PR 都跑四台接收端的全矩阵：

| 级别 | 何时 | 内容 |
| --- | --- | --- |
| T0 | 每次提交 | 门禁（Spotless、单测、lint、构建）与架构/契约测试 |
| T1 | 每个行为保持 PR | 一台测试台接收端（DiPlay、WinPlay、macplay 轮换）跑 3 个冒烟场景：带声音冷启动、断开后立即重连、Wi-Fi 入网中取消；对比脚本确认时间线与日志序列和基准一致 |
| T2 | 每个 Phase 结束，以及每个行为变更 PR | 四台接收端（DiPlay、WinPlay、macplay、艾瑞泽 8）全矩阵，发一个 `-test.N` 预发布 |

- **全矩阵场景**：冷启动（带声音/不带声音）；用户断开后立即重连；Wi-Fi 入网中取消；配对中断开蓝牙（车机熄火）；
  推流中回原车界面再点回 CarPlay；持续推流 30 分钟。Phase 3、4 额外跑：空端点等晚到 0x4301（有条件的车机）。
- **工作量估算**：原方案 Phase 1–3 约 14 个 PR 各跑全矩阵，约 390 次场景、14 轮实车。分级后约 16 个 PR 的 T1 共 48 次冒烟，
  加 4 次 T2 共 112 次，实车从 14 轮降到 4 轮。数字是估算，用来说明量级。
- **记录**：按附录 A 的模板；新现象按 AGENTS.md 写进 `docs/CARWITH-ENTRY-FINDINGS.md`。

### 对比脚本

```bash
python3 tools/compare_diagnostics.py 基准报告.txt 候选报告.txt
```

两份报告是同一场景在基准构建和候选构建上各导出的支持报告。退出码 0 表示一致，1 表示有差异。

- **时间线**：取两边最近 N 次连接尝试（`--attempts N`，默认 1，从末尾对齐；默认只看 `carplay` 轨道）。比较阶段顺序、里程碑首次出现的顺序、
  结束状态与错误码、告警码、记录项、首包事件、各媒体计数是否前进。耗时与帧数不计入结论，首帧和结束耗时作为提示输出。
- **日志**：只取这些尝试的时间窗内的行（首个事件前 3 秒到末个事件后 15 秒，可用 `--margin-before/--margin-after` 调整）。
  日志行只有时分秒，日期靠每次进程启动写的 `DIAGNOSTICS: … timeMs=…` 行推算；该行被截断时退回报告头的导出时间；都没有就比较全部日志并说明。
- **归一化**：去掉时间戳，数字、长十六进制、UUID、地址和脱敏别名换成占位符；`0x4301` 这类四位以内的协议消息号保留。
- **判据**：按标签分组（`AIRPLAY:`、`SESSION:`、`>>>` 等），比较每个标签下「出现过哪些行」和「各行首次出现的先后」。
  重试次数、周期性输出的次数、不同标签之间的交错都不比较。「基准一次、候选多次」只作为提示，`--strict-counts` 时计入结论。
- **默认忽略的标签**：`ENV`、`HID`、`HID-RAW`、`TIMING`、`DIMMER`、`UI`、`UPDATE`、`DISCLAIMER`、`BTSNOOP`、`DIAGNOSTICS`、`PRIVACY`、
  `UNREDACTED`。用 `--ignore-tag`、`--keep-tag`、`--ignore-line 正则` 调整。
- **噪声基线**：同一构建跑两次也会有差异（线程竞态导致的先后变化）。把第二次基准运行传给 `--noise-baseline`，
  两次基准之间同样存在的差异会被标为噪声、不计入结论。建议每台接收端的基线都采两份。
- 也接受 `run-as` 取出的裸 `main_debug.log`：没有时间线，只比较全部日志。
- **测试台之外的车型**（奥迪 MMI、帕萨特/ID7、本田 HFT、凯迪拉克 CT5 等）有专门分支但不在必测集。主要依靠附录 B：
  每条历史事故在对应 PR 里变成 JVM 测试。能联系到车主时随 T2 的 test 版一并验证；联系不到的在 CHANGELOG 里写明未验证。

## 7. 与发布的关系

- 门槛按条件而不是版本号：当前 `main`（v0.1.6，2026-11-06）欠的四台接收端回归做完，Phase 1 即可开始。那次回归同时是 Phase 0 的基线，
  与下一个正式版叫什么无关。
- Phase 0 只加测试与工具，可随时合入。
- Phase 1 起从 `main` 切 `refactor/session-*` 分支逐个合入；每个 Phase 结束跟一个 `-test.N` 预发布，拿到 T2 结果再进下一阶段。
- 重构期间其他功能/修复照常进 `main`；改到尚未抽取的 `SessionController` 段落时，抽取 PR 以最新 `main` 为准重做该段，不长期保留大分支。
- 每个 Phase 结束时更新 `IMPLEMENTATION_PLAN.md` 的当前基线，并在本文标记进度。

## 8. 风险

| 风险 | 缓解 |
| --- | --- |
| 抽取时漏掉某个注释里记载的时序约束 | 常量与注释整体搬移；PR 描述列出搬走的每条约束及其新落点；附录 B 对应的测试 |
| 日志措辞变化导致 logbot 规则失配 | `LogContractTest`；改措辞必须同 PR 改 rules.py |
| 收尾合一时无意改变某条路径的步骤 | 1c 先写三种原因的步骤集合测试并让它在旧代码的包装上通过，再合并实现；疑似遗漏不在此修 |
| Phase 3 新旧归属判断不一致 | 并行判断 + `SCOPE_MISMATCH` 日志，确认一轮实车后再删旧逻辑 |
| 状态锁内执行了阻塞动作 | `transition` 是纯函数，动作一律在锁外执行；单测断言 `dispatch` 不调用环境接口 |
| 抽取 PR 与并行的功能修复冲突 | PR 保持小（S/M 为主），以最新 `main` 为准；L 级的 2c、2g、4a 避开发布窗口 |
| 测试台外车型回归无法覆盖 | 附录 B 的测试；见第 6 节；不在 release 说明里声称这些车型已验证 |

## 9. 待确认问题

1. 新包名用 `session/` 是否可以，还是倾向放在根包 `com.mcarplay.app` 下？
2. `SessionSnapshot` 用简单的监听器回调（与现有 `logListener` 一致），还是引入 `androidx.lifecycle` 的 `LiveData`/`StateFlow`？
   方案默认前者，不新增依赖。
3. 分级回归（T1 只跑一台测试台接收端的 3 个场景）是否可以接受？这改变了「合并 main 必跑四台」的惯例，仅限标为行为保持的 PR。
4. 2.5 表里车机拆流路径不停 `AudioSender`、不关 `AirPlayClient`，是有意为之还是遗漏？方案默认原样保留。
5. 测试台之外的车型（奥迪、帕萨特、ID7、本田、凯迪拉克）有没有可以联系上做 test 版验证的车主？
6. Phase 5（拆 `AirPlayClient`）是否纳入计划，还是视 Phase 4 后的维护情况再定？

## 附录 A：回归记录模板

T1、T2 以及 Phase 0 的基线，每台接收端填一份，贴进 PR 描述。支持报告保持脱敏，存放在仓库之外（例如本机
`~/mcarplay-regression/<日期>-<接收端>/`），这里只写文件名。真车上观察到的新现象按 AGENTS.md 另记到 `docs/CARWITH-ENTRY-FINDINGS.md`。
T1 只填前三个场景。

```text
日期：YYYY-MM-DD        级别：<基线 | T1 | T2>
构建：<分支> @ <commit>，versionName=<x.y.z>，flavor=standard，debug/release
基准：<基线 commit>（Phase 0 归档的报告）

手机：<型号> / HyperOS <版本> / Android <版本>
CarWith：<版本>    小米 CarLife：<版本>
接收端：<DiPlay | WinPlay | macplay | 艾瑞泽 8 | 其他> / 固件 <版本>
车机侧连接方式：<车机发起 | 手机主动连 | 测试台回连>

场景（结果：通过 / 失败 / 未测；失败时写现象与报告文件名）
| 场景                                   | 结果 | 首帧耗时 | 备注 |
| -------------------------------------- | ---- | -------- | ---- |
| 冷启动连接（带声音）                   |      |          |      |
| 用户断开后立即重连                     |      |          |      |
| Wi-Fi 入网中取消                       |      |          |      |
| 冷启动连接（不带声音）                 |      |          |      |
| 配对中断开蓝牙（车机熄火/关蓝牙）      |      |          |      |
| 推流中回原车界面，再点回 CarPlay       |      |          |      |
| 持续推流 30 分钟                       |      |          |      |
| 空端点等晚到 0x4301（仅 Phase 3/4）    |      |          |      |

观察：画面 <正常/花屏/黑屏/卡顿>；声音 <正常/无声/断续/未开>；触控 <正常/偏移/无响应>
对比（tools/compare_diagnostics.py）：时间线 <一致 | 不一致：…>；日志序列 <一致 | 第一处分歧：…>；错误码 <一致 | 新增：…>
支持报告：<文件名列表>
结论：<可合并 | 阻塞：…>
```

## 附录 B：历史事故对照表

来源是 `SessionController` 与 `ReceiverCompatibility` 注释里带日期的实车记录（行号为基线）。每条在所列 PR 中成为具名测试；
多数车型不在必测集里，这张表是它们的主要回归手段。「落点」一栏：`transition` 指 3.4 的转移函数，`收尾` 指 3.2 的清单。

| # | 出处 | 事件序列 | 期望 | 落点（PR） |
| --- | --- | --- | --- | --- |
| B-1 | 2026-10-11，Activity 重建（:84） | 管线运行中 Activity 销毁重建，再点「连接」 | 沿用现有会话对象，不建第二套；「断开」能停掉原管线 | `transition`：运行中 `UserStart`（4b） |
| B-2 | 2026-10-15，DiPlay（:1273） | 推流中收到冗余 0x5703 | 只刷新凭据缓存，不重绑网络、不重注册 ctrl | `transition` + `CarWifiJoiner`（2c、4b） |
| B-3 | DiPlay，未记日期（:3159） | 推流中接收端重连后重发 0x4301 | 忽略该端点，不重启配对 | `transition`（4b） |
| B-4 | 2026-10-16，DiPlay（:1002、:1195） | 新一轮配对起步时的重置里 detach 了 BT | `ROUND_RESET` 不 detach BT（否则车机判 bring-up failed，连上约 3 秒即断） | 收尾（1c） |
| B-5 | 2026-10-22，奥迪 3315（:580） | 配对未完成时 BT 链路断开，随后车机重连并送来新端点 | 未推流时取消本轮并释放互斥，新端点被接受；`KEEP_NETWORK_SESSION` 时不取消 | `transition` + `AttemptScope`（3、4b） |
| B-6 | 2026-10-22，奥迪 3315（:601） | 手机已在车机热点上，本轮既无 0x5703 也无 0x4301 | 提供已知 SSID，蓝牙会话不拆链 | `ConnectionSession` 提供 `knownCarSsid`（4a） |
| B-7 | 2026-10-18（:4062） | 配对已失败、互斥尚未释放的窗口内晚到 0x4301 | 暂存，本轮收尾时补投一次（未取消时） | `transition`（3、4b） |
| B-8 | 奥迪 MMI 9310（:120、:2725） | 空端点后 0x4301 晚到 2 分 35 秒；或 5 分钟仍未到 | 保活等待并接受晚到端点；超时才经具名请求 detach BT | `transition` + 具名 detach（4a、4b） |
| B-9 | 2026-10-31，大众（:2689） | 空端点等待期间只收到 ctrl 邀请 | 绑定当前 Wi-Fi 后按邀请继续，每次新邀请最多试一次 | `transition`（4b） |
| B-10 | 2026-11-01 21:45，帕萨特（:934） | 会话进行中再次调用「启动连接」（上车自动连接） | 不清零邀请计数与重试次数 | `transition`：运行中 `UserStart`（4b） |
| B-11 | ID7 / 帕萨特，Cinemo 栈（`Policy.reconnectOnStreamingInvite`） | 推流中车机发 ctrl 邀请 | 该策略为真时退役并按邀请重连；其余车机只记日志 | `ScreenOwnership` + `transition`（2f、4b） |
| B-12 | 2026-10-11、2026-10-21（:3412–3418） | 配对失败后考虑「BT 退役重试」 | 仅当策略允许、本轮尝试过配对、未被 403 时触发 | 已有 `PairingFallbackTest`；补具名 detach 的调用条件（4a） |
| B-13 | 2026-10-19（:3857） | 车机拆流结束会话，没有用户操作 | 暗幕与常亮一并收掉，手机屏不在 45 秒后变黑 | 收尾：`RECEIVER_ENDED`（1c） |
| B-14 | 2026-10-23（:3865） | 车机熄火拆流，用户未点「断开」 | shell 兜底保存的系统网络被清理 | 收尾：`RECEIVER_ENDED`（1c） |
| B-15 | 2026-10-19 Honda HFT；2026-10-23 MIB3（:2397、:2412） | 音频起流后 20 秒内被拆；或链路协商完成前被车机看门狗拆 | 前者记该档位不兼容；后者不记 | `AudioChannelStarter`（2d） |
| B-16 | 2026-10-17，奥迪（:3754、:3948） | 屏幕 SETUP 完成 | 先连数据口；先 RECORD 起视频，再协商音频 | `AttemptRunner` 阶段顺序（4a） |
| B-17 | 2026-11-01 22:47、23:22，帕萨特（:3674、:2781） | 回原车主界面后再点 CarPlay 图标 | 按策略让出/拿回主屏；拿回 3 秒后记录车机反应 | `ScreenOwnership`，已有 `ModeArbiterTest`（2f） |
| B-18 | 未记日期（:824） | 旧 CarLife 源的 `onEnded` 晚于新一轮启动 | 不停掉新一轮的发送端 | `StreamPipeline`（2g） |

不进状态机、但搬移时必须连注释原样保留的时序常量：`CTRL_INVITE_WAIT_MS` 9 秒（帕萨特 2026-11-01 23:02，原 4 秒超时）、
`SPECIFIER_JOIN_TIMEOUT_MS` 40 秒（奥迪 3315 2026-10-22，车机 15 秒拆 BT）、`PENDING_START_SESSION_WAIT_MS` 5 分钟（奥迪 MMI 9310）、
`AUDIO_PROFILE_PROBE_MS` 20 秒（2026-10-19，拆链发生在起流后 6.5 秒）、`WIFI_BIND_WAIT_S` 15 秒。

## 修订记录

**2026-11-10 Phase 1a 实施时的调整：**

- `AttemptScope` 拆成 `isCurrent` 与 `isActive` 两个判断（3.3），原稿只有 `isActive`，无法表达「已取消但仍负责收尾」。
- `cancel`/`own`/`await`/`sleep` 等成员推迟到第一个用到它们的 PR 再加。
- 不再单独定义 `SessionLog`：代码库现有模块统一用 `log: (String) -> Unit` 构造参数，抽出的模块沿用即可，另起接口没有收益。
- 记下 2c 的行为风险：Wi-Fi 入网代码只查取消标志、不查归属，换成 `scope.isActive` 不是等价替换。

**2026-11-10 第一次修订**（对照 `ab0cb93` 复查后）：

- 更正：一轮流程内有两处合法的 BT detach（3.2），原稿「Attempt 拿不到 detach 接口」不成立，改为两个具名请求。
- 更正：Phase 1 原称「纯搬移」不准确（Wi-Fi 入网段有 58 处会话标志/UI 钩子引用），改为「行为保持的抽取」，并先定义接口。
- 更正：`retireSession` 不得 `resetAll` 的约束指向已删除的代码，改为历史说明。
- 新增：2.5 三条收尾路径现状、2.6 全局单例、3.8 `SessionEnvironment`、附录 B 历史事故对照表。
- 调整：收尾从「两级」改为一份按原因执行的清单并提前到 Phase 1；状态机收窄到跨轮决策，用锁同步执行，不设事件线程；
  原 Phase 3 拆成 4a–4c；`ScreenOwnership` 抽取前先把帕萨特回屏改为 `Policy` 字段。
- 调整：回归分 T0/T1/T2 三级；Phase 0 增加对比脚本；发布门槛由「1.0.0 之后」改为「当前 main 的回归完成后」；
  完成标准由行数改为字段数、嵌套层数、归属机制数与收尾清单数。
