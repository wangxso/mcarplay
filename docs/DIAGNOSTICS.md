# Connection Diagnostics

[English README](../README.md) | [中文说明](../README.zh-CN.md)

## For users

1. Reproduce the problem, then tap **导出脱敏诊断报告** on the main connection card.
   No computer, root or Shizuku is required.
2. If the app disappeared, reopen and export immediately. Do not reinstall or
   clear app data first: recent previous-process evidence is included automatically.
3. Review the text, then choose **分享文件**. Files are in `Download/mcarplay/`.
   Nothing is automatically uploaded.
4. Include the report ID, local failure time, reproduction steps, receiver model
   and firmware, screen message, and whether video, audio and touch work.
   Do not include passwords, pairing material or location screenshots.

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
  (`SHIZUKU`, `SETTINGS`, `MANUAL`, `REMEMBERED` or `NONE`), never the address itself.
- Random process/attempt IDs correlate stage transitions, warnings, terminal
  reasons, elapsed time, media counts and coarse memory/thread samples. Critical
  events use JSONL schema 1, independently of verbose protocol logs.
- Source H.264 frames, video transport writes, native PCM chunks and sent audio
  packets are separate evidence. Writes do not prove receiver display or sound.
- `RECEIVER_DETECTION` and `DETECT` notes contain the local wizard's fixed state,
  trial/stage, storage status and media/input counters. Private receiver bindings,
  installation secrets and environment/capability digests are excluded. Counts do
  not replace the user's picture, sound or input confirmation. See
  [receiver detection](RECEIVER-AUTODETECT.md).
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
- Default runtime tail: 512KB; journal: two 128KB segments; runtime storage: two
  approximately 2MB segments. Truncation is marked. History is bounded, not
  permanent; clearing app data removes it.

Exports remove common credentials, key fields, wire dumps, contact/location fields
and URLs. MAC/IP addresses and known receiver/network names use report-local
aliases. Details redacted before journal storage use generic placeholders, not
cross-event identities. APK/signing fingerprints are deliberately retained.
Arbitrary free text cannot be guaranteed anonymous: review before sharing.

## Maintainer workflow

```bash
python3 tools/analyze_diagnostics.py /path/to/mcarplay-log-...-diagnostics.txt
```

The offline tool summarizes the last five retained attempts. Correlate `process`,
`attempt`, `stage`, `elapsedMs`, `code` and counters with logs and user observations.
`track` separates CarPlay attempts (`carplay`) from CarWith self-tests (`self_test`);
the two never supersede each other. Showing a permission dialog is not recorded;
only a denial is.

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

手机日志能确定已记录的阶段和事实，但不能保证还原所有车机内部故障，必要时仍需接收端日志。
“收到源数据”“已发送”“接收端显示/有声”是不同证据层级；分享前仍需检查自由文本中的隐私。


目标蓝牙 SDP 摘要中的“接收端 iAP2(…caff) 服务”仅表示该出站 RFCOMM UUID 是否存在，
不代表无线 CarPlay 的完整能力判定。未声明它的车机仍可能主动回连手机 `…cafe` 服务；
例如 2026-10-31 奇瑞实测。手机正常连接期间同时公告 CarPlay 能力 UUID，停止监听时一并撤销。
