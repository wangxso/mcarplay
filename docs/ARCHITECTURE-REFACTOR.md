# 会话编排层重构方案（草案）

基线：`main` @ `ab0cb93`（v0.1.6，文中行号与统计均以此为准，搬移后会漂移）。
状态：**Phase 0 的代码护栏已在 `refactor/session-phase0-guardrails` 完成（只加测试，不改生产代码）；基线实车报告待采集；
Phase 1 起尚未开始。** 本文仍是讨论稿：第 9 节的问题确认后再定稿、拆 PR。

## 1. 背景与目标

协议层（`carlife/`、`iap2/`、`airplay/`、`bt/`、`net/`、`video/`、`audio/`）的分包和单测覆盖整体健康；问题集中在把它们串起来的
编排层 [`SessionController.kt`](../app/src/main/java/com/mcarplay/app/SessionController.kt)。近几个版本的回归（「连上约 3 秒即断」
「僵尸轮次堵门」「SETUP 后控制通道被关」「旧轮迟到回调停掉新一轮发送端」）都出在**轮次归属与收尾时机**上，而这部分恰好没有
单元测试，只能靠实车回归兜底。

目标（按优先级）：

1. **生命周期显式化**：「一次用户连接」与「一轮端点 → 配对 → 推流」成为两个有类型的对象，各自负责自己的收尾，
   现在写在注释里的「这里绝不能做 X」变成编译期结构。
2. **轮次归属只有一种机制**：取代现在并存的 8 种标志/代际/身份比较（见 2.3）。
3. **编排逻辑可单测**：阶段转移、取消、晚到事件、收尾竞态能在 JVM 单测里复现。
4. **车型差异只经 `ReceiverCompatibility.Policy` 进入流程**，流程代码不出现车型名。

非目标：不重写协议实现；不改变任何对车机可见的行为；不迁移到协程；不拆 Gradle 多模块（见 3.5、3.7）。

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
| 94–380 | 常量、`ensure`、日志缓冲、媒体键分发 | `session/SessionLog`；媒体键 → `input/` |
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
- 基线已有 6 处例外，登记在测试的允许清单里，计划在 Phase 1f 消除：
  - `carlife/CarLifeDiagnostics` → `SessionController`、`service`
  - `carlife/ExternalHuController` → `SessionController`（这两处直接调用 `appendLog/claimCarLifeSession/releaseCarLifeSession`，
    改为注入 `log` 与 `CarLifeSessionRegistry`）
  - `diagnostics/SupportReport` → `SessionController`（读取蓝牙诊断摘要，改为由调用方传入）
  - `diagnostics/btsnoop/BtSnoopActivity` → `ui`（本身是界面，考虑移到 `ui/`）
  - `bt/CarBluetoothReceiver` → `service`（系统广播入口，负责拉起前台服务，可保留并注明）
- 新增顶层包必须在测试里归类为引擎层或编排/界面层，否则测试失败。
- `session/` 内的流程代码只读 `Policy` 字段，不按车型名分支。

### 3.2 生命周期：两级对象、两级收尾

| | `ConnectionSession` | `Attempt` |
| --- | --- | --- |
| 对应 | 一次用户连接（点「连接」到「断开」/BT 终态） | 一轮端点 → 绑网 → 配对 → SETUP → 推流 |
| 持有 | BT server/client、ctrl 广播、`_airplay._tcp` 发现器、Wi-Fi 连接器租约、诊断 attempt | `AirPlayClient`、`VideoSender`、`AudioSender`、CarLife 源与音频桥、事件路由、`StreamingWifiLock`、暗幕/熄屏 |
| `close()` 负责 | 当前 Attempt 收尾 → 按 iPhone 顺序 TEARDOWN（失败则离网）→ `0xEA01` 收 EA 会话 → 停 BT/ctrl/发现器 → 释放 Wi-Fi 与 shell 兜底网络 → 强制横屏复原 `resetAll` | 停流、关客户端、恢复物理屏、释放锁、清共享密钥 |
| `close()` **不得**做 | — | detach BT 控制会话（DiPlay 2026-10-16）、`resetAll`（奥迪 3315 2026-10-23）、停 ctrl 广播、清邀请计数 |
| 实现约束 | 持有 BT 句柄 | **构造时拿不到** BT 会话的 detach 接口，只拿只读的 `LinkFacts`，于是「不得 detach」由类型保证 |

`retireSession()` 的两种用法因此分开：每轮起步 = 上一个 `Attempt.close()`；终态 = `ConnectionSession.close()`。

### 3.3 `AttemptScope`：统一归属与取消

