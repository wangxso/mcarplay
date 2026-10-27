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
- Random process/attempt IDs correlate stage transitions, warnings, terminal
  reasons, elapsed time, media counts and coarse memory/thread samples. Critical
  events use JSONL schema 1, independently of verbose protocol logs.
- Source H.264 frames, video transport writes, native PCM chunks and sent audio
  packets are separate evidence. Writes do not prove receiver display or sound.
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

手机日志能确定已记录的阶段和事实，但不能保证还原所有车机内部故障，必要时仍需接收端日志。
“收到源数据”“已发送”“接收端显示/有声”是不同证据层级；分享前仍需检查自由文本中的隐私。
