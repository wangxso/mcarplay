# Connection Diagnostics

[English README](../README.md) | [中文说明](../README.zh-CN.md)

Standard builds and all release builds export redacted reports. The explicitly
disclosed `remote/debug` diagnostic export described below is unredacted.

## For users (standard/release)

1. Reproduce the problem, then tap **导出脱敏诊断报告** on the main connection card.
   No computer, root or Shizuku is required.
2. If the app disappeared, reopen and export immediately. Do not reinstall or
   clear app data first: recent previous-process evidence is included automatically.
3. Review the text, then choose **分享文件**. Files are in `Download/mcarplay/`.
   Nothing is automatically uploaded.
4. Include the report ID, local failure time, reproduction steps, receiver model
   and firmware, screen message, and whether video, audio and touch work.
   Do not include passwords, pairing material or location screenshots.

For CarWith entry-stage failures, allowing **Notification access** for mcarplay
lets the report show whether a CarWith notification was observed while the seven
ports were closed. This reuses the existing media/music permission; it records
only the fixed CarWith package presence, foreground-service flag and timing, not
notification titles or text. Without that access the claim stays explicitly
unknown instead of treating "no notification seen" as "CarWith never started".

The log switch controls display, not file diagnostics. Clearing the UI console
does not erase report history. Long-pressing the log export icon includes larger,
still-redacted recent runtime history.

## Contents and retention

- App version/code/flavor, APK SHA-256 and signing-certificate fingerprint identify
  a build even when multiple APKs display the same version.
- Phone/Android/ROM and CarWith/CarLife versions, entry availability, permissions,
  Bluetooth/WLAN and active/bound network transports. Environment fields describe
  export time, not necessarily the state when the problem occurred.
- `bluetooth.identitySource` records where the phone's Bluetooth address came from
  (`SHIZUKU`, `SETTINGS`, `MANUAL`, `REMEMBERED` or `NONE`). Redacted reports exclude
  the address; remote/debug also includes the stored identity in its raw snapshot.
- Random process/attempt IDs correlate stage transitions, warnings, terminal
  reasons, elapsed time, media counts and coarse memory/thread samples. Critical
  events use JSONL schema 1, independently of verbose protocol logs.
- `MILESTONE` events (with a `milestone` field) record the first time an attempt
  reaches each phone-side step: `BT_LINK`, `IAP2_IDENTIFIED`, `IAP2_AUTHENTICATED`,
  `WIFI_CREDENTIALS`, `WIFI_JOINED`, `CTRL_INVITED`, `RECEIVER_RESOLVED`,
  `AIRPLAY_CONNECTED`, `PAIR_SETUP`, `PAIR_VERIFY`, `AUTH_SETUP`, `SESSION_SETUP`,
  `RECORD`; `FIRST_CARWITH_VIDEO`, `FIRST_VIDEO` and `FIRST_AUDIO_PACKET` carry
  `CARWITH_FRAME`, `FIRST_VIDEO` and `FIRST_AUDIO`. Repeats are not journaled and
  details hold only fixed tokens (`source=0x5703`, `port=fallback`, `savedTrust=true`).
  `PAIR_SETUP` is absent when saved trust was reused. The layers are independent:
  `IAP2_AUTHENTICATED` records the phone sending `0xAA05`; it does not prove local
  certificate/signature verification or imply `PAIR_VERIFY`. `AUTH_SETUP` records
  HTTP 200 and the response shape; the current implementation does not verify MFi
  certificates/signatures and still proceeds to SETUP after auth-setup errors.
  Bluetooth milestones belong to the attempt that was current when
  they arrived, so an attempt that a receiver restart began at Wi-Fi may lack them.
