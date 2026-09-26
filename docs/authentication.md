# Authentication

How identity works across the platform, and the reasoning behind the parts that are easy to get
subtly wrong.

---

## 1. Shape

```
  iOS client
     │  POST /api/v1/auth/register  or  /login
     ▼
  api-gateway :8080
     │  verifies the access token with the shared JwtCodec
     │  overwrites X-User-Id / X-Username / X-Email from the token's claims
     │  strips any inbound copy of those headers, and of X-Gateway-Secret
     │  adds X-Gateway-Secret from gateway.internal-secret
     ▼
  auth-service :8081  ─┐
  video-service :8082  ─┼─ every service requires X-Gateway-Secret
  ...                 ─┘
```

**The gateway is the only supported entry point.** Two mechanisms enforce that, and both are
needed:

1. **The gateway overwrites the identity headers** from the verified token. A client that sends
   its own `X-User-Id` is asking to be someone else, and what it sent is discarded.
2. **Every service requires `X-Gateway-Secret`**, which the gateway adds from configuration and
   strips from inbound traffic. Without this, anyone who can reach a service's port directly sets
   the header to whoever they like, and every ownership check downstream is theatre.

`video-service` goes further: controllers take an injected `CallerIdentity` rather than reading
`X-User-Id` themselves, so no controller can be written that quietly bypasses the check.

---

## 2. Token design

| | Access token | Refresh token |
|---|---|---|
| Format | JWT | Opaque, 256-bit random |
| Lifetime | **15 min** | 30 days |
| Storage | none (stateless) | SHA-256 hash in `refresh_tokens` |
| Revocable before expiry | **no** | yes |
| Rotated on use | n/a | **yes** |

**Access tokens are short because they cannot be revoked.** Verification has to be stateless or
every request touches a database, so the expiry *is* the revocation policy. The previous value in
this repo was 24 hours, which is the gap between "cheap" and "worth stealing".

**Refresh tokens are opaque so they can be revoked.** A JWT refresh token needs a server-side
denylist to invalidate, and a denylist consulted on every refresh is the database this design was
avoiding. Opaque tokens are looked up by hash, so revocation is a column update and detection is a
query.

**Stored as SHA-256, not bcrypt.** The input is 256 bits of CSPRNG output, so there is no
dictionary to attack and a deliberately slow hash would only add latency. A database leak must not
yield working credentials, which hashing achieves.

### Rotation and reuse detection

Every refresh consumes the presented token and issues a new one in the same **family**.

| Situation | Response | Why |
|---|---|---|
| Replay within the grace window (20 s) | Tolerated; the session survives | Almost certainly a client retrying after a lost response. Without this, a dropped connection on cellular burns the only copy and the user looks logged out at random. |
| Replay after the grace window | **Whole family revoked**, `401` | Two parties hold the token and there is no way to tell which is the real client. The only safe move is to end the session for both. |
| Revoked token presented | Family revoked again, `401` | Covers replay after logout. |

Two implementation details that are load-bearing, and both were bugs first:

- **The family revocation must run in a separate bean on `REQUIRES_NEW`.** `rotate` revokes and
  then throws; inside the caller's transaction that rollback discards the revocation, leaving the
  token we just distrusted working. It is a separate bean because `@Transactional` works through
  a proxy and a self-invocation inside `RefreshTokenService` bypasses it — the annotation is
  present, the comment says it is load-bearing, and it does nothing. Found by the iOS client's
  integration test, not by any Java test: a mocked repository has no transaction to lose.
- **The revocation is a bulk `@Modifying` update**, not load-and-iterate, so reuse detection is one
  round trip however many rows the family has.

**What a live test showed.** With a genuinely broken revocation, replaying a rotated token
outside the grace window returned `401` for the replayed token while the attacker's *replacement*
token kept working. Detection worked; containment did not. `RefreshTokenRevocationTest` now guards
the annotation and the routing so the shape cannot come back quietly, and `LiveBackendTests` plus
`scripts/e2e-auth-test.sh` assert the behaviour against a real database.

---

## 3. Sign-in hardening

| Control | Value | Notes |
|---|---|---|
| Password | ≥ 10 chars, ≤ 128 | Length only. Composition rules mostly produce `Passw0rd!`. |
| Hashing | BCrypt cost 12 | ~250 ms per hash, the usual balance between offline-cracking cost and sign-in throughput. |
| Failed attempts | 5 → lock | Per account, not per IP: a brute-force run against one account comes from many addresses, and a per-IP limiter misses it entirely. |
| Lockout | 15 min | Reported as `423` with `retryAfterSeconds`, so the client can show a countdown. |
| Lockout expiry | Lazy | No sweeper. The field is only read when it already blocks. |
| Unknown accounts | Password still hashed | Otherwise response timing enumerates the user table. Asserted directly with a spy, not left to a comment. |