```kotlin
class AttemptScope(val id: String, private val epoch: Long) : AutoCloseable {
    val isActive: Boolean
    val cancelReason: DiagnosticCode?

    fun cancel(reason: DiagnosticCode)                     // 幂等；唤醒本轮所有等待并关闭登记的资源
    fun checkActive()                                      // 已取消则抛 AttemptCancelled（单点 catch）
    fun <T : AutoCloseable> own(resource: T): T            // 登记资源，close/cancel 时逆序关闭
    fun await(latch: CountDownLatch, timeoutMs: Long): Boolean  // 取消即返回 false
    fun sleep(ms: Long)                                    // 可被 cancel 打断
    fun isCurrent(): Boolean                               // 取代 ownsConnection / currentClient === client / connectionGeneration
}
```

映射：

- #1 `sessionStarting` → 「`ConnectionSession.activeAttempt != null`」；外部只读 `SessionSnapshot.phase`。
- #2 `connectCancelled` → `scope.cancel(USER_STOP)` 或 `scope.cancel(BT_LINK_CLOSED)`，原因不再混用一个布尔。
- #3/#4/#5 → `scope.isCurrent()`；`ReceiverDetectionRuntime` 的 epoch 作为 `AttemptScope.epoch` 的来源，不再另判。
- #6 CarLife lease 保留（它还管诊断自检的互斥），但 lease 归 Attempt 持有，迟到回调先过 `scope.isCurrent()`。
- #7 `disconnectCompletion` → `ConnectionSession.closed: CountDownLatch`，下一次 `start` 等它。
- #8 `BtSessionGate.sessionActive` 由 `ConnectionSession` 在 Attempt 进入/离开 PAIRING 时统一置位。

现在每个阻塞点之后的样板

```kotlin
if (wasCancelled()) { if (ownsConnection()) retireSession(); appendLog("…"); if (ownsConnection()) sessionStarting.set(false); return@run }
```

收敛为 `scope.checkActive()`，在 `AttemptRunner.run()` 顶层一处 `catch (AttemptCancelled)` 统一记日志、记诊断、收尾。

### 3.4 显式状态与纯转移函数

```kotlin
sealed interface SessionPhase {
    data object Idle : SessionPhase
    data object AwaitingBluetooth : SessionPhase
    data class AwaitingEndpoint(val since: Long) : SessionPhase   // 空端点，等晚到 0x4301
    data class JoiningWifi(val attempt: String, val ssid: String) : SessionPhase
    data class Discovering(val attempt: String) : SessionPhase
    data class Pairing(val attempt: String, val plain: Boolean) : SessionPhase
    data class SettingUp(val attempt: String) : SessionPhase
    data class Streaming(val attempt: String, val audio: Boolean) : SessionPhase
    data class Stopping(val reason: DiagnosticCode) : SessionPhase
}

sealed interface SessionEvent { /* UserStart, UserCancel, UserDisconnect, Endpoint, LateEndpoint, WifiCredentials,
                                   SenderLinkClosed, CtrlInvite, StreamingInvite, WifiLost, StreamEnded, StageDone */ }

/** 纯函数：只决定下一状态与要执行的动作，不做 I/O。 */
fun transition(phase: SessionPhase, event: SessionEvent, policy: Policy?): Pair<SessionPhase, List<SessionAction>>
```

- 冗余端点忽略（推流中再来 0x4301）、「已有会话在处理中」、BT 断开时 `KEEP_NETWORK_SESSION` 与 `END_SESSION` 的分支、
  收尾时补投暂存的晚到 0x4301，这些现在散在流程里的判断都进 `transition`，可以穷举单测。
- `ConnectionStage`（诊断）由 `SessionPhase` 映射得到，不再由流程手动 `timeline.stage(...)`，两者不会不一致。
- UI 只读 `SessionSnapshot(phase, phaseHint, framesSent, audioActive, scalePercent, targetFps)`，
  取代 `isSessionRunning() || sessionStarting.get()` 与直接读 `videoSender`。

### 3.5 线程模型（决策：不引入协程）

- 保留「每个 Attempt 一条阻塞线程，按阶段线性执行」的写法：BluetoothSocket、Socket、`requestNetwork` 回调都是阻塞或回调式 API，
  线性代码最好读，也与 `CarLifeSession` 一致。
- 外部事件（BT 回调、ctrl 邀请、网络丢失、用户操作）不直接改状态，而是投递到一个单线程 `session-events` executor，
  在那里调用 `transition` 并执行动作。状态只在这条线程上写，`@Volatile` 字段随之大幅减少。