- After `EXPORT_SNAPSHOT` the report adds `STAGE_DURATIONS` (accumulated milliseconds
  per stage, `*` on the current one), `MILESTONES` (offset from the attempt start, or
  `none`) and, while waiting or after a failure, `BLOCKED_AT <STAGE>:<STEP>` naming the
  missing step (for example `PAIRING:HOMEKIT_PAIRING` after TCP connected but before
  pair-verify). The main card shows the same layers as a stepper; after 30 s from
  `BT_LINK` without `RECORD` it shows the observed Chery Arrizo 8 connection-window
  hint. The head unit measured 30.002 s from its own `TimerThread begin`, then
  stopped the failed attempt and deregistered Bonjour; a later success used a new
  upper-layer Start. Phone `BT_LINK` is only an approximate alignment point, and
  this hint neither stops the phone attempt nor promises an automatic retry.
  That window was observed only on that head unit. The report mapping and software /
  on-device checks are in [the Arrizo 8 regression matrix](ARRIZO8-PROTOCOL-REGRESSION.md).
- Source H.264 frames, video transport writes, native PCM chunks and sent audio
  packets are separate evidence. Writes do not prove receiver display or sound.
- `SCREEN_OUTPUT` events and the snapshot field `screenSuspended` distinguish an
  intentional main-screen pause from transport stalling. While the head unit owns
  the screen, the report states that video is suspended instead of warning that no
  new frame was written. The state clears only after the first fresh IDR is sent;
  the screen stream socket, audio and keepAlive remain active.
- `RECEIVER_DETECTION` and `DETECT` notes contain the local wizard's fixed state,
  trial/stage, storage status and media/input counters. Private receiver bindings,
  installation secrets and environment/capability digests are excluded. Counts do
  not replace the user's picture, sound or input confirmation. See
  [receiver detection](RECEIVER-AUTODETECT.md).
- `BLUETOOTH_DIAGNOSTICS` is an in-process, per-attempt Bluetooth snapshot. `BT-DIAG`
  runtime lines record local iAP2/CarPlay SDP registration and listener state,
  observed bond/ACL changes, SDP query completion/timeout/not-started/error,
  RFCOMM socket creation versus connection failures and elapsed time, cancellations,
  incoming iAP2 target decisions, and capability-port connections. Peer numbers
  are local to a run; no names or addresses are included. Repeated identical
  outbound failures for the same peer are logged at most once per 15 s; every attempt is counted.
  The fixed summary omits exception text: only allowlisted exception classes,
  fixed reasons, `readRet`, numeric socket status, and known errno tokens survive. `READ_FAILED` does not
  distinguish a closed socket from a timeout when Android reports both possibilities.
  Local registration is **not** evidence that the head unit saw the SDP records;
  capability-port connections are not iAP2 handshakes, and a zero count does not
  prove that the head unit never queried SDP. Snapshots end with the process;
  recent `BT-DIAG` lines remain in the existing bounded runtime history.
  Only remote/debug additionally records original exception stacks and exact
  peer addresses in `REMOTE-RAW` lines.
- `CARLIFE_SELF_TEST_DIAGNOSTICS` retains the current or last standalone source
  self-test snapshot in this process: entry request result and route (`ATTACHED`,
  `CARWITH_ENTRY`, `LEGACY_ROOT_SERVICE`, `CARWITH_SETTINGS`, or `NONE`), whether it began in
  the background, protocol state, elapsed time, parsed video counts, and the
  existing seven-channel connection attempt's progress. `pendingPort` is the
  current connect, `lastFailedPort` the last observed failure, and
  `openedThisAttempt` the ports opened in that attempt, possibly already rolled
  back. `allConnectedOnce` is historical, not a claim that sockets remain open.
  `CARLIFE-DIAG` lines carry a source-run ID and record entry, source environment,
  all-listeners-connected, stop and failure snapshots. No extra TCP probes are
  made: a second connection can reset CarLife's session.
