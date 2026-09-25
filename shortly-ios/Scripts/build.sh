#!/bin/zsh
set -euo pipefail

ROOT_DIR="${0:A:h:h}"
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

xcodebuild \
  -project "$ROOT_DIR/Shortly.xcodeproj" \
  -scheme Shortly \
  -configuration Debug \
  -destination "generic/platform=iOS Simulator" \
  -derivedDataPath "$ROOT_DIR/.derivedData" \
  "API_BASE_URL=$API_BASE_URL" \
  build
