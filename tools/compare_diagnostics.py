#!/usr/bin/env python3
"""Compare two mcarplay support reports (baseline vs candidate) offline.

Regression oracle for behaviour-preserving refactors (docs/ARCHITECTURE-REFACTOR.md, section 6). It checks two things:

* the connection timeline of the compared attempts: stage order, milestone order, end status/code, warnings, notes and
  which media counters moved;
* the runtime log inside the time window of those attempts: per tag, the set of distinct normalised lines and the order in
  which each line first appears.

Repeat counts, timing and the interleaving between tags are not compared, because retries and threads make them vary
between two runs of the same build. Pass a second baseline run as --noise-baseline to learn what else varies.

Exit status: 0 consistent, 1 differences found, 2 unusable input.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

MAX_BYTES = 16_000_000
DAY_MS = 86_400_000
# Lines stamped by different threads may land slightly out of order; anything larger is a day change.
JITTER_MS = 1_000

DEFAULT_IGNORED_TAGS = frozenset({
    "BTSNOOP", "DIAGNOSTICS", "DIMMER", "DISCLAIMER", "ENV", "HID", "HID-RAW", "PRIVACY", "TIMING", "UI", "UNREDACTED",
    "UPDATE",
})
UNTAGGED = "(无标签)"

SECTION = re.compile(r"^=== ([A-Z][A-Z ]+?)(?: /.*)? ===$")
LOG_LINE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\.(\d{3})\] ?(.*)$")
TAG = re.compile(r"^(>>>|[A-Z][A-Za-z0-9_-]*(?=:)|[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+(?=\s))")
PROCESS_ANCHOR = re.compile(r"^DIAGNOSTICS: schema=\d+ process=\S+ timeMs=(\d+)")
HEADER_GENERATED = re.compile(r"^generatedAt=(\S+) timezone=(\S+)", re.M)

# Order matters: earlier alternatives win. Protocol message ids such as 0x4301 are kept; long hex literals are values.
VARIABLE = re.compile(
    r"(?P<alias><(?P<kind>[A-Z]+)-\d+>)"
    r"|(?P<uuid>\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b)"
    r"|(?P<mac>\b[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5}\b)"
    r"|(?P<ipv4>(?<![\w.])\d{1,3}(?:\.\d{1,3}){3}(?![\w.]))"
    r"|(?P<hexlit>\b0x[0-9a-fA-F]+)"
    r"|(?P<hexid>(?<![A-Za-z0-9])(?=[0-9a-fA-F]*\d)(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{8,}(?![A-Za-z0-9]))"
    r"|(?P<size>(?<![A-Za-z0-9_])\d+x\d+)"
    r"|(?P<num>(?<![A-Za-z0-9_])\d+(?:\.\d+)?)"
)


def _replace_variable(match: re.Match) -> str:
    if match.group("alias"):
        return f"<{match.group('kind')}>"
    if match.group("uuid"):
        return "<uuid>"
    if match.group("mac"):
        return "<MAC>"
    if match.group("ipv4"):
        return "<IP>"
    if match.group("hexlit"):
        literal = match.group("hexlit")
        return literal if len(literal) <= 6 else "0x#"
    if match.group("hexid"):
        return "<hex>"
    if match.group("size"):
        return "#x#"
    return "#"


def normalize(message: str) -> str:
    """Reduce a log message to its skeleton: variable values become placeholders, wording stays."""
    return " ".join(VARIABLE.sub(_replace_variable, message).split())


def tag_of(message: str) -> str:
    match = TAG.match(message)
    return match.group(1) if match else UNTAGGED


@dataclass
class LogLine:
    tod_ms: int
    message: str
    at_ms: int | None = None  # absolute epoch milliseconds once dated


@dataclass
class Attempt:
    process: str
    attempt: str
    events: list[dict]

    def time_range(self) -> tuple[int, int] | None:
        times = [event["timeMs"] for event in self.events if isinstance(event.get("timeMs"), (int, float))]
        return (int(min(times)), int(max(times))) if times else None


@dataclass
class Report:
    is_report: bool
    header: str
    attempts: list[Attempt]
    log: list[LogLine]
    continuation_lines: int
    truncated: bool


@dataclass(frozen=True)
class Diff:
    area: str  # "timeline" or "log"
    key: tuple  # stable identity, used to match --noise-baseline findings
    message: str
    severity: str = "fail"  # "fail", "warn" or "noise"


@dataclass
class Result:
    diffs: list[Diff] = field(default_factory=list)
    info: list[str] = field(default_factory=list)
    timeline_compared: int = 0
    log_lines: tuple[int, int] = (0, 0)
    log_tags: int = 0
    log_window: str = ""

    def failing(self, area: str | None = None) -> list[Diff]:
        return [d for d in self.diffs if d.severity == "fail" and (area is None or d.area == area)]


def parse_report(text: str, track: str | None = "carplay") -> Report:
    """Split a support report into timeline attempts and runtime log. A file without report sections is treated as a bare log."""
    is_report = text.startswith("mcarplay support report")
    sections: dict[str, list[str]] = {}
    header: list[str] = []
    current: list[str] | None = None
    for line in text.splitlines():
        match = SECTION.match(line)
        if match:
            current = sections.setdefault(match.group(1).strip(), [])
        elif current is None:
            header.append(line)
        else:
            current.append(line)
    if not sections:
        log_lines, timeline_lines = text.splitlines(), []
    else:
        log_lines, timeline_lines = sections.get("RUNTIME LOG", []), sections.get("CONNECTION TIMELINE", [])

    attempts: dict[tuple[str, str], Attempt] = {}
    for line in timeline_lines:
        if not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
        except (ValueError, RecursionError):
            continue
        if not isinstance(event, dict) or event.get("event") == "PROCESS_START":
            continue
        process, attempt = event.get("process"), event.get("attempt")
        if not isinstance(process, str) or not isinstance(attempt, str):
            continue
        if track is not None and event.get("track", "carplay") != track:
            continue
        attempts.setdefault((process, attempt), Attempt(process, attempt, [])).events.append(event)

    log: list[LogLine] = []
    continuation = 0
    for line in log_lines:
        match = LOG_LINE.match(line)
        if match is None:
            continuation += 1 if line.strip() else 0
            continue
        hours, minutes, seconds, millis, message = match.groups()
        log.append(LogLine(((int(hours) * 60 + int(minutes)) * 60 + int(seconds)) * 1000 + int(millis), message))
    return Report(
        is_report=is_report,
        header="\n".join(header),
        attempts=list(attempts.values()),
        log=log,
        continuation_lines=continuation,
        truncated="[earlier log content truncated]" in text,
    )


def date_log(report: Report) -> bool:
    """Give every log line an absolute time. Log lines only carry HH:mm:ss.SSS; process-start lines and the header carry dates."""
    lines = report.log
    if not lines:
        return False
    anchors = []
    for index, line in enumerate(lines):
        match = PROCESS_ANCHOR.match(line.message)
        if match:
            anchors.append((index, int(match.group(1))))
    if not anchors:
        anchor = _header_anchor(report, lines[-1])
        if anchor is None:
            return False
        anchors = [(len(lines) - 1, anchor)]
    for index, at_ms in anchors:
        lines[index].at_ms = at_ms
    first = anchors[0][0]
    for index in range(first - 1, -1, -1):
        step = lines[index + 1].tod_ms - lines[index].tod_ms
        if step < -JITTER_MS:
            step += DAY_MS
        lines[index].at_ms = lines[index + 1].at_ms - step
    for index in range(first + 1, len(lines)):
        if lines[index].at_ms is not None:
            continue
        step = lines[index].tod_ms - lines[index - 1].tod_ms
        if step < -JITTER_MS:
            step += DAY_MS
        lines[index].at_ms = lines[index - 1].at_ms + step
    return True


def _header_anchor(report: Report, last: LogLine) -> int | None:
    """Date the last log line from the report header: the latest instant not after generatedAt with that local time of day."""
    match = HEADER_GENERATED.search(report.header)
    if match is None:
        return None
    # java.time.Instant prints 0, 3, 6 or 9 fractional digits; keep whole seconds plus milliseconds.
    instant = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", match.group(1))
    if instant is None:
        return None
    try:
        from zoneinfo import ZoneInfo

        generated = datetime.strptime(instant.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
        generated += timedelta(milliseconds=int((instant.group(2) or "0").ljust(3, "0")[:3]))
        local = generated.astimezone(ZoneInfo(match.group(2)))
    except (ValueError, KeyError, ImportError, OSError):
        return None
    generated_ms = int(generated.timestamp() * 1000)
    local_tod = ((local.hour * 60 + local.minute) * 60 + local.second) * 1000 + local.microsecond // 1000
    return generated_ms - ((local_tod - last.tod_ms) % DAY_MS)


def attempt_signature(attempt: Attempt) -> dict:
    """The parts of an attempt that a behaviour-preserving change must not alter."""
    stages: list[str] = []
    milestones: list[str] = []
    warnings: list[str] = []
    notes: list[str] = []
    firsts: set[str] = set()
    last: dict = {}
    end = None
    elapsed: dict[str, object] = {}
    for event in attempt.events:
        last.update(event)
        kind = event.get("event")
        stage = event.get("stage")
        if isinstance(stage, str) and (not stages or stages[-1] != stage):
            stages.append(stage)
        if kind == "MILESTONE" and isinstance(event.get("milestone"), str) and event["milestone"] not in milestones:
            milestones.append(event["milestone"])
            elapsed[event["milestone"]] = event.get("elapsedMs")
        elif kind == "WARNING":
            code = str(event.get("code", "NONE"))
            if not warnings or warnings[-1] != code:
                warnings.append(code)
        elif kind == "NOTE":
            name = str(event.get("detail", "")).split(" ", 1)[0].split("=", 1)[0] or "(空)"
            if name not in notes:
                notes.append(name)
        elif kind == "END":
            end = event
        elif isinstance(kind, str) and kind.startswith("FIRST_"):
            firsts.add(kind)
            elapsed.setdefault(kind, event.get("elapsedMs"))
        elif kind == "UNCAUGHT_EXCEPTION":
            notes.append("UNCAUGHT_EXCEPTION")
    if end is not None:
        elapsed["END"] = end.get("elapsedMs")
    return {
        "stages": stages,
        "milestones": milestones,
        "end": [end.get("status", "unknown"), end.get("code", "NONE")] if end else ["(无 END)", "(无 END)"],
        "warnings": warnings,
        "notes": notes,
        "firsts": sorted(firsts),
        "media": {
            "sourceVideo": bool(last.get("sourceVideoFrames", 0)),
            "video": bool(last.get("videoFrames", 0)),
            "nativeAudio": bool(last.get("audioChunks", 0)),
            "audio": bool(last.get("audioPacketsSent", 0)),
        },
        "elapsedMs": elapsed,
    }


TIMELINE_FIELDS = (
    ("stages", "阶段顺序"),
    ("milestones", "里程碑顺序"),
    ("end", "结束状态/错误码"),
    ("warnings", "告警码"),
    ("notes", "记录项"),
    ("firsts", "首包事件"),
    ("media", "媒体计数是否前进"),
)


def compare_timelines(base: list[Attempt], cand: list[Attempt], count: int, result: Result) -> tuple[list[Attempt], list[Attempt]]:
    usable = min(count, len(base), len(cand))
    if usable < count:
        result.diffs.append(Diff(
            "timeline", ("attempt-count",),
            f"要求比较最近 {count} 次连接尝试，但基准只有 {len(base)} 次、候选只有 {len(cand)} 次",
        ))
    base_tail, cand_tail = (base[-usable:], cand[-usable:]) if usable else ([], [])
    for position, (left, right) in enumerate(zip(base_tail, cand_tail), start=1):
        mine, theirs = attempt_signature(left), attempt_signature(right)
        for name, label in TIMELINE_FIELDS:
            if mine[name] != theirs[name]:
                result.diffs.append(Diff(
                    "timeline", (name, position),
                    f"[尝试 {position}/{usable}] {label}不同：基准 {_show(mine[name])}；候选 {_show(theirs[name])}",
                ))
        result.info.extend(_timing_info(position, usable, mine["elapsedMs"], theirs["elapsedMs"]))
    result.timeline_compared = usable
    return base_tail, cand_tail


def _show(value) -> str:
    if isinstance(value, dict):
        return "、".join(f"{key}={'是' if flag else '否'}" for key, flag in value.items())
    return " → ".join(value) if value else "(空)"


def _timing_info(position: int, total: int, base: dict, cand: dict) -> list[str]:
    lines = []
    for name, label in (("FIRST_VIDEO", "首帧"), ("END", "结束")):
        left, right = base.get(name), cand.get(name)
        if isinstance(left, (int, float)) and isinstance(right, (int, float)):
            lines.append(f"[尝试 {position}/{total}] {label}耗时：基准 {left / 1000:.1f}s / 候选 {right / 1000:.1f}s")
    slow = []
    for name, left in base.items():
        right = cand.get(name)
        if name in ("FIRST_VIDEO", "END") or not isinstance(left, (int, float)) or not isinstance(right, (int, float)):
            continue
        if abs(right - left) > max(2_000, 0.5 * max(left, 1)):
            slow.append(f"{name} {left / 1000:.1f}s→{right / 1000:.1f}s")
    if slow:
        lines.append(f"[尝试 {position}/{total}] 耗时差异较大（仅提示）：" + "，".join(slow))
    return lines


def window_log(report: Report, attempts: list[Attempt], before_ms: int, after_ms: int) -> tuple[list[LogLine], str]:
    """Keep the log lines written while the compared attempts ran. Falls back to the whole log when lines cannot be dated."""
    ranges = [r for r in (attempt.time_range() for attempt in attempts) if r]
    if not ranges:
        return report.log, "全部日志（没有可用的时间线事件）"
    if not date_log(report):
        return report.log, "全部日志（日志行无法定日期：缺少进程起点行和报告头时间）"
    start, end = min(r[0] for r in ranges) - before_ms, max(r[1] for r in ranges) + after_ms
    kept = [line for line in report.log if line.at_ms is not None and start <= line.at_ms <= end]
    return kept, f"连接尝试时间窗内 {(end - start) / 1000:.0f}s"


def log_profile(lines: list[LogLine], ignored_tags: frozenset[str], ignored_lines: list[re.Pattern]) -> dict[str, dict[str, int]]:
    """tag -> {skeleton: count}, skeletons in order of first appearance."""
    profile: dict[str, dict[str, int]] = {}
    for line in lines:
        tag = tag_of(line.message)
        if tag in ignored_tags or any(pattern.search(line.message) for pattern in ignored_lines):
            continue
        skeleton = normalize(line.message)
        bucket = profile.setdefault(tag, {})
        bucket[skeleton] = bucket.get(skeleton, 0) + 1
    return profile


def compare_logs(base: dict[str, dict[str, int]], cand: dict[str, dict[str, int]], strict_counts: bool, result: Result) -> None:
    for tag in sorted(set(base) | set(cand)):
        mine, theirs = base.get(tag, {}), cand.get(tag, {})
        for skeleton in mine:
            if skeleton not in theirs:
                result.diffs.append(Diff("log", ("missing", tag, skeleton), f"缺少（基准有、候选无）：{skeleton}"))
        for skeleton in theirs:
            if skeleton not in mine:
                result.diffs.append(Diff("log", ("new", tag, skeleton), f"新增（候选有、基准无）：{skeleton}"))
        common_mine = [s for s in mine if s in theirs]
        common_theirs = [s for s in theirs if s in mine]
        for index, (left, right) in enumerate(zip(common_mine, common_theirs), start=1):
            if left != right:
                result.diffs.append(Diff(
                    "log", ("order", tag),
                    f"首次出现顺序不同（标签 {tag} 第 {index} 条起）：基准「{left}」；候选「{right}」",
                ))
                break
        for skeleton in common_mine:
            left, right = mine[skeleton], theirs[skeleton]
            if min(left, right) == 1 and max(left, right) > 1:
                result.diffs.append(Diff(
                    "log", ("count", tag, skeleton),
                    f"次数不同（基准 {left} 次 / 候选 {right} 次）：{skeleton}",
                    "fail" if strict_counts else "warn",
                ))


@dataclass
class Options:
    attempts: int = 1
    track: str | None = "carplay"
    full_log: bool = False
    before_ms: int = 3_000
    after_ms: int = 15_000
    ignored_tags: frozenset[str] = DEFAULT_IGNORED_TAGS
    ignored_lines: list[re.Pattern] = field(default_factory=list)
    strict_counts: bool = False


def compare(base_text: str, cand_text: str, options: Options | None = None) -> Result:
    options = options or Options()
    base, cand = parse_report(base_text, options.track), parse_report(cand_text, options.track)
    result = Result()
    base_attempts: list[Attempt] = []
    cand_attempts: list[Attempt] = []
    if base.attempts or cand.attempts:
        base_attempts, cand_attempts = compare_timelines(base.attempts, cand.attempts, options.attempts, result)
    else:
        result.info.append("两份输入都没有时间线事件：只比较日志")
    if options.full_log or not (base_attempts and cand_attempts):
        base_lines, cand_lines, window = base.log, cand.log, "全部日志"
    else:
        base_lines, window = window_log(base, base_attempts, options.before_ms, options.after_ms)
        cand_lines, cand_window = window_log(cand, cand_attempts, options.before_ms, options.after_ms)
        if cand_window != window:
            window = f"基准：{window}；候选：{cand_window}"
    base_profile = log_profile(base_lines, options.ignored_tags, options.ignored_lines)
    cand_profile = log_profile(cand_lines, options.ignored_tags, options.ignored_lines)
    compare_logs(base_profile, cand_profile, options.strict_counts, result)
    result.log_lines = (len(base_lines), len(cand_lines))
    result.log_tags = len(set(base_profile) | set(cand_profile))
    result.log_window = window
    if not base_lines or not cand_lines:
        result.diffs.append(Diff(
            "log", ("empty",),
            f"时间窗内没有可比较的日志行（基准 {len(base_lines)} 行 / 候选 {len(cand_lines)} 行）",
        ))
    for name, report in (("基准", base), ("候选", cand)):
        if report.truncated:
            result.info.append(f"{name}的运行日志开头被截断：窗口靠前的行可能缺失")
    return result


def apply_noise(result: Result, noise: Result) -> None:
    """Demote findings that also show up between two runs of the baseline build."""
    noisy = {diff.key for diff in noise.diffs}
    result.diffs = [
        Diff(diff.area, diff.key, diff.message, "noise") if diff.key in noisy and diff.severity != "noise" else diff
        for diff in result.diffs
    ]


def render(result: Result, base_name: str, cand_name: str) -> str:
    out = [f"基准：{base_name}", f"候选：{cand_name}"]
    for area, title in (("timeline", "时间线"), ("log", "日志")):
        failing = result.failing(area)
        if area == "timeline":
            detail = f"比较最近 {result.timeline_compared} 次连接尝试"
        else:
            detail = (
                f"{result.log_window}，基准 {result.log_lines[0]} 行 / 候选 {result.log_lines[1]} 行，"
                f"{result.log_tags} 个标签"
            )
        if area == "timeline" and result.timeline_compared == 0 and not failing:
            verdict = "未比较"
        else:
            verdict = "一致" if not failing else f"不一致，{len(failing)} 项"
        out.append(f"{title}（{detail}）：{verdict}")
        out.extend(f"  {diff.message}" for diff in failing)
    warnings = [d for d in result.diffs if d.severity == "warn"]
    if warnings:
        out.append(f"提示（不计入结论，--strict-counts 可改为计入），{len(warnings)} 项：")
        out.extend(f"  {diff.message}" for diff in warnings)
    noise = [d for d in result.diffs if d.severity == "noise"]
    if noise:
        out.append(f"噪声（两次基准运行之间同样存在，已忽略）：{len(noise)} 项")
    out.extend(result.info)
    failing = result.failing()
    out.append("结论：一致" if not failing else f"结论：不一致（{len(failing)} 项）")
    return "\n".join(out)


def to_json(result: Result) -> dict:
    return {
        "consistent": not result.failing(),
        "timelineAttemptsCompared": result.timeline_compared,
        "logWindow": result.log_window,
        "logLines": {"baseline": result.log_lines[0], "candidate": result.log_lines[1]},
        "logTags": result.log_tags,
        "differences": [
            {"area": d.area, "kind": d.key[0], "severity": d.severity, "message": d.message} for d in result.diffs
        ],
        "info": result.info,
    }


def _read(parser: argparse.ArgumentParser, path: Path) -> str:
    try:
        if path.stat().st_size > MAX_BYTES:
            parser.error(f"{path} exceeds the 16MB limit")
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        parser.error(f"cannot read {path}: {error}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("baseline", type=Path, help="report from the reference build")
    parser.add_argument("candidate", type=Path, help="report from the build under test, same scenario")
    parser.add_argument("--attempts", type=int, default=1, help="compare the last N connection attempts (default 1)")
    parser.add_argument("--track", default="carplay", help="timeline track to compare; 'all' for every track")
    parser.add_argument("--full-log", action="store_true", help="compare the whole runtime log instead of the attempt window")
    parser.add_argument("--margin-before", type=float, default=3.0, help="seconds of log kept before the first event")
    parser.add_argument("--margin-after", type=float, default=15.0, help="seconds of log kept after the last event")
    parser.add_argument("--ignore-tag", action="append", default=[], metavar="TAG", help="also ignore this log tag")
    parser.add_argument("--keep-tag", action="append", default=[], metavar="TAG", help="compare a tag ignored by default")
    parser.add_argument("--ignore-line", action="append", default=[], metavar="REGEX", help="ignore log lines matching this")
    parser.add_argument("--strict-counts", action="store_true", help="treat once-versus-many repeat differences as failures")
    parser.add_argument("--noise-baseline", type=Path, help="a second run of the reference build; shared differences are ignored")
    parser.add_argument("--json", action="store_true", help="print machine-readable JSON")
    args = parser.parse_args(argv)
    if args.attempts < 1:
        parser.error("--attempts must be at least 1")
    try:
        ignored_lines = [re.compile(pattern) for pattern in args.ignore_line]
    except re.error as error:
        parser.error(f"invalid --ignore-line pattern: {error}")
    options = Options(
        attempts=args.attempts,
        track=None if args.track == "all" else args.track,
        full_log=args.full_log,
        before_ms=int(args.margin_before * 1000),
        after_ms=int(args.margin_after * 1000),
        ignored_tags=frozenset((DEFAULT_IGNORED_TAGS | set(args.ignore_tag)) - set(args.keep_tag)),
        ignored_lines=ignored_lines,
        strict_counts=args.strict_counts,
    )
    base_text = _read(parser, args.baseline)
    result = compare(base_text, _read(parser, args.candidate), options)
    if args.noise_baseline:
        apply_noise(result, compare(base_text, _read(parser, args.noise_baseline), options))
    if args.json:
        print(json.dumps(to_json(result), ensure_ascii=False, indent=2))
    else:
        print(render(result, str(args.baseline), str(args.candidate)))
    return 0 if not result.failing() else 1


if __name__ == "__main__":
    sys.exit(main())
