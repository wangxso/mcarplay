#!/usr/bin/env python3
"""Summarize an explicitly supplied, already-redacted mcarplay support report offline."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def analyze_report(text: str) -> dict:
    attempts: dict[tuple[str, str], list[dict]] = {}
    processes = set()
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

    summaries = []
    for (process, attempt), events in list(attempts.items())[-5:]:
        last = {}
        for event in events:
            last.update(event)
        ended = next((event for event in reversed(events) if event.get("event") == "END"), None)
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
        if any(event.get("event") == "UNCAUGHT_EXCEPTION" for event in events):
            evidence.append("An uncaught thread exception was captured; inspect its stack in the report.")
        summaries.append({
            "process": process,
            "attempt": attempt,
            "stage": last.get("stage", "unknown"),
            "status": (ended or last).get("status", "unknown"),
            "code": (ended or last).get("code", "NONE"),
            "elapsedMs": (ended or last).get("elapsedMs"),
            "sourceVideoFrames": last.get("sourceVideoFrames", 0),
            "videoFrames": last.get("videoFrames", 0),
            "nativeAudioChunks": last.get("audioChunks", 0),
            "audioPacketsSent": last.get("audioPacketsSent", 0),
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    if args.report.stat().st_size > 16_000_000:
        parser.error("Report exceeds the 16MB analysis limit")
    result = analyze_report(args.report.read_text(encoding="utf-8", errors="replace"))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