- 取消靠 `AttemptScope` 关闭登记的 socket/连接器并唤醒等待，不再依赖「等它自己醒来查标志」。
- 项目当前没有 `kotlinx-coroutines` 依赖；迁移协程的收益（结构化取消）已由 `AttemptScope` 覆盖，成本是全量改写阻塞 I/O，不划算。
  1.x 稳定后如有新需求再评估。

### 3.6 车型差异规则

- 流程代码（`session/`、`net/CarWifiJoiner`、`audio/AudioChannelStarter`、`airplay/ScreenOwnership`）只能读 `Policy` 字段。
- 现有直接分支（例如 `passatReturnStrategy()`/`applyPassatReturnStrategy`、音频/RECORD 里的车型判断）改为 `Policy` 上的具名字段，
  字段 KDoc 保留日期证据，与现有 `Policy` 写法一致。
- 车型名可以继续出现在注释与日志里（日志文本是 logbot 契约，见 4）。架构测试只检查去掉注释和字符串后的标识符：
  按驼峰/下划线拆词后不得出现 passat、audi、honda、cadillac、diplay、winplay、macplay、chery、arrizo、mib3、id7、vw、cinemo。
  上述流程文件建立前，这条规则没有可扫描的文件。

### 3.7 不拆 Gradle 多模块

单 `app` 模块 + 包边界 + 架构测试已经足够；多模块会牵动 `standard`/`remote` flavor、Spotless、lint baseline 与 CI 门禁，
收益只是编译隔离。若将来想把纯 JVM 协议层抽成 `:protocol` 以加速测试，再单独评估。

## 4. 不变量（每个 PR 都必须保持）

1. **日志文本是契约**：[`tools/logbot/rules.py`](../tools/logbot/rules.py) 按日志文本匹配（如 `CARLIFE:` 前缀、
   `VIDEO: 车机关闭了屏幕数据口`、`POWER: 未豁免电池优化`）。搬移时日志前缀与措辞逐字保留；必须改时同 PR 改 rules.py 与其测试。
2. **诊断时间线不变**：同一接收端、同一操作序列，`ConnectionTimeline` 的 stage/milestone 顺序与 `DiagnosticCode` 不变
   （用 `tools/analyze_diagnostics.py` 对比重构前后的支持报告）。
3. **对车机可见的行为不变**：报文顺序、时序常量（`CTRL_INVITE_WAIT_MS`、`SPECIFIER_JOIN_TIMEOUT_MS` 等）、重试次数原样搬移，常量连同注释一起走。
4. **门面 API 不变**：MainActivity、CarPlayService、UpdateActivity、`remote` flavor 的 `BridgeCommands` 调用的公开成员保持签名，
   内部改为转发；`sessionStarting`、`videoSender` 这类直接暴露的可变字段在 Phase 3 统一换成 `SessionSnapshot` 后再删。
5. 不在视频、音频、UI 线程上做磁盘 I/O；`applicationId`、`run-as` 路径不动；`remote` 代码不进 `standard`。

## 5. 分阶段实施

每个 PR 只做一件事，标题写明「行为不变」或「行为变更」。Phase 1 全部是纯搬移。

### Phase 0：护栏（不改行为，可在 1.0.0 之前合入）

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

待完成：按附录 A 的模板在基线版本上跑一遍四台接收端，留存脱敏支持报告作为对比基准（报告不进仓库）。

完成标准：门禁通过；基线报告已归档。

### Phase 1：纯搬移，削薄 `SessionController`（低风险）

按依赖少到多的顺序，每项一个 PR：

| PR | 内容 | 新增测试 |
| --- | --- | --- |
| 1a | `net/CarWifiJoiner`：1271–2100、4130–4181 与 4078–4128 的分派；状态 `boundCarSsid`/`lastCarWifiSsid`/`lastWifiPsk` 归入 `CarWifiState`；弹窗经 `WifiJoinPrompts` 接口 | 四条入网路径的回退顺序、取消、超时（假 `WifiCarPlayConnector`） |
| 1b | `net/EndpointResolver`：`awaitCtrlInvite`、`resolveEndpointHost`、`mergeBrowserEndpoint`、`enrichEndpointFromBrowser`、`preferEndpoint`、`txtValue` | 端点合并优先级、IPv6-only、邀请超时（纯函数部分直接测） |
| 1c | `audio/AudioChannelStarter`：`startAudioChannel`、`attributeAudioTeardown`、`audioCandidateKeys`、`maybeStartAudioNow`、`retryAudioAfterClaimReturned` 及 `activeAudio*` 状态 | 档位候选顺序、20 s 存活窗口归因、主音频认领后重试 |
| 1d | `airplay/ScreenOwnership`：`ModesAction` 队列、`enqueueModesAssert`、`scheduleModesReclaim`、`scheduleTakeBackCheck`、帕萨特回屏、推流中邀请重连 | 合并语义、让出/拿回间隔、回屏策略按 `Policy` 选择 |
| 1e | `session/EventCommandRouter`：3523–3717 的 `when(command["type"])` | 五类命令各自的分发与「非当前客户端丢弃」 |
| 1f | `session/SessionLog`、`service/ScreenPowerController`；`carlife/` 两处反向调用改为注入 | 架构测试允许清单删去对应项 |

