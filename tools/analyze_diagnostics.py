#!/usr/bin/env python3
"""Summarize an explicitly supplied, already-redacted mcarplay support report offline."""

from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path

BENIGN_END_CODES = frozenset({"NONE", "USER_STOP", "SUPERSEDED", "ENDPOINT_PENDING"})


def _parse_attempts(text: str) -> tuple[dict[tuple[str, str], list[dict]], set[str], int]:
    attempts: dict[tuple[str, str], list[dict]] = {}
    processes: set[str] = set()
    invalid_events = 0
    in_timeline = False
    for line in text.splitlines():
        if line.startswith("=== CONNECTION TIMELINE"):
            in_timeline = True
            continue
        if line.startswith("=== "):
            in_timeline = False
        if not in_timeline or not line.startswith("{"):
            continue
        try:
            event = json.loads(line)
            if not isinstance(event, dict) or not isinstance(event.get("process"), str):
                raise ValueError("Missing process identity")
            processes.add(event["process"])
            if event.get("event") == "PROCESS_START":
                continue
            if not isinstance(event.get("attempt"), str):
                raise ValueError("Missing attempt identity")
            attempts.setdefault((event["process"], event["attempt"]), []).append(event)
        except (ValueError, RecursionError):
            invalid_events += 1
    return attempts, processes, invalid_events


def _is_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def analyze_report(text: str) -> dict:
    attempts, processes, invalid_events = _parse_attempts(text)
    summaries = []
    for (process, attempt), events in list(attempts.items())[-5:]:
        last = {}
        for event in events:
            last.update(event)
        ended = next((event for event in reversed(events) if event.get("event") == "END"), None)
        milestones: dict[str, object] = {}
        for event in events:
            name = event.get("milestone")
            if isinstance(name, str) and name not in milestones:
                milestones[name] = event.get("elapsedMs")
        evidence = []
        if ended is None:
            evidence.append("No END retained: may still be active or records were interrupted; not proof of a crash.")
        if last.get("sourceVideoFrames", 0) and not last.get("videoFrames", 0):
            evidence.append("Native video input recorded, but no successful video transport write recorded.")
        if last.get("audioChunks", 0) and not last.get("audioPacketsSent", 0):
            evidence.append("Native PCM input recorded, but no audio packet send recorded.")
        if last.get("videoFrames", 0):
            evidence.append("Video transport writes recorded; receiver display/decode is not established by these counters.")
        if ended and ended.get("code") == "USER_STOP":
            evidence.append("User-requested stop, not a connection failure.")
        if "IAP2_AUTHENTICATED" in milestones and "AIRPLAY_CONNECTED" in milestones and "PAIR_VERIFY" not in milestones:
            evidence.append("iAP2 authentication completed, but AirPlay pair-verify did not; the layers succeed independently.")
        if any(event.get("event") == "UNCAUGHT_EXCEPTION" for event in events):
            evidence.append("An uncaught thread exception was captured; inspect its stack in the report.")
        summaries.append({
            "process": process,
            "attempt": attempt,
            "track": last.get("track", "carplay"),
            "stage": last.get("stage", "unknown"),
            "status": (ended or last).get("status", "unknown"),
            "code": (ended or last).get("code", "NONE"),
            "elapsedMs": (ended or last).get("elapsedMs"),
            "sourceVideoFrames": last.get("sourceVideoFrames", 0),
            "videoFrames": last.get("videoFrames", 0),
            "nativeAudioChunks": last.get("audioChunks", 0),
            "audioPacketsSent": last.get("audioPacketsSent", 0),
            "milestones": milestones,
            "lastMilestone": next(reversed(milestones), None),
            "evidence": evidence,
        })
    return {
        "reportFormatRecognized": text.startswith("mcarplay support report"),
        "processesRetained": len(processes),
        "invalidEvents": invalid_events,
        "truncated": "[earlier log content truncated]" in text,
        "attempts": summaries,
        "limitations": "Offline evidence summary only. Correlate timestamps, stacks and receiver observations before assigning a root cause.",
    }


