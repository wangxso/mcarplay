# CarPlay 资料更新与 mcarplay 优化建议

审查日期：2026-11-01（Asia/Shanghai）。代码基线：`aeccca7` 加当前工作区未提交修改；包含正在开发的邀请时效、requestUI 和视频转码候选。本文是资料核对与代码审查，不是认证结论，也不代表候选已通过实车验证。

**结论：优先完善资源仲裁、能力声明和视频输出契约，再扩展新一代功能。** 当前问题不能用“车机太新”解释，也不能只靠补一个 `requestUI → assertModes()` 分支解决。

**资料是否更新**

此前引用的认证指南 R5 封面日期为 2016-05-13。可核验的更晚公开转载为 R7，封面日期为 2016-09-08；这两个版本都属于历史资料。本次未找到可验证的公开现行认证指南修订号，不能将 R7 称为最新版。Apple 当前明确将 CarPlay、iAP2 的技术规范和认证工具放在 MFi 体系内；2024 年的系统架构演讲仍将技术文档访问指向 MFi。[R5](https://usermanual.wiki/Pdf/CarPlay20Certification20Guide20R5.364158415.pdf)、[R7](https://usermanual.wiki/Pdf/CarPlay20Certification20Guide20R7.1840286682.pdf)、[MFi 官方说明](https://mfi.apple.com/en/how-it-works)、[WWDC24 架构](https://developer.apple.com/videos/play/wwdc2024/10111/)

对本项目更有用的更新资料如下。它们补充设计依据，不能代替完整的当前线协议规范。

| 资料 | 可用于本项目的内容 | 使用边界 |
| --- | --- | --- |
| [Developing CarPlay Systems, Part 2，WWDC16](https://developer.apple.com/videos/play/wwdc2016/723/) | 控制端资源管理，主屏和主音频的 take / borrow / unborrow、约束及 appStates | 虽然较早，资源仲裁仍是本项目目前缺失的重要基础 |
| [Developing Wireless CarPlay Systems，WWDC17](https://developer.apple.com/videos/play/wwdc2017/717/) | 蓝牙发现、Wi-Fi 交接和无线重连的整体流程 | 大部分要求面向车机；Android 手机桥接需要按角色转换 |
| [Advances in CarPlay Systems，WWDC19](https://developer.apple.com/videos/play/wwdc2019/252/) | 显示尺寸、view area、动态布局等显示契约 | 不能只读宽高而忽略画布和输入坐标之间的关系 |
| [Optimize CarPlay for vehicle systems，WWDC23](https://developer.apple.com/videos/play/wwdc2023/10150/) | 显示集成、短暂断链、缓冲音频、HEVC 与 H.264 回退 | 适合区分当前可靠性工作与后续能力扩展 |
| [Meet the next generation of CarPlay architecture，WWDC24](https://developer.apple.com/videos/play/wwdc2024/10111/) | 多显示屏、本地和远端合成、逐帧同步、车辆状态协议 | 属于后续研究方向，不是此次帕萨特失败必须补齐的功能集 |
| [Rev up your CarPlay app，WWDC26](https://developer.apple.com/videos/play/wwdc2026/212/) | 更新的 iOS CarPlay 应用类别和框架能力 | 不是 Android 发送端的蓝牙、RTSP、视频线协议指南 |

mcarplay 扮演手机侧 controller；Apple 的很多公开系统资料站在车机 accessory 侧讲解。应映射双方职责，不应把车机 AP、接收解码器或 iOS 应用 entitlement 的要求直接搬到本项目。

**当前代码已有的改进**

- `Iap2SenderSession` 已有等待热点凭据时轮询 ctrl 邀请的候选，避免一直堵到整体超时。
- `CtrlInviteGate` 已将邀请时刻与 mDNS 兜底区分，配有纯函数测试。
- `VideoSender` / `CarWithTranscoder` 已有“尺寸不符且持续请求关键帧时转码”的候选，转码帧率配置会考虑接收端 maxFPS。
- 现有诊断已区分 CarWith 源帧和传输写入；已有蓝牙交接后保留网络会话的策略及回归。
- CarLife 音频已有重采样、导航混音、压低和断流补静音，不需要为解决当前黑屏重做这些机制。

以下建议承接这些工作，不将它们重复列作“尚未实现”。

**1. P0：建立真正的资源仲裁，统一处理 requestUI 和 changeModes**

代码位置：[`AirPlayClient.assertModes`](../app/src/main/java/com/mcarplay/app/airplay/AirPlayClient.kt)、[`SessionController` 的事件命令分发](../app/src/main/java/com/mcarplay/app/SessionController.kt)。

当前 `assertModes()` 将主屏和主音频都固定归给手机，并将通话、导航和语音 appStates 写成固定值；`changeModes` 和候选 `requestUI` 都直接调用它。这无法正确表达车机正在取回屏幕、借用音频或返还资源。Apple 公开说明：资源管理器位于手机控制端，`changeModes` 表达请求，`modesChanged` 描述仲裁后的状态；两种资源需要分别管理。[官方资源管理说明](https://developer.apple.com/videos/play/wwdc2016/723/)

建议增加每会话的资源状态模型，保存主屏、主音频的当前及永久归属、借用关系、限制条件和 appStates。无参数 `requestUI` 按请求显示当前投射界面处理，尝试以用户操作优先级取得主屏；已持有时可重申状态。保持未涉及的音频和 appStates。`changeModes` 则解析请求，执行实际 take / borrow / unborrow，不能一律“抢回手机”。

现有事件层在分发前已经回复 RTSP 200，不能把旧日志中的“仅打印 requestUI”写成“没有协议应答”。还需根据完整规范核对有业务响应体的命令；统一空 200 并不代表业务仲裁完成。

验收：原厂菜单 → CarWith → 原厂菜单；只切屏不切 FM 音频；屏幕借用结束恢复原归属；带限制的借用期间不被后台请求抢回。`requestUI` 修复是否解决绿图标问题仍需单独复测。

**2. P0：让事件读取线程只负责接收、应答与投递**

代码位置：`AirPlayClient.connectEventChannel`、`SessionController` 的命令回调、`VideoSender.requestKeyFrame/startTranscoding`、`CarWithTranscoder.start`。

事件回调当前同步调用 `assertModes()`，后者等待控制通道 RTSP 响应；关键帧请求还可能同步创建编码器，并等待 EGL 初始化最多 3 秒。即使已发出 200，这段时间仍不能继续处理同一事件连接上的 HID 和 iAP 消息。

建议将控制动作放进每会话串行队列，耗时的媒体初始化放进独立媒体任务。合并密集 requestUI / forceKeyFrame 请求；任务执行前后校验会话世代和取消状态。不要每收到一次请求就新开一个后台线程。转码准备完成后再原子切换输出，失败释放未交付的资源。

验收：故意延迟 RTSP 回应和 EGL 初始化时，事件通道仍可接收后续命令；取消后迟到任务不能发送 modesChanged、启动转码或关闭新会话。

**3. P0：把能力声明与实现能力逐项对应**

代码位置：`AirPlayClient.CONTROLLER_FEATURES`、`buildSessionSetupBody`、`VideoSender.createEncoder`；现有回归为 `SessionSetupBodyTest`。

当前固定声明包含 `hevc`、`mainBuffered`、`altScreen`、`enhancedSiri`、`vehicleStateProtocol`，而当前视频创建和配置发送路径为 AVC/H.264。代码搜索也没有发现这些声明全部对应的完整实现。新能力会影响接收端预期，不能仅为了获得某种 SETUP 结果就假定清单中的每项都已支持。Apple 2023 年明确区分了新的视频编码、缓冲音频等独立能力。[官方更新](https://developer.apple.com/videos/play/wwdc2023/10150/)

建议建立能力清单：每一项关联实现入口、协商依赖、测试和已验证接收端。将能力按“已实现”“仅兼容协商所需的占位”“后续实现”明确记录。先冻结现有可用车机的协商基线，再通过对照实验收敛清单；不要一次性删光，已有实测表明过度缩小清单也可能破坏协商。

HEVC、缓冲音频、双屏和车辆状态协议应各自有实现和开关后再启用。H.264 仍保留为兼容路径，声明的 profile/level 能力与实际 SPS、编码器能力一起核对。

验收：模拟接收端根据声明请求对应功能时，项目有实际可执行路径；艾瑞泽 8、帕萨特和 WinPlay 的基线协商分别回归。

**4. P0：固定视频输出契约，并验证转码完整链路**

代码位置：`VideoSender.setVideoDimensions/startTranscoding/drainExternalVideo/drainEncoder`、`CarWithTranscoder`、`TranscodePolicy`、`SessionController` 的触摸映射。

帕萨特声明 1280×720，CarWith 返回 1920×1140；当前候选通过关键帧请求风暴触发转码。这个思路可以保留，但“持续要关键帧”只能作为视频恢复异常的信号，不能单凭它确认分辨率是唯一根因。Apple 的显示流程明确把车机声明尺寸与手机输出尺寸联系起来。[WWDC19 显示说明](https://developer.apple.com/videos/play/wwdc2019/252/)

建议显式保存“源编码尺寸”“协商流尺寸”“显示视口/触摸坐标系”，禁止混用。输出契约应包含编码格式、profile/level、尺寸、FPS 上限、显示 UUID 和变换矩阵。源不采纳尺寸时，选择经过验证的透传或转码策略；转码切换必须保证新配置先于新流的首个 IDR，SPS/PPS、帧加密计数、发送顺序一致，旧帧不能跨配置混入。

新增测试不能只停留在 `needsTranscode()` 等纯函数。需要 Android 设备上的“真实 H.264 → 解码 → SurfaceTexture/EGL → 编码 → 本地解码验证”测试，检查首帧、SPS 尺寸、丢帧恢复和停止竞态。布局拉伸、留边或裁剪选择一旦变化，要让触摸使用同一变换矩阵。

验收：帕萨特切换后实际出画、可点击，关键帧风暴消退；艾瑞泽 8 和 WinPlay 不因新策略引入延迟或清晰度退化。

**5. P1：重建逐帧时间戳、限帧和 GOP 恢复策略**

代码位置：`CarLifeVideoAccessUnit.toExternalVideoFrame`、`CarWithTranscoder.submit/drawFrame`、`VideoSender.externalQueue/createEncoder/buildHeader`。

源解析器已注明 CarLife 时间戳是秒级，同一秒可包含许多帧，但 `toExternalVideoFrame()` 仍直接乘 1,000,000 作为 PTS；转码继续使用该值及 SurfaceTexture 时间戳，数据头又取发送当时的 `System.nanoTime()`。应统一媒体时间基准，明确帧采集、编码输出和展示时刻的转换，避免同秒重复 PTS 和转码新增延迟失去记录。

仅设置 `KEY_FRAME_RATE` 是编码速率提示，不等于实际限帧。Surface 输入可结合 `KEY_MAX_FPS_TO_ENCODER` 或应用侧节拍，以接收端 maxFPS 为上限。[Android MediaFormat 官方说明](https://developer.android.com/reference/android/media/MediaFormat#KEY_FRAME_RATE)、[Surface 输入限帧](https://developer.android.com/reference/android/media/MediaFormat#KEY_MAX_FPS_TO_ENCODER)

64 帧外部队列在 30fps 下可容纳约 2.1 秒视频；满时直接丢新帧，还可能丢掉后续 P 帧所依赖的参考帧。建议按时间限制积压，记录队列年龄，积压或参考链破坏时丢到恢复 IDR、请求关键帧并重建解码连续性。指标应覆盖源、转码和写入各阶段的丢帧与延迟。

验收：同一源秒内多帧具有合理的连续 PTS；60fps 源输出不超过声明上限；拥塞结束后从 IDR 恢复，不长期播放积压旧画面。

**6. P1：完善接收端就绪条件和 SETUP 失败路径**

代码位置：`CtrlInviteGate.decide/isFresh`、`CarPlayCtrlAdvertiser.handleClient`、`SessionController.awaitCtrlInvite`、`AirPlayClient.runSetup/loadInfoWithDiagnostics`、`Iap2BluetoothClient.awaitIap2Service`。

邀请候选中的 20 秒有效期、3 秒 mDNS 宽限来自有限现场观察，不是已知的 Apple 协议常量。失败与邀请时序的相关性有排查价值，尚不足以证明所有车机都要求同一窗口。应按会话、接收端实例和网络绑定邀请，保留有界 mDNS 兼容路径；邀请时效用单调时钟的当前时刻评估，并覆盖调度停顿后旧邀请过期的边界。

`CarPlayCtrlAdvertiser` 当前把接收端 ID 用于日志及计数，尚未作为后续端点关联的结构化事实。SDP 探测也仍将超时与确实不含 UUID 合并成 Boolean。建议区分 PRESENT / ABSENT / UNKNOWN，未知不永久否决车机；保留已实现的主动连接、车机回连和指定目标兜底。

`runSetup()` 即使 `/info` 因控制连接关闭而失败，仍可能用竖屏默认尺寸构造结果并打印 SETUP 成功。应区分“信息缺字段但会话仍有效”与“控制连接已关闭/探测超时”。后者停止下游流协商，记录真实阶段，执行有界的重新就绪/配对流程；不要用随机显示 UUID 掩盖已失效的会话。

验收：晚邀请、旧邀请、其他实例邀请、SDP 超时、控制通道断开分别走可解释路径；不会把已关闭的 SETUP 当成功，也不会破坏正常不发邀请的接收端。

**7. P1：将诊断与回归延伸到界面恢复和视频恢复**

代码位置：`ConnectionTimeline`、`SupportReport`、`DiagnosticRedactor`、`tools/analyze_diagnostics.py`、`app/src/test/java/.../replay/`。

现有“源帧”和“传输写入”区分正确，应进一步增加：requestUI 接收、协议应答、业务处理、资源状态变化、关键帧请求、源 IDR 到达、新配置及新 IDR 发送、转码首帧、队列延迟。画面已显示和声音已到达继续由用户确认，不从 TCP 写入数推导。

诊断保留具体构建哈希、会话和策略来源，报告中说明兼容阈值是实测策略而非规范要求；新增字段先扩展脱敏与对应测试。修正文档中“旧 requestUI 没有应答”“车机从不返回凭据”等过强表述：本轮已知事件层会回 200，后续会话也实际收到 0x5703。

现有车辆录制重放依赖私有、git-ignored fixtures，缺失时会跳过；因此需要少量完全合成、无真实标识符的公开协议场景进入 CI。覆盖参数缺失的 requestUI、资源借还、晚邀请、配置切换和取消后的迟到消息。继续保留私有真车重放作为补充，不能让新鲜克隆只剩跳过。

**8. P2：在基础链路稳定后扩展能力**

后续可研究 view area、安全区域、动态尺寸、HEVC、多路音频与多屏，但按能力协商逐项加入。Apple 2023 年说明新系统使用 HEVC 时仍需 H.264 兼容；缓冲音频属于独立的 main buffered 路径，不能把当前实时音频队列简单加大来替代。Apple 2024 年的架构还包括本地合成和 UI 同步，工程范围远大于本项目当前单主屏桥接。[WWDC23](https://developer.apple.com/videos/play/wwdc2023/10150/)、[WWDC24](https://developer.apple.com/videos/play/wwdc2024/10111/)

**建议实施顺序与验收**

| 顺序 | 交付 | 最小验证 |
| --- | --- | --- |
| 第一批 | 资源模型、命令队列、能力清单审计、SETUP 失败状态修正 | 合成接收端验证命令顺序、返回原生界面、音频归属、取消与迟到消息 |
| 第二批 | 转码完整链路、统一时钟、限帧、队列和 IDR 恢复 | Android 真编解码测试；帕萨特出画和触摸；艾瑞泽 8 / WinPlay 回归 |
| 第三批 | 邀请/网络实例关联、SDP 三态、诊断和公开合成重放 | 冷启动、重连、手动入网、蓝牙释放、短断链和锁屏恢复 |
| 后续 | 按能力逐项加入新功能 | 每个新增能力有对应协商、数据路径及接收端证据 |

正式代码交付仍运行仓库完整 gate。涉及 Android MediaCodec/EGL 的核心路径还必须有设备验证，JVM 纯函数测试不能代替。实车至少分别检查冷启动、切入/返回原生界面、持续音画触控、断开重连、锁屏；沿用项目已有十分钟稳定性门槛，并将用户主动断开与自发断开区分。新增时间目标和性能预算由项目制定，不标成 Apple 认证阈值。

本次仅新增审查文档，未修改应用实现，未运行构建或 JVM/设备测试；未断言上述优化已经实现或通过。
