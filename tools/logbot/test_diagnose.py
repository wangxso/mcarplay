import json
import re
import unittest
from pathlib import Path

from logbot.diagnose import diagnose, format_reply, primary_attempt
from logbot.redact import redact
from logbot.rules import CODE_ADVICE, STAGE_LABELS

TIMELINE_KT = Path(__file__).resolve().parents[2] / "app/src/main/java/com/mcarplay/app/diagnostics/ConnectionTimeline.kt"


def report(events=(), env=None, log="", exits="[no exit records available; not proof of no previous termination]"):
    env = env or {"app.version": "0.1.0 (100)", "device": "Xiaomi 23127PN0CC", "carwith.version": "2.0 (200)"}
    return (
        "mcarplay support report / 诊断报告 schema=1\nreportId=abcdef1234567890\n"
        "=== ENVIRONMENT / 导出时环境（不等于故障发生时状态） ===\n"
        + "\n".join(f"{k}={v}" for k, v in env.items())
        + "\n=== CONNECTION SUMMARY / 连接摘要 ===\nsummary\n"
        "=== CONNECTION TIMELINE / 最近进程与连接事件 (JSONL schema=1) ===\n"
        + "\n".join(json.dumps(e) for e in events)
        + f"\n=== SYSTEM EXIT HISTORY / 本应用系统退出记录 ===\n{exits}\n=== RUNTIME LOG / 运行日志 ===\n{log}\n"
    )


def titles(diagnosis):
    return [f.title for f in diagnosis.findings]


