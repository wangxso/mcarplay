import contextlib
import io
import json
import re
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from compare_diagnostics import UNTAGGED, Options, apply_noise, compare, main, normalize, tag_of

CST = timezone(timedelta(hours=8))
START = datetime(2026, 11, 10, 11, 25, 5, tzinfo=CST)


def ms(moment: datetime) -> int:
    return int(moment.timestamp() * 1000)


def stamp(moment: datetime) -> str:
    return moment.strftime("[%H:%M:%S.") + f"{moment.microsecond // 1000:03d}]"


def event(kind, at=0.0, attempt="a1", process="p1", start=START, **fields):
    record = {"process": process, "attempt": attempt, "track": "carplay", "event": kind,
              "timeMs": ms(start + timedelta(seconds=at)), "elapsedMs": int(at * 1000),
              "stage": "BLUETOOTH", "status": "waiting", "code": "NONE"}
    record.update(fields)
    return record


def render_log(entries, start=START):
    """entries: (seconds after start, message). Lines are written in time order, as the app does; a message may carry
    continuation lines after a newline."""
    ordered = sorted(entries, key=lambda entry: entry[0])
    return [f"{stamp(start + timedelta(seconds=seconds))} {message}" for seconds, message in ordered]


def report(events, entries, generated="2026-11-10T03:30:00Z", tz="Asia/Shanghai", anchor=True, start=START):
    if anchor:
        boot = start - timedelta(seconds=30)
        entries = list(entries) + [(-30, f"DIAGNOSTICS: schema=1 process=405441f7 timeMs={ms(boot)}")]
    lines = render_log(entries, start)
    return "\n".join([
        "mcarplay support report / 诊断报告 schema=1",
        "reportId=r1",
        f"generatedAt={generated} timezone={tz}",
        "=== ENVIRONMENT / 导出时环境（不等于故障发生时状态） ===",
        "app.version=0.1.6",
        "=== CONNECTION TIMELINE / 最近进程与连接事件 (JSONL schema=1) ===",
        *[json.dumps(item, ensure_ascii=False) for item in events],
        "=== SYSTEM EXIT HISTORY / 本应用系统退出记录 ===",
        "=== RUNTIME LOG / 运行日志 ===",
        *lines,
        "",
    ])


def streaming_attempt(first_video=8.2, end=40.0, **overrides):
    events = [
        event("BEGIN", 0.0),
        event("MILESTONE", 0.4, milestone="BT_LINK"),
        event("STAGE", 2.0, stage="WIFI"),
        event("MILESTONE", 3.1, stage="WIFI", milestone="WIFI_JOINED"),
        event("STAGE", 4.0, stage="PAIRING"),
        event("NOTE", 4.1, stage="PAIRING", detail="RECEIVER srcvers=320.17 features=0x5A7FFFF7"),
        event("STAGE", 6.0, stage="STREAMING"),
        event("FIRST_VIDEO", first_video, stage="STREAMING", videoFrames=1, sourceVideoFrames=1),
        event("MEDIA_SAMPLE", 20.0, stage="STREAMING", videoFrames=600, sourceVideoFrames=600),
        event("END", end, stage="STREAMING", status="ended", code="USER_STOP", videoFrames=900, sourceVideoFrames=900),
    ]
    for item in events:
        item.update(overrides)
    return events


BASE_LOG = [
    (0.1, "BT-C: 连接目标 <MAC-1>"),
    (2.1, "WIFI: 加入车机热点 <NAME-2>"),
    (4.2, "AIRPLAY: 配对开始 host=<IP-1>:7000"),
    (5.0, "AIRPLAY: ✓ 配对完成 writeKey=0a1b2c3d4e5f"),
    (6.1, "VIDEO: 已发 1 帧"),
    (40.1, "SESSION: 旧会话 TEARDOWN 与蓝牙/WiFi 清理完成"),
]


def messages(result, severity="fail"):
    return [diff.message for diff in result.diffs if diff.severity == severity]


