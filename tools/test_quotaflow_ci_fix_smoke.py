import unittest

from tools.quotaflow_ci_fix_smoke import classify_runner_failure


class QuotaFlowCiFixSmokeTest(unittest.TestCase):
    def test_network_failure_is_infrastructure(self) -> None:
        self.assertEqual("infrastructure", classify_runner_failure("network"))


if __name__ == "__main__":
    unittest.main()
