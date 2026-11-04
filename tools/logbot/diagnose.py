"""Rule-based diagnosis of an mcarplay support report or raw runtime log."""

from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass, field

from analyze_diagnostics import analyze_report
from logbot.rules import (
    CODE_ADVICE,
    EXCEPTION_LINE,
    EXIT_REASON_HINTS,
    LOG_PATTERNS,
    PERMISSION_HINTS,
    PERMISSION_SDKS,
    STAGE_LABELS,
)

SEVERITY_ORDER = {"error": 0, "warn": 1, "info": 2}
SEVERITY_MARK = {"error": "❌", "warn": "⚠️", "info": "ℹ️"}
BENIGN_CODES = {"NONE", "USER_STOP", "SUPERSEDED", "ENDPOINT_PENDING"}
MCARPLAY_MARKERS = re.compile(r"mcarplay|CARLIFE:|CarWith|AirPlay|iAP2|VIDEO:|SESSION:|COMPAT:")
INTERESTING_LINE = re.compile(
    r"(?i)exception|error|fail|refused|reset|timeout|timed out|失败|错误|异常|超时|断开|关闭"
    r"|COMPAT:|SESSION:|EVENT:|VIDEO:|TIMING:|STREAM_END|POWER|CARLIFE: 入流门控"
)
SECTION_HEADER = re.compile(r"^=== ([A-Z][A-Z ]+?)(?: /.*)? ===$")
TIMESTAMP_PREFIX = re.compile(r"^\[[^\]]{4,32}\]\s*")


@dataclass
class Finding:
    severity: str
    title: str
    advice: str = ""
    evidence: str = ""


@dataclass
class Diagnosis:
    kind: str  # "report" | "runtime_log" | "unknown"
    facts: dict[str, str] = field(default_factory=dict)
    attempts: list[dict] = field(default_factory=list)
    findings: list[Finding] = field(default_factory=list)
    excerpt: str = ""

    def sorted_findings(self) -> list[Finding]:
        return sorted(self.findings, key=lambda f: SEVERITY_ORDER.get(f.severity, 9))


def split_sections(text: str) -> dict[str, str]:
    sections: dict[str, list[str]] = {"HEADER": []}
    current = "HEADER"
    for line in text.splitlines():
        match = SECTION_HEADER.match(line)
        if match:
            current = match.group(1).strip()
            sections.setdefault(current, [])
            continue
        sections[current].append(line)
    return {name: "\n".join(lines) for name, lines in sections.items()}


def parse_environment(section: str) -> dict[str, str]:
    env = {}
    for line in section.splitlines():
        key, sep, value = line.partition("=")
        if sep and key and " " not in key:
            env[key] = value.strip()
    return env


def diagnose(text: str) -> Diagnosis:
    text = text.replace("\r\n", "\n")
    if text.startswith("mcarplay support report"):
        return _diagnose_report(text)
    if MCARPLAY_MARKERS.search(text[:200_000]):
        diagnosis = Diagnosis(kind="runtime_log")
        _scan_runtime_log(text, diagnosis)
        diagnosis.findings.append(
            Finding(
                "info",
                "这是原始运行日志，不含连接时间线和环境信息。",
                "在 mcarplay 首页点「导出脱敏诊断报告」再发送，能得到更准确的结论。",
            )
        )
        diagnosis.excerpt = _excerpt(diagnosis, text)
        return diagnosis
    return Diagnosis(
        kind="unknown",
        findings=[Finding("info", "没有识别出 mcarplay 日志。", "请发送 mcarplay 导出的 mcarplay-log-…-diagnostics.txt 文件。")],
    )


