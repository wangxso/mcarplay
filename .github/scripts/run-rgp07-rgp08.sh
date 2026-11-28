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

# macOS runners have no GNU timeout (coreutils is not installed), so the RGP08 bound
# must not depend on it alone. Prefer timeout, then Homebrew's gtimeout, and otherwise
# supervise the command here while keeping GNU timeout's exit code 124 on expiry.
run_with_timeout() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$seconds" "$@"
    return $?
  fi
  if command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$seconds" "$@"
    return $?
  fi
  local fired status watchdog
  fired="$(mktemp)"
  "$@" &
  local child=$!
  (
    sleep "$seconds"
    echo fired >"$fired"
    kill -TERM "$child" 2>/dev/null || true
    sleep 2
    kill -KILL "$child" 2>/dev/null || true
  ) >/dev/null 2>&1 &
  watchdog=$!
  wait "$child" 2>/dev/null
  status=$?
  kill "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true
  if [[ -s "$fired" ]]; then
    status=124
  fi
  rm -f "$fired"
  return "$status"
}

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

# 慢速模拟器（嵌套虚拟化的 macOS runner）上 adb install 偶尔以
# INSTALL_FAILED_VERIFICATION_FAILURE（Integrity verification timed out）失败；
# 这不是 APK 或测试的问题。重试安装，第二次改用非流式（push + pm install）路径，
# 并在重试前再次关闭安装校验。安装是幂等的，重试不改变测试语义。
install_apk() {
  local apk="$1"
  local label="$2"
  local attempt mode
  for attempt in 1 2 3; do
    if [[ "$attempt" -eq 2 ]]; then
      mode="--no-streaming"
    else
      mode="streaming"
    fi
    local installed=0
    if [[ "$mode" == "--no-streaming" ]]; then
      if adb install -r --no-streaming "$apk"; then installed=1; fi
    else
      if adb install -r "$apk"; then installed=1; fi
    fi
    if [[ "$installed" -eq 1 ]]; then
      echo "$label install: Success (attempt $attempt, $mode)"
      return 0
    fi
    echo "$label install attempt $attempt failed ($mode)" >&2
    adb shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1 || true
    adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true
    sleep 15
  done
  echo "$label install failed after 3 attempts" >&2
  return 1
}

# 嵌套虚拟化的 macOS runner 上 am instrument 偶尔在测试真正开始前就崩溃
# （INSTRUMENTATION_RESULT: shortMsg=Process crashed.），崩溃场景在 normal/legacy/corrupt
# 之间随机漂移，同一 SHA 的其他运行和本地运行都能通过，属于模拟器进程启动抖动而非断言失败。
# 判定标准保持不变（必须报告 OK (1 test)）；仅在崩溃且没有任何测试结论时重试一次，
# 并在每次失败时转储 logcat，便于从 artifact 里确认原因。
instrumentation_crashed_without_result() {
  local output="$1"
  [[ -s "$output" ]] || return 1
  grep -q "shortMsg=Process crashed" "$output" || return 1
  ! grep -qE "^(OK \(|FAILURES)" "$output"
}