class NormalizeTest(unittest.TestCase):
    def test_protocol_ids_stay_and_values_become_placeholders(self):
        self.assertEqual("拿到 0x4301 端点 port=#", normalize("拿到 0x4301 端点 port=7000"))
        self.assertEqual("features=0x#", normalize("features=0x5A7FFFF7"))
        self.assertEqual("run=<hex> id=<uuid>", normalize("run=b2d551a3 id=e5fe1bca-9d2f-4c7a-8a11-0123456789ab"))

    def test_redacted_and_raw_identifiers_normalise_alike(self):
        self.assertEqual("<MAC> <IP> <NAME>", normalize("<MAC-2>  <IP-11> <NAME-3>"))
        self.assertEqual("<MAC> <IP>", normalize("aa:bb:cc:dd:ee:ff 192.168.43.1"))

    def test_numbers_next_to_text_and_units(self):
        self.assertEqual("已发 # 帧，耗时 #ms，第#次，流 #x#", normalize("已发 3 帧，耗时 120ms，第2次，流 1920x720"))
        self.assertEqual("拆链发生在起流后 #s", normalize("拆链发生在起流后 6.5s"))

    def test_identifiers_keep_their_digits(self):
        text = "iAP2 MIB3 IPv4 H264 t102 et=NONE"
        self.assertEqual(text, normalize(text))

    def test_tags(self):
        self.assertEqual("SVC", tag_of("SVC: 收到「下车自动结束」"))
        self.assertEqual(">>>", tag_of(">>> [车机回连] 拿到接收端热点"))
        self.assertEqual("CARLIFE-DIAG", tag_of("CARLIFE-DIAG run=b2d551a3 event=ENTRY"))
        self.assertEqual("HU-SDP", tag_of("HU-SDP: 注册"))
        self.assertEqual(UNTAGGED, tag_of("已手动断开并停止保活服务"))
        self.assertEqual(UNTAGGED, tag_of("Caused by: java.io.IOException"))


class TimelineTest(unittest.TestCase):
    def test_timing_and_counters_do_not_matter(self):
        base = report(streaming_attempt(), BASE_LOG)
        cand = report(streaming_attempt(first_video=9.4, end=55.0), BASE_LOG)
        result = compare(base, cand)
        self.assertEqual([], messages(result))
        self.assertEqual(1, result.timeline_compared)
        self.assertTrue(any("首帧耗时：基准 8.2s / 候选 9.4s" in line for line in result.info))

    def test_missing_milestone_and_changed_end_code_are_reported(self):
        events = [item for item in streaming_attempt() if item.get("milestone") != "WIFI_JOINED"]
        events[-1] = dict(events[-1], status="failed", code="VIDEO_CLOSED")
        result = compare(report(streaming_attempt(), BASE_LOG), report(events, BASE_LOG))
        found = messages(result)
        self.assertTrue(any("里程碑顺序不同" in line and "WIFI_JOINED" in line for line in found))
        self.assertTrue(any("结束状态/错误码不同" in line and "VIDEO_CLOSED" in line for line in found))

    def test_stage_order_warning_note_and_media_differences(self):
        events = streaming_attempt()
        events.insert(3, event("WARNING", 2.5, stage="WIFI", code="WIFI_LOST"))
        events.insert(4, event("NOTE", 2.6, stage="WIFI", detail="EXCEPTION java.lang.IllegalStateException"))
        events[-1] = dict(events[-1], videoFrames=0)
        found = messages(compare(report(streaming_attempt(), BASE_LOG), report(events, BASE_LOG)))
        self.assertTrue(any("告警码不同" in line and "WIFI_LOST" in line for line in found))
        self.assertTrue(any("记录项不同" in line and "EXCEPTION" in line for line in found))
        self.assertTrue(any("媒体计数是否前进不同" in line and "video=否" in line for line in found))

    def test_attempts_align_from_the_end_and_self_test_is_ignored(self):
        older = [event("BEGIN", -600, attempt="a0"), event("END", -590, attempt="a0", status="failed", code="PAIRING_FAILED")]
        self_test = [dict(event("BEGIN", 50, attempt="s1"), track="self_test")]
        base = report(older + streaming_attempt() + self_test, BASE_LOG)
        cand = report(streaming_attempt(), BASE_LOG)
        self.assertEqual([], messages(compare(base, cand)))
        short = compare(base, cand, Options(attempts=2))
        self.assertTrue(any("候选只有 1 次" in line for line in messages(short)))
        self.assertEqual(1, short.timeline_compared)

    def test_missing_end_differs_from_an_ended_attempt(self):
        found = messages(compare(report(streaming_attempt(), BASE_LOG), report(streaming_attempt()[:-1], BASE_LOG)))
        self.assertTrue(any("(无 END)" in line for line in found))

    def test_screen_output_suspend_and_resume_transitions_are_compared(self):
        events = streaming_attempt()
        paused = events[:-1] + [
            event("SCREEN_OUTPUT", 10.0, stage="STREAMING", screenSuspended=True),
            event("SCREEN_OUTPUT", 12.0, stage="STREAMING", screenSuspended=False),
        ] + events[-1:]
        found = messages(compare(report(paused, BASE_LOG), report(events, BASE_LOG)))
        self.assertEqual(1, len(found))
        self.assertIn("屏幕输出暂停/恢复顺序不同", found[0])
        self.assertIn("suspended → resumed", found[0])
        self.assertIn("(空)", found[0])