def align_attempts_to_end(
    texts: list[str],
    *,
    track: str = "carplay",
    include_benign: bool = False,
) -> dict:
    """Measure T_end - T_anchor across ended attempts and rank anchors by lowest variance."""
    deltas: dict[str, list[float]] = {}
    qualifying = 0
    excluded_benign = 0
    incomplete = 0

    for text in texts:
        attempts, _, _ = _parse_attempts(text)
        for events in attempts.values():
            last: dict = {}
            for event in events:
                last.update(event)
            if last.get("track", "carplay") != track:
                continue
            ended = next((event for event in reversed(events) if event.get("event") == "END"), None)
            if ended is None or not _is_number(ended.get("elapsedMs")):
                incomplete += 1
                continue
            code = ended.get("code", last.get("code", "NONE"))
            if not include_benign and code in BENIGN_END_CODES:
                excluded_benign += 1
                continue

            end_ms = float(ended["elapsedMs"])
            qualifying += 1
            anchors: dict[str, float] = {"BEGIN": 0.0}
            for event in events:
                milestone = event.get("milestone")
                if isinstance(milestone, str) and _is_number(event.get("elapsedMs")):
                    anchors.setdefault(f"MILESTONE:{milestone}", float(event["elapsedMs"]))
                if event.get("event") == "STAGE" and isinstance(event.get("stage"), str) and _is_number(event.get("elapsedMs")):
                    anchors.setdefault(f"STAGE:{event['stage']}", float(event["elapsedMs"]))
            for anchor, anchor_ms in anchors.items():
                if end_ms >= anchor_ms:
                    deltas.setdefault(anchor, []).append(end_ms - anchor_ms)

    summaries = []
    for anchor, samples in deltas.items():
        count = len(samples)
        min_ms = round(min(samples), 1)
        max_ms = round(max(samples), 1)
        spread_ms = round(max_ms - min_ms, 1)
        mean_ms = round(statistics.mean(samples), 1)
        stddev_ms = round(statistics.pstdev(samples), 1) if count >= 2 else None
        summaries.append({
            "anchor": anchor,
            "count": count,
            "minMs": int(min_ms) if min_ms.is_integer() else min_ms,
            "maxMs": int(max_ms) if max_ms.is_integer() else max_ms,
            "spreadMs": int(spread_ms) if spread_ms.is_integer() else spread_ms,
            "meanMs": mean_ms,
            "stddevMs": stddev_ms,
        })

    summaries.sort(
        key=lambda item: (
            0 if item["count"] >= 2 else 1,
            item["stddevMs"] if item["stddevMs"] is not None else float("inf"),
            item["spreadMs"],
            -item["count"],
            item["anchor"],
        )
    )
    best = next((item for item in summaries if item["count"] >= 2), None)
    return {
        "track": track,
        "reportsAnalyzed": len(texts),
        "qualifyingAttempts": qualifying,
        "excludedBenignAttempts": excluded_benign,
        "incompleteAttempts": incomplete,
        "bestAnchor": best,
        "anchors": summaries,
        "limitations": (
            "Statistical alignment only (T_end - T_anchor across ended attempts). "
            "Low stddev suggests a candidate timer anchored near that step, not causal proof."
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+", type=Path, metavar="report")
    parser.add_argument(
        "--align-end",
        action="store_true",
        help="Align ended attempts backward from END and rank milestones/stages by lowest variance.",
    )
    parser.add_argument(
        "--include-benign",
        action="store_true",
        help="Include USER_STOP/SUPERSEDED/NONE/ENDPOINT_PENDING attempts in --align-end.",
    )
    args = parser.parse_args()
    texts: list[str] = []
    for path in args.reports:
        if path.stat().st_size > 16_000_000:
            parser.error(f"Report exceeds the 16MB analysis limit: {path.name}")
        texts.append(path.read_text(encoding="utf-8", errors="replace"))
    if len(texts) == 1 and not args.align_end:
        result = analyze_report(texts[0])
    else:
        result = {
            "reports": [
                {"file": path.name, **analyze_report(text)}
                for path, text in zip(args.reports, texts)
            ],
            "endAlignment": align_attempts_to_end(texts, include_benign=args.include_benign),
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
