#!/usr/bin/env python3
"""CarLife 4.0 loopback probe for a Xiaomi phone.

This tool is diagnostic only. It can launch CarWith's ADB entry or attach to an
existing session, forward the seven CarLife TCP channels, and save raw Annex B
H.264 for validation. It never sends authentication success before verifying
CarLife's signature.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import re
import secrets
import socket
import struct
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

PORTS = (7240, 8240, 9240, 9241, 9242, 9340, 9440)

CMD_PROTOCOL_VERSION = 0x18001
CMD_HU_INFO = 0x18003
CMD_HU_AUTHEN_REQUEST = 0x18048
CMD_HU_AUTHEN_RESULT = 0x1804A
CMD_MD_AUTH_RESULT_ACK = 0x1804C
CMD_HU_FEATURE_RESPONSE = 0x18052
CMD_HU_RSA_PUBLIC_KEY = 0x1806B
CMD_HU_AES_ACK = 0x1806D
CMD_VIDEO_ENCODER_INIT = 0x18007
CMD_VIDEO_ENCODER_START = 0x18009
CMD_TOUCH_DOWN = 425986
CMD_TOUCH_UP = 425987

RX_MD_INFO = 0x10004
RX_MD_FOREGROUND = 0x1001B
RX_MD_AUTHEN_RESPONSE = 0x10049
RX_MD_AUTHEN_RESULT = 0x1004B
RX_VIDEO_ENCODER_INIT = 0x10008
RX_VIDEO_DATA = 0x20001
RX_VIDEO_HEARTBEAT = 0x20002
RX_MD_PROTOCOL_MATCH = 0x10002
RX_MD_FEATURE_REQUEST = 0x10051
RX_MD_RSA_KEY_REQUEST = 0x1006A
RX_MD_AES_SEED = 0x1006C
RX_MD_ENCRYPT_READY = 0x1006E
CMD_TOUCH_MOVE = 425988
CMD_HU_GO_TO_FOREGROUND = 0x18025

CARWITH_ENTRY = (
    "com.miui.carlink/.castfwk.usb.CarlifeAdbActivity"
)

XIAOMI_CARLIFE_PUBLIC_KEY = b"""-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC86AUdyKOcMUB/iDMtHpFBOdLP
WThDziP8t20sF0mRKlNBIAxlUZxQWIa/DEhJL9kx05+RyrFDN4B3d66UIURtia3G
hWPoiBCbNornl73ynfFKk1BUDma3O8Qs9Z6YOCkXrGOFQBDfltfXs/w7CogkuS2s
ZsBPOOMVom04I7KWPQIDAQAB
-----END PUBLIC KEY-----
"""


def adb(serial: str, *args: str) -> bytes:
    return subprocess.run(
        ("adb", "-s", serial, *args),
        check=True,
        capture_output=True,
    ).stdout


def listening_ports(ss_output: bytes) -> set[int]:
    ports = set()
    for line in ss_output.decode(errors="replace").splitlines():
        fields = line.split()
        if len(fields) < 4 or fields[0] != "LISTEN":
            continue
        port = fields[3].rsplit(":", 1)[-1]
        if port.isdecimal():
            ports.add(int(port))
    return ports


def occupied_ports(ss_output: bytes) -> set[int]:
    ports = set()
    for line in ss_output.decode(errors="replace").splitlines():
        fields = line.split()
        if len(fields) >= 4 and fields[0] == "ESTAB":
            port = fields[3].rsplit(":", 1)[-1]
            if port.isdecimal() and int(port) in PORTS:
                ports.add(int(port))
    return ports


def features_payload(features: dict[str, int]) -> bytes:
    body = pb_int(1, len(features))
    for key, value in features.items():
        entry = pb_string(1, key) + pb_int(2, value)
        body += varint((2 << 3) | 2) + varint(len(entry)) + entry
    return body


def aes_encrypt(key: bytes, payload: bytes) -> bytes:
    if not payload:
        return b""
    encryptor = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
    # CarLife uses PKCS#5 padding, which is byte-identical to PKCS#7 for AES.
    pad = 16 - (len(payload) % 16)
    return encryptor.update(payload + bytes([pad]) * pad) + encryptor.finalize()


def aes_decrypt(key: bytes, payload: bytes) -> bytes:
    if not payload:
        return b""
    decryptor = Cipher(algorithms.AES(key), modes.ECB()).decryptor()
    padded = decryptor.update(payload) + decryptor.finalize()
    pad = padded[-1]
    if pad < 1 or pad > 16 or padded[-pad:] != bytes([pad]) * pad:
        raise ValueError("invalid AES padding")
    return padded[:-pad]


def read_exact(sock: socket.socket, count: int) -> bytes:
    out = bytearray()
    while len(out) < count:
        part = sock.recv(count - len(out))
        if not part:
            raise EOFError("socket closed")
        out.extend(part)
    return bytes(out)


def varint(value: int) -> bytes:
    out = bytearray()
    while value > 0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def pb_int(field_number: int, value: int) -> bytes:
    return varint(field_number << 3) + varint(value)


def pb_string(field_number: int, value: str) -> bytes:
    encoded = value.encode()
    return varint((field_number << 3) | 2) + varint(len(encoded)) + encoded


def send_cmd(sock: socket.socket, kind: int, payload: bytes = b"") -> None:
    sock.sendall(struct.pack(">HHI", len(payload), 0, kind) + payload)


def split_annex_b(data: bytes) -> list[bytes]:
    starts: list[tuple[int, int]] = []
    index = 0
    while index + 3 <= len(data):
        if data[index : index + 3] == b"\x00\x00\x01":
            starts.append((index, 3))
            index += 3
            continue
        if index + 4 <= len(data) and data[index : index + 4] == b"\x00\x00\x00\x01":
            starts.append((index, 4))
            index += 4
            continue
        index += 1
    return [
        data[start + prefix : starts[i + 1][0] if i + 1 < len(starts) else len(data)]
        for i, (start, prefix) in enumerate(starts)
    ]


@dataclass
class VideoStats:
    packets: int = 0
    bytes: int = 0
    heartbeats: int = 0
    sps: int = 0
    pps: int = 0
    idr: int = 0
    sei: int = 0
    non_idr: int = 0
    first_timestamp: int | None = None
    last_timestamp: int | None = None
    max_timestamp_gap: int = 0
    timestamp_discontinuities: int = 0
    key_timestamps: list[int] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def add(self, timestamp: int, payload: bytes) -> None:
        with self.lock:
            self.packets += 1
            self.bytes += len(payload)
            if self.first_timestamp is None:
                self.first_timestamp = timestamp
            elif self.last_timestamp is not None:
                gap = timestamp - self.last_timestamp
                if gap < 0:
                    self.timestamp_discontinuities += 1
                else:
                    self.max_timestamp_gap = max(self.max_timestamp_gap, gap)
            self.last_timestamp = timestamp
            key = False
            for nal in split_annex_b(payload):
                if not nal:
                    continue
                nal_type = nal[0] & 0x1F
                if nal_type == 7:
                    self.sps += 1
                elif nal_type == 8:
                    self.pps += 1
                elif nal_type == 6:
                    self.sei += 1
                elif nal_type == 5:
                    self.idr += 1
                    key = True
                elif nal_type in (1, 2, 3, 4):
                    self.non_idr += 1
            if key:
                self.key_timestamps.append(timestamp)

    def add_heartbeat(self) -> None:
        with self.lock:
            self.heartbeats += 1

    def summary(self) -> str:
        with self.lock:
            span = 0.0
            if self.first_timestamp is not None and self.last_timestamp is not None:
                span = max(0, self.last_timestamp - self.first_timestamp)
            fps = self.packets / span if span > 0 else 0.0
            return (
                f"video packets={self.packets} bytes={self.bytes} heartbeats={self.heartbeats} "
                f"SPS={self.sps} PPS={self.pps} IDR={self.idr} non-IDR={self.non_idr} "
                f"SEI={self.sei} ts-span={span}s max-gap={self.max_timestamp_gap}s "
                f"discontinuities={self.timestamp_discontinuities} avg={fps:.2f}pkt/s"
            )

    def has_video_candidate(self) -> bool:
        with self.lock:
            if self.first_timestamp is None or self.last_timestamp is None:
                return False
            return (
                self.sps > 0
                and self.pps > 0
                and self.idr > 0
                and self.non_idr >= 30
                and self.last_timestamp - self.first_timestamp >= 30
                and self.max_timestamp_gap <= 2
                and self.timestamp_discontinuities == 0
            )


class Probe:
    def __init__(
        self,
        serial: str,
        *,
        video: bool,
        full: bool,
        save_h264: Path | None,
        tap: tuple[int, int] | None,
        attach: bool = False,
    ) -> None:
        self.serial = serial
        self.video = video
        self.full = full
        self.save_h264 = save_h264
        self.tap = tap
        self.attach = attach
        self.local_ports: dict[int, int] = {}
        self.sockets: dict[int, socket.socket] = {}
        self.stop = threading.Event()
        self.stats = VideoStats()
        self.video_file = None
        self.send_lock = threading.Lock()
        self.readers: list[threading.Thread] = []
        self.auth_requested = False
        self.auth_verified = False
        self.auth_challenge = "2.0;" + secrets.token_hex(16)
        self.device_info: tuple[str, str, str] | None = None
        self.protocol_matched = False
        self.peer_auth_accepted = False
        self.feature_requested = False
        self.md_info_received = False
        self.feature_response_sent = False
        self.video_init_sent = False
        self.video_start_sent = False
        self.rsa_private_key = None
        self.aes_key: bytes | None = None
        self.encryption_ready = False
        self.logcat_path = Path("/tmp/mcarplay-carwith-logcat.txt")
        self.logcat_since = ""

    def run(self, seconds: float) -> int:
        self.logcat_since = adb(self.serial, "shell", "date", "+%Y-%m-%dT%H:%M:%S.000").decode().strip().replace("T", " ")
        if self.attach:
            status = adb(self.serial, "shell", "ss", "-ant")
            available = listening_ports(status)
            missing = sorted(set(PORTS) - available)
            if missing:
                raise RuntimeError(f"CarLife listeners absent: {missing}; start CarWith through its own UI first")
            occupied = sorted(occupied_ports(status))
            if occupied:
                raise RuntimeError(f"CarLife channels already occupied: {occupied}; refusing a second consumer")
        else:
            adb(self.serial, "shell", "am", "start", "-n", CARWITH_ENTRY)
            time.sleep(1.0)
        try:
            for remote in PORTS:
                local = int(adb(self.serial, "forward", "tcp:0", f"tcp:{remote}").decode().strip())
                self.local_ports[remote] = local
            for remote, local in self.local_ports.items():
                sock = socket.create_connection(("127.0.0.1", local), timeout=3)
                sock.settimeout(None)
                self.sockets[remote] = sock
                print(f"connected {remote}", flush=True)

            if self.save_h264:
                self.save_h264.parent.mkdir(parents=True, exist_ok=True)
                self.video_file = self.save_h264.open("wb")
            self.start_reader(7240)
            if self.video:
                self.start_reader(8240)
                self.start_heartbeat()
            self.send_cmd(CMD_PROTOCOL_VERSION, b"\x08\x04\x10\x01")
            if self.tap:
                threading.Thread(target=self.perform_tap, daemon=True).start()
            self.stop.wait(seconds)
            if self.full and not (self.auth_verified and self.peer_auth_accepted):
                return 2
            if self.video and (not self.stats.has_video_candidate() or (self.full and not self.encryption_ready)):
                return 2
            return 0
        finally:
            self.stop.set()
            for sock in self.sockets.values():
                try:
                    sock.close()
                except OSError:
                    pass
            for reader in self.readers:
                reader.join(timeout=2)
            if self.video_file:
                self.video_file.flush()
                self.video_file.close()
            self.save_logcat()
            for local in self.local_ports.values():
                try:
                    adb(self.serial, "forward", "--remove", f"tcp:{local}")
                except subprocess.CalledProcessError:
                    pass
            print(
                f"authentication proof={self.auth_verified} phone-admission={self.peer_auth_accepted} aes={self.encryption_ready}",
                flush=True,
            )
            print(self.stats.summary(), flush=True)

    def save_logcat(self) -> None:
        result = subprocess.run(
            (
                "adb", "-s", self.serial, "logcat", "-d", "-v", "threadtime",
                "-T", self.logcat_since, "-s", "CarlinkSM:V", "CarlinkService:V",
                "CastingActivity:V", "CarlifeAdbActivity:V", "CarlifeConnService:V",
                "CarlifeConnectService:V", "ConnectManager:V",
            ),
            check=False,
            capture_output=True,
        )
        redacted = re.sub(
            rb"\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b",
            b"<redacted-mac>",
            result.stdout,
        )
        self.logcat_path.write_bytes(redacted)

    def start_reader(self, port: int) -> None:
        reader = threading.Thread(target=self.read_loop, args=(port,), daemon=True)
        reader.start()
        self.readers.append(reader)

    def start_heartbeat(self) -> None:
        def loop() -> None:
            while not self.stop.wait(1.0):
                sock = self.sockets.get(8240)
                if sock is None:
                    return
                header = struct.pack(">III", 0, int(time.time()), RX_VIDEO_HEARTBEAT)
                try:
                    with self.send_lock:
                        sock.sendall(header)
                except OSError:
                    return

        threading.Thread(target=loop, daemon=True).start()

    def perform_tap(self) -> None:
        if not self.tap:
            return
        time.sleep(3.0)
        x, y = self.tap
        touch = self.sockets[9340]
        with self.send_lock:
            self.send_control(
                touch,
                CMD_TOUCH_DOWN,
                pb_int(1, x) + pb_int(2, y),
                lock_held=True,
            )
            time.sleep(0.08)
            self.send_control(
                touch,
                CMD_TOUCH_UP,
                pb_int(1, x) + pb_int(2, y),
                lock_held=True,
            )
        print(f"TAP ({x},{y})", flush=True)

    def send_cmd(self, kind: int, payload: bytes = b"") -> None:
        cmd = self.sockets[7240]
        body = aes_encrypt(self.aes_key, payload) if self.aes_key else payload
        with self.send_lock:
            send_cmd(cmd, kind, body)
        print(f"TX 0x{kind:05x} len={len(payload)}", flush=True)

    def send_control(
        self,
        sock: socket.socket,
        kind: int,
        payload: bytes = b"",
        *,
        lock_held: bool = False,
    ) -> None:
        body = aes_encrypt(self.aes_key, payload) if self.aes_key else payload
        if lock_held:
            send_cmd(sock, kind, body)
        else:
            with self.send_lock:
                send_cmd(sock, kind, body)

    def read_loop(self, port: int) -> None:
        sock = self.sockets[port]
        try:
            while not self.stop.is_set():
                if port == 8240:
                    self.read_video(sock)
                else:
                    self.read_command(sock, port)
        except (EOFError, OSError, ValueError) as error:
            if not self.stop.is_set():
                print(f"channel {port} closed: {error}", flush=True)
                if port in (7240, 8240):
                    self.stop.set()

    def read_command(self, sock: socket.socket, port: int) -> None:
        header = read_exact(sock, 8)
        length, reserved, kind = struct.unpack(">HHI", header)
        if length > 65_535:
            raise ValueError(f"invalid command length {length}")
        body = read_exact(sock, length)
        if self.aes_key and body:
            body = aes_decrypt(self.aes_key, body)
        print(
            f"RX port={port} 0x{kind:05x} reserved={reserved} len={length}",
            flush=True,
        )
        if port != 7240:
            return
        if kind == RX_MD_PROTOCOL_MATCH and self.full:
            if self.protocol_matched:
                raise ValueError("duplicate protocol match")
            self.protocol_matched = True
            self.send_control(sock, 0x18027, hu_statistics_payload())
        if kind == RX_MD_INFO:
            if self.md_info_received:
                raise ValueError("duplicate MD_INFO")
            self.md_info_received = True
            values = tuple(first_string_field(body, number) for number in (4, 14, 20))
            if not all(value and value.strip() and len(value) <= 256 for value in values):
                raise ValueError("invalid MD_INFO authentication fields")
            self.device_info = values
        # Xiaomi CarLife sends MD_INFO between protocol match and auth, so
        # the challenge is issued below when MD_INFO is received.
        if kind == RX_MD_INFO and self.full and not self.auth_requested:
            self.auth_requested = True
            self.send_control(sock, CMD_HU_INFO, hu_info_payload())
            self.send_cmd(CMD_HU_AUTHEN_REQUEST, pb_string(1, self.auth_challenge))
        if kind == RX_MD_FOREGROUND:
            self.send_cmd(CMD_HU_GO_TO_FOREGROUND)
        if kind == RX_MD_AUTHEN_RESPONSE and self.full:
            if not self.md_info_received:
                raise ValueError("authentication response before MD_INFO")
            encrypted = first_string_field(body, 1)
            if encrypted is None or not verify_carlife_auth(encrypted, self.auth_challenge, self.device_info):
                print("authentication verification failed; not sending success", flush=True)
                self.stop.set()
                return
            self.auth_verified = True
            self.send_cmd(CMD_HU_AUTHEN_RESULT, pb_int(1, 1))
        if kind == RX_MD_AUTHEN_RESULT and self.full:
            if not self.protocol_matched or self.peer_auth_accepted or first_int_field(body, 1) != 1:
                raise ValueError("phone channel admission rejected or out of order")
            self.peer_auth_accepted = True
            self.send_cmd(CMD_MD_AUTH_RESULT_ACK)
        if kind == RX_MD_FEATURE_REQUEST and self.full:
            if not self.protocol_matched or self.feature_requested:
                raise ValueError("duplicate or out-of-order feature request")
            self.feature_requested = True
        if self.feature_requested and self.auth_verified and not self.feature_response_sent:
            self.feature_response_sent = True
            self.send_cmd(CMD_HU_FEATURE_RESPONSE, features_payload({"CONTENT_ENCRYPTION": 1}))
        if kind == RX_MD_RSA_KEY_REQUEST and self.full:
            if not self.feature_response_sent:
                raise ValueError("RSA requested before verified authentication")
            if self.rsa_private_key is None:
                self.rsa_private_key = rsa.generate_private_key(
                    public_exponent=65537,
                    key_size=2048,
                )
                public_pem = self.rsa_private_key.public_key().public_bytes(
                    serialization.Encoding.DER,
                    serialization.PublicFormat.SubjectPublicKeyInfo,
                )
                self.send_cmd(
                    CMD_HU_RSA_PUBLIC_KEY,
                    pb_string(1, base64.b64encode(public_pem).decode()),
                )
        if kind == RX_MD_AES_SEED and self.full and self.aes_key is None:
            encrypted = first_string_field(body, 1)
            if encrypted is None or self.rsa_private_key is None:
                raise ValueError("unexpected AES seed")
            self.aes_key = self.rsa_private_key.decrypt(
                base64.b64decode(encrypted),
                padding.PKCS1v15(),
            )
            if len(self.aes_key) not in (16, 24, 32):
                raise ValueError("invalid AES key length")
            self.send_cmd(CMD_HU_AES_ACK)
        if kind == RX_MD_ENCRYPT_READY and self.aes_key:
            self.encryption_ready = True
        if self.encryption_ready and self.peer_auth_accepted:
            if self.video and not self.video_init_sent:
                self.video_init_sent = True
                self.send_cmd(
                    CMD_VIDEO_ENCODER_INIT,
                    pb_int(1, 1280) + pb_int(2, 720) + pb_int(3, 30),
                )
        if kind == RX_VIDEO_ENCODER_INIT and self.video and not self.video_init_sent:
            if self.full:
                raise ValueError("video initialization arrived before authenticated encryption")
            self.video_init_sent = True
            self.send_cmd(
                CMD_VIDEO_ENCODER_INIT,
                pb_int(1, 1280) + pb_int(2, 720) + pb_int(3, 30),
            )
        if kind == RX_VIDEO_ENCODER_INIT and self.video and not self.video_start_sent:
            self.video_start_sent = True
            self.send_cmd(CMD_VIDEO_ENCODER_START)

    def read_video(self, sock: socket.socket) -> None:
        header = read_exact(sock, 12)
        length, timestamp, kind = struct.unpack(">III", header)
        if length > 2_000_000:
            raise ValueError(f"invalid video length {length}")
        body = read_exact(sock, length)
        if kind == RX_VIDEO_HEARTBEAT:
            self.stats.add_heartbeat()
            return
        if kind != RX_VIDEO_DATA:
            return
        if self.aes_key:
            body = aes_decrypt(self.aes_key, body)
        self.stats.add(timestamp, body)
        if self.video_file:
            self.video_file.write(body)
            self.video_file.flush()
        count = self.stats.packets
        if count <= 12 or count % 60 == 0:
            print(
                f"VIDEO #{count} ts={timestamp} len={length} head={body[:24].hex()}",
                flush=True,
            )


def hu_info_payload() -> bytes:
    return (
        pb_string(1, "Android")
        + pb_string(4, "mcarplay")
        + pb_string(14, "Xiaomi")
        + pb_string(20, "CarWith")
    )


def hu_statistics_payload() -> bytes:
    return (
        pb_string(1, "mcarplay-local")
        + pb_string(2, "0.1.0")
        + pb_int(3, 1)
        + pb_string(4, "29999999")
        + pb_int(5, 1)
        + pb_int(6, 0)
        + pb_int(7, 0)
    )


def first_string_field(payload: bytes, field_number: int) -> str | None:
    offset = 0
    while offset < len(payload):
        tag, size = read_varint(payload, offset)
        offset += size
        field, wire_type = tag >> 3, tag & 7
        if wire_type == 0:
            _, size = read_varint(payload, offset)
            offset += size
        elif wire_type == 2:
            length, size = read_varint(payload, offset)
            offset += size
            value = payload[offset : offset + length]
            if len(value) != length:
                raise ValueError("truncated protobuf string")
            if field == field_number:
                return value.decode()
            offset += length
        elif wire_type == 1:
            offset += 8
        elif wire_type == 5:
            offset += 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire_type}")
    return None


def read_varint(payload: bytes, start: int) -> tuple[int, int]:
    value = 0
    shift = 0
    offset = start
    while offset < len(payload) and shift < 64:
        byte = payload[offset]
        value |= (byte & 0x7F) << shift
        offset += 1
        if byte & 0x80 == 0:
            return value, offset - start
        shift += 7
    raise ValueError("invalid protobuf varint")


def first_int_field(payload: bytes, field_number: int) -> int | None:
    offset = 0
    while offset < len(payload):
        tag, size = read_varint(payload, offset)
        offset += size
        field, wire_type = tag >> 3, tag & 7
        if wire_type == 0:
            value, size = read_varint(payload, offset)
            offset += size
            if field == field_number:
                return value
        elif wire_type == 2:
            length, size = read_varint(payload, offset)
            offset += size + length
        elif wire_type == 1:
            offset += 8
        elif wire_type == 5:
            offset += 4
        else:
            raise ValueError(f"unsupported protobuf wire type {wire_type}")
        if offset > len(payload):
            raise ValueError("truncated protobuf field")
    return None


def verify_carlife_auth(
    encrypted: str,
    challenge: str,
    device_info: tuple[str, str, str] | None = None,
) -> bool:
    if challenge.startswith("2.0;"):
        nonce = challenge[4:]
        if device_info is None or not re.fullmatch(r"[A-Za-z0-9_-]{6,128}", nonce):
            return False
        if not re.fullmatch(r"[0-9a-f]{32}", encrypted):
            return False
        expected = hashlib.md5(("CARLIFE" + "".join(device_info) + nonce).encode()).hexdigest()
        return hmac.compare_digest(expected, encrypted)
    try:
        public_key = serialization.load_pem_public_key(XIAOMI_CARLIFE_PUBLIC_KEY)
        if not isinstance(public_key, rsa.RSAPublicKey):
            return False
        signature = base64.b64decode(encrypted, validate=True)
        numbers = public_key.public_numbers()
        encoded = pow(
            int.from_bytes(signature, "big"),
            numbers.e,
            numbers.n,
        ).to_bytes((numbers.n.bit_length() + 7) // 8, "big")
        padding_size = encoded.find(b"\x00", 2)
        if encoded[:2] != b"\x00\x01" or padding_size < 10:
            return False
        if any(value != 0xFF for value in encoded[2:padding_size]):
            return False
        plaintext = encoded[padding_size + 1 :]
        return plaintext == challenge.encode()
    except Exception:
        return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("serial", help="Xiaomi wireless ADB serial")
    parser.add_argument("--seconds", type=float, default=10)
    parser.add_argument("--video", action="store_true", help="request 1280x720 video")
    parser.add_argument("--full", action="store_true", help="complete feature negotiation")
    parser.add_argument("--attach", action="store_true", help="attach to existing CarWith listeners without launching its ADB entry")
    parser.add_argument("--save-h264", type=Path, help="write raw Annex B H.264")
    parser.add_argument("--tap", nargs=2, type=int, metavar=("X", "Y"))
    args = parser.parse_args()
    probe = Probe(
        args.serial,
        video=args.video,
        full=args.full,
        save_h264=args.save_h264,
        tap=tuple(args.tap) if args.tap else None,
        attach=args.attach,
    )
    try:
        raise SystemExit(probe.run(args.seconds))
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"probe error: {error}", file=sys.stderr)
        raise SystemExit(3) from error


if __name__ == "__main__":
    main()