`LoginAttemptService` exists solely because of transaction semantics: the failure counter must
commit independently of the exception that accompanies it, so it runs in `REQUIRES_NEW` from a
separate bean (a self-invoked `REQUIRES_NEW` would silently join the caller's transaction).

---

## 4. Error contract

Every service returns RFC 9457 problem documents with a `code` from the shared
`com.shortly.contracts.errors.ApiError` enum, so a client branches on the code rather than parsing
prose, and does not care which service answered.

```json
{
  "type": "https://shortly.dev/problems/account-locked",
  "title": "account-locked",
  "status": 423,
  "detail": "Account is temporarily locked. Try again in 900 seconds.",
  "instance": "/api/v1/auth/login",
  "code": "account-locked",
  "retryAfterSeconds": 900
}
```

Codes are kebab-case and stable. Renaming one is a breaking API change.

| Code | Status | Extra properties |
|---|---|---|
| `validation-failed` | 400 | `violations`, `fieldErrors` |
| `malformed-request` | 400 | — |
| `unauthenticated` | 401 | — |
| `token-expired` | 401 | — |
| `token-invalid` | 401 | — |
| `refresh-token-invalid` | 401 | — |
| `invalid-credentials` | 401 | — |
| `access-denied` | 403 | — |
| `gateway-secret-invalid` | 403 | — |
| `username-or-email-taken` | 409 | — |
| `conflicting-state` | 409 | `currentStatus` |
| `upload-missing` / `upload-too-large` | 409 / 413 | `actualBytes`, `maxBytes` |
| `account-locked` | 423 | `retryAfterSeconds` |
| `internal-error` | 500 | `traceId` |

**`token-expired` and `token-invalid` are separate codes on purpose.** Both are 401, but the
recovery differs — refresh and retry versus sign in again — and a client that cannot tell them
apart cannot recover on its own.

**Security failures never leak internals.** An unexpected exception returns a fresh `traceId` and
a generic message; the message and stack trace go to the log under that id. The 5xx detail
routinely contains SQL, file paths, or caller-supplied input.

**Rejections from the security layer also carry a body.** Spring's default entry point returns a
bodiless 401, which is the one shape a client cannot parse and the most common one a mobile client
meets. `ProblemAuthenticationEntryPoint` writes the same document the exception handler does.

---

## 5. Endpoints

| Method | Path | Auth | Success |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | none | `201` + token pair |
| `POST` | `/api/v1/auth/login` | none | `200` + token pair |
| `POST` | `/api/v1/auth/refresh` | refresh token in body | `200` + new pair |
| `POST` | `/api/v1/auth/logout` | refresh token in body | `204` |
| `POST` | `/api/v1/auth/logout` `allDevices: true` | refresh token in body | `204` |
| `GET` | `/api/v1/auth/me` | access token | `200` + profile |

`register` returns a token pair rather than requiring a follow-up login: the user has just proven
they hold a valid email, so making them type their password again is friction with no benefit.

`/complete`-style long operations aside, `/me` is the only endpoint that needs a token, and it is
the reason the security matchers are enumerated rather than a `/api/v1/auth/**` wildcard. A
wildcard would also exempt `/me`, whose handler dereferences a principal — an anonymous caller
would get past the security layer and then fault inside the controller, surfacing as a 500 for what
is really a 401.

**iOS compatibility.** `AuthResponse.token` is still the access token, so the existing
`AuthSession { token, userId, username, email }` decodes unchanged. `refreshToken`, `expiresIn` and
`refreshExpiresIn` are additive, and Swift's `Decodable` ignores keys it does not know.

---

## 6. Verifying it

```bash
./scripts/e2e-auth-test.sh
```

Twenty-six assertions through the gateway, covering registration, login by username and email,
the non-enumerating failure message, `/me`, forged `X-User-Id`, a direct call to a service being
refused, refresh rotation, grace-window tolerance, reuse detection revoking the family, logout and
its idempotence, account lockout, and the 404/405 mappings.

---

## 7. Known gaps

| Gap | Impact | Recommendation |
|---|---|---|
| **Refresh tokens live in Postgres only** | No way to say "sign out everywhere" across a fleet, or revoke a stolen token before 30 days. | Redis denylist keyed by `jti`, checked on refresh only. Cheap; the access path stays stateless. |
| **No refresh on 401 in the client** | The iOS client has no interceptor yet. | Add one that retries once on `token-expired`, then clears the session on `token-invalid`. |
| **Lockout is per account, not per IP** | A distributed guessing attack across many accounts from one address is unthrottled. | Per-IP rate limiting at the gateway. Account lockout alone does not cover it. |
| **No email verification or password reset** | Anyone can register an address they do not own. | Needed before public launch. |
| **No MFA** | A single password is the whole factor. | Consider TOTP for anything with real money attached. |
| **Refresh token rotation is not yet visible to the user** | `GET /me` returns `activeSessionCount` but there is no "your devices" screen. | Add a session list, and make reuse detection's family revocation explainable to the user rather than an unexplained logout. |
