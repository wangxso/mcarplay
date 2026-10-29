#!/system/bin/sh
set -eu
# The NCM function stays the same in both modes. "iphone-ui" only changes the
# composite device identity so receivers that filter on an iPhone product name
# can list it; it does not implement Apple usbmux/lockdown/carkit.
G=/config/usb_gadget/g2
C=$G/configs/b.1
F=$G/functions/ncm.mcarplay_bench
L=$C/ncm.mcarplay_bench
S=/data/local/tmp/mcarplay-ncm-bench-state
PREF=8997
TABLE=742
FILES="idVendor idProduct bcdUSB bcdDevice bDeviceClass bDeviceSubClass bDeviceProtocol strings/0x409/manufacturer strings/0x409/product strings/0x409/serialnumber configs/b.1/MaxPower configs/b.1/bmAttributes configs/b.1/strings/0x409/configuration"

add_peer_route() {
    host=$1
    iface=$(cat "$F/ifname")
    case "$host" in
        *:*)
            family=6
            prefix=128
            ;;
        *.*)
            family=4
            prefix=32
            ;;
        *)
            return 1
            ;;
    esac
    ip -"$family" route replace "$host/$prefix" dev "$iface" table "$TABLE" || return 1
    ip -"$family" rule del pref "$PREF" to "$host/$prefix" lookup "$TABLE" 2>/dev/null || true
    ip -"$family" rule add pref "$PREF" to "$host/$prefix" lookup "$TABLE"
}

ipv4_from_mac() {
    mac=$1
    old_ifs=$IFS
    IFS=:
    set -- $mac
    IFS=$old_ifs
    [ "$#" -eq 6 ] || return 1
    mac5=$5
    mac6=$6
    echo "169.254.$((0x$mac5 % 254 + 1)).$((0x$mac6 % 254 + 1))"
}

link_local_from_mac() {
    mac=$1
    old_ifs=$IFS
    IFS=:
    set -- $mac
    IFS=$old_ifs
    [ "$#" -eq 6 ] || return 1
    first=$(printf '%02x' "$((0x$1 ^ 2))")
    echo "fe80::$first$2:${3}ff:fe$4:$5$6"
}

restore() {
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ] || return 1
    iface=$(cat "$F/ifname" 2>/dev/null || cat "$S/iface" 2>/dev/null || true)
    while ip -6 rule del pref "$PREF" 2>/dev/null; do :; done
    while ip -4 rule del pref "$PREF" 2>/dev/null; do :; done
    ip -6 route flush table "$TABLE" 2>/dev/null || true
    ip -4 route flush table "$TABLE" 2>/dev/null || true
    echo '' > "$G/UDC" || true
    if [ -L "$L" ]; then
        rm -f "$L"
    fi
    if [ -n "$iface" ] && [ -d "/sys/class/net/$iface" ]; then
        ip -4 addr flush dev "$iface" 2>/dev/null || true
        ip -6 addr flush dev "$iface" 2>/dev/null || true
        ip link set "$iface" down 2>/dev/null || true
    fi
    rmdir "$F" 2>/dev/null || true
    for f in $FILES; do
        key=$(echo "$f" | tr / _)
        [ -f "$S/$key" ] && cat "$S/$key" > "$G/$f" || true
    done
    if [ -f "$S/android_config" ]; then
        previous=$(cat "$S/android_config")
        if [ -n "$previous" ] && [ "$previous" != none ]; then
            svc usb setFunctions "$previous" 2>/dev/null || setprop sys.usb.config "$previous" || true
        fi
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
        if [ -n "$iface" ] && [ -d "/sys/class/net/$iface" ]; then
            [ -f "$S/ready" ] || { sleep 0.1; i=$((i + 1)); continue; }
            link_state=$(ip link show dev "$iface" 2>/dev/null || true)
            carrier=$(cat "/sys/class/net/$iface/carrier" 2>/dev/null || true)
            case "$link_state" in
                *LOWER_UP*) ;;
                *) [ "$carrier" = 1 ] || { sleep 0.1; i=$((i + 1)); continue; } ;;
            esac
            ip link set "$iface" up || { sleep 0.1; i=$((i + 1)); continue; }
            device_mac=$(cat "$S/device_mac" 2>/dev/null || true)
            ipv4_host=$(ipv4_from_mac "$device_mac" 2>/dev/null || true)
            link_local=$(link_local_from_mac "$device_mac" 2>/dev/null || true)
            [ -n "$ipv4_host" ] && ip -4 addr replace "$ipv4_host/16" dev "$iface" || true
            if [ -n "$link_local" ]; then
                ip -6 addr replace "$link_local/64" dev "$iface" nodad ||
                    ip -6 addr replace "$link_local/64" dev "$iface" || true
            fi
            # nodad 通常立即可用；保留短窗口兼容不识别 nodad 的旧 ip 工具。
            sleep 0.2
            ipv6=$(cat /proc/net/if_inet6 2>/dev/null | grep " $iface$" || true)
            ipv4=$(ip -4 addr show dev "$iface" 2>/dev/null | awk '/inet / { split($2, a, "/"); print a[1]; exit }')
            if { [ -n "$ipv6" ] || [ -n "$ipv4" ]; }; then
                echo "$iface"
                cat "/sys/class/net/$iface/ifindex"
                echo "$ipv6"
                [ -n "$ipv4" ] && echo "IPV4=$ipv4"
                [ -n "$link_local" ] && echo "LINKLOCAL=$link_local"
                exit 0
            fi
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
            *mcarplay-ncm-bench.sh*)
                kill -TERM "$pid" 2>/dev/null || true
                i=0
                while kill -0 "$pid" 2>/dev/null && [ "$i" -lt 50 ]; do
                    sleep 0.1
                    i=$((i + 1))
                done
                kill -KILL "$pid" 2>/dev/null || true
                ;;
            *) echo 'NCM: stale pid, restoring saved gadget';;
        esac
    fi
    [ ! -d "$S" ] || restore
    echo 'NCM: restored Android USB'
    exit 0
    ;;
