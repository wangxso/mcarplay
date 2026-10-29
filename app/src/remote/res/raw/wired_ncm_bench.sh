#!/system/bin/sh
set -eu
# The NCM function carries CarPlay media. The FunctionFS function and helper
# provide the minimal Apple usbmux/lockdown/carkit device path used by MacPlay.
G=/config/usb_gadget/g2
C=$G/configs/b.1
F=$G/functions/ncm.mcarplay_bench
L=$C/ncm.mcarplay_bench
M=$G/functions/ffs.usbmux_bench
ML=$C/ffs.usbmux_bench
MD=/dev/usb-ffs/usbmux_bench
S=/data/local/tmp/mcarplay-ncm-bench-state
PREF=8997
TABLE=742
APP=com.mcarplay.app.rd
FILES="idVendor idProduct bcdUSB bcdDevice bDeviceClass bDeviceSubClass bDeviceProtocol strings/0x409/manufacturer strings/0x409/product strings/0x409/serialnumber configs/b.1/MaxPower configs/b.1/bmAttributes configs/b.1/strings/0x409/configuration"

# HyperOS Greezer freezes background uids while another app launches and does
# not always thaw them afterwards, which stalls the media sender mid-session.
# Only state recorded here is undone on restore.
protect_app() {
    uid=$(stat -c %u "/data/user/0/$APP" 2>/dev/null || true)
    [ -n "$uid" ] || return 0
    echo "$uid" > "$S/app_uid"
    if cmd greezer unmonitor "$uid" >/dev/null 2>&1; then
        touch "$S/greezer_unmonitored"
    fi
    if ! dumpsys deviceidle whitelist 2>/dev/null | grep -q ",$APP,"; then
        dumpsys deviceidle whitelist +"$APP" >/dev/null 2>&1 && touch "$S/idle_whitelisted"
    fi
    echo "GREEZER: protected uid=$uid"
}

thaw_app() {
    uid=$(cat "$S/app_uid" 2>/dev/null || true)
    [ -n "$uid" ] || return 0
    for events in /sys/fs/cgroup/apps/uid_"$uid"/pid_*/cgroup.events; do
        [ -f "$events" ] && grep -q '^frozen 1' "$events" || continue
        cmd greezer thuid "$uid" 1000 >/dev/null 2>&1 || true
        if grep -q '^frozen 1' "$events"; then
            echo 0 > "${events%/cgroup.events}/cgroup.freeze" 2>/dev/null || true
        fi
        echo "GREEZER: thawed ${events%/cgroup.events}"
    done
}

unprotect_app() {
    uid=$(cat "$S/app_uid" 2>/dev/null || true)
    if [ -n "$uid" ] && [ -f "$S/greezer_unmonitored" ]; then
        cmd greezer monitor "$uid" >/dev/null 2>&1 || true
    fi
    if [ -f "$S/idle_whitelisted" ]; then
        dumpsys deviceidle whitelist -"$APP" >/dev/null 2>&1 || true
    fi
}

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
    # Deleting and re-adding the rule every second opens a window where
    # unconnected UDP sends to the peer fail with ENETUNREACH.
    if ip -"$family" rule show pref "$PREF" 2>/dev/null | grep -Fq "to $host lookup $TABLE"; then
        return 0
    fi
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

ensure_iface_addresses() {
    iface=$1
    device_mac=${2:-$(cat "$S/device_mac" 2>/dev/null || true)}
    ip link set "$iface" up || return 1
    echo 0 > "/proc/sys/net/ipv6/conf/$iface/disable_ipv6"
    ipv4_host=$(ipv4_from_mac "$device_mac" 2>/dev/null || true)
    link_local=$(link_local_from_mac "$device_mac" 2>/dev/null || true)
    [ -n "$ipv4_host" ] && ip -4 addr replace "$ipv4_host/16" dev "$iface" || true
    if [ -n "$link_local" ]; then
        ip -6 addr replace "$link_local/64" dev "$iface" nodad ||
            ip -6 addr replace "$link_local/64" dev "$iface" || true
    fi
}