def _diagnose_report(text: str) -> Diagnosis:
    sections = split_sections(text)
    env = parse_environment(sections.get("ENVIRONMENT", ""))
    summary = analyze_report(text)
    diagnosis = Diagnosis(kind="report", attempts=summary["attempts"])
    report_id = re.search(r"^reportId=(\S+)", sections.get("HEADER", ""), re.M)
    for label, value in (
        ("报告", report_id.group(1)[:8] if report_id else ""),
        ("版本", env.get("app.version", "")),
        ("手机", env.get("device", "")),
        ("系统", env.get("system", "").split(" build=")[0]),
        ("CarWith", env.get("carwith.version", "")),
        ("小米 CarLife", env.get("carlife.version", "")),
    ):
        if value:
            diagnosis.facts[label] = value

    _check_attempts(summary["attempts"], diagnosis)
    _check_environment(env, diagnosis)
    _check_exits(sections.get("SYSTEM EXIT HISTORY", ""), diagnosis)
    _check_uncaught(sections.get("CONNECTION TIMELINE", ""), diagnosis)
    _scan_runtime_log(sections.get("RUNTIME LOG", ""), diagnosis, env)
    if summary["truncated"]:
        diagnosis.findings.append(Finding("info", "报告中较早的日志已被截断。", "长按日志导出按钮可导出更大的历史。"))
    if not summary["attempts"]:
        diagnosis.findings.append(Finding("info", "报告里没有连接尝试记录。", "请先复现问题（发起一次连接），再导出报告。"))
    diagnosis.excerpt = _excerpt(diagnosis, sections.get("RUNTIME LOG", ""), sections.get("CONNECTION TIMELINE", ""))
    return diagnosis


def primary_attempt(attempts: list[dict]) -> dict | None:
    carplay = [a for a in attempts if a.get("track", "carplay") == "carplay"] or attempts
    if not carplay:
        return None
    failed = [a for a in carplay if a.get("status") == "failed" or a.get("code") not in BENIGN_CODES]
    return failed[-1] if failed else carplay[-1]


def _check_attempts(attempts: list[dict], diagnosis: Diagnosis) -> None:
    attempt = primary_attempt(attempts)
    if attempt is None:
        return
    code = attempt.get("code", "NONE")
    stage = STAGE_LABELS.get(attempt.get("stage", ""), attempt.get("stage", "unknown"))
    if code in CODE_ADVICE:
        title, advice = CODE_ADVICE[code]
        severity = "info" if code in BENIGN_CODES else "error"
        diagnosis.findings.append(Finding(severity, f"[{code}] {title}", advice, f"停在「{stage}」阶段"))
    elif code != "NONE":
        diagnosis.findings.append(Finding("error", f"连接以未知代码 {code} 结束（阶段「{stage}」）。", "这是新版本代码，请发给维护者。"))
    elif attempt.get("status") not in ("stopped", "streaming"):
        diagnosis.findings.append(
            Finding(
                "warn",
                f"最近一次连接停在「{stage}」阶段，没有结束记录。",
                "可能仍在进行、被系统结束或记录被截断；不足以认定崩溃。请说明当时界面停在哪一步。",
            )
        )

    source, sent = attempt.get("sourceVideoFrames", 0), attempt.get("videoFrames", 0)
    if attempt.get("stage") in ("CARWITH", "STREAMING") and not source:
        diagnosis.findings.append(
            Finding("warn", "没有收到 CarWith 的原生视频帧。", "问题在手机侧的 CarWith 出流，先用「CarWith 自检」确认能出画面。")
        )
    elif source and not sent:
        diagnosis.findings.append(
            Finding("warn", f"收到 CarWith 视频 {source} 帧，但没有成功发送到接收端。", "问题在手机到接收端的传输，检查热点连接和接收端状态。")
        )
    elif sent:
        diagnosis.findings.append(
            Finding("info", f"已向接收端发送 {sent} 帧视频。", "发送成功不代表接收端已显示；若车机黑屏，多半是接收端解码或显示问题。")
        )
    if attempt.get("nativeAudioChunks", 0) and not attempt.get("audioPacketsSent", 0):
        diagnosis.findings.append(Finding("warn", "收到了手机原生音频，但没有发出音频包。", "检查音频是否被协商成功（AUDIO_UNAVAILABLE）。"))