class DiagnoseTest(unittest.TestCase):
    def test_failed_code_maps_to_advice_and_stage(self):
        d = diagnose(report([
            {"process": "p", "attempt": "a1", "event": "END", "stage": "PAIRING", "status": "failed", "code": "PAIRING_FAILED"},
        ]))
        self.assertEqual("report", d.kind)
        self.assertTrue(any("PAIRING_FAILED" in t for t in titles(d)))
        self.assertEqual("error", d.sorted_findings()[0].severity)
        self.assertEqual("abcdef12", d.facts["报告"])

    def test_primary_attempt_prefers_last_failure_over_later_user_stop(self):
        attempts = [
            {"attempt": "a1", "status": "failed", "code": "WIFI_JOIN_FAILED"},
            {"attempt": "a2", "status": "stopped", "code": "USER_STOP"},
        ]
        self.assertEqual("a1", primary_attempt(attempts)["attempt"])

    def test_self_test_track_does_not_hide_carplay_attempt(self):
        attempts = [
            {"attempt": "a1", "track": "carplay", "status": "failed", "code": "CARWITH_FAILED"},
            {"attempt": "s1", "track": "self_test", "status": "failed", "code": "CARWITH_BUSY"},
        ]
        self.assertEqual("a1", primary_attempt(attempts)["attempt"])

    def test_source_frames_without_transport_writes(self):
        d = diagnose(report([
            {"process": "p", "attempt": "a1", "event": "MEDIA_SAMPLE", "stage": "STREAMING", "status": "receiving_source",
             "sourceVideoFrames": 120, "videoFrames": 0},
        ]))
        self.assertTrue(any("没有成功发送" in t for t in titles(d)))

    def test_missing_end_is_not_called_a_crash(self):
        d = diagnose(report([{"process": "p", "attempt": "a1", "event": "STAGE", "stage": "WIFI", "status": "waiting"}]))
        finding = next(f for f in d.findings if "没有结束记录" in f.title)
        self.assertIn("不足以认定崩溃", finding.advice)

    def test_environment_permissions_and_missing_carwith(self):
        d = diagnose(report(env={"permission.NEARBY_WIFI_DEVICES": "false", "carwith.version": "unavailable:NameNotFoundException"}))
        self.assertTrue(any("附近 WLAN 设备" in t for t in titles(d)))
        self.assertTrue(any("没有检测到 CarWith" in t for t in titles(d)))

    def test_location_permission_only_matters_on_android_12(self):
        env = {"permission.ACCESS_FINE_LOCATION": "false", "system": "Android 15 SDK=35 build=x"}
        self.assertFalse(any("精确位置" in t for t in titles(diagnose(report(env=env)))))
        env["system"] = "Android 12 SDK=31 build=x"
        self.assertTrue(any("精确位置" in t for t in titles(diagnose(report(env=env)))))

    def test_carlife_loopback_refusal_is_not_a_receiver_refusal(self):
        log = "[10:00:00.000] CARLIFE: 连接尝试 1 未完成：failed to connect (port 7240): ECONNREFUSED (Connection refused)"
        found = titles(diagnose(report(log=log)))
        self.assertFalse(any("接收端端口" in t for t in found))
        self.assertTrue(any("CarWith 本地端口" in t for t in found))

    def test_enonet_with_unknown_binding(self):
        log = "\n".join(["[10:00:01.000] AIRPLAY: connect failed: ENONET"] * 3)
        d = diagnose(report(env={"network.bound": "unknown"}, log=log))
        finding = next(f for f in d.findings if "ENONET" in f.title)
        self.assertIn("network.bound=unknown", finding.advice)
        self.assertNotIn("[10:00", finding.evidence)

    def test_exit_history_and_uncaught_stack(self):
        d = diagnose(report(
            [{"process": "p", "attempt": "a1", "event": "UNCAUGHT_EXCEPTION", "thread": "video", "stack": "java.lang.IllegalStateException: x\n at y"}],
            exits="time=2026-11-01T10:00:00Z reason=3(LOW_MEMORY) status=0",
        ))
        self.assertTrue(any("内存不足" in t for t in titles(d)))
        uncaught = next(f for f in d.findings if "未捕获异常" in f.title)
        self.assertIn("IllegalStateException", uncaught.evidence)

    def test_raw_runtime_log_and_unknown_text(self):
        raw = diagnose("[10:00:00.000] VIDEO: 车机关闭了屏幕数据口（EOF，此前回传 0B，已发 3 帧）\n")
        self.assertEqual("runtime_log", raw.kind)
        self.assertTrue(any("关闭了屏幕数据口" in t for t in titles(raw)))
        self.assertEqual("unknown", diagnose("hello world").kind)

    def test_reply_and_excerpt_are_bounded(self):
        log = "\n".join(f"[10:00:{i % 60:02d}.000] SESSION: line {i} failed" for i in range(5000))
        d = diagnose(report([{"process": "p", "attempt": "a1", "event": "END", "stage": "SETUP", "status": "failed",
                              "code": "SETUP_FAILED", "elapsedMs": 4200}], log=log))
        self.assertLess(len(d.excerpt), 16_000)
        reply = format_reply(d, "补充")
        self.assertIn("耗时 4.2s", reply)
        self.assertIn("AI 补充分析", reply)


class RulesSyncTest(unittest.TestCase):
    def test_every_app_code_and_stage_has_a_rule(self):
        source = TIMELINE_KT.read_text(encoding="utf-8")
        codes = re.search(r"enum class DiagnosticCode\((.*?)\n}", source, re.S).group(1)
        stages = re.search(r"enum class ConnectionStage\((.*?)\n}", source, re.S).group(1)
        self.assertEqual(set(re.findall(r"^    ([A-Z_]+)\(", codes, re.M)), set(CODE_ADVICE))
        self.assertEqual(set(re.findall(r"^    ([A-Z_]+)\(", stages, re.M)), set(STAGE_LABELS))


class RedactTest(unittest.TestCase):
    def test_removes_network_identifiers_and_secrets(self):
        text = redact("mac=AA:BB:CC:DD:EE:FF ip 192.168.1.20 ssid=MyCar password=hunter2 13812345678 https://x.example/a")
        for secret in ("AA:BB", "192.168", "MyCar", "hunter2", "13812345678", "example"):
            self.assertNotIn(secret, text)

    def test_keeps_timestamps_and_keyframe_words(self):
        text = "[22:39:01.123] forceKeyFrame keyframes=3"
        self.assertEqual(text, redact(text))


if __name__ == "__main__":
    unittest.main()
