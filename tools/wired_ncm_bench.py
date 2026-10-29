#!/usr/bin/env python3
"""Root-only USB NCM display bench. Borrows an idle g2 and restores its attributes.

This is a media transport bench, not iPhone emulation or a head-unit role switch.
The detached daemon survives adb loss and rolls back at its deadline.
"""

import argparse
import ipaddress
import json
import re
import shlex
import subprocess
import time
from pathlib import Path

REMOTE_SCRIPT = "/data/local/tmp/mcarplay-ncm-bench.sh"
REMOTE_LOG = "/data/local/tmp/mcarplay-ncm-bench.log"

# The snapshot contains device strings and stays on the phone with mode 0700.
# Never remove an Android-owned gadget or write Type-C role nodes.
SCRIPT_PATH = Path(__file__).resolve().parents[1] / "app/src/remote/res/raw/wired_ncm_bench.sh"
SCRIPT = SCRIPT_PATH.read_text(encoding="utf-8")


def root_command(serial: str, arguments: list[str]) -> list[str]:
    command = " ".join(shlex.quote(arg) for arg in arguments)
    return ["adb", "-s", serial, "shell", "su -c " + shlex.quote(command)]


def root(serial: str, arguments: list[str], **kwargs):
    return subprocess.run(
        root_command(serial, arguments),
        check=True,
        **kwargs,
    )


def mac_interface(host_mac: str) -> dict | None:
    """Find only the new NCM interface's exact host MAC, never a Wi-Fi interface."""
    output = subprocess.check_output(["ifconfig", "-a"], text=True)
    for block in re.split(r"(?m)^(?=\w[^\s:]*: flags=)", output):
        if f"ether {host_mac.lower()}" not in block.lower():
            continue
        name = block.split(":", 1)[0]
        address = re.search(r"inet6 (fe80:[0-9a-f:]+)%", block, re.I)
        return {"interface": name, "address": address.group(1) if address else None}
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Connected adb transport; USB adb disconnects after daemon launch")
    commands = parser.add_subparsers(dest="command", required=True)
    up = commands.add_parser("up")
    up.add_argument("--seconds", type=int, default=300)
    up.add_argument("--peer", help="Previously observed Mac USB IPv6 address; also launches the remote debug media bench")
    peer = commands.add_parser("peer")
    peer.add_argument("address")
    commands.add_parser("down")
    interface = commands.add_parser("mac-interface")
    interface.add_argument("host_mac")
    args = parser.parse_args()
    if args.command == "mac-interface":
        print(json.dumps(mac_interface(args.host_mac)))
        return
    if args.command == "peer":
        address = ipaddress.IPv6Address(args.address)
        if not address.is_link_local:
            parser.error("The peer must be the Mac USB interface's link-local IPv6 address")
        root(args.serial, ["sh", REMOTE_SCRIPT, "peer", str(address)])
        return
    if args.command == "down":
        root(args.serial, ["sh", REMOTE_SCRIPT, "down"])
        return
    if not 10 <= args.seconds <= 1800:
        parser.error("--seconds must be in 10..1800")
    if args.peer and not ipaddress.IPv6Address(args.peer).is_link_local:
        parser.error("--peer must be the observed Mac USB link-local IPv6 address")
    import secrets
    import tempfile

    def random_mac():
        return "02:" + ":".join(f"{b:02x}" for b in secrets.token_bytes(5))

    device_mac, host_mac = random_mac(), random_mac()
    print(json.dumps({"host_mac": host_mac}), flush=True)
    with tempfile.TemporaryDirectory(prefix="mcarplay-ncm-") as temporary:
        script = Path(temporary) / "ncm.sh"
        script.write_text(SCRIPT)
        subprocess.run(["adb", "-s", args.serial, "push", str(script), REMOTE_SCRIPT], check=True)
    arguments = [
        "setsid",
        "sh",
        REMOTE_SCRIPT,
        "up",
        str(args.seconds),
        secrets.token_hex(12),
        device_mac,
        host_mac,
        args.peer or "",
        "" if args.peer is None else "1",
    ]
    launch = (
        "command -v setsid >/dev/null && umask 077 && "
        + shlex.join(arguments)
        + " > " + shlex.quote(REMOTE_LOG) + " 2>&1 < /dev/null &"
    )
    # Some su implementations retain the launcher's adb channel until all its
    # descendants exit. It must not own the host CLI's lifetime or the USB lease.
    process = subprocess.Popen(
        root_command(args.serial, ["sh", "-c", launch]),
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        code = process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        code = None
    if code not in (None, 0, 255):
        raise RuntimeError(f"Could not dispatch the USB daemon (adb exit {code})")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        info = mac_interface(host_mac)
        if info and info["address"]:
            print(json.dumps(info), flush=True)
            print(f"USB NCM bound; daemon rolls back in at most {args.seconds + 15}s. Log: {REMOTE_LOG}", flush=True)
            return
        time.sleep(0.5)
    raise RuntimeError("The Mac did not enumerate the requested NCM interface; inspect the phone bench log")


if __name__ == "__main__":
    main()
