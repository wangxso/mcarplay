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
SCRIPT = r'''#!/system/bin/sh
set -eu
G=/config/usb_gadget/g2
C=$G/configs/b.1
F=$G/functions/ncm.mcarplay_bench
L=$C/ncm.mcarplay_bench
S=/data/local/tmp/mcarplay-ncm-bench-state
PREF=8997
TABLE=742
FILES="idVendor idProduct bcdUSB bcdDevice bDeviceClass bDeviceSubClass bDeviceProtocol strings/0x409/manufacturer strings/0x409/product strings/0x409/serialnumber configs/b.1/MaxPower configs/b.1/bmAttributes configs/b.1/strings/0x409/configuration"

restore() {
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ] || return 1
    if [ -f "$S/peer" ]; then
        peer=$(cat "$S/peer")
        ip -6 rule del pref "$PREF" to "$peer/128" lookup "$TABLE" 2>/dev/null || true
        ip -6 route flush table "$TABLE" 2>/dev/null || true
    fi
    if [ -L "$L" ]; then
        echo '' > "$G/UDC" || true
        rm -f "$L"
    fi
    rmdir "$F" 2>/dev/null || true
    for f in $FILES; do
        key=$(echo "$f" | tr / _)
        [ -f "$S/$key" ] && cat "$S/$key" > "$G/$f" || true
    done
    if [ -f "$S/android_config" ]; then
        previous=$(cat "$S/android_config")
        [ -n "$previous" ] && [ "$previous" != none ] && setprop sys.usb.config "$previous" || true
    fi
    svc usb resetUsbGadget || true
    rm -rf "$S"
    echo 'NCM: restored Android USB'
}

case "${1:-}" in
down)
    if [ ! -d "$S" ]; then
        [ ! -L "$L" ]
        echo 'NCM: already restored'
        exit 0
    fi
    if [ -f "$S/pid" ] && kill -0 "$(cat "$S/pid")" 2>/dev/null; then
        pid=$(cat "$S/pid")
        case "$(tr '\000' ' ' < /proc/$pid/cmdline)" in
            *mcarplay-ncm-bench.sh*) kill -TERM "$pid"; exit 0;;
            *) echo 'NCM: stale pid, restoring saved gadget';;
        esac
    fi
    restore
    exit 0
    ;;
peer)
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ]
    [ ! -f "$S/peer" ]
    [ -z "$(ip -6 rule show | grep "^$PREF:" || true)" ]
    [ -z "$(ip -6 route show table "$TABLE" 2>/dev/null)" ]
    iface=$(cat "$F/ifname")
    printf '%s' "$2" > "$S/peer"
    ip -6 route add "$2/128" dev "$iface" table "$TABLE"
    ip -6 rule add pref "$PREF" to "$2/128" lookup "$TABLE"
    echo "NCM: peer route ready on $iface"
    exit 0
    ;;
up) ;;
*) echo 'NCM: expected up, peer or down'; exit 1;;
esac

[ "$(id -u)" = 0 ]
[ -d "$G" ] && [ -d "$C/strings/0x409" ] && [ -d "$G/strings/0x409" ]
[ -z "$(cat "$G/UDC")" ]
[ -z "$(ls -A "$G/functions")" ]
for item in "$G/configs/"*; do [ "$item" = "$C" ]; done
for item in "$C/"*; do [ ! -L "$item" ]; done
grep -q '\[device\]' /sys/class/typec/port0/data_role
grep -q '\[sink\]' /sys/class/typec/port0/power_role
umask 077
mkdir "$S"
echo mcarplay-ncm-bench-v1 > "$S/owner"
echo $$ > "$S/pid"
trap 'restore' EXIT
timerpid=''
trap 'kill "$timerpid" 2>/dev/null || true; exit 130' INT TERM HUP
for f in $FILES; do
    key=$(echo "$f" | tr / _)
    cat "$G/$f" > "$S/$key"
done
getprop sys.usb.config > "$S/android_config"
udc=$(getprop sys.usb.controller)
case "$udc" in ''|*[!a-zA-Z0-9._:-]*) exit 1;; esac

# Prepare while the system gadget is still active; touch the cable only at bind.
echo 0x1d6b > "$G/idVendor"
echo 0x0104 > "$G/idProduct"
echo 0x0200 > "$G/bcdUSB"
echo 0x0100 > "$G/bcdDevice"
echo 0xef > "$G/bDeviceClass"
echo 0x02 > "$G/bDeviceSubClass"
echo 0x01 > "$G/bDeviceProtocol"
echo mcarplay > "$G/strings/0x409/manufacturer"
echo 'mcarplay USB media bench' > "$G/strings/0x409/product"
echo "$3" > "$G/strings/0x409/serialnumber"
echo 'USB NCM bench' > "$C/strings/0x409/configuration"
echo 250 > "$C/MaxPower"
echo 0x80 > "$C/bmAttributes"
mkdir "$F"
echo "$4" > "$F/dev_addr"
echo "$5" > "$F/host_addr"
ln -s "$F" "$L"
setprop sys.usb.config none
sleep 1
for gadget in /config/usb_gadget/*; do
    [ "$gadget" = "$G" ] && continue
    [ -n "$(cat "$gadget/UDC")" ] && echo '' > "$gadget/UDC"
done
echo "$udc" > "$G/UDC"
iface=$(cat "$F/ifname")
ip link set "$iface" up
echo 0 > "/proc/sys/net/ipv6/conf/$iface/disable_ipv6"
echo "NCM: ready iface=$iface"
if [ -n "${6:-}" ]; then
    # Wi-Fi/adb can disappear during source bring-up. The independent daemon
    # also restores its one-peer route if netd resets the new USB interface.
    sleep 3
    sh /data/local/tmp/mcarplay-ncm-bench.sh peer "$6"
fi
began=$(cut -d. -f1 /proc/uptime)
launched=0
while [ "$(cut -d. -f1 /proc/uptime)" -lt "$((began + $2))" ]; do
    if [ -f "$S/peer" ]; then
        peer=$(cat "$S/peer")
        ip -6 route replace "$peer/128" dev "$iface" table "$TABLE"
    fi
    if [ -n "${6:-}" ] && [ "$launched" = 0 ] &&
        [ "$(cut -d. -f1 /proc/uptime)" -ge "$((began + 6))" ]; then
        am start --user 0 -n com.mcarplay.app.rd/com.mcarplay.app.bridge.UsbMediaBenchActivity \
            --es interface "$iface" --es host "$6" --ei port 17000 \
            --ei scope "$(cat /sys/class/net/$iface/ifindex)" &
        launched=1
    fi
    sleep 1 &
    timerpid=$!
    wait "$timerpid" || true
done
'''


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
    arguments = ["setsid", "sh", REMOTE_SCRIPT, "up", str(args.seconds), secrets.token_hex(12), device_mac, host_mac, args.peer or ""]
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