run_phase() {
  local label="$1"
  local phase_class="$2"
  local marker="$3"
  local scenario="$4"
  local output="$5"
  local attempt status
  for attempt in 1 2; do
    status=0
    if ! adb shell am instrument \
      -e class "$phase_class" \
      -e marker "$marker" \
      -e scenario "$scenario" \
      -w "$INSTRUMENTATION_TARGET" | tee "$output"; then
      status=1
    fi
    if [[ "$status" -eq 0 ]] && grep -q "OK (1 test)" "$output"; then
      return 0
    fi
    adb shell logcat -d -t 3000 >"${output%.txt}-attempt${attempt}.logcat.txt" 2>/dev/null || true
    if [[ "$attempt" -eq 1 ]] && instrumentation_crashed_without_result "$output"; then
      echo "$label: instrumentation process crashed before reporting a result; retrying once" >&2
      sleep 10
      continue
    fi
    if [[ "$status" -ne 0 ]]; then
      echo "$label failed: $scenario" >&2
    else
      echo "$label did not report one passing test: $scenario" >&2
    fi
    return 1
  done
  return 1
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
install_apk "$APP_APK" "app"
install_apk "$ANDROID_TEST_APK" "androidTest"
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
  if ! run_phase "RGP07 phase1" "$PHASE1_CLASS" "$marker" "$scenario" "$phase1_output"; then
    exit 1
  fi
  adb shell am force-stop "$PACKAGE_ID"
  if ! run_phase "RGP07 phase2" "$PHASE2_CLASS" "$marker" "$scenario" "$phase2_output"; then
    exit 1
  fi
  clear_package "post-$scenario"
done
rgp07_elapsed="$(( $(date +%s) - rgp07_start ))"

mkdir -p "$OUTPUT_DIR/rgp08"
echo "=== RGP08 cross-process prefs ==="
rgp08_start="$(date +%s)"
rgp08_run_id="${MARKER_PREFIX}-${RGP08_RUN_ATTEMPT}"
rgp08_marker="${rgp08_run_id}-rgp08"
rgp08_ok=1
rgp08_output="$OUTPUT_DIR/rgp08/rgp08.txt"
for rgp08_attempt in 1 2; do
  rgp08_attempt_output="${rgp08_output%.txt}-attempt${rgp08_attempt}.txt"
  clear_package "pre-RGP08 attempt $rgp08_attempt"
  adb shell am force-stop "$PACKAGE_ID"
  rgp08_status=0
  if run_with_timeout "$RGP08_TIMEOUT_SECONDS" \
    adb shell am instrument \
    -e class "$RGP08_CLASS" \
    -e marker "$rgp08_marker" \
    -e runId "$rgp08_run_id" \
    -w "$INSTRUMENTATION_TARGET" | tee "$rgp08_attempt_output"; then
    rgp08_status=0
  else
    rgp08_status=$?
  fi
  cp "$rgp08_attempt_output" "$rgp08_output"
  if [[ "$rgp08_status" -eq 0 ]] && grep -q "OK (1 test)" "$rgp08_attempt_output"; then
    rgp08_ok=0
    break
  fi
  adb shell logcat -d -t 3000 >"${rgp08_attempt_output%.txt}.logcat.txt" 2>/dev/null || true
  if [[ "$rgp08_attempt" -eq 1 ]] && instrumentation_crashed_without_result "$rgp08_attempt_output"; then
    echo "RGP08: instrumentation process crashed before reporting a result; retrying once" >&2
    sleep 10
    continue
  fi
  break
done
if [[ "$rgp08_status" -ne 0 || "$rgp08_ok" -ne 0 ]]; then
  printf 'RGP08 failed incremental elapsed: %ss\n' \
    "$(( $(date +%s) - rgp08_start ))" | tee "$OUTPUT_DIR/rgp08/failed-timing.txt"
  adb shell logcat -d -t 2000 > "$OUTPUT_DIR/rgp08/failed-logcat.txt" || true
  if [[ "$rgp08_status" -ne 0 ]]; then
    echo "RGP08 instrumentation failed with exit $rgp08_status" >&2
    exit "$rgp08_status"
  fi
  echo "RGP08 did not report one passing test" >&2
  exit 1
fi
rgp08_result="$(sed -n 's/.*INSTRUMENTATION_STATUS: rgp08_output=\({.*}\).*/\1/p' "$rgp08_output" | tail -1 | tr -d '\r')"
if [[ -z "$rgp08_result" ]]; then
  echo "Missing rgp08_output status bundle" >&2
  exit 1
fi
rgp08_json="$OUTPUT_DIR/rgp08/rgp08.json"
python3 -c 'import json,sys; print(json.dumps(json.loads(sys.argv[1]), ensure_ascii=False, indent=2))' \
  "$rgp08_result" > "$rgp08_json"
python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); marker=data["marker"]; expected=sys.argv[2]; assert marker == expected, (marker, expected)' \
  "$rgp08_json" "$rgp08_marker"
rgp08_elapsed="$(( $(date +%s) - rgp08_start ))"
printf 'RGP07 incremental elapsed: %ss\nRGP08 incremental elapsed: %ss\nTotal script elapsed: %ss\n' \
  "$rgp07_elapsed" "$rgp08_elapsed" "$(( $(date +%s) - start_time ))" | tee "$OUTPUT_DIR/timing.txt"
