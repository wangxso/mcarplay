import subprocess
import sys
from pathlib import Path
import tempfile
import unittest

from release_notes import extract_release_notes


class ReleaseNotesTest(unittest.TestCase):
    def test_selects_exact_tag_and_preserves_bilingual_subheadings(self):
        changelog = (
            "# Changelog\n\n## [v0.1.0-test.4] - 2026-10-26\n\n"
            "### English\n- Added badges.\n\n### 中文\n- 新增徽章。\n\n"
            "## [v0.1.0-test.3] - 2026-10-26\n\nOlder notes.\n"
        )
        notes = extract_release_notes(changelog, "v0.1.0-test.4")
        self.assertIn("### English\n- Added badges.", notes)
        self.assertIn("### 中文\n- 新增徽章。", notes)
        self.assertNotIn("Older notes", notes)
        self.assertTrue(notes.startswith("# mcarplay v0.1.0-test.4\n"))

    def test_extracts_final_entry_without_trailing_newline(self):
        notes = extract_release_notes("## [v0.1.0]\n\nStable notes.", "v0.1.0")
        self.assertTrue(notes.endswith("Stable notes.\n"))

    def test_does_not_match_tag_prefix(self):
        with self.assertRaises(ValueError):
            extract_release_notes("## [v0.1.0-test.40]\n\nOther release.\n", "v0.1.0-test.4")

    def test_rejects_missing_entry(self):
        with self.assertRaises(ValueError):
            extract_release_notes("# Changelog\n", "v0.1.0-test.4")

    def test_rejects_empty_entry(self):
        with self.assertRaises(ValueError):
            extract_release_notes("## [v0.1.0-test.4]\n \n## [v0.1.0]\nOther.\n", "v0.1.0-test.4")

    def test_rejects_duplicate_entries(self):
        with self.assertRaises(ValueError):
            extract_release_notes("## [v0.1.0]\nOne.\n## [v0.1.0]\nTwo.\n", "v0.1.0")

    def test_rejects_unsupported_tag(self):
        with self.assertRaises(ValueError):
            extract_release_notes("## [latest]\nNotes.\n", "latest")

    def test_cli_does_not_create_output_when_entry_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            changelog = Path(directory) / "CHANGELOG.md"
            output = Path(directory) / "notes.md"
            changelog.write_text("# Changelog\n", encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name("release_notes.py")),
                 "--tag", "v0.1.0", "--changelog", str(changelog), "--output", str(output)],
                capture_output=True, text=True, check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Expected exactly one changelog entry", result.stderr)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
