# Shortly iOS client

The Swift client for the Shortly backend. See [../docs/ios-client.md](../docs/ios-client.md) for
the design, and [../docs/authentication.md](../docs/authentication.md) for the auth contract it
depends on.

## Requirements

Xcode with the iOS 17 SDK or later. The project targets iOS 17 and Swift 5 language mode.

If `xcodebuild` reports that it needs Xcode, the command line tools are selected instead:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
```

## Running

The client talks to the gateway at `http://localhost:8080`, configured through the
`API_BASE_URL` build setting and read from `Info.plist`. The simulator reaches the host's
`localhost`, so no extra configuration is needed for local development. `Info.plist` already
permits cleartext HTTP for `localhost`.

Start the backend first:

```bash
cd ..
docker compose up -d --build
```

Then run from Xcode, or:

```bash
xcodebuild build -project Shortly.xcodeproj -scheme Shortly \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro'
```

## Tests

```bash
# Unit and UI tests. No backend required; the live suites skip when it is absent.
xcodebuild test -project Shortly.xcodeproj -scheme Shortly \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro'
```

`LiveBackendTests` and `LiveAuthUITests` drive the real client and the real UI against a running
stack. One test waits out a genuine access-token expiry, which at the default 15-minute TTL would
mean a quarter of an hour in a test run, so it is gated on a short TTL and skipped otherwise:

```bash
cd ..
ACCESS_TOKEN_TTL=20s docker compose up -d auth-service
cd shortly-ios
xcodebuild test -project Shortly.xcodeproj -scheme Shortly \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
  -only-testing:ShortlyTests/LiveBackendTests
```

Restore the normal TTL afterwards with `docker compose up -d auth-service`.

## Current scope

Sign up, sign in, silent access-token refresh, sign out, and the video list on the profile screen.
Uploads work against LocalStack but have not been exercised from a physical device. Known gaps are
listed in [../docs/ios-client.md §8](../docs/ios-client.md).
