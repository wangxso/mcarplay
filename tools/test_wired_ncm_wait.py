"""Exercise the APK's fixed root waiter without USB, root, or device identifiers."""
import pathlib
import shlex
import subprocess
import tempfile
import unittest


class WiredNcmWaitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = pathlib.Path(self.temp.name)
        source = (pathlib.Path(__file__).resolve().parents[1] /
                  "app/src/remote/res/raw/wired_ncm_bench.sh").read_text()
        source = source.replace("S=/data/local/tmp/mcarplay-ncm-bench-state",
                                "S=" + shlex.quote(str(self.state)))
        self.script = self.state / "wait.sh"
        self.script.write_text(source)
        (self.state / "owner").write_text("mcarplay-ncm-bench-v1\n")

    def run_wait(self):
        return subprocess.run(["sh", str(self.script), "wait-start-session"],
                              capture_output=True, text=True, timeout=3)

    def test_returns_complete_start_file(self):
        payload = "port=7000\naddress=fe80::1\n"
        (self.state / "carplay-start").write_text(payload)
        result = self.run_wait()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(payload, result.stdout)

    def test_missing_or_foreign_owner_stops_waiting(self):
        for owner in (None, "another-bench\n"):
            path = self.state / "owner"
            if owner is None:
                path.unlink()
            else:
                path.write_text(owner)
            self.assertEqual(3, self.run_wait().returncode)

    def test_wait_is_bounded_when_no_start_arrives(self):
        # Shorten only the polling budget; exercise the production loop and exit code.
        source = self.script.read_text().replace('[ "$i" -lt 450 ]', '[ "$i" -lt 1 ]')
        self.script.write_text(source)
        self.assertEqual(3, self.run_wait().returncode)


if __name__ == "__main__":
    unittest.main()