iface_diagnostics() {
    iface=$(cat "$S/iface" 2>/dev/null || cat "$F/ifname" 2>/dev/null || true)
    mux_pid=$(cat "$S/usbmux_pid" 2>/dev/null || true)
    carrier=$(cat "/sys/class/net/$iface/carrier" 2>/dev/null || true)
    echo "NCM: diagnostics iface=${iface:-missing} carrier=${carrier:-unknown}"
    [ -n "$iface" ] && ip link show dev "$iface" 2>/dev/null || true
    [ -n "$iface" ] && ip -4 addr show dev "$iface" 2>/dev/null || true
    [ -n "$iface" ] && ip -6 addr show dev "$iface" 2>/dev/null || true
    if [ -n "$mux_pid" ]; then
        if kill -0 "$mux_pid" 2>/dev/null; then
            echo "USBMUX: helper_pid=$mux_pid alive=true"
        else
            echo "USBMUX: helper_pid=$mux_pid alive=false"
        fi
    else
        echo 'USBMUX: helper_pid=missing'
    fi
    tail -80 /data/local/tmp/mcarplay-ncm-bench.log 2>/dev/null || true
}

require_usb_device_mode() {
    port_status=$(dumpsys usb 2>/dev/null | sed -n '/port_manager=/,/alsa_manager=/p')
    if [ -n "$port_status" ]; then
        case "$port_status" in
            *"connected=true"*"current_mode=ufp"*) ;;
            *)
                echo 'NCM: USB port is not connected in device mode'
                return 1
                ;;
        esac
        return 0
    fi

    # Older vendor USB stacks may not expose the port_manager block.
    grep -q '\[device\]' /sys/class/typec/port0/data_role
}

restore() {
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ] || return 1
    iface=$(cat "$F/ifname" 2>/dev/null || cat "$S/iface" 2>/dev/null || true)
    unprotect_app
    if [ -f "$S/usbmux_pid" ]; then
        mux_pid=$(cat "$S/usbmux_pid" 2>/dev/null || true)
        if [ -n "$mux_pid" ] && kill -0 "$mux_pid" 2>/dev/null; then
            kill -TERM "$mux_pid" 2>/dev/null || true
            i=0
            while kill -0 "$mux_pid" 2>/dev/null && [ "$i" -lt 50 ]; do
                sleep 0.1
                i=$((i + 1))
            done
            kill -KILL "$mux_pid" 2>/dev/null || true
        fi
    fi
    while ip -6 rule del pref "$PREF" 2>/dev/null; do :; done
    while ip -4 rule del pref "$PREF" 2>/dev/null; do :; done
    ip -6 route flush table "$TABLE" 2>/dev/null || true
    ip -4 route flush table "$TABLE" 2>/dev/null || true
    echo '' > "$G/UDC" || true
    if [ -L "$L" ]; then
        rm -f "$L"
    fi
    if [ -L "$ML" ]; then
        rm -f "$ML"
    fi
    if [ -n "$iface" ] && [ -d "/sys/class/net/$iface" ]; then
        ip -4 addr flush dev "$iface" 2>/dev/null || true
        ip -6 addr flush dev "$iface" 2>/dev/null || true
        ip link set "$iface" down 2>/dev/null || true
    fi
    umount "$MD" 2>/dev/null || true
    rmdir "$MD" 2>/dev/null || true
    rmdir "$F" "$M" 2>/dev/null || true
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
    i=0
    missing_owner=0
    while [ "$i" -lt 300 ]; do
        if [ ! -f "$S/owner" ]; then
            missing_owner=$((missing_owner + 1))
            if [ "$missing_owner" -ge 50 ]; then
                iface_diagnostics
                exit 1
            fi
            sleep 0.1
            i=$((i + 1))
            continue
        fi
        [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ] || {
            echo "NCM: state directory is owned by another process"
            exit 1
        }
        missing_owner=0
        if [ -f "$S/bound" ]; then
            iface=$(cat "$S/iface" 2>/dev/null || true)
            if [ -n "$iface" ] && [ -d "/sys/class/net/$iface" ]; then
                echo "$iface"
                cat "/sys/class/net/$iface/ifindex"
                exit 0
            fi
        fi
        sleep 0.1
        i=$((i + 1))
    done
    iface_diagnostics
    exit 1
    ;;
