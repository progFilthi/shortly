# iOS client

The Swift client, and the parts of it that are load-bearing rather than obvious.

---

## 1. Layout

```text
Shortly/
  App/
    AppModel.swift          @MainActor view model: session, videos, lockout, alerts
    ShortlyApp.swift        entry point and root view
    AppConfiguration.swift  API_BASE_URL from Info.plist
  Networking/
    APIClient.swift         transport. Attaches tokens, retries once, decodes problems
    APIClientError.swift    typed errors, mapped from RFC 9457 problem codes
    SessionManager.swift    owns the session. Refresh, persistence, sign-out
    VideoUploader.swift     direct-to-S3 PUT
  Models/APIModels.swift    wire types, AuthSession, ISO-8601 parsing
  Persistence/SessionStore.swift   Keychain and in-memory stores
  Features/                 Auth, Feed, Upload, Profile views
```

---

## 2. Tokens

The server issues a **15-minute access token** and a **30-day refresh token**. `AuthResponse.token`
is still the access token, so the original `AuthSession { token, userId, username, email }` decodes
unchanged; the refresh fields are additive and Swift's `Decodable` ignores keys it does not know.

`AuthSession` is what gets persisted, and it holds an **absolute** `accessTokenExpiresAt` rather
than the server's relative `expiresIn`. A relative lifetime cannot be compared against a stored
value without knowing when it was stored.

That expiry is normalised to whole seconds in `AuthSession`'s initialiser. The Keychain stores it
as ISO-8601, which has no sub-second component, so a fractional value would not compare equal to
the same session read back from disk. Rounding down is the safe direction: it can only refresh
marginally early.

---

## 3. Refreshing

This is the part worth reading twice, because getting it wrong is silent.

### The problem

A 15-minute token with nothing to renew it is a hard logout every quarter hour. Worse, the app
fires several requests at once on launch, so a naive "on 401, refresh, retry" interceptor issues
one refresh per in-flight request. The server **rotates** refresh tokens and **revokes the family
on replay**, so N parallel refreshes are indistinguishable from a stolen token, and the user gets
signed out.

### The fix

`SessionManager` is an actor that owns the session and serialises refresh:

- **Proactive.** A token inside 60 seconds of expiry is refreshed *before* the request goes out,
  so a token cannot die mid-flight. One extra refresh per 15 minutes is much cheaper than an
  intermittent error the user sees.
- **Single-flight.** One in-flight refresh task; concurrent callers await the same task.
  `testConcurrentRefreshesCollapseIntoOne` fires eight parallel requests and asserts exactly one
  refresh went out.
- **Retry once, then give up.** `APIClient` retries a rejected token exactly once. A second
  rejection clears the session.
- **Prefer the current token.** On rejection, the client first asks for whatever token the session
  has now, because a concurrent request may already have refreshed. Only a *forced* refresh is
  issued when the token has not actually changed, which is the real signal that our view of the
  expiry and the server's disagree.

### `token-expired` versus everything else

The server distinguishes `token-expired` (refreshable) from `token-invalid` (forged or revoked,
not refreshable). The client relies on that split: `token-expired` triggers the retry, every other
401 clears the session. A forged token is not a substitute for an expired one in tests — it takes
the *other* branch, and asserting on it would test the wrong thing.

### A construction cycle

`APIClient` needs a token provider, and the token provider needs an `APIClient` to refresh with.
`SessionRefresherBox` breaks it: the client is built with the box, the manager is built with the
client, then the box is given the manager. The box holds its manager weakly, so the three objects
are collectable together rather than pinning each other.

---

## 4. Errors

Every failure is a typed `APIClientError`, mapped from the server's problem `code`:

| Case | Trigger |
|---|---|
| `.tokenExpired` | `token-expired` — recoverable, retried |
| `.sessionExpired` | any other 401, or a bodiless one — sign in again |
| `.invalidCredentials` | `invalid-credentials` — **not** a reason to sign out |
| `.accountLocked(seconds)` | `account-locked` — carries `retryAfterSeconds` |
| `.validationFailed([String])` | `validation-failed` — every violation, not just the first |
| `.conflict` | `conflicting-state`, `upload-missing`, `username-or-email-taken` |
| `.server` | anything else, with the code when there was one |