def _check_environment(env: dict[str, str], diagnosis: Diagnosis) -> None:
    sdk_match = re.search(r"SDK=(\d+)", env.get("system", ""))
    sdk = int(sdk_match.group(1)) if sdk_match else None
    denied = [
        label
        for name, label in PERMISSION_HINTS.items()
        if env.get(f"permission.{name}") == "false" and (sdk is None or name not in PERMISSION_SDKS or sdk in PERMISSION_SDKS[name])
    ]
    if denied:
        diagnosis.findings.append(
            Finding("warn", "导出时以下权限未授予：" + "、".join(denied) + "。", "在系统设置 → 应用 → mcarplay → 权限中允许后重试。")
        )
    if env.get("bluetooth.enabled") == "false":
        diagnosis.findings.append(Finding("warn", "导出时蓝牙是关闭的。", "无线 CarPlay 先走蓝牙，需开启蓝牙。"))
    if env.get("wifi.enabled") == "false":
        diagnosis.findings.append(Finding("warn", "导出时 WLAN 是关闭的。", "需要开启 WLAN 才能加入车机热点。"))
    if env.get("carwith.version", "").startswith("unavailable"):
        diagnosis.findings.append(Finding("error", "手机上没有检测到 CarWith。", "安装或更新小米 CarWith 后重试。"))
    if env.get("carlife.version", "").startswith("unavailable"):
        diagnosis.findings.append(Finding("warn", "手机上没有检测到小米 CarLife 组件。", "CarWith 出流依赖该组件，请在应用商店安装或更新。"))
    if "enabled=false" in env.get("carwith.entry", ""):
        diagnosis.findings.append(Finding("error", "CarWith 的连接入口被禁用。", "检查 CarWith 是否被冻结或被系统禁用。"))


def _check_exits(section: str, diagnosis: Diagnosis) -> None:
    reasons = Counter(re.findall(r"reason=\d+\((\w+)\)", section))
    for reason, count in reasons.items():
        if reason in EXIT_REASON_HINTS:
            severity = "warn" if reason in ("LOW_MEMORY", "SIGNALED") else "error"
            diagnosis.findings.append(
                Finding(severity, f"{EXIT_REASON_HINTS[reason]}（{count} 次）", "需对照故障时间确认是否是这一次；旧记录不一定相关。")
            )


def _check_uncaught(section: str, diagnosis: Diagnosis) -> None:
    for line in section.splitlines():
        if '"UNCAUGHT_EXCEPTION"' not in line:
            continue
        try:
            event = json.loads(line)
        except ValueError:
            continue
        stack = str(event.get("stack", ""))
        head = stack.strip().splitlines()[0][:200] if stack.strip() else "（无堆栈）"
        diagnosis.findings.append(
            Finding("error", f"线程 {event.get('thread', '?')} 未捕获异常。", "把完整报告发给维护者。", head)
        )


def _scan_runtime_log(log: str, diagnosis: Diagnosis, env: dict[str, str] | None = None) -> None:
    lines = log.splitlines()
    for pattern in LOG_PATTERNS:
        hits = [line for line in lines if pattern.regex.search(line) and not (pattern.exclude and pattern.exclude.search(line))]
        if len(hits) < pattern.min_count:
            continue
        advice = pattern.advice
        if pattern.key == "enonet" and env and env.get("network.bound", "").startswith("unknown"):
            advice += "（导出时 network.bound=unknown，支持这一判断。）"
        title = pattern.title if len(hits) == 1 else f"{pattern.title}（{len(hits)} 次）"
        diagnosis.findings.append(Finding(pattern.severity, title, advice, _clean(hits[-1])))

    compat = [line for line in lines if "COMPAT: family=" in line]
    if compat:
        diagnosis.facts["接收端策略"] = _clean(compat[-1]).split("COMPAT: ", 1)[-1][:120]

    exceptions = Counter()
    sample = {}
    for line in lines:
        match = EXCEPTION_LINE.search(line)
        if match:
            exceptions[match.group(1)] += 1
            sample.setdefault(match.group(1), _clean(line))
    for name, count in exceptions.most_common(3):
        if name.endswith("OutOfMemoryError"):
            continue
        diagnosis.findings.append(
            Finding("info", f"日志中出现 {name.rsplit('.', 1)[-1]}（{count} 次）。", "", sample[name])
        )