完成标准：`SessionController` 降到约 1900 行以内；新增类各自有单测；每个 PR 四台接收端回归通过。

### Phase 2：统一轮次归属（中风险）

- 引入 `AttemptScope`，按 3.3 的映射逐个替换 #1–#8；先让新旧两套并行判断并在不一致时记 `SCOPE_MISMATCH` 日志，跑一轮实车后再删旧判断。
- 把 24 处阻塞等待改为 `scope.await/scope.sleep`，取消时立即返回。
- 测试：在假传输上覆盖「各阶段中途取消」「配对中 BT 断开」「`finally` 中晚到 0x4301」「断开后立即重连」「旧 CarLife 源迟到回调」。

完成标准：`connectCancelled`、`ownsConnection()`、`currentClient !== client` 在流程代码中归零；`SCOPE_MISMATCH` 在回归中未出现。

### Phase 3：显式状态与两级收尾（中高风险）

- 建 `ConnectionSession` / `Attempt` / `AttemptRunner`；`onCarPlayEndpoint` 拆为 `joinWifi → discover → pair → setup → stream`，
  每个阶段方法控制在约 150 行内，嵌套不超过 4 层。
- `retireSession()` 拆为 `Attempt.close()` 与 `ConnectionSession.close()`，按 3.2 的表格分配职责。
- 引入 `SessionPhase` + `transition` + `session-events` 线程；`ConnectionStage` 改为由 `SessionPhase` 派生。
- UI 改读 `SessionSnapshot`；删除 `sessionStarting`、`videoSender` 等对外可变字段；调整两处直接改写单例字段的测试。

完成标准：`transition` 有穷举测试；`SessionController` 只剩门面（约 300 行）；回归通过且时间线与基准一致。

### Phase 4：拆 `AirPlayClient`（可选，1.0.x 稳定后）

`AirPlayClient` 保留为门面，内部拆出：`RtspConnection`（`ensureConnected`/`request`/`readLoop`/`dispatchResponse`/解析）、
`PairingFlow`（`runPairing`/`runAuthSetup`/`retryWithPairing`）、`InfoProbe`、`IapTunnel`（`IapTunnelClient`/`runIapTunnel`/`armMib3VirtualLink`）、
`AudioNegotiation`（`runAudioStream`/`audioCandidates`/`probeAndFixVolume`）、`ScreenStreamControl`（`runScreenStream`/`teardownScreenStream`/`record`）。
现有 `AirPlay*Test` 是这一步的主要护栏。

### Phase 5：`MainActivity`（低优先级）

把连接前置检查链（`ensureBluetoothReady` → `ensureWifiLocationReady` → … → `continueAfterBackgroundStartCheck`）收成
`ui/ConnectPrecheckFlow`，残留虚拟显示与 Shizuku 状态收成独立控制器，沿用现有 `ui/*Controller` 的写法。

## 6. 回归方案

- **接收端**：DiPlay、WinPlay、macplay、艾瑞泽 8（合并 main 的必测集）。
- **场景矩阵**（每个 Phase 1–3 PR 都跑）：冷启动连接；带声音/不带声音；用户断开后立即重连；Wi-Fi 入网中取消；配对中拔 BT（车机熄火）；
  推流中回原车界面再点回 CarPlay；30 分钟持续推流。Phase 2/3 额外跑：空端点等晚到 0x4301（有条件的车机）。
- **对比方式**：按附录 A 的模板记录；每个场景导出脱敏支持报告，用 `tools/analyze_diagnostics.py` 与 Phase 0 基准比对 stage/milestone 顺序与错误码；
  记录手机型号、HyperOS、CarWith、小米 CarLife 版本与接收端固件，按 AGENTS.md 要求写进 `docs/CARWITH-ENTRY-FINDINGS.md`。