wait-address)
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ]
    iface=$(cat "$S/iface" 2>/dev/null || true)
    if [ -z "$iface" ] || [ ! -d "/sys/class/net/$iface" ]; then
        iface_diagnostics
        exit 1
    fi
    i=0
    while [ "$i" -lt 1200 ]; do
        carrier=$(cat "/sys/class/net/$iface/carrier" 2>/dev/null || true)
        if [ "$carrier" = 1 ]; then
            ensure_iface_addresses "$iface" || true
            ipv6=$(cat /proc/net/if_inet6 2>/dev/null | grep " $iface$" || true)
            ipv4=$(ip -4 addr show dev "$iface" 2>/dev/null | awk '/inet / { split($2, a, "/"); print a[1]; exit }')
            if [ -n "$ipv6" ] || [ -n "$ipv4" ]; then
                echo "$iface"
                cat "/sys/class/net/$iface/ifindex"
                echo "$ipv6"
                [ -n "$ipv4" ] && echo "IPV4=$ipv4"
                link_local=$(link_local_from_mac "$(cat "$S/device_mac")" 2>/dev/null || true)
                [ -n "$link_local" ] && echo "LINKLOCAL=$link_local"
                exit 0
            fi
        fi
        sleep 0.1
        i=$((i + 1))
    done
    iface_diagnostics
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
start-session)
    [ -f "$S/owner" ] && [ "$(cat "$S/owner")" = mcarplay-ncm-bench-v1 ]
    [ -f "$S/carplay-start" ] || exit 3
    cat "$S/carplay-start"
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
*) echo 'NCM: expected up, wait-iface, wait-address, peer, peers, start-session or down'; exit 1;;
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
require_usb_device_mode
umask 077
mkdir "$S"
echo mcarplay-ncm-bench-v1 > "$S/owner"
echo $$ > "$S/pid"
echo "$identity_mode" > "$S/identity"
echo "$4" > "$S/device_mac"
trap 'restore' EXIT
timerpid=''
mux_pid=''
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
mkdir "$M"
mkdir -p "$MD"
mount -t functionfs usbmux_bench "$MD"
ln -s "$M" "$ML"
class_path=$(pm path "$APP" | sed -n 's/^package://p' | head -n 1)
[ -f "$class_path" ]
protect_app
CLASSPATH="$class_path" app_process /system/bin com.mcarplay.app.usb.root.WiredUsbMuxMain \
    serve --ep0 "$MD/ep0" --ready "$S/usbmux-ready" --transport-id "$3" \
    --udc "$udc" --udc-file "$G/UDC" --start-file "$S/carplay-start" &
mux_pid=$!
echo "$mux_pid" > "$S/usbmux_pid"
i=0
while [ ! -f "$S/usbmux-ready" ]; do
    kill -0 "$mux_pid" 2>/dev/null || {
        tail -80 /data/local/tmp/mcarplay-ncm-bench.log 2>/dev/null || true
        exit 1
    }
    [ "$i" -lt 50 ] || {
        echo 'USBMUX: FunctionFS helper did not become ready'
        exit 1
    }
    sleep 0.1
    i=$((i + 1))
done
setprop sys.usb.config none
sleep 1
for gadget in /config/usb_gadget/*; do
    [ "$gadget" = "$G" ] && continue
    [ -n "$(cat "$gadget/UDC")" ] && echo '' > "$gadget/UDC"
done
echo "$udc" > "$G/UDC"
iface=''
i=0
while [ -z "$iface" ] && [ "$i" -lt 50 ]; do
    iface=$(cat "$F/ifname" 2>/dev/null || true)
    [ -n "$iface" ] || sleep 0.1
    i=$((i + 1))
done
[ -n "$iface" ] && [ -d "/sys/class/net/$iface" ]
echo "$iface" > "$S/iface"
touch "$S/bound"
ip -4 addr flush dev "$iface" 2>/dev/null || true
ip -6 addr flush dev "$iface" 2>/dev/null || true
ensure_iface_addresses "$iface" "$4"
touch "$S/ready"
echo "NCM: ready iface=$iface identity=$identity_mode usbmux=$mux_pid"
if [ -n "${6:-}" ]; then
    sleep 3
    # The Mac may still be configuring its NCM side; the loop below retries.
    sh /data/local/tmp/mcarplay-ncm-bench.sh peer "$6" || echo "NCM: peer route deferred for $6"
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
    thaw_app
    if [ "$launch_bench" = 1 ] && [ -n "${6:-}" ] && [ "$launched" = 0 ] &&
        [ "$(cut -d. -f1 /proc/uptime)" -ge "$((began + 6))" ]; then
        am start --user 0 -n "$APP"/com.mcarplay.app.bridge.UsbMediaBenchActivity \
            --es interface "$iface" --es host "$6" --ei port 17000 \
            --ei scope "$(cat /sys/class/net/$iface/ifindex)" &
        launched=1
    fi
    sleep 1 &
    timerpid=$!
    wait "$timerpid" || true
done
