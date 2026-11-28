import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from analyze_diagnostics import align_attempts_to_end, analyze_report


def report(*events):
    return ("mcarplay support report / schema=1\n=== CONNECTION TIMELINE / events ===\n"
            + "\n".join(json.dumps(event) for event in events)
            + "\n=== RUNTIME LOG / log ===\n")


class AnalyzeDiagnosticsTest(unittest.TestCase):
    def test_exception_record_keeps_last_known_stage_and_media_counters(self):
        data = analyze_report(report(
            {"process": "p1", "attempt": "a1", "event": "WARNING", "stage": "STREAMING", "videoFrames": 5},
            {"process": "p1", "attempt": "a1", "event": "UNCAUGHT_EXCEPTION", "stack": "stack trace"},
        ))
        self.assertEqual("STREAMING", data["attempts"][0]["stage"])
        self.assertEqual(5, data["attempts"][0]["videoFrames"])
        self.assertIn("uncaught thread exception", data["attempts"][0]["evidence"][-1])

    def test_retains_failed_stage_and_reason(self):
        data = analyze_report(report({
            "process": "p1", "attempt": "a1", "event": "END", "stage": "PAIRING",
            "status": "failed", "code": "PAIRING_FAILED", "elapsedMs": 9000,
        }))
        self.assertEqual("PAIRING_FAILED", data["attempts"][0]["code"])
        self.assertEqual(9000, data["attempts"][0]["elapsedMs"])

    def test_collects_first_milestones_in_arrival_order(self):
        data = analyze_report(report(
            {"process": "p1", "attempt": "a1", "event": "MILESTONE", "milestone": "BT_LINK", "elapsedMs": 400},
            {"process": "p1", "attempt": "a1", "event": "MILESTONE", "milestone": "IAP2_AUTHENTICATED", "elapsedMs": 900},
            {"process": "p1", "attempt": "a1", "event": "STAGE", "stage": "PAIRING", "elapsedMs": 4000},
            {"process": "p1", "attempt": "a1", "event": "MILESTONE", "milestone": "AIRPLAY_CONNECTED", "elapsedMs": 4100},
            {"process": "p1", "attempt": "a1", "event": "MILESTONE", "milestone": "BT_LINK", "elapsedMs": 9000},
            {"process": "p1", "attempt": "a1", "event": "END", "stage": "PAIRING", "status": "failed",
             "code": "PAIRING_FAILED", "elapsedMs": 30000},
        ))
        attempt = data["attempts"][0]
        self.assertEqual({"BT_LINK": 400, "IAP2_AUTHENTICATED": 900, "AIRPLAY_CONNECTED": 4100}, attempt["milestones"])
        self.assertEqual(["BT_LINK", "IAP2_AUTHENTICATED", "AIRPLAY_CONNECTED"], list(attempt["milestones"]))
        self.assertEqual("AIRPLAY_CONNECTED", attempt["lastMilestone"])
        self.assertTrue(any("pair-verify did not" in line for line in attempt["evidence"]))

    def test_reports_without_milestones_stay_compatible(self):
        data = analyze_report(report({"process": "p1", "attempt": "a1", "event": "STAGE", "stage": "WIFI"}))
        self.assertEqual({}, data["attempts"][0]["milestones"])
        self.assertIsNone(data["attempts"][0]["lastMilestone"])

    def test_missing_end_does_not_claim_crash(self):
        data = analyze_report(report({"process": "p1", "attempt": "a1", "event": "STAGE", "stage": "CARWITH"}))
        self.assertIn("not proof of a crash", data["attempts"][0]["evidence"][0])

    def test_distinguishes_native_input_from_transport_output(self):
        data = analyze_report(report({
            "process": "p1", "attempt": "a1", "event": "MEDIA_SAMPLE",
            "sourceVideoFrames": 100, "videoFrames": 0, "audioChunks": 30, "audioPacketsSent": 0,
        }))
        self.assertEqual(3, len(data["attempts"][0]["evidence"]))

    def test_separates_process_restarts_even_if_attempt_ids_repeat(self):
        data = analyze_report(report(
            {"process": "p1", "attempt": "a1", "event": "END", "code": "USER_STOP"},
            {"process": "p2", "attempt": "a1", "event": "BEGIN"},
        ))
        self.assertEqual(2, data["processesRetained"])
        self.assertEqual(2, len(data["attempts"]))
        self.assertIn("not a connection failure", data["attempts"][0]["evidence"][-1])

    def test_ignores_runtime_json_and_reports_malformed_events(self):
        text = report({"process": "p1", "event": "PROCESS_START"})
        text = text.replace("=== RUNTIME", "{broken\n=== RUNTIME")
        text += '{"process":"raw","attempt":"not-diagnostic"}\n[earlier log content truncated]'
        data = analyze_report(text)
        self.assertEqual([], data["attempts"])
        self.assertEqual(1, data["invalidEvents"])
        self.assertTrue(data["truncated"])

    def test_align_attempts_to_end_ranks_tightest_watchdog_anchor_first(self):
        # Simulate three failed runs where handshake duration varies (2s, 5s, 9s),
        # but the receiver disconnects ~10.0s after RECORD every time.
        r1 = report(
            {"process": "p1", "attempt": "a1", "event": "MILESTONE", "milestone": "BT_LINK", "elapsedMs": 300},
            {"process": "p1", "attempt": "a1", "event": "MILESTONE", "milestone": "RECORD", "elapsedMs": 2000},
            {"process": "p1", "attempt": "a1", "event": "END", "status": "failed", "code": "STREAM_ENDED", "elapsedMs": 12002},
            # Benign stop and incomplete attempt must not pollute failure variance.
            {"process": "p1", "attempt": "a2", "event": "MILESTONE", "milestone": "RECORD", "elapsedMs": 1500},
            {"process": "p1", "attempt": "a2", "event": "END", "status": "stopped", "code": "USER_STOP", "elapsedMs": 60000},
            {"process": "p1", "attempt": "a3", "event": "STAGE", "stage": "PAIRING", "elapsedMs": 1000},
        )
        r2 = report(
            {"process": "p2", "attempt": "a1", "event": "MILESTONE", "milestone": "BT_LINK", "elapsedMs": 800},
            {"process": "p2", "attempt": "a1", "event": "MILESTONE", "milestone": "RECORD", "elapsedMs": 5000},
            {"process": "p2", "attempt": "a1", "event": "END", "status": "failed", "code": "STREAM_ENDED", "elapsedMs": 14998},
            # Self-test track is excluded from carplay alignment.
            {"process": "p2", "attempt": "s1", "track": "self_test", "event": "END", "code": "CARWITH_TIMEOUT", "elapsedMs": 30000},
        )
        r3 = report(
            {"process": "p3", "attempt": "a1", "event": "MILESTONE", "milestone": "BT_LINK", "elapsedMs": 1200},
            {"process": "p3", "attempt": "a1", "event": "MILESTONE", "milestone": "RECORD", "elapsedMs": 9000},
            {"process": "p3", "attempt": "a1", "event": "END", "status": "failed", "code": "STREAM_ENDED", "elapsedMs": 19000},
        )
        aligned = align_attempts_to_end([r1, r2, r3])
        self.assertEqual(3, aligned["reportsAnalyzed"])
        self.assertEqual(3, aligned["qualifyingAttempts"])
        self.assertEqual(1, aligned["excludedBenignAttempts"])
        self.assertEqual(1, aligned["incompleteAttempts"])
        best = aligned["bestAnchor"]
        self.assertIsNotNone(best)
        self.assertEqual("MILESTONE:RECORD", best["anchor"])
        self.assertEqual(3, best["count"])
        self.assertEqual(4, best["spreadMs"])
        self.assertLess(best["stddevMs"], 5.0)
        by_name = {entry["anchor"]: entry for entry in aligned["anchors"]}
        self.assertGreater(by_name["BEGIN"]["stddevMs"], 2000.0)
        self.assertGreater(by_name["MILESTONE:BT_LINK"]["stddevMs"], 2000.0)

    def test_align_attempts_to_end_requires_two_samples_for_best_anchor(self):
        single = align_attempts_to_end([
            report(
                {"process": "p1", "attempt": "a1", "event": "MILESTONE", "milestone": "BT_LINK", "elapsedMs": 500},
                {"process": "p1", "attempt": "a1", "event": "END", "status": "failed", "code": "PAIRING_FAILED", "elapsedMs": 9500},
            )
        ])
        self.assertEqual(1, single["qualifyingAttempts"])
        self.assertIsNone(single["bestAnchor"])
        self.assertIsNone(single["anchors"][0]["stddevMs"])

    def test_cli_preserves_single_report_schema_and_supports_align_end(self):
        script = Path(__file__).resolve().with_name("analyze_diagnostics.py")
        with tempfile.TemporaryDirectory() as tmp:
            p1 = Path(tmp) / "r1.txt"
            p2 = Path(tmp) / "r2.txt"
            p1.write_text(report(
                {"process": "p1", "attempt": "a1", "event": "STAGE", "stage": "SETUP", "elapsedMs": 2000},
                {"process": "p1", "attempt": "a1", "event": "END", "status": "failed", "code": "SETUP_FAILED", "elapsedMs": 8000},
            ), encoding="utf-8")
            p2.write_text(report(
                {"process": "p2", "attempt": "a1", "event": "STAGE", "stage": "SETUP", "elapsedMs": 4500},
                {"process": "p2", "attempt": "a1", "event": "END", "status": "failed", "code": "SETUP_FAILED", "elapsedMs": 10500},
            ), encoding="utf-8")

            single_out = json.loads(subprocess.check_output([sys.executable, str(script), str(p1)], text=True))
            self.assertIn("attempts", single_out)
            self.assertNotIn("endAlignment", single_out)

            multi_out = json.loads(subprocess.check_output([sys.executable, str(script), str(p1), str(p2)], text=True))
            self.assertEqual(["r1.txt", "r2.txt"], [item["file"] for item in multi_out["reports"]])
            self.assertEqual("STAGE:SETUP", multi_out["endAlignment"]["bestAnchor"]["anchor"])
            self.assertEqual(0.0, multi_out["endAlignment"]["bestAnchor"]["stddevMs"])


if __name__ == "__main__":
    unittest.main()