- **测试台之外的车型**（奥迪 MMI、帕萨特/ID7、本田 HFT、凯迪拉克 CT5 等）有专门分支但不在必测集。Phase 1 纯搬移不改分支逻辑；
  Phase 2/3 前列出受影响的 `Policy` 字段，能找到车主的发 test 版验证，找不到的在 CHANGELOG 中说明未验证。

## 7. 与发布的关系

- 1.0.0 之前：只合 Phase 0（护栏，不改行为）。当前 `main` 为 v0.1.6（2026-11-06），WinPlay、macplay、艾瑞泽 8 的回归尚未完成，
  在 1.0.0 发布前不引入结构性改动。
- 1.0.0 之后：Phase 1 从 `main` 切 `refactor/session-*` 分支逐个合入；Phase 2、3 各自跟一个 `-test.N` 预发布，拿到实车反馈再进下一阶段。
- 每个 Phase 结束时更新 `IMPLEMENTATION_PLAN.md` 的当前基线，并在本文标记进度。

## 8. 风险

| 风险 | 缓解 |
| --- | --- |
| 搬移时漏掉某个注释里记载的时序约束 | 常量与注释整体搬移；PR 描述列出搬走的每条「绝不能」约束及其新落点 |
| 日志措辞变化导致 logbot 规则失配 | `LogContractTest`；改措辞必须同 PR 改 rules.py |
| Phase 2 新旧归属判断不一致 | 并行判断 + `SCOPE_MISMATCH` 日志，确认一轮实车后再删旧逻辑 |
| 单线程事件 executor 被阻塞操作卡住 | 规则：事件线程只做 `transition` 与非阻塞动作，阻塞工作一律交给 Attempt 线程；加一条 watchdog 日志 |
| 测试台外车型回归无法覆盖 | 见第 6 节；不在 release 说明里声称这些车型已验证 |

## 9. 待确认问题

1. Phase 0 是否同意在 1.0.0 之前合入（只加测试，不改 `main` 源码行为）？
2. 新包名用 `session/` 是否可以，还是倾向放在根包 `com.mcarplay.app` 下？
3. `SessionSnapshot` 用简单的监听器回调（与现有 `logListener` 一致），还是引入 `androidx.lifecycle` 的 `LiveData`/`StateFlow`？
   方案默认前者，不新增依赖。
4. 测试台之外的车型（奥迪、帕萨特、ID7、本田、凯迪拉克）有没有可以联系上做 test 版验证的车主？
5. Phase 4（拆 `AirPlayClient`）是否纳入 1.x 计划，还是视 Phase 3 后的维护体感再定？

## 附录 A：回归记录模板

每个 Phase 1–3 PR、以及 Phase 0 的基线，每台接收端填一份，贴进 PR 描述。支持报告保持脱敏，存放在仓库之外（例如本机
`~/mcarplay-regression/<日期>-<接收端>/`），这里只写文件名。真车上观察到的新现象按 AGENTS.md 另记到 `docs/CARWITH-ENTRY-FINDINGS.md`。

```text
日期：YYYY-MM-DD
构建：<分支> @ <commit>，versionName=<x.y.z>，flavor=standard，debug/release
基准：<基线 commit>（Phase 0 归档的报告）

手机：<型号> / HyperOS <版本> / Android <版本>
CarWith：<版本>    小米 CarLife：<版本>
接收端：<DiPlay | WinPlay | macplay | 艾瑞泽 8 | 其他> / 固件 <版本>
车机侧连接方式：<车机发起 | 手机主动连 | 测试台回连>

场景（结果：通过 / 失败 / 未测；失败时写现象与报告文件名）
| 场景                                   | 结果 | 首帧耗时 | 备注 |
| -------------------------------------- | ---- | -------- | ---- |
| 冷启动连接（不带声音）                 |      |          |      |
| 冷启动连接（带声音）                   |      |          |      |
| 用户断开后立即重连                     |      |          |      |
| Wi-Fi 入网中取消                       |      |          |      |
| 配对中断开蓝牙（车机熄火/关蓝牙）      |      |          |      |
| 推流中回原车界面，再点回 CarPlay       |      |          |      |
| 持续推流 30 分钟                       |      |          |      |
| 空端点等晚到 0x4301（仅 Phase 2/3）    |      |          |      |

观察：画面 <正常/花屏/黑屏/卡顿>；声音 <正常/无声/断续/未开>；触控 <正常/偏移/无响应>
时间线对比（tools/analyze_diagnostics.py）：stage/milestone 顺序与基准 <一致 | 不一致：…>；错误码 <一致 | 新增：…>
支持报告：<文件名列表>
结论：<可合并 | 阻塞：…>
```
