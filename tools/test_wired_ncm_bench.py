import subprocess
import unittest
from unittest.mock import patch

import wired_ncm_bench as bench


class WiredNcmBenchTest(unittest.TestCase):
    def test_launch_arguments_keep_the_empty_app_pid_before_launch_flag(self):
        arguments = bench.launch_arguments(
            300,
            "token",
            "02:11:22:33:44:55",
            "02:aa:bb:cc:dd:ee",
            None,
            "iphone-ui",
        )
        self.assertEqual(
            [
                "setsid",
                "sh",
                bench.REMOTE_SCRIPT,
                "up",
                "300",
                "token",
                "02:11:22:33:44:55",
                "02:aa:bb:cc:dd:ee",
                "",
                "",
                "",
                "iphone-ui",
            ],
            arguments,
        )

    def test_launch_arguments_enable_the_media_bench_only_for_an_explicit_peer(self):
        arguments = bench.launch_arguments(
            600,
            "token",
            "02:11:22:33:44:55",
            "02:aa:bb:cc:dd:ee",
            "fe80::1",
            "generic",
        )
        self.assertEqual("fe80::1", arguments[8])
        self.assertEqual("", arguments[9])
        self.assertEqual("1", arguments[10])
        self.assertEqual("generic", arguments[11])

    def test_identifies_usb_peer_by_exact_mac_with_its_own_ipv6_scope(self):
        sample = """en0: flags=8863<UP,BROADCAST,RUNNING> mtu 1500
\tether 02:11:22:33:44:55
\tinet6 fe80::aaaa%en0 prefixlen 64 scopeid 0x5
en12: flags=8863<UP,BROADCAST,RUNNING> mtu 1500
\tether 02:aa:bb:cc:dd:ee
\tinet6 fe80::1234:5678%en12 prefixlen 64 scopeid 0x14
"""
        with patch.object(subprocess, "check_output", return_value=sample):
            self.assertEqual(
                {"interface": "en12", "address": "fe80::1234:5678"},
                bench.mac_interface("02:AA:BB:CC:DD:EE"),
            )
            self.assertIsNone(bench.mac_interface("02:aa:bb:cc:dd:ef"))

    def test_new_interface_without_address_does_not_fall_back_to_wifi(self):
        sample = """en0: flags=8863<UP> mtu 1500
\tether 02:11:22:33:44:55
\tinet6 fe80::aaaa%en0 prefixlen 64
en12: flags=8863<UP> mtu 1500
\tether 02:aa:bb:cc:dd:ee
"""
        with patch.object(subprocess, "check_output", return_value=sample):
            self.assertEqual(
                {"interface": "en12", "address": None},
                bench.mac_interface("02:aa:bb:cc:dd:ee"),
            )


if __name__ == "__main__":
    unittest.main()
