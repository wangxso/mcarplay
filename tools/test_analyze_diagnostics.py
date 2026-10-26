import json
import unittest

from analyze_diagnostics import analyze_report


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


if __name__ == "__main__":
    unittest.main()