class LogTest(unittest.TestCase):
    def setUp(self):
        self.events = streaming_attempt()

    def compare_logs(self, base_lines, cand_lines, options=None):
        return compare(report(self.events, base_lines), report(self.events, cand_lines), options)

    def test_retry_counts_and_values_do_not_matter(self):
        base = BASE_LOG + [(7 + i * 0.4, f"CARLIFE: 连接尝试 {i + 1} 未完成 port=7240 opened=0 reason=REFUSED") for i in range(3)]
        cand = BASE_LOG + [(7 + i * 0.4, f"CARLIFE: 连接尝试 {i + 1} 未完成 port=7240 opened=0 reason=REFUSED") for i in range(7)]
        result = self.compare_logs(base, cand)
        self.assertEqual([], messages(result))
        self.assertEqual([], messages(result, "warn"))

    def test_missing_and_new_lines(self):
        cand = [entry for entry in BASE_LOG if "配对完成" not in entry[1]] + [(5.5, "AIRPLAY: 明文会话兜底")]
        found = messages(self.compare_logs(BASE_LOG, cand))
        self.assertEqual(2, len(found))
        self.assertTrue(any(line.startswith("缺少") and "配对完成 writeKey=<hex>" in line for line in found))
        self.assertTrue(any(line.startswith("新增") and "明文会话兜底" in line for line in found))

    def test_first_appearance_order_within_a_tag(self):
        base = BASE_LOG + [(6.5, "AIRPLAY: RECORD 已确认"), (6.8, "AIRPLAY: 音频协商开始")]
        cand = BASE_LOG + [(6.5, "AIRPLAY: 音频协商开始"), (6.8, "AIRPLAY: RECORD 已确认")]
        found = messages(self.compare_logs(base, cand))
        self.assertEqual(1, len(found))
        self.assertIn("首次出现顺序不同（标签 AIRPLAY 第 3 条起）", found[0])

    def test_interleaving_between_tags_does_not_matter(self):
        cand = [(2.1, BASE_LOG[0][1]), (0.1, BASE_LOG[1][1])] + BASE_LOG[2:]
        self.assertEqual([], messages(self.compare_logs(BASE_LOG, cand)))

    def test_once_versus_many_is_a_hint_unless_strict(self):
        cand = BASE_LOG + [(6.6, "AIRPLAY: 配对开始 host=<IP-1>:7000")]
        result = self.compare_logs(BASE_LOG, cand)
        self.assertEqual([], messages(result))
        self.assertTrue(any("基准 1 次 / 候选 2 次" in line for line in messages(result, "warn")))
        strict = self.compare_logs(BASE_LOG, cand, Options(strict_counts=True))
        self.assertEqual(1, len(messages(strict)))

    def test_ignored_tags_lines_and_continuations(self):
        cand = BASE_LOG + [
            (10, "ENV: wifi=-52dBm thermal=1"),
            (11, "TIMING: 时钟偏移 3ms\n    at com.mcarplay.app.Foo.bar(Foo.kt:12)"),
            (12, "VIDEO: 转码统计（已发 300 帧）"),
        ]
        self.assertTrue(any("转码统计" in line for line in messages(self.compare_logs(BASE_LOG, cand))))
        ignoring = Options(ignored_lines=[re.compile("转码统计")])
        self.assertEqual([], messages(self.compare_logs(BASE_LOG, cand, ignoring)))
        keeping = Options(ignored_tags=frozenset(), ignored_lines=[re.compile("转码统计")])
        self.assertEqual(2, len(messages(self.compare_logs(BASE_LOG, cand, keeping))))

    def test_only_the_attempt_window_is_compared(self):
        history = [(-3600, "AIRPLAY: 上一次会话的配对失败"), (-3599, "SESSION: 用户取消连接")]
        later = [(120, "CARLIFE: 用户请求启动 CarWith 出流自检")]
        result = self.compare_logs(BASE_LOG, history + BASE_LOG + later)
        self.assertEqual([], messages(result))
        self.assertEqual((6, 6), result.log_lines)  # the process-start line 30 s earlier is outside the window
        whole = self.compare_logs(BASE_LOG, history + BASE_LOG + later, Options(full_log=True))
        self.assertEqual(3, len(messages(whole)))

    def test_window_follows_the_attempt_across_midnight(self):
        start = datetime(2026, 11, 10, 23, 59, 50, tzinfo=CST)
        events = [event("BEGIN", 0, start=start), event("END", 20, start=start, status="ended", code="USER_STOP")]
        scenario = [(1, "AIRPLAY: 配对开始"), (12, "AIRPLAY: ✓ 配对完成"), (21, "SESSION: 清理完成")]
        # Same wall-clock time one day earlier, written before this process started: must stay outside the window.
        yesterday = [(15 - 86400, "AIRPLAY: 前一天同一时刻的失败")]
        base = report(events, scenario, start=start)
        cand = report(events, yesterday + scenario, start=start)
        result = compare(base, cand)
        self.assertEqual([], messages(result))
        self.assertEqual((3, 3), result.log_lines)

    def test_header_time_dates_the_log_when_no_process_start_line_is_retained(self):
        history = [(-7200, "AIRPLAY: 两小时前的失败")]
        base = report(self.events, BASE_LOG, anchor=False)
        cand = report(self.events, history + BASE_LOG, anchor=False)
        result = compare(base, cand)
        self.assertEqual([], messages(result))
        self.assertEqual((6, 6), result.log_lines)

    def test_header_time_with_nanoseconds_is_accepted(self):
        history = [(-7200, "AIRPLAY: 两小时前的失败")]
        stamp_ns = "2026-11-10T03:30:00.123456789Z"
        base = report(self.events, BASE_LOG, anchor=False, generated=stamp_ns)
        cand = report(self.events, history + BASE_LOG, anchor=False, generated=stamp_ns)
        result = compare(base, cand)
        self.assertEqual([], messages(result))
        self.assertIn("时间窗内", result.log_window)

    def test_undatable_logs_fall_back_to_the_whole_log(self):
        base = report(self.events, BASE_LOG, anchor=False, generated="unknown")
        cand = report(self.events, [(-7200, "AIRPLAY: 两小时前的失败")] + BASE_LOG, anchor=False, generated="unknown")
        result = compare(base, cand)
        self.assertEqual(1, len(messages(result)))
        self.assertIn("无法定日期", result.log_window)

    def test_bare_log_files_are_compared_without_a_timeline(self):
        result = compare("\n".join(render_log(BASE_LOG)), "\n".join(render_log(BASE_LOG[:-1])))
        self.assertEqual(0, result.timeline_compared)
        self.assertEqual(1, len(messages(result)))
        self.assertTrue(any("只比较日志" in line for line in result.info))

    def test_empty_window_is_a_failure_not_a_pass(self):
        result = self.compare_logs(BASE_LOG, [])
        self.assertTrue(any("没有可比较的日志行" in line for line in messages(result)))

    def test_profile_filtered_to_nothing_is_a_failure_not_a_pass(self):
        entries = [(5.0, "ENV: wifi=-52dBm thermal=1"), (5.1, "DIAGNOSTICS: 诊断状态已刷新")]
        result = self.compare_logs(entries, entries)
        found = messages(result)
        self.assertEqual(1, len(found))
        self.assertIn("没有可比较的日志行", found[0])
        self.assertIn("过滤后 0 行", found[0])
        # A noise baseline cannot turn "no comparable evidence" into a pass.
        apply_noise(result, self.compare_logs(entries, entries))
        self.assertEqual(1, len(messages(result)))
        self.assertEqual([], messages(result, "noise"))


