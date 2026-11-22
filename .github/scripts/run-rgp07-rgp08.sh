#!/usr/bin/env bash
set -euo pipefail

PACKAGE_ID="${PACKAGE_ID:?PACKAGE_ID is required}"
INSTRUMENTATION_TARGET="${INSTRUMENTATION_TARGET:?INSTRUMENTATION_TARGET is required}"
PHASE1_CLASS="${PHASE1_CLASS:?PHASE1_CLASS is required}"
PHASE2_CLASS="${PHASE2_CLASS:?PHASE2_CLASS is required}"
RGP08_CLASS="${RGP08_CLASS:?RGP08_CLASS is required}"
MARKER_PREFIX="${MARKER_PREFIX:?MARKER_PREFIX is required}"
RGP08_RUN_ATTEMPT="${RGP08_RUN_ATTEMPT:?RGP08_RUN_ATTEMPT is required}"
RGP08_TIMEOUT_SECONDS="${RGP08_TIMEOUT_SECONDS:?RGP08_TIMEOUT_SECONDS is required}"
EXPECTED_SERIAL="${EXPECTED_SERIAL:?EXPECTED_SERIAL is required}"
OUTPUT_DIR="${OUTPUT_DIR:?OUTPUT_DIR is required}"
APP_APK="${APP_APK:?APP_APK is required}"
ANDROID_TEST_APK="${ANDROID_TEST_APK:?ANDROID_TEST_APK is required}"

if ! adb -s "$EXPECTED_SERIAL" get-state | grep -qx device; then
  echo "Expected emulator $EXPECTED_SERIAL is not ready" >&2
  exit 1
fi
qemu_state="$(adb -s "$EXPECTED_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
qemu_boot_state="$(adb -s "$EXPECTED_SERIAL" shell getprop ro.boot.qemu | tr -d '\r')"
if [[ "$qemu_state" != "1" && "$qemu_boot_state" != "1" ]]; then
  echo "Expected serial $EXPECTED_SERIAL is not an emulator" >&2
  exit 1
fi

adb() {
  command adb -s "$EXPECTED_SERIAL" "$@"
}

clear_package() {
  local label="$1"
  local output status
  if output="$(adb shell pm clear "$PACKAGE_ID" 2>&1)"; then
    status=0
  else
    status=$?
  fi
  if [[ "$status" -ne 0 ]]; then
    echo "$label pm clear failed with exit $status: $output" >&2
    return 1
  fi
  if [[ "$output" != "Success" ]]; then
    echo "$label pm clear output was '$output', expected Success" >&2
    return 1
  fi
  echo "$label pm clear: Success"
}

cleanup() {
  local original_status="$?"
  trap - EXIT
  local cleanup_status=0
  clear_package "cleanup trap" || cleanup_status=$?
  echo "cleanup original_status=$original_status cleanup_status=$cleanup_status" >&2
  if [[ "$original_status" -ne 0 ]]; then
    exit "$original_status"
  fi
  if [[ "$cleanup_status" -ne 0 ]]; then
    exit "$cleanup_status"
  fi
}
trap cleanup EXIT

start_time="$(date +%s)"
adb devices -l
adb install -r "$APP_APK"
adb install -r "$ANDROID_TEST_APK"
adb shell pm list packages "$PACKAGE_ID"
clear_package "pre-RGP07"

