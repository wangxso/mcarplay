import unittest

from ao_review_smoke_fixture import blocking_findings


class ReviewSmokeFixtureTest(unittest.TestCase):
    def test_blocking_and_optional_findings(self):
        findings = [{"severity": "P0"}, {"severity": "P1"}, {"severity": "P3"}]
        self.assertEqual(findings[:2], blocking_findings(findings))

    def test_p2_findings_are_blocking(self):
        findings = [{"severity": "P2"}]
        self.assertEqual(findings, blocking_findings(findings))


if __name__ == "__main__":
    unittest.main()