- `CARWITH_NOTIFICATION_PROBE` is a process-wide, read-only presence probe that
  reuses the existing notification-access grant. It records only whether a
  notification from `com.miui.carlink` was observed, whether
  `FLAG_FOREGROUND_SERVICE` was set, active counts, removals and elapsed
  timestamps. Notification keys, titles, text, progress and other content never
  enter the report or runtime log. `access=CONNECTED` means the listener is
  actually bound; `UNKNOWN` or `DISCONNECTED` cannot support a claim about
  whether CarWith started. In a CarLife attempt, `baseline=run` and
  `deltaPosts`/`deltaForegroundPosts` compare against the session start.
  `notification.accessEnabled` is the export-time settings-list value, not proof
  that the listener was bound or that a notification was delivered.
  `CARLIFE_SELF_TEST_DIAGNOSTICS` also carries the same probe snapshot with
  `commandFrames`; zero command frames after all seven ports connected is
  evidence of a silent protocol channel, not of a working cast session.
- Source environment fields describe the fixed CarWith/CarLife packages' enabled
  and system-app flags, declared/granted permissions, the CarLife connect-service
  declaration and caller permission, and the readable CarWith global feature
  switch. Export environment fields are still export-time observations; runtime
  `CARLIFE-DIAG event=ENV` records a source-start sample. Missing privileges are
  observations, not a diagnosis. Private consent preferences and actual other-app
  activity/process execution cannot be verified by these fields. `entry=REQUESTED`
  means the local `startActivity()` returned, not that CarWith received it.
- `POWER` notes record screen-off, screen-on, unlock and Doze transitions while
  streaming: interactive, keyguard locked/secure, Doze and battery-optimization
  exemption flags only. `STREAM_END_SCREEN_OFF afterScreenOffMs=…` marks a stream
  that ended while the screen was off. They locate lock-screen exits; they do not
  change any system state.
- Streaming runtime-log lines (numbers and codec parameters only):
  - `VIDEO: CarWith 参数集` and the phone encoder's `参数集` lines: SPS/PPS traits (profile, constraint flags, level, POC
    type, reference frames, VUI timing and reorder depth, CABAC, 8×8 transform) plus the avcC and config-header bytes;
  - `VIDEO: 透传码流统计` and `VIDEO: 转码统计`, written after 300 frames, then every 30 s, on a switch to transcoding
    and at stream end: slice/NAL composition, frame sizes, IDR intervals, send gaps, slow socket writes, queue drops,
    the time from the last sent IDR to each receiver `forceKeyFrame`, and how fast CarWith answers a key-frame request;
  - `VIDEO: 车机经屏幕数据口回传` / `车机关闭了屏幕数据口` when the receiver writes to or closes the video data port;
  - `TIMING:` drift of the receiver's clock against the phone's monotonic clock, and the timesync request interval;
  - `EVENT: 首次收到车机命令` (the structure of each new command type, never its values), `EVENT: 本轮车机命令统计`,
    non-200 event replies, the `modesChanged` reply shape and the full `/info modes`;
  - `SESSION: 本轮车机交互统计` (forceKeyFrame / requestUI / touch counts) and `CARLIFE: 入流门控` rejoin lines;
  - `AUTO:` manual-disconnect cooldown skips, including already queued service and controller starts. The 30 s cooldown is
    scoped to the selected receiver; a manual connection overrides it. A new connection waits for the previous teardown;
  - `PAIRING: stage=SETUP/VERIFY reuse=…` distinguishes registration from verification with a saved receiver identity.
    Only an explicit TLV authentication rejection invalidates saved trust; EOF, HTTP 403 and busy errors do not. Private
    receiver identity records remain in app preferences and are not added to the support report;
  - `AIRPLAY: TEARDOWN` records separate screen-stream and full-session responses before Bluetooth/Wi-Fi cleanup.
    `SCREEN:` logs a screen-only SETUP/RECORD after a mid-session screen relinquishment and a later `requestUI`.
    A 200 reply or fresh video writes do not prove that the receiver returned to CarPlay; check touch counts and user feedback;
  - `ENV:` at the first frame and every 60 s: Wi-Fi RSSI, frequency, standard and link speeds, estimated
    bandwidth, thermal status, interactive, power-save and battery level. SSID and BSSID are not recorded.
