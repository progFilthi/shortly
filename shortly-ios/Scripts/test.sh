#!/bin/zsh
set -euo pipefail

ROOT_DIR="${0:A:h:h}"
SIMULATOR_NAME="${SIMULATOR_NAME:-iPhone 18 Pro}"
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

xcodebuild \
  -project "$ROOT_DIR/Shortly.xcodeproj" \
  -scheme Shortly \
  -configuration Debug \
  -destination "platform=iOS Simulator,name=$SIMULATOR_NAME,OS=latest" \
  -derivedDataPath "$ROOT_DIR/.derivedData" \
  "API_BASE_URL=$API_BASE_URL" \
  test