class NoiseTest(unittest.TestCase):
    def test_differences_between_two_baseline_runs_are_demoted(self):
        events = streaming_attempt()
        racy = [(6.5, "AIRPLAY: 保活已启动"), (6.6, "AIRPLAY: 事件通道已连接")]
        swapped = [(6.5, "AIRPLAY: 事件通道已连接"), (6.6, "AIRPLAY: 保活已启动")]
        base = report(events, BASE_LOG + racy)
        second_baseline = report(events, BASE_LOG + swapped)
        cand = report(events, BASE_LOG + swapped + [(7.0, "AIRPLAY: 新增的一行")])
        result = compare(base, cand)
        self.assertEqual(2, len(messages(result)))
        apply_noise(result, compare(base, second_baseline))
        self.assertEqual(1, len(messages(result, "noise")))
        self.assertEqual(["新增（候选有、基准无）：AIRPLAY: 新增的一行"], messages(result))

    def test_noise_does_not_hide_a_different_order_change(self):
        events = streaming_attempt()
        pairing = "AIRPLAY: 配对开始 host=<IP-1>:7000"
        event_channel = "AIRPLAY: 事件通道已连接"
        keepalive = "AIRPLAY: 保活已启动"
        audio = "AIRPLAY: 音频协商开始"
        base = report(events, [(6.1, pairing), (6.2, event_channel), (6.3, keepalive), (6.4, audio)])
        # The second baseline swaps keepalive/audio; the candidate swaps pairing/event_channel at another position.
        second_baseline = report(events, [(6.1, pairing), (6.2, event_channel), (6.3, audio), (6.4, keepalive)])
        candidate = report(events, [(6.0, event_channel), (6.1, pairing), (6.3, keepalive), (6.4, audio)])
        result = compare(base, candidate)
        self.assertEqual(1, len(messages(result)))
        apply_noise(result, compare(base, second_baseline))
        self.assertEqual([], messages(result, "noise"))
        self.assertEqual(1, len(messages(result)))
        self.assertIn("第 1 条起", messages(result)[0])