- Uncaught stacks and up to five exit records from this app only; an available
  recent crash/ANR trace is capped at 64KB. Other apps' logcat is not read.
- Default redacted runtime tail: 512KB; remote/debug export tail: up to 4MB.
  Journal: two 128KB segments; runtime storage: two
  approximately 2MB segments. Truncation is marked. History is bounded, not
  permanent; clearing app data removes it.

Redacted exports remove common credentials, key fields, wire dumps, contact/location fields
and URLs. MAC/IP addresses and known receiver/network names use report-local
aliases. Details redacted before journal storage use generic placeholders, not
cross-event identities. APK/signing fingerprints are deliberately retained.
Arbitrary free text cannot be guaranteed anonymous: review before sharing.

### Unredacted remote/debug export

`DiagnosticExportPolicy` permits raw exports only when flavor is exactly `remote`,
build type is exactly `debug`, and the debug flag is true. Even a debuggable
release build remains redacted. There is no preference or server command that enables
raw export in standard or release. The raw path skips report redaction for runtime
logs, timeline detail, summaries and this app's exit records. Newly recorded
remote/debug timeline details and exception stacks retain their original text;
text already discarded at the source by an older version cannot be recovered.

Raw reports have `privacyMode=UNREDACTED_REMOTE_DEBUG` and `-raw.txt` filenames.
They can contain names, MAC/IP addresses, hotspot details and passwords or pairing/
session data already present in retained logs or wire dumps. The debug setup
screen and update-resume prompt disclose the scope; an export notice precedes
writing the file. The exported file is shared manually. An enabled, configured
remote bridge continues to stream runtime logs under its existing behavior.
The external agent log API still applies its existing redactor and is not a
new raw-download or command endpoint. Its browser viewer (`/log-viewer/`) holds
no data: it needs the same read-only bearer token, keeps it only in the tab's
session storage, and its whole-file export (`/api/v1/export`, up to 64 MB) is
redacted server-side with one redactor so `<NAME-n>` aliases agree across lines.
Paged views redact each response separately, so aliases can differ between pages.
`tools/debug-bridge/local-viewer.mjs` serves the same page on this machine only
(`127.0.0.1`) and forwards the caller's token to the bridge; it stores no token.
Against a bridge without `/api/v1/export` it rebuilds the file from pages and
marks the download `X-Export-Mode: paged`, since aliases may then differ by page.

Additional `raw.*` export fields are supplied by the remote source set on the
background export thread: build fingerprint/ABI, stored phone/target Bluetooth
identity, paired-device cached classes/UUIDs, current WLAN identifiers and link
state, network handles/addresses/DNS/routes/interfaces, the fixed app/CarWith/
CarLife packages' version/UID/path/signing certificates/permissions/services,
component enabled settings, and this process's thread stacks. The collector
checks the same remote/debug guard before reading. It neither queries all
installed apps nor reads another app's private storage or system logcat.
Ordinary Android permission restrictions and privacy placeholders still apply.
No new permission request, active SDP/port probe, or shell command is added.

Collection remains bounded: up to 64 paired devices, 32 networks/interfaces,
96 services per selected package and 96 threads with up to 24 frames each.
Individual raw fields are capped at 64,000 characters and marked with
`raw.<field>.truncated=true` when capped. Original exception records are capped
at 24,000 characters; the existing rotating log and journal budgets still apply.

### Bluetooth capture analysis (`BTSNOOP`)

Apps cannot enable or read the Bluetooth HCI snoop log, so the main screen's
"Bluetooth capture analysis" page (`diagnostics/btsnoop/`) analyzes a bug report
instead. The user sets the HCI log to *Enabled* (on Android 13 the GD stack treats
*Filtered* as off), restarts Bluetooth, reproduces, takes a bug report and shares it
to mcarplay (`ACTION_SEND_MULTIPLE`, `application/vnd.android.bugreport`) or picks the
zip or a raw `btsnoop_hci.log`. The page streams `FS/data/misc/bluetooth/logs/btsnoop_hci.log*`
out of the zip (one nested zip level) without copying, storing or uploading it.

