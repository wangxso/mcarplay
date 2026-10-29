#!/system/bin/sh
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
wait-iface)
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ]
    i=0
    while [ "$i" -lt 300 ]; do
        iface=$(cat "$F/ifname" 2>/dev/null || true)
        ipv6=$(cat /proc/net/if_inet6 2>/dev/null | grep " $iface$" || true)
        if [ -n "$iface" ] && [ -d "/sys/class/net/$iface" ] && [ -n "$ipv6" ]; then
            echo "$iface"
            cat "/sys/class/net/$iface/ifindex"
            echo "$ipv6"
            exit 0
        fi
        sleep 0.1
        i=$((i + 1))
    done
    tail -80 /data/local/tmp/mcarplay-ncm-bench.log 2>/dev/null || true
    exit 1
    ;;
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
    sleep 3
    sh /data/local/tmp/mcarplay-ncm-bench.sh peer "$6"
fi
app_pid=${7:-}
launch_bench=${8:-}
began=$(cut -d. -f1 /proc/uptime)
launched=0
while [ "$(cut -d. -f1 /proc/uptime)" -lt "$((began + $2))" ]; do
    if [ -n "$app_pid" ] && [ ! -d "/proc/$app_pid" ]; then
        echo 'NCM: app process exited'
        break
    fi
    if [ -f "$S/peer" ]; then
        peer=$(cat "$S/peer")
        ip -6 route replace "$peer/128" dev "$iface" table "$TABLE"
    fi
    if [ "$launch_bench" = 1 ] && [ -n "${6:-}" ] && [ "$launched" = 0 ] &&
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
