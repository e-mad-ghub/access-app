#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLEW="$ROOT_DIR/gradlew"
APP_ID="com.easyapps.easypass"
DEBUG_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
RELEASE_AAB="$ROOT_DIR/app/build/outputs/bundle/release/app-release.aab"

usage() {
  cat <<EOF
EasyPass Android helper

Usage:
  ./scripts/android.sh <command>

Build commands:
  build-debug-apk       Build debug APK
  build-release-apk     Build signed release APK
  build-release-aab     Build signed release AAB for Play Console
  build-all             Build debug APK, release APK, and release AAB

Install commands:
  install-debug         Build and install debug APK on the connected phone
  install-release       Build and install signed release APK on the connected phone
  reinstall-debug       Uninstall app, then build and install debug APK
  reinstall-release     Uninstall app, then build and install signed release APK

Device commands:
  devices               List adb devices
  check-device          Verify exactly one connected adb device

Output files:
  Debug APK:    app/build/outputs/apk/debug/app-debug.apk
  Release APK:  app/build/outputs/apk/release/app-release.apk
  Release AAB:  app/build/outputs/bundle/release/app-release.aab

Notes:
  - AAB files are for Play Console upload and cannot be installed directly with adb.
  - Release builds require local.properties and keystore/easypass-upload.jks.
EOF
}

fail() {
  echo "Error: $*" >&2
  exit 1
}

require_gradle() {
  [[ -x "$GRADLEW" ]] || fail "gradlew is missing or not executable."
}

require_adb() {
  command -v adb >/dev/null 2>&1 || fail "adb was not found in PATH."
}

device_serial() {
  require_adb
  local adb_output
  if ! adb_output="$(adb devices 2>&1)"; then
    fail "adb devices failed. Check USB debugging, adb permissions, or run 'adb kill-server' then retry.

$adb_output"
  fi
  mapfile -t devices < <(printf '%s\n' "$adb_output" | awk 'NR > 1 && $2 == "device" {print $1}')
  case "${#devices[@]}" in
    0) fail "no connected adb device found. Connect a phone and enable USB debugging." ;;
    1) printf '%s\n' "${devices[0]}" ;;
    *) printf '%s\n' "${devices[@]}" >&2; fail "multiple adb devices found. Connect only one device or set up a targeted install." ;;
  esac
}

check_release_signing() {
  [[ -f "$ROOT_DIR/local.properties" ]] || fail "local.properties is missing."
  [[ -f "$ROOT_DIR/keystore/easypass-upload.jks" ]] || fail "keystore/easypass-upload.jks is missing."
}

gradle() {
  require_gradle
  "$GRADLEW" "$@"
}

build_debug_apk() {
  gradle :app:assembleDebug
  [[ -f "$DEBUG_APK" ]] || fail "debug APK was not created."
  print_artifact "Debug APK" "$DEBUG_APK"
}

build_release_apk() {
  check_release_signing
  gradle :app:assembleRelease
  [[ -f "$RELEASE_APK" ]] || fail "release APK was not created."
  print_artifact "Release APK" "$RELEASE_APK"
}

build_release_aab() {
  check_release_signing
  gradle :app:bundleRelease
  [[ -f "$RELEASE_AAB" ]] || fail "release AAB was not created."
  print_artifact "Release AAB" "$RELEASE_AAB"
}

print_artifact() {
  local label="$1"
  local path="$2"
  local size
  size="$(du -h "$path" | awk '{print $1}')"
  echo
  echo "$label built successfully"
  echo "Path: $path"
  echo "Size: $size"
  echo
}

install_apk() {
  local apk="$1"
  [[ -f "$apk" ]] || fail "APK not found: $apk"
  local serial
  serial="$(device_serial)"
  echo "Installing on device: $serial"
  adb -s "$serial" install -r "$apk"
}

uninstall_app() {
  local serial
  serial="$(device_serial)"
  echo "Uninstalling $APP_ID from device: $serial"
  adb -s "$serial" uninstall "$APP_ID" || true
}

case "${1:-}" in
  ""|-h|--help|help)
    usage
    ;;
  build-debug-apk)
    build_debug_apk
    ;;
  build-release-apk)
    build_release_apk
    ;;
  build-release-aab)
    build_release_aab
    ;;
  build-all)
    build_debug_apk
    build_release_apk
    build_release_aab
    ;;
  devices)
    require_adb
    adb devices
    ;;
  check-device)
    serial="$(device_serial)"
    echo "Ready: $serial"
    ;;
  install-debug)
    build_debug_apk
    install_apk "$DEBUG_APK"
    ;;
  install-release)
    build_release_apk
    install_apk "$RELEASE_APK"
    ;;
  reinstall-debug)
    uninstall_app
    build_debug_apk
    install_apk "$DEBUG_APK"
    ;;
  reinstall-release)
    uninstall_app
    build_release_apk
    install_apk "$RELEASE_APK"
    ;;
  *)
    usage
    fail "unknown command: $1"
    ;;
esac