mkdir -p "$OUTPUT_DIR"
rgp07_start="$(date +%s)"
for scenario in normal legacy corrupt partial; do
  marker="${MARKER_PREFIX}-${scenario}"
  mkdir -p "$OUTPUT_DIR/$scenario"
  phase1_output="$OUTPUT_DIR/$scenario/phase1.txt"
  phase2_output="$OUTPUT_DIR/$scenario/phase2.txt"
  echo "=== scenario: $scenario; marker: $marker ==="
  adb shell am force-stop "$PACKAGE_ID"
  if ! adb shell am instrument \
    -e class "$PHASE1_CLASS" \
    -e marker "$marker" \
    -e scenario "$scenario" \
    -w "$INSTRUMENTATION_TARGET" | tee "$phase1_output"; then
    echo "RGP07 phase1 failed: $scenario" >&2
    exit 1
  fi
  if ! grep -q "OK (1 test)" "$phase1_output"; then
    echo "RGP07 phase1 did not report one passing test: $scenario" >&2
    exit 1
  fi
  adb shell am force-stop "$PACKAGE_ID"
  if ! adb shell am instrument \
    -e class "$PHASE2_CLASS" \
    -e marker "$marker" \
    -e scenario "$scenario" \
    -w "$INSTRUMENTATION_TARGET" | tee "$phase2_output"; then
    echo "RGP07 phase2 failed: $scenario" >&2
    exit 1
  fi
  if ! grep -q "OK (1 test)" "$phase2_output"; then
    echo "RGP07 phase2 did not report one passing test: $scenario" >&2
    exit 1
  fi
  clear_package "post-$scenario"
done
rgp07_elapsed="$(( $(date +%s) - rgp07_start ))"

mkdir -p "$OUTPUT_DIR/rgp08"
echo "=== RGP08 cross-process prefs ==="
rgp08_start="$(date +%s)"
rgp08_marker="${MARKER_PREFIX}-rgp08-${RGP08_RUN_ATTEMPT}"
clear_package "pre-RGP08"
adb shell am force-stop "$PACKAGE_ID"
timeout "$RGP08_TIMEOUT_SECONDS" \
  adb shell am instrument \
  -e class "$RGP08_CLASS" \
  -e marker "$rgp08_marker" \
  -e runId "${MARKER_PREFIX}-${RGP08_RUN_ATTEMPT}" \
  -w "$INSTRUMENTATION_TARGET" | tee "$OUTPUT_DIR/rgp08/rgp08.txt"
rgp08_status=${PIPESTATUS[0]}
if [[ "$rgp08_status" -ne 0 ]]; then
  printf 'RGP08 failed incremental elapsed: %ss\n' \
    "$(( $(date +%s) - rgp08_start ))" | tee "$OUTPUT_DIR/rgp08/failed-timing.txt"
  adb shell logcat -d -t 2000 > "$OUTPUT_DIR/rgp08/failed-logcat.txt" || true
  echo "RGP08 instrumentation failed with exit $rgp08_status" >&2
  exit "$rgp08_status"
fi
if ! grep -q "OK (1 test)" "$OUTPUT_DIR/rgp08/rgp08.txt"; then
  echo "RGP08 did not report one passing test" >&2
  exit 1
fi
rgp08_output="$(sed -n 's/.*INSTRUMENTATION_STATUS: rgp08_output=\({.*}\).*/\1/p' "$OUTPUT_DIR/rgp08/rgp08.txt" | tail -1 | tr -d '\r')"
if [[ -z "$rgp08_output" ]]; then
  echo "Missing rgp08_output status bundle" >&2
  exit 1
fi
rgp08_json="$OUTPUT_DIR/rgp08/rgp08.json"
python3 -c 'import json,sys; print(json.dumps(json.loads(sys.argv[1]), ensure_ascii=False, indent=2))' \
  "$rgp08_output" > "$rgp08_json"
python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); marker=data["marker"]; expected=sys.argv[2]; assert marker == expected, (marker, expected)' \
  "$rgp08_json" "$rgp08_marker"
rgp08_elapsed="$(( $(date +%s) - rgp08_start ))"
printf 'RGP07 incremental elapsed: %ss\nRGP08 incremental elapsed: %ss\nTotal script elapsed: %ss\n' \
  "$rgp07_elapsed" "$rgp08_elapsed" "$(( $(date +%s) - start_time ))" | tee "$OUTPUT_DIR/timing.txt"