peer)
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ]
    [ "$#" -eq 2 ]
    host=$2
    touch "$S/peers"
    if ! grep -Fxq "$host" "$S/peers"; then
        printf '%s\n' "$host" >> "$S/peers"
    fi
    add_peer_route "$host"
    echo "NCM: peer route ready on $(cat "$F/ifname")"
    exit 0
    ;;
peers)
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ]
    iface=$(cat "$F/ifname")
    ip -4 neigh show dev "$iface" 2>/dev/null || true
    ip -6 neigh show dev "$iface" 2>/dev/null || true
    exit 0
    ;;
up) ;;
*) echo 'NCM: expected up, peer, peers or down'; exit 1;;
esac

[ "$(id -u)" = 0 ]
[ "$#" -ge 8 ] && [ "$#" -le 9 ]
identity_mode=${9:-generic}
case "$identity_mode" in
    iphone-ui|generic) ;;
    *) echo "NCM: invalid identity mode"; exit 1 ;;
esac
[ "${#3}" -eq 40 ]
case "$3" in *[!0-9a-f]*) echo "NCM: invalid serial"; exit 1 ;; esac
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
echo "$identity_mode" > "$S/identity"
echo "$4" > "$S/device_mac"
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
case "$identity_mode" in
    iphone-ui)
        vendor_id=0x05ac
        product_id=0x12a8
        bcd_device=0x0100
        manufacturer='Apple Inc.'
        product_name='iPhone'
        configuration_name='CarPlay'
        max_power=500
        ;;
    generic)
        vendor_id=0x1d6b
        product_id=0x0104
        bcd_device=0x0100
        manufacturer='mcarplay'
        product_name='mcarplay USB media bench'
        configuration_name='USB NCM bench'
        max_power=250
        ;;
esac
echo "$vendor_id" > "$G/idVendor"
echo "$product_id" > "$G/idProduct"
echo 0x0200 > "$G/bcdUSB"
echo "$bcd_device" > "$G/bcdDevice"
echo 0xef > "$G/bDeviceClass"
echo 0x02 > "$G/bDeviceSubClass"
echo 0x01 > "$G/bDeviceProtocol"
printf '%s\n' "$manufacturer" > "$G/strings/0x409/manufacturer"
printf '%s\n' "$product_name" > "$G/strings/0x409/product"
echo "$3" > "$G/strings/0x409/serialnumber"
printf '%s\n' "$configuration_name" > "$C/strings/0x409/configuration"
echo "$max_power" > "$C/MaxPower"
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
echo "$iface" > "$S/iface"
ip link set "$iface" up
echo 0 > "/proc/sys/net/ipv6/conf/$iface/disable_ipv6"
ip -4 addr flush dev "$iface" 2>/dev/null || true
ip -6 addr flush dev "$iface" 2>/dev/null || true
mac4=$(printf '%s' "$4" | cut -d: -f5)
mac5=$(printf '%s' "$4" | cut -d: -f6)
ipv4_host="169.254.$((0x$mac4 % 254 + 1)).$((0x$mac5 % 254 + 1))"
ip -4 addr replace "$ipv4_host/16" dev "$iface" || true
link_local=$(link_local_from_mac "$4" 2>/dev/null || true)
if [ -n "$link_local" ]; then
    ip -6 addr replace "$link_local/64" dev "$iface" nodad ||
        ip -6 addr replace "$link_local/64" dev "$iface" || true
fi
touch "$S/ready"
echo "NCM: ready iface=$iface identity=$identity_mode"
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
    if [ -f "$S/peers" ]; then
        while IFS= read -r peer; do
            [ -n "$peer" ] && add_peer_route "$peer" || true
        done < "$S/peers"
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