Two distinctions carry real weight:

- **A failed sign-in must not clear a valid session.** A mistyped password would otherwise log the
  user out. `requiresSignIn` is therefore true only for `.sessionExpired`.
- **An error body is decoded leniently.** A proxy's HTML 401 must surface as "your session ended",
  not as "the response could not be read".

---

## 5. Timestamps

The server renders `issuedAt` with **nanosecond precision**:
`2026-09-26T08:24:58.103135928Z`.

`JSONDecoder.dateDecodingStrategy.iso8601` **cannot read that**, and fails the entire decode with
no hint about which field broke — every successful sign-in would look like a server error. The
wire format is therefore decoded as a `String` and parsed by `ISO8601.date(from:)`, which tries
fractional seconds and then plain.

---

## 6. Lockout

A `423` sets `AppModel.lockout` with a wall-clock deadline, and the sign-in button stays disabled
until it passes. The countdown text is driven by a `TimelineView` scoped to the banner alone; a
timer on the whole form would re-evaluate every text field once a second for as long as it is
open. The deadline is a date rather than a tick count so it stays correct while backgrounded.

---

## 7. Tests

| Suite | What it covers |
|---|---|
| `APIClientTests` | request shape, problem decoding, nanosecond timestamps, freshness |
| `TokenRefreshTests` | proactive refresh, retry-once, no loops, auth endpoints unauthenticated |
| `SessionManagerTests` | persistence, single-flight refresh, dead-token recovery, sign-out |
| `SessionStoreTests` | Keychain round trip, including the expiry |
| `ShortlyUITests` | form validation, mode switching, the 10-character minimum |
| `LiveBackendTests` | the real client against a running stack |
| `LiveAuthUITests` | register and sign out through the real UI |

```bash
# Unit and UI tests, no backend needed
xcodebuild test -project Shortly.xcodeproj -scheme Shortly \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro'

# The expiry-recovery test waits out a real token lifetime, so it needs a short one.
# Skipped otherwise rather than sitting there for fifteen minutes.
ACCESS_TOKEN_TTL=20s docker compose up -d auth-service
xcodebuild test -project Shortly.xcodeproj -scheme Shortly \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
  -only-testing:ShortlyTests/LiveBackendTests
```

`LiveBackendTests` and `LiveAuthUITests` skip cleanly when the gateway is not answering.

### Two test-environment notes

**`.textContentType(.newPassword)` breaks programmatic typing.** It is the right hint on the
create-account screen — it is what lets iOS offer a generated strong password — but the AutoFill
flow swallows `typeText` after the first character. A field typed 20 characters in arrives holding
one, and the create-account path becomes untestable. Under UI tests only, the content type is
downgraded to `.password`; nothing else about the auth path changes.

**The first tap on a tab is routinely lost.** A freshly created `TabView` reaches the
accessibility tree before it is interactive. `showProfileTab()` therefore waits for the *result*
of the tap rather than sleeping a fixed amount.

---

## 8. Known gaps

| Gap | Impact | Recommendation |
|---|---|---|
| **No upload from the simulator to a real device** | The upload path is unit-tested and the backend E2E covers it, but not from the app. | Run the real flow on a device against LocalStack. |
| **Refresh is invisible to the user** | `activeSessionCount` is shown on the profile, but there is no session list to revoke from. | A "your devices" screen, and a way to sign out one session. |
| **No session expiry is surfaced** | If the refresh token dies, the app returns to sign-in with no explanation. | Distinguish "signed out for safety" from "signed out by you". |
| **No biometric lock** | The Keychain is `WhenUnlockedThisDeviceOnly`, so the token is gone when the device locks, but anyone who unlocks the phone has the app. | `LocalAuthentication` behind a launch gate. |
| **`uploadProgress` is not real** | It jumps between four fixed values rather than tracking bytes. | `URLSessionTaskDelegate` progress, or `URLSession.upload(for:fromFile:)` progress reporting. |
| **No offline handling** | A transport failure surfaces as a raw error string. | Distinguish "no connection" from "server said no", and cache the feed. |
