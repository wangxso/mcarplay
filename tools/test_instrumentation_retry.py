import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / ".github" / "scripts" / "run-rgp07-rgp08.sh"


class InstrumentationRetryTest(unittest.TestCase):
    def test_script_is_valid_bash(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)

    def test_crash_only_retry_is_shared_by_rgp07_and_rgp08(self):
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("instrumentation_crashed_without_result()", script)
        self.assertIn(
            "grep -q \"shortMsg=Process crashed\" \"$output\"",
            script,
        )
        self.assertIn(
            '! grep -qE "^(OK \\(|FAILURES)" "$output"',
            script,
        )
        self.assertIn(
            "[[ \"$attempt\" -eq 1 ]] && instrumentation_crashed_without_result \"$output\"",
            script,
        )
        self.assertIn(
            "[[ \"$rgp08_attempt\" -eq 1 ]] && "
            "instrumentation_crashed_without_result \"$rgp08_attempt_output\"",
            script,
        )
        self.assertIn(
            "RGP08: instrumentation process crashed before reporting a result; retrying once",
            script,
        )


if __name__ == "__main__":
    unittest.main()