Each analyzed file appends `BTSNOOP:` lines to the debug log, so later exports include them:

- local class of device, local name length, every Write EIR (byte use, 16/128-bit UUID
  counts and completeness, CarPlay `2d8d2466` and iAP2 `…cafe` presence) and the
  discoverable windows with whether the EIR in effect carried CarPlay;
- per peer, numbered by first appearance: class of device, ACL connections by initiator,
  Simple Pairing results and L2CAP connections by PSM;
- each SDP query in both directions: PDU, search pattern, byte limit, responses,
  continuation requests, status (`完成`, `请求方中止续传`, error, unanswered, open at end),
  attribute bytes and records by service class, with index, byte offset and RFCOMM
  channel for CarPlay and iAP2 records;
- factual conclusions, such as whether the peer received a CarPlay record or stopped
  continuing before it.

Addresses, device names, service names, link keys and packet bytes never reach the output.
A capture shows only what was on the air during that run; it is not vehicle-compatibility evidence.

## Maintainer workflow

```bash
python3 tools/analyze_diagnostics.py /path/to/mcarplay-log-...-diagnostics.txt
```

The offline tool summarizes the last five retained attempts. The QQ bot in
[`tools/logbot/`](../tools/logbot/README.md) builds a user-facing diagnosis on top of it;
update its `rules.py` when adding a `DiagnosticCode`. Correlate `process`,
`attempt`, `stage`, `elapsedMs`, `code` and counters with logs and user observations.
`track` separates CarPlay attempts (`carplay`) from CarWith self-tests (`self_test`);
the two never supersede each other. Showing a permission dialog is not recorded;
only a denial is.

### Remote connection/source diagnostic APK

```bash
./gradlew -Pmcarplay.diagnosticsBuild=true :app:assembleRemoteDebug
```

This produces `app/build/outputs/apk/remote/debug/app-remote-debug.apk`, package
`com.mcarplay.app.rd`, version `0.1.3-rd-diag3-debug`. It can update the existing
remote debug app when signed with the same debug key. Bridge settings and phone
identity stay in that app's storage; it does not update the standard app.
Stop the standard app before a controlled test. Start the diagnostic app, select
the target, start the connection, then select the phone in the head unit's Bluetooth
list. After about a minute, export the report and note whether the head unit
labels the phone as supporting wireless CarPlay.

For a standalone source failure, stop CarPlay and other CarLife sessions, keep
the phone unlocked and mcarplay in front, then run **CarWith 出流自检** for about
30 s and export. This does not require a head unit. The snapshot distinguishes
which of the seven ports is still pending from handshake/first-frame failures.
The older `-Pmcarplay.btDiagnosticsBuild=true` flag remains an alias for this
diagnostic build; it no longer produces the earlier `btdiag1` version.

The existing bridge log stream and support export both include the diagnostics.
The bridge command whitelist also accepts read-only `get_bluetooth_diagnostics`
for the current snapshot, including a still-pending RFCOMM connect. The bridge
also accepts `get_carlife_diagnostics` to read the current/last source self-test
snapshot without starting or stopping it. The external agent log API remains
read-only and does not expose command execution. No new
permissions, arbitrary shell, automatic support-report upload, or connection
fallbacks are added. An APK build and JVM checks do not establish Android 13 or
vehicle compatibility; the affected user's phone/head unit must still be retested.

