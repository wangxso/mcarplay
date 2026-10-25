"""Local checks for CarLife probe lifecycle and media gate."""

import unittest
import io
import struct
from unittest.mock import Mock, call, patch

import carlife_probe as protocol
from carlife_probe import Probe, VideoStats, listening_ports, occupied_ports, verify_carlife_auth


class CarLifeProbeTest(unittest.TestCase):
    def test_statistics_contains_required_connection_counters_and_time(self) -> None:
        payload = protocol.hu_statistics_payload()
        self.assertEqual(1, protocol.first_int_field(payload, 5))
        self.assertEqual(0, protocol.first_int_field(payload, 6))
        self.assertEqual(0, protocol.first_int_field(payload, 7))

    def test_listening_ports_uses_only_local_listeners(self) -> None:
        status = b"""State Recv-Q Send-Q Local Address:Port Peer Address:Port
LISTEN 0 50 127.0.0.1:7240 0.0.0.0:*
ESTAB 0 0 127.0.0.1:8240 127.0.0.1:12345
LISTEN 0 50 [::ffff:127.0.0.1]:9240 0.0.0.0:*
"""
        self.assertEqual({7240, 9240}, listening_ports(status))

    def test_attach_without_listeners_never_launches_or_forwards(self) -> None:
        probe = Probe("test-device", video=True, full=True, save_h264=None, tap=None, attach=True)
        with patch("carlife_probe.adb", side_effect=[b"2026-10-25T10:00:00.000\n", b"State Recv-Q Send-Q\n"]) as adb_mock:
            with self.assertRaisesRegex(RuntimeError, "listeners absent"):
                probe.run(35)

        self.assertEqual(
            [
                call("test-device", "shell", "date", "+%Y-%m-%dT%H:%M:%S.000"),
                call("test-device", "shell", "ss", "-ant"),
            ],
            adb_mock.call_args_list,
        )

    def test_attach_refuses_existing_receiver_before_creating_forwards(self) -> None:
        status = "\n".join(f"LISTEN 0 50 *:{port} *:*" for port in protocol.PORTS).encode()
        status += b"\nESTAB 0 0 [::ffff:192.168.49.117]:8240 [::ffff:192.168.49.1]:12345\n"
        self.assertEqual({8240}, occupied_ports(status))
        probe = self.probe()
        with patch("carlife_probe.adb", side_effect=[b"2026-10-25T20:00:00.000\n", status]) as adb_mock:
            with self.assertRaisesRegex(RuntimeError, "already occupied"):
                probe.run(35)
        self.assertEqual(2, adb_mock.call_count)

    def test_version2_digest_binds_nonce_and_device_without_legacy_fallback(self) -> None:
        challenge = "2.0;0123456789abcdef0123456789abcdef"
        proof = "a16c98b37110fe95360bb686025d6fec"
        info = ("Xiaomi", "2509FPN0BC", "29")
        self.assertTrue(verify_carlife_auth(proof, challenge, info))
        self.assertFalse(verify_carlife_auth(proof, challenge))
        self.assertFalse(verify_carlife_auth(proof, "2.0;different_nonce", info))
        self.assertFalse(verify_carlife_auth(proof, challenge, ("Xiaomi", "other", "29")))
        self.assertFalse(verify_carlife_auth(proof.upper(), challenge, info))
        self.assertFalse(verify_carlife_auth(proof, "CarLife_1.0.0", info))

    def test_early_feature_and_admission_wait_for_valid_phone_proof(self) -> None:
        probe = self.probe()
        probe.auth_challenge = "2.0;0123456789abcdef0123456789abcdef"
        probe.send_cmd = Mock()
        probe.send_control = Mock()
        self.receive(probe, protocol.RX_MD_PROTOCOL_MATCH)
        self.receive(probe, protocol.RX_MD_AUTHEN_RESULT, protocol.pb_int(1, 1))
        self.receive(probe, protocol.RX_MD_FEATURE_REQUEST)
        self.assertFalse(probe.feature_response_sent)
        self.receive(probe, protocol.RX_MD_INFO, self.info_payload())
        probe.send_cmd.assert_any_call(protocol.CMD_HU_AUTHEN_REQUEST, protocol.pb_string(1, probe.auth_challenge))
        self.assertFalse(probe.auth_verified)
        self.receive(probe, protocol.RX_MD_AUTHEN_RESPONSE, protocol.pb_string(1, "a16c98b37110fe95360bb686025d6fec"))
        self.assertTrue(probe.auth_verified)
        self.assertTrue(probe.feature_response_sent)
        probe.send_cmd.assert_any_call(protocol.CMD_HU_AUTHEN_RESULT, protocol.pb_int(1, 1))

    def test_rejected_admission_and_tampered_proof_cannot_emit_success(self) -> None:
        probe = self.probe()
        probe.protocol_matched = True
        probe.send_cmd = Mock()
        with self.assertRaisesRegex(ValueError, "admission rejected"):
            self.receive(probe, protocol.RX_MD_AUTHEN_RESULT, protocol.pb_int(1, 0))
        probe.send_cmd.assert_not_called()

        probe.md_info_received = True
        probe.device_info = ("Xiaomi", "2509FPN0BC", "29")
        self.receive(probe, protocol.RX_MD_AUTHEN_RESPONSE, protocol.pb_string(1, "0" * 32))
        self.assertTrue(probe.stop.is_set())
        self.assertFalse(probe.auth_verified)
        probe.send_cmd.assert_not_called()

    def test_encrypted_command_rejects_invalid_padding(self) -> None:
        probe = self.probe()
        probe.aes_key = bytes(range(16))
        probe.send_cmd = Mock()
        with self.assertRaises(ValueError):
            self.receive(probe, protocol.RX_VIDEO_ENCODER_INIT, b"invalid")
        probe.send_cmd.assert_not_called()

    def test_late_encrypted_admission_is_required_before_video_init(self) -> None:
        probe = self.probe()
        probe.protocol_matched = True
        probe.auth_verified = True
        probe.aes_key = bytes(range(16))
        probe.send_cmd = Mock()
        self.receive(probe, protocol.RX_MD_ENCRYPT_READY)
        self.assertTrue(probe.encryption_ready)
        self.assertFalse(probe.video_init_sent)
        probe.send_cmd.assert_not_called()
        self.receive(probe, protocol.RX_MD_AUTHEN_RESULT, protocol.aes_encrypt(probe.aes_key, protocol.pb_int(1, 1)))
        self.assertTrue(probe.video_init_sent)
        probe.send_cmd.assert_any_call(protocol.CMD_MD_AUTH_RESULT_ACK)
        probe.send_cmd.assert_any_call(
            protocol.CMD_VIDEO_ENCODER_INIT,
            protocol.pb_int(1, 1280) + protocol.pb_int(2, 720) + protocol.pb_int(3, 30),
        )

    @staticmethod
    def probe() -> Probe:
        return Probe("test-device", video=True, full=True, save_h264=None, tap=None, attach=True)

    @staticmethod
    def info_payload() -> bytes:
        return protocol.pb_string(4, "Xiaomi") + protocol.pb_string(14, "2509FPN0BC") + protocol.pb_string(20, "29")

    @staticmethod
    def receive(probe: Probe, kind: int, body: bytes = b"") -> None:
        stream = io.BytesIO(struct.pack(">HHI", len(body), 0, kind) + body)
        sock = Mock()
        sock.recv.side_effect = stream.read
        with patch("builtins.print"):
            probe.read_command(sock, 7240)

    def test_video_gate_requires_parameter_sets_frames_and_thirty_seconds(self) -> None:
        stats = VideoStats()
        stats.add(100, b"\x00\x00\x00\x01\x67\x01\x00\x00\x00\x01\x68\x01\x00\x00\x00\x01\x65\x01")
        self.assertFalse(stats.has_video_candidate())
        for timestamp in range(101, 130):
            stats.add(timestamp, b"\x00\x00\x00\x01\x41\x01")
        self.assertFalse(stats.has_video_candidate())
        stats.add(130, b"\x00\x00\x00\x01\x41\x01")
        self.assertTrue(stats.has_video_candidate())

    def test_video_gate_rejects_a_large_timestamp_gap(self) -> None:
        stats = VideoStats()
        stats.add(100, b"\x00\x00\x00\x01\x67\x01\x00\x00\x00\x01\x68\x01\x00\x00\x00\x01\x65\x01")
        for timestamp in range(120, 150):
            stats.add(timestamp, b"\x00\x00\x00\x01\x41\x01")
        self.assertFalse(stats.has_video_candidate())

    def test_heartbeat_is_not_video(self) -> None:
        stats = VideoStats()
        stats.add_heartbeat()
        self.assertFalse(stats.has_video_candidate())


if __name__ == "__main__":
    unittest.main()