def _clean(line: str) -> str:
    return TIMESTAMP_PREFIX.sub("", line.strip())[:240]


def _excerpt(diagnosis: Diagnosis, log: str, timeline: str = "", budget: int = 14_000) -> str:
    """Compact, already-redacted context for the optional AI pass."""
    parts = ["[facts] " + json.dumps(diagnosis.facts, ensure_ascii=False)]
    for attempt in diagnosis.attempts[-3:]:
        parts.append("[attempt] " + json.dumps({k: v for k, v in attempt.items() if k != "evidence"}, ensure_ascii=False))
    for finding in diagnosis.sorted_findings():
        parts.append(f"[rule:{finding.severity}] {finding.title} {finding.evidence}".strip())
    events = [line for line in timeline.splitlines() if line.startswith("{")][-30:]
    if events:
        parts.append("[timeline tail]")
        parts.extend(line[:400] for line in events)
    head = "\n".join(parts)

    seen = set()
    interesting = []
    for line in log.splitlines():
        cleaned = _clean(line)
        key = re.sub(r"\d+", "#", cleaned)
        if INTERESTING_LINE.search(cleaned) and key not in seen:
            seen.add(key)
            interesting.append(cleaned)
    tail = [_clean(line) for line in log.splitlines()[-60:]]
    remaining = max(budget - len(head), 2_000)
    body = _fit(interesting, remaining * 2 // 3) + ["[log tail]"] + _fit(tail, remaining // 3)
    return head + "\n[log lines]\n" + "\n".join(body)


def _fit(lines: list[str], budget: int) -> list[str]:
    """Keep the most recent lines that fit in the budget, preserving order."""
    kept, used = [], 0
    for line in reversed(lines):
        used += len(line) + 1
        if used > budget:
            break
        kept.append(line)
    return kept[::-1]


def format_reply(diagnosis: Diagnosis, ai_text: str = "") -> str:
    out = ["mcarplay 日志诊断"]
    if diagnosis.facts:
        out.append(" · ".join(f"{k} {v}" for k, v in diagnosis.facts.items()))
    attempt = primary_attempt(diagnosis.attempts)
    if attempt:
        stage = STAGE_LABELS.get(attempt.get("stage", ""), attempt.get("stage", "?"))
        elapsed = attempt.get("elapsedMs")
        seconds = f"，耗时 {elapsed / 1000:.1f}s" if isinstance(elapsed, (int, float)) else ""
        out.append(f"重点连接：阶段「{stage}」，状态 {attempt.get('status')}，代码 {attempt.get('code')}{seconds}")
        out.append(
            f"视频 源帧 {attempt.get('sourceVideoFrames', 0)} / 已发 {attempt.get('videoFrames', 0)}；"
            f"音频 源 {attempt.get('nativeAudioChunks', 0)} / 已发 {attempt.get('audioPacketsSent', 0)}"
        )
    out.append("")
    for finding in diagnosis.sorted_findings()[:8]:
        out.append(f"{SEVERITY_MARK.get(finding.severity, '·')} {finding.title}")
        if finding.evidence:
            out.append(f"   依据：{finding.evidence}")
        if finding.advice:
            out.append(f"   建议：{finding.advice}")
    if ai_text:
        out += ["", "AI 补充分析（仅供参考）：", ai_text.strip()]
    out += ["", "手机日志不能还原接收端内部状态；请同时说明车机型号、固件以及是否有画面/声音/触控。"]
    return "\n".join(out)