| Code | Next check |
| --- | --- |
| `BT_PERMISSION`, `BT_DISABLED` | Bluetooth permission denied, or Bluetooth switched off. |
| `WIFI_PERMISSION` | Android 13+ nearby-WLAN-devices permission denied. |
| `LOCATION_REQUIRED` | Android 12 WLAN permission/location-service prerequisite. |
| `ENDPOINT_PENDING` | Awaiting receiver information; not a terminal failure. |
| `BT_LINK_CLOSED` | Control link ended before session establishment. |
| `WIFI_JOIN_FAILED`, `WIFI_LOST` | WLAN, nearby-device permission, system join confirmation and hotspot. |
| `PAIRING_FAILED` | AirPlay pairing responses and receiver state. |
| `SETUP_FAILED` | SETUP status and advertised capabilities. |
| `CARWITH_FAILED` | Component versions, entry, original permissions, handshake and source video. |
| `CARWITH_ENTRY_BLOCKED` | CarWith's seven listeners never appeared and the entry was requested while mcarplay was not in the foreground. Android/MIUI can drop such a background activity start without an error; on Xiaomi allow "Display pop-up windows while running in the background" (后台弹出界面) or keep mcarplay in front, then reconnect. When the Xiaomi permission is denied, mcarplay offers a shortcut to it before connecting. |
| `CARWITH_ENTRY_NOT_OBSERVED` | The notification listener was bound, no CarWith notification was observed during the wait, and the seven ports stayed closed. This is not proof that CarWith's code never ran, but it points to entry/activation rather than the later protocol. Keep mcarplay in front and start Baidu CarLife wireless connection from CarWith settings. |
| `CARWITH_ENTRY_STATUS_UNKNOWN` | The seven ports stayed closed and no notification listener was bound. The report cannot decide whether the CarWith entry ran. Use the CarWith settings entry manually; allowing notification access improves only future diagnostics. |
| `CARWITH_LISTENERS_PENDING` | A CarWith notification was observed, but the seven ports stayed closed. CarWith reached at least its notification/service phase; check the CarWith and Xiaomi CarLife versions and restart the factory wireless connection. |
| `CARWITH_PROTOCOL_SILENT` | All seven ports connected but the command channel delivered no CarLife frames. Listening sockets are not a valid cast session. Stop the existing CarWith connection and restart Baidu CarLife wireless connection from CarWith settings. |
| `CARWITH_BUSY` | Stop the standalone self-test before starting a formal CarPlay connection. |
| `VIDEO_CLOSED` | Transport ended. Status `stopped` after frames were sent (normal end, e.g. receiver off); `failed` if none were sent. Reset/broken pipe alone does not establish receiver crash. |
| `AUDIO_UNAVAILABLE` | Audio setup failed; video may continue. Inspect format and input/output counts. |
| `UNCAUGHT_EXCEPTION` | Captured stack; worker exception does not necessarily mean process death. |
| `USER_STOP`, `SUPERSEDED` | Expected user stop or replacement, not a connection defect. |
| `CONNECTION_INCOMPLETE` | Ended before pairing with no more specific cause; an `EXCEPTION` note carries any stack. |
| `DETECTION_TIMEOUT` | Explicit receiver detection reached its eight-minute pre-video budget; unobserved capabilities remain unverified. |

A missing END may mean an active session, abrupt termination or truncated records.
No exit records does not prove no previous exit. Do not attribute an old low-memory
record to a later reset without matching timestamps and receiver-side evidence.
Local car Wi-Fi without Internet validation is not by itself a connection failure.
Bluetooth may close normally after the network iAP session takes over (WinPlay
1.2.4 does this). An ACL disconnect must not stop a running Wi-Fi video sender.
The auto-stop path before video starts records `BT_LINK_CLOSED`; an explicit user
stop records `USER_STOP`. Automatic teardown must not be labelled a manual stop.
`COMPAT:` entries show the selected receiver family, pairing and Bluetooth policy,
and fixed rule IDs. They contain no additional raw device identifiers. See
[receiver compatibility](RECEIVER-COMPATIBILITY.md) for matching conditions and
version boundaries; a matched policy does not establish device support.
Repeated `ENONET` with an active Wi-Fi and `network.bound=unknown` calls for checking
an expired process network binding before treating it as an AirPlay pairing defect.
An uncaught exception is associated with the active attempt at capture time; this
does not prove that attempt created the failing thread. Inspect the stack and timing.