class CliTest(unittest.TestCase):
    def run_cli(self, base, cand, *flags):
        with tempfile.TemporaryDirectory() as folder:
            left, right = Path(folder, "base.txt"), Path(folder, "cand.txt")
            left.write_text(base, encoding="utf-8")
            right.write_text(cand, encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                status = main([str(left), str(right), *flags])
            return status, output.getvalue()

    def test_bare_logs_report_the_timeline_as_not_compared(self):
        status, text = self.run_cli("\n".join(render_log(BASE_LOG)), "\n".join(render_log(BASE_LOG)))
        self.assertEqual(0, status)
        self.assertIn("时间线（比较最近 0 次连接尝试）：未比较", text)

    def test_exit_status_and_text_output(self):
        base = report(streaming_attempt(), BASE_LOG)
        status, text = self.run_cli(base, base)
        self.assertEqual(0, status)
        self.assertIn("时间线（比较最近 1 次连接尝试）：一致", text)
        self.assertIn("结论：一致", text)
        status, text = self.run_cli(base, report(streaming_attempt(), BASE_LOG[:-1]))
        self.assertEqual(1, status)
        self.assertIn("日志（连接尝试时间窗内", text)
        self.assertIn("结论：不一致（1 项）", text)

    def test_json_output(self):
        base = report(streaming_attempt(), BASE_LOG)
        status, text = self.run_cli(base, report(streaming_attempt(), BASE_LOG[:-1]), "--json")
        data = json.loads(text)
        self.assertEqual(1, status)
        self.assertFalse(data["consistent"])
        self.assertEqual("missing", data["differences"][0]["kind"])

    def test_invalid_arguments_exit_with_status_two(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            main(["a", "b", "--attempts", "0"])
        self.assertEqual(2, raised.exception.code)


if __name__ == "__main__":
    unittest.main()
