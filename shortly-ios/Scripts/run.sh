#!/bin/zsh
set -euo pipefail

ROOT_DIR="${0:A:h:h}"
SIMULATOR_NAME="${SIMULATOR_NAME:-iPhone 18 Pro}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

"$ROOT_DIR/Scripts/build.sh"
xcrun simctl boot "$SIMULATOR_NAME" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$SIMULATOR_NAME" -b
xcrun simctl install "$SIMULATOR_NAME" "$ROOT_DIR/.derivedData/Build/Products/Debug-iphonesimulator/Shortly.app"
xcrun simctl launch "$SIMULATOR_NAME" com.shortly.ios