## 中文快速反馈

复现后点击首页 **导出脱敏诊断报告**，检查后分享文件。若应用意外消失，重新打开后立即导出，
不要先重装或清数据。请提供故障时间、步骤、接收端型号/固件、屏幕提示，以及是否有画面、声音和触控。

报告包含最近进程关键事件、阶段错误码、输入/发送计数、内存采样、堆栈和系统退出记录；
无需电脑、root 或 Shizuku，不会自动上传。“显示日志”只影响界面；长按日志导出按钮可取更大历史。
维护者可用上述离线脚本生成摘要。

推流时运行日志还会记录 CarWith 与手机编码器的 SPS/PPS 特征、透传/转码码流统计（含车机要关键帧距上个 IDR 的时间）、
车机时钟漂移、车机命令统计与结构、屏幕数据口回传或关闭，以及每分钟的 Wi-Fi 信号/速率、温控与电量（不含 SSID/BSSID）。

首页卡片按「蓝牙 › 入网 › 发现 › 配对 › 会话 › CarWith › 画面」显示每层状态与耗时、最近到达的里程碑和当前层还缺的那一步；
报告里对应 `STAGE_DURATIONS`、`MILESTONES` 和 `BLOCKED_AT` 三行，日志里是带 `milestone` 字段的 `MILESTONE` 事件。
各层相互独立：iAP2 认证通过不代表 AirPlay 配对成功。自蓝牙链路起 30 s 仍未激活会话时，卡片会提示艾瑞泽 8 车机此时会自行重置重连；
该窗口只在这台车机上观察到。

手机日志能确定已记录的阶段和事实，但不能保证还原所有车机内部故障，必要时仍需接收端日志。
“收到源数据”“已发送”“接收端显示/有声”是不同证据层级；分享前仍需检查自由文本中的隐私。


目标蓝牙 SDP 摘要中的“接收端 iAP2(…caff) 服务”仅表示该出站 RFCOMM UUID 是否存在，
不代表无线 CarPlay 的完整能力判定。未声明它的车机仍可能主动回连手机 `…cafe` 服务；
例如 2026-10-31 奇瑞实测。手机正常连接期间同时公告 CarPlay 能力 UUID，停止监听时一并撤销。

连接/源端排查包使用上面的构建命令，版本为 `0.1.3-rd-diag3-debug`，沿用 remote 调试桥。
新增 `BT-DIAG` 日志和 `BLUETOOTH_DIAGNOSTICS` 摘要，也可用桥命令 `get_bluetooth_diagnostics`
只读查看正在阻塞的连接、累计失败、配对/ACL 事件和回连接受/拒绝计数。固定摘要只保留原因、异常类型和必要数字；
仅 remote/debug 另外记录原始异常和实际地址。旧轮迟到回调不能覆盖新轮结果，没有新增权限或连接兜底。
本地公告成功不等于车机已识别；仍需用户在原车上复现，并反馈车机是否显示支持无线 CarPlay。
2026-11-06 起两项本机公告共用生命周期：任一监听结束或蓝牙关闭即撤销整组，`SERVER_STOPPED recordsReleased=true` 表示
本地注册与监听状态已清除；用户仍请求连接时可最多自动恢复三次，连续立即监听失败也受该上限约束。
重新注册仍只记录 `SDP_REGISTER outcome=OK evidence=LOCAL_REGISTRATION_ONLY`，不增加车机已识别或回连计数。
手动停止后不因蓝牙重开或旧恢复任务再次公告。实现与模拟器证据见 [HyperOS 手机兼容记录](HYPEROS-PHONE-COMPATIBILITY.md)。

独立自检失败时，先停正式连接并保持解锁，在前台运行「CarWith 出流自检」约 30 秒后导出。
`CARLIFE_SELF_TEST_DIAGNOSTICS` 和远程只读命令 `get_carlife_diagnostics` 会保留本轮/最近一次
入口请求结果与路径（已有会话附着、公开入口、root 旧版服务或原厂设置页）、后台状态、等待时长、最后失败端口、部分连入端口及协议/源帧计数。
诊断只观察原有七路连接，不额外探测端口。报告还列出 CarWith/CarLife 的启用、系统应用和权限状态，
以及 CarLife 服务声明；这些字段不能替代 CarWith 内部运行日志或证明某项未授权就是根因。

按用户要求，第三版仅在 `remote/debug` 取消导出脱敏，文件标记 `UNREDACTED_REMOTE_DEBUG` 并带 `-raw` 后缀。
手机调试页、重装续接和导出前均告知收集范围，导出默认带最近约 4MB 运行日志，并增加实际蓝牙/网络标识、
配对服务缓存、网络地址/路由、指定组件版本/权限/服务、签名指纹和本进程线程栈。已有日志中的凭据或线上报文也不再过滤。
standard 和所有 release 保持脱敏；服务器只读日志 API 仍按原规则脱敏。新版需重新复现，旧版已在源头删掉的内容无法恢复。

蓝牙抓包分析：普通应用不能开启或读取 HCI 日志，所以主界面「蓝牙抓包分析」改为分析系统错误报告。
开发者选项里把「蓝牙 HCI 信息收集日志」设为「已启用」（Android 13 的「已过滤」等于关闭），关开一次蓝牙后复现，
再「提交错误报告」并分享给 mcarplay，或在页面里选择 zip / `btsnoop_hci.log`。文件只在本机流式解析，不复制、不保存、不上传；
结果以 `BTSNOOP:` 写入诊断日志，只含时间、计数、CoD、服务 UUID 和结论（EIR 是否含 CarPlay、车机 SDP 是否收到 CarPlay 记录或中途停止续传），
不含地址、设备名、服务名和链路密钥。错误报告原件含大量个人信息，不要转发。


### CarWith system-log export (remote flavor only, 2026-11-04)

The remote-debug settings page offers **导出 CarWith 系统日志** and a separate share button.
Reproduce the failure first, then export promptly: this reads recent buffers rather than starting a continuous recording.
The fixed bridge command `export_carwith_system_logs` performs the same local export and returns only its status/path;
it does not upload the system-log body or append it to the bridge's runtime log. Share the file explicitly after review.
There is no arbitrary remote shell or ADB command execution field.

Choose either an already running, authorized Shizuku instance, or grant the remote package development permissions from a computer:

```sh
adb shell pm grant com.mcarplay.app.rd android.permission.READ_LOGS
adb shell pm grant com.mcarplay.app.rd android.permission.DUMP
```

Root is not required for the ADB grant route. Keep the remote app in the foreground and allow the system's device-log prompt
when shown (Android 13+). A ROM may reject grants or restrict logs; permission granted is not proof of complete visibility.
Without Shizuku or READ_LOGS, export stops with these instructions rather than presenting this app's own log as system evidence.
Without DUMP, the ADB route marks service sections unavailable. To revoke these development grants:

```sh
adb shell pm revoke com.mcarplay.app.rd android.permission.READ_LOGS
adb shell pm revoke com.mcarplay.app.rd android.permission.DUMP
```

The report contains up to 2,000 recent logcat records for fixed CarWith/CarLife, CTA/security-center, activity-manager,
crash and lifecycle tags; two package-scoped service dumps; TCP listener state; and read-only feature/app-op values.
System activity-manager tags can also mention other apps. Each command retains at most 256 KB per output pipe, with
concurrent draining and a deadline (5 seconds; 30 seconds for the ADB logcat consent prompt). Exit, timeout and truncation
are reported independently; a saved file or missing event does not establish successful/failed component execution.
Files are always passed through `DiagnosticRedactor`, including on raw remote-debug builds, and saved separately to
`Download/mcarplay/mcarplay-carwith-system-*.txt`. Review before sharing; arbitrary third-party log text may contain private data.
The permissions, UI, collector and bridge command are all in `src/remote`; standard builds do not include them.
