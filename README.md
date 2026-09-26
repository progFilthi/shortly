# Shortly

Shortly is a backend foundation for a short-video and reels platform. The product vision is for users to upload videos, watch an endless feed, and interact with creators through likes, follows, and comments, with asynchronous media processing and notifications.

> **Current status:** This repository is an early backend prototype. The API gateway, the full authentication lifecycle (register, login, refresh with rotation and reuse detection, logout, account lockout), the video upload flow, and the full HLS transcoding pipeline are implemented and verified end to end against a local stack by two script suites. The feed, interaction, and notification services are still application scaffolds. See [docs/authentication.md](docs/authentication.md) for the auth design and [docs/transcoding.md](docs/transcoding.md) for the media pipeline and its remaining gaps.

## What we are building

The intended platform is composed of these capabilities:

- **Identity and access** — user registration, authentication, and JWT-based authorization.
- **Video management** — video metadata, direct-to-S3 uploads, CDN delivery, and asynchronous transcoding.
- **Feed** — a personalized, cursor-paginated stream of short videos, with Redis-backed caching.
- **Interactions** — likes, follows, and comments.
- **Notifications** — real-time and mobile notifications for user activity.
- **Platform infrastructure** — PostgreSQL, Redis, RabbitMQ, AWS S3/CDN, and an API gateway.

The current code implements the first two capabilities only in part. The remaining services are reserved in the gateway and dependency graph, but their business logic has not been written yet.

## Repository layout

```text
.
├── api-gateway/          JWT validation, header sanitizing, and HTTP routing
├── auth-service/         Registration, sign-in, sessions, lockout, and profiles
├── video-service/        Video metadata, S3 presigned uploads, and event publishing
├── feed-service/         Feed service scaffold
├── interaction-service/  Likes, follows, and comments scaffold
├── notification-service/ Notifications, WebSocket, and push scaffold
├── transcoder-service/   FFmpeg worker: adaptive HLS ladder + cover art
├── common-contracts/     Shared event, error, and internal-header contracts (no logic)
├── common-jwt/           One HMAC JWT implementation, shared by gateway and auth
├── shortly-ios/          Swift client (register, sign in, refresh, upload, profile)
├── scripts/              e2e-auth-test.sh and e2e-pipeline-test.sh
├── docs/                 authentication.md, ios-client.md, transcoding.md, init-db.sql
├── docker-compose.yaml   Local infrastructure only (Postgres, Redis, RabbitMQ)
└── pom.xml               Maven reactor
```

All services are independently packaged Spring Boot applications. The root `pom.xml` aggregates them but is not their parent POM.

`common-contracts` is deliberately plain Java with no Spring on its classpath, so services can share a vocabulary without sharing a framework. `common-jwt` is the exception: it contains real behaviour, because two independent JWT implementations inevitably drift and reject each other's tokens.

## Architecture

### HTTP request flow

```text
Client ──(Bearer JWT)──►
  │
  ▼
API Gateway :8080
  │  verifies the JWT, rewrites the identity headers, adds X-Gateway-Secret
  ├── /api/v1/auth/register|login|refresh|logout  ──► Auth service :8081 ──► auth_db
  ├── /api/v1/auth/me       ──► Auth service :8081
  ├── /api/v1/videos/**       ──► Video service :8082 ──► video_db
  ├── /api/v1/interactions/** ──► Intended service :8083
  ├── /api/v1/feed/**         ──► Intended service :8084
  └── /api/v1/notifications/**──► Intended service :8086

Video upload flow:
Client ──► API Gateway ──► Video service ──► S3 presigned URL
                                      └──► Client uploads directly to S3
                                      └──► RabbitMQ event after completion

Intended media flow:
RabbitMQ ──► Transcoder worker ──► FFmpeg ──► processed S3 output/CDN
```

The gateway currently uses fixed `localhost` destinations and does not use service discovery. The route targets for the feed, interaction, and notification services are reserved but are not currently usable because those services have no implemented endpoints or matching port configuration.

### Authentication flow

1. A client registers through `POST /api/v1/auth/register`, or signs in through `POST /api/v1/auth/login`.
2. The auth service stores the user in PostgreSQL with a BCrypt (cost 12) password hash, and returns a **15-minute** access JWT plus an opaque 30-day refresh token.
3. The access token carries the user ID as `sub` plus username and email claims. It is stateless and not revocable before expiry — the short lifetime *is* the revocation policy.
4. The refresh token is stored only as a SHA-256 hash, rotated on every use, and grouped into a family. Replaying a rotated token revokes the whole family.
5. For protected routes the gateway verifies the JWT, overwrites the downstream `X-User-Id` / `X-Username` / `X-Email` headers from the claims, and strips any inbound copy of them.
6. The gateway adds `X-Gateway-Secret` from configuration; every service requires it, so a direct caller of a backend port cannot mint an identity. The video service injects a verified `CallerIdentity` into controllers rather than reading the raw header.
7. Five consecutive failures lock the account for 15 minutes (`423`, with `retryAfterSeconds`).

Full detail, including the error contract and the reasoning behind each choice, is in [docs/authentication.md](docs/authentication.md).

## Current progress

| Module | Responsibility | Current state |
| --- | --- | --- |
| `api-gateway` | JWT validation and routing | **Implemented**. Public auth routes; Bearer JWT required elsewhere; identity-header sanitizing; a separate internal secret added downstream. |
| `common-contracts` | Shared event, error, and internal-header contracts | **Implemented**. Framework-free, so services share a vocabulary without a shared framework. |
| `common-jwt` | JWT issuing and verification | **Implemented**. One HMAC implementation used by both the gateway and auth service, so tokens cannot drift apart. |
| `auth-service` | Registration, sign-in, sessions, and profiles | **Implemented**. Register/login/refresh/logout/logout-all/me, refresh rotation with reuse detection, account lockout, non-enumerating failures. |
| `video-service` | Video records and direct uploads | **Implemented**. Presigned S3 uploads, server-side upload verification via `HeadObject`, Flyway-managed schema, the full status lifecycle, cover-frame selection, and consumers for transcoder outcomes. |
| `feed-service` | Infinite reels feed | **Scaffold**. Redis and PostgreSQL dependencies exist, but there is no feed logic, web API, persistence, or cache use. |
| `interaction-service` | Likes, follows, and comments | **Scaffold**. AMQP, Redis, and PostgreSQL dependencies exist, but there are no APIs, entities, or listeners. |
| `notification-service` | Notifications, WebSocket, and APNs | **Scaffold**. AMQP, WebSocket, PostgreSQL, and Pushy dependencies exist, but no delivery logic is implemented. |
| `shortly-ios` | Swift client | **Implemented**. Register, sign in, automatic access-token refresh with single-flight coordination, sign out, upload, and profile. Verified against the live stack. |
| `transcoder-service` | Asynchronous video processing | **Implemented**. Consumes `VideoUploadedEvent`, probes with ffprobe, produces a 6-rung adaptive HLS ladder in a single ffmpeg process, generates poster and scrub sprite sheet, uploads to S3, and publishes `video.ready` / `video.failed`. |

### Implemented video behavior

Full detail in [docs/transcoding.md](docs/transcoding.md).

- Video creation generates a UUID and an S3 key in the form `raw/{userId}/{videoId}.{extension}`.
- A presigned S3 `PUT` URL is valid for 15 minutes. The response also returns the platform's
  size and duration ceilings so the client can reject bad input before spending bandwidth.
- The client uploads the file directly to S3; video bytes do not pass through the application.
- The content type is checked against a video allow-list at the controller boundary.
- The completion endpoint verifies the object with `HeadObject`. A missing, empty, or oversized
  upload marks the record `FAILED`, returns `409`/`413` with a machine-readable problem code, and
  deletes an oversized object immediately.
- On success the record becomes `PROCESSING` and a `VideoUploadedEvent` is published. It never
  becomes `READY` at this point — the video is not playable yet.
- `READY` is set only by the transcoder's `video.ready` event, which carries the HLS manifest
  URL, the produced ladder, the output geometry, the duration, and the cover-art URLs.
- `FAILED` is set by `video.failed`, which carries a machine-readable reason
  (`DURATION_EXCEEDED`, `SIZE_EXCEEDED`, `SOURCE_CORRUPT`, …) so the client can tell the user
  which limit was hit.
- The transcoder emits a 6-rung 9:16 ladder (1080x1920 down to 180x320), a poster frame, and a
  sprite sheet the client uses for Instagram-style cover selection.
- `PUT /api/v1/videos/{id}/thumbnail` records the user's chosen cover as a tile index.

## Requirements

### Local tooling

- JDK 25. Every service declares Java 25 in its Maven POM.
- Docker with Docker Compose, for the infrastructure only.
- ffmpeg and ffprobe with `zscale`, for the transcoder — see below.
- Network access for the first Maven dependency download and for AWS SDK access.
- An S3-compatible bucket and a CDN/base URL, for uploads and playback.

The repository includes Maven Wrapper `3.9.16`; use `./mvnw` rather than relying on a system Maven installation.

#### ffmpeg

The transcoder runs on the host, so it uses your ffmpeg rather than the pinned Alpine build in its
`Dockerfile`. It preflights its toolchain at startup and refuses to start if `zscale` or `tonemap`
is missing. Homebrew's `ffmpeg` is built without `libzimg` and so has neither; the `homebrew-ffmpeg`
tap has both:

```bash
brew tap homebrew-ffmpeg/ffmpeg
brew uninstall ffmpeg
brew install homebrew-ffmpeg/ffmpeg/ffmpeg
```

`HlsCommandBuilderIT` skips itself when ffmpeg is not on `PATH`, so a green `./mvnw verify` does
not prove the ladder was exercised.

### Ports

Every application service runs as its own host process, so these ports must be unique on the
machine. That is why the transcoder is on `8085` and not the `8080` it used in its container.

| Port | Component |
| ---: | --- |
| `5432` | PostgreSQL (Docker) |
| `6379` | Redis (Docker) |
| `5672` | RabbitMQ AMQP (Docker) |
| `15672` | RabbitMQ management UI (Docker) |
| `4566` | LocalStack S3, if you run one (not a Compose service) |
| `8080` | API gateway |
| `8081` | Auth service |
| `8082` | Video service |
| `8083` | Interaction service (scaffold) |
| `8084` | Feed service (scaffold) |
| `8085` | Transcoder actuator only; no business endpoints |
| `8086` | Notification service (scaffold) |

## Local infrastructure and services

Compose runs the stateful dependencies and nothing else:

```bash
docker compose up -d
docker compose ps
```

| Service | Notes |
| --- | --- |
| `postgres` | Credentials `shortly_admin` / `shortly_password`, initial database `auth_db`. |
| `redis` | No authentication. |
| `rabbitmq` | Credentials `guest` / `guest`, management UI on `15672`. |

The application services are **not** in Compose. Run them from the IDE — the gutter arrow next to
each `*Application` class, or a compound configuration:

| Service | How to start it |
| --- | --- |
| `auth-service` | `AuthServiceApplication.main` |
| `video-service` | `VideoServiceApplication.main` |
| `api-gateway` | `ApiGatewayApplication.main` |
| `transcoder-service` | `TranscoderServiceApplication.main` |

The per-service `Dockerfile`s remain in the repository but Compose no longer references them.

No environment variables need to be exported: every service has development defaults in its own
`application.yaml`. See [Configuration](#configuration).

`docs/init-db.sql` creates `video_db`, `interaction_db`, `feed_db`, and `notification_db` when
PostgreSQL initializes a new data volume. It does not rerun when an existing volume is reused.

**The `videos` schema is owned by Flyway**, not by Hibernate — `ddl-auto` is `validate`, so a
drift between the entity and the migrations fails the deploy rather than silently editing a
production schema. Migrations live in `video-service/src/main/resources/db/migration`.

Stop the containers without deleting their volumes:

```bash
docker compose down
```

Everything above is local development only. The compose file does not model TLS, secrets,
multi-AZ, or a CDN.

### Object storage is not in Compose

The services default to `http://localhost:4566`. Start LocalStack yourself — the CLI, the desktop
app, or its own container — then create the bucket and its CORS rule once:

```bash
S3_ENDPOINT=http://localhost:4566 ./scripts/init-local-storage.sh
```

The CORS rule is not optional: it is what lets a browser `PUT` to the presigned URL.

To use a real bucket, empty `AWS_ENDPOINT_URL` and override `AWS_REGION`, `AWS_S3_BUCKET`, and
`CDN_DOMAIN`.

Then exercise the end-to-end pipeline, which covers upload, verification, transcoding, HLS fetch,
cover selection, and the failure paths:

```bash
./scripts/e2e-pipeline-test.sh
```

## Configuration

Each service's development configuration lives in its own `src/main/resources/application.yaml`,
as `${VAR:default}` placeholders. The defaults are the local stack — Compose's published
`localhost` ports, the `shortly_admin` database credentials, `guest` on the broker, LocalStack's
placeholder AWS keys — so nothing has to be exported before a Run button. An environment variable
of the same name always wins, which is how a real deployment overrides any of it.

### Environment variables

| Variable | Used by | Default | Notes |
| --- | --- | --- | --- |
| `JWT_SIGNING_SECRET` | Auth, gateway | dev key | Base64-encoded HMAC key for access tokens. Auth and gateway must receive the same value. Distinct from `GATEWAY_INTERNAL_SECRET`; never reuse one for the other. |
| `GATEWAY_INTERNAL_SECRET` | Gateway, auth, video | `dev-only-internal-secret` | Proves a request arrived through the gateway. The gateway adds it; every service validates it. Keep backend ports private regardless. |
| `DB_HOST` | Auth, video | `localhost` | PostgreSQL host. |
| `DB_PORT` | Auth, video | `5432` | PostgreSQL port. |
| `DB_USER` | Auth, video | `shortly_admin` | Database user. |
| `DB_PASSWORD` | Auth, video | `shortly_password` | Database password. |
| `RABBITMQ_HOST` | Video, transcoder | `localhost` | RabbitMQ host. |
| `RABBITMQ_PORT` | Video, transcoder | `5672` | AMQP port. |
| `RABBITMQ_USER` | Video, transcoder | `guest` | RabbitMQ user. |
| `RABBITMQ_PASSWORD` | Video, transcoder | `guest` | RabbitMQ password. |
| `AWS_REGION` | Video, transcoder | `us-east-1` | Region used to build the S3 presigner. |
| `AWS_ACCESS_KEY_ID` | Video, transcoder | `test` | Static AWS access key. |
| `AWS_SECRET_ACCESS_KEY` | Video, transcoder | `test` | Static AWS secret key. |
| `AWS_S3_BUCKET` | Video, transcoder | `shortly-videos-bucket` | Bucket that receives raw uploads. |
| `AWS_ENDPOINT_URL` | Video, transcoder | `http://localhost:4566` | S3-compatible endpoint. **Set empty against real AWS.** |
| `CDN_DOMAIN` | Video, transcoder | `http://localhost:4566/shortly-videos-bucket` | Base URL used to construct the public video URL. Must be reachable by the client. |
| `TRANSCODER_WORK_DIR` | Transcoder | `${java.io.tmpdir}/shortly-transcoder` | ffmpeg scratch space. Point it at a tmpfs mount in a deployment. |
| `ACCESS_TOKEN_TTL` | Auth | `15m` | Overridable so the iOS integration test can wait out a real expiry. |
| `REFRESH_REUSE_GRACE` | Auth | `PT4S` | Shortened from the `PT20S` production default so the e2e script can exercise the grace window without sleeping 20s twice. |

Generate real secrets once and reuse the value across the gateway, auth, and video services — a
mismatch fails closed, which is the right behaviour but a confusing way to spend an afternoon:

```bash
export JWT_SIGNING_SECRET="$(openssl rand -base64 32)"
export GATEWAY_INTERNAL_SECRET="$(openssl rand -hex 32)"
```

Do not commit generated secrets or real AWS credentials. Spring Boot does not automatically load
a module's `.env` file, so an IDE run configuration needs these as explicit environment
variables — but for the local stack it does not need them at all.

The video service currently configures the AWS SDK with static credentials. Its S3 bucket,
permissions, and CDN distribution must already exist. A browser client also needs an appropriate
S3 CORS policy for the presigned `PUT` request.

## Build and test

Run the full Maven reactor from the repository root:

```bash
./mvnw clean verify
```

> **Build health at this snapshot:** the full reactor builds clean. `common-contracts`,
> `video-service`, and `transcoder-service` have passing tests. `auth-service` has no tests:
> its only candidate was a generated context-load test, and a context test cannot run without a
> real database here — excluding the data layer just leaves `AuthService` with no
> `UserRepository` to inject. Adding Testcontainers Postgres is the proper fix and is not done.

Run the whole build, or one module's tests:

```bash
./mvnw clean install

./mvnw -pl video-service test
# `verify` also runs HlsCommandBuilderIT, which executes the real ffmpeg binary
./mvnw -pl transcoder-service verify
```

Start the services from the IDE — the gutter arrow next to each `*Application` class, or a
compound configuration that starts all four at once. `spring-boot:run` works from a terminal too,
and needs no exported variables:

```bash
./mvnw -f auth-service/pom.xml spring-boot:run
./mvnw -f video-service/pom.xml spring-boot:run
./mvnw -f api-gateway/pom.xml spring-boot:run
./mvnw -f transcoder-service/pom.xml spring-boot:run
```

`docker compose up -d` starts only the infrastructure. The feed, interaction, and notification
modules remain scaffolds.

The gateway, auth, video, and transcoder services have real test coverage: 146 tests in total, including JWT expiry and tampering, refresh rotation and reuse detection, account lockout, gateway header sanitizing, and transcoding against real ffmpeg. To run the two end-to-end suites against a live stack:

```bash
./scripts/e2e-auth-test.sh       # 26 assertions through the gateway
./scripts/e2e-pipeline-test.sh   # upload, transcode, verify HLS

# The iOS client, against that same stack
cd shortly-ios
xcodebuild test -project Shortly.xcodeproj -scheme Shortly \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro'
```

The iOS suite is 40 tests. `LiveBackendTests` and `LiveAuthUITests` exercise the real client and
the real UI against the running stack and skip cleanly when it is absent. One test waits out a
genuine access-token expiry, so auth-service needs a short one — set `ACCESS_TOKEN_TTL=20s` in its
run configuration, or export it before starting it.

See [docs/ios-client.md](docs/ios-client.md).

## HTTP API

The examples below use the gateway at `http://localhost:8080`. Only the endpoints in this section currently have application-level implementations.

### Register a user

`POST /api/v1/auth/register` is public through the gateway and returns HTTP `201 Created`.

```bash
curl -X POST "http://localhost:8080/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "alice",
    "email": "alice@example.com",
    "password": "change-me"
  }'
```

The request requires a nonblank username, a valid email, and a password of **10 to 128 characters** (length only; composition rules mostly produce `Passw0rd!`). A successful response is `201 Created`:

```json
{
  "token": "<access jwt, 15 min>",
  "refreshToken": "<opaque, 30 days>",
  "expiresIn": 900,
  "refreshExpiresIn": 2592000,
  "issuedAt": "2026-01-01T12:00:00Z",
  "userId": "<user-id>",
  "username": "alice",
  "email": "alice@example.com"
}
```

`token` is still the access token, so an existing client decoding `AuthSession { token, userId, username, email }` is unaffected; the refresh fields are additive and Swift ignores keys it does not know.

### Sign in

`POST /api/v1/auth/login` is public and accepts either the username or the email in `identifier`. It returns the same body as registration, with `200 OK`.

```bash
curl -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"identifier": "alice", "password": "change-me-please"}'
```

An unknown account and a wrong password return the **same** `401 invalid-credentials` body, and the unknown-account path still performs a password hash so response timing does not enumerate accounts. Five consecutive failures lock the account:

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

### Refresh, logout, and profile

| Method | Path | Body | Notes |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/refresh` | `{"refreshToken": "..."}` | Rotates the token. A replay inside the 20 s grace window is tolerated; after that the whole family is revoked. |
| `POST` | `/api/v1/auth/logout` | `{"refreshToken": "...", "allDevices": false}` | `204`. Idempotent. `allDevices: true` revokes every session. |
| `GET` | `/api/v1/auth/me` | — | Requires `Authorization: Bearer`. Returns the profile and `activeSessionCount`. |

### Error responses

Every service returns RFC 9457 problem documents with a stable kebab-case `code` from the shared `ApiError` enum, so a client branches on the code rather than parsing prose. `token-expired` and `token-invalid` are separate codes even though both are `401`, because the recovery differs — retry with a refresh, or sign in again. Full table in [docs/authentication.md §4](docs/authentication.md).

There is currently no login endpoint. A token is returned as part of registration, but there is no implemented password-authentication flow.

### Start a video upload

`POST /api/v1/videos` requires a Bearer token when called through the gateway. The gateway supplies `X-User-Id`; clients should not need to set that header themselves.

```bash
curl -X POST "http://localhost:8080/api/v1/videos" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "First reel",
    "description": "A first upload",
    "contentType": "video/mp4"
  }'
```

The response contains a video ID, S3 key, presigned upload URL, and expiry:

```json
{
  "videoId": "<video-id>",
  "uploadUrl": "<presigned-s3-url>",
  "s3Key": "raw/<user-id>/<video-id>.mp4",
  "expiresAt": "<iso-8601-timestamp>"
}
```

Upload the file directly to S3 within 15 minutes:

```bash
curl -X PUT "${UPLOAD_URL}" \
  -H "Content-Type: video/mp4" \
  --upload-file "./first-reel.mp4"
```

The declared content type must be one of `video/mp4`, `video/quicktime`, `video/x-m4v`,
`video/webm`, or `video/x-matroska`; anything else is rejected with `400`. Extension mapping is
`video/quicktime` to `.mov`, `video/x-m4v` to `.m4v`, `video/webm` to `.webm`,
`video/x-matroska` to `.mkv`, and `video/mp4` to `.mp4`.

The presigned URL is built from `AWS_ENDPOINT_URL`, so a local run points straight at LocalStack on
`localhost:4566` and needs no rewriting from a browser. Against real S3 no change is needed.

### Complete and retrieve a video

After the direct S3 upload, tell the application that the client is finished:

```bash
curl -X POST "http://localhost:8080/api/v1/videos/${VIDEO_ID}/complete" \
  -H "Authorization: Bearer ${TOKEN}"
```

The current implementation marks the video `READY`, constructs `CDN_DOMAIN/{s3Key}`, and publishes a `VideoUploadedEvent`. It does not call S3 to verify the object or invoke a transcoder.

Retrieve a video or a user's videos:

```bash
curl "http://localhost:8080/api/v1/videos/${VIDEO_ID}" \
  -H "Authorization: Bearer ${TOKEN}"

curl "http://localhost:8080/api/v1/videos/user/${USER_ID}" \
  -H "Authorization: Bearer ${TOKEN}"
```

The response field named `uploadUrl` is currently populated from the entity's public `videoUrl`; it is not a newly generated presigned URL after completion.

## Data and messaging

### Databases

- `auth_db` stores the `users` table managed by Hibernate.
- `video_db` stores the `videos` table managed by Hibernate.
- `interaction_db`, `feed_db`, and `notification_db` are created by the bootstrap script but currently have no service-owned tables.
- Auth and video use `hibernate.ddl-auto: update`; no Flyway migration files are committed.
- User and video IDs are cross-service string values, not database foreign keys.

The current logical models are:

- **User:** UUID/string ID, unique username, unique email, BCrypt password, optional profile picture and bio, creation time.
- **Video:** UUID ID, title, description, raw S3 key, public video URL, user ID, status, creation time, update time.

Use explicit migrations before relying on the schema outside local development.

### RabbitMQ event

The video service publishes this record after the completion call:

```text
VideoUploadedEvent
  videoId
  userId
  s3Key
  videoUrl
  title
  description
  createdAt
```

Names live in `common-contracts` so there is exactly one definition of each. Both producer and
consumer declare what they need with identical arguments, so the topology converges whichever
service starts first.

```
video.exchange (topic, durable)
  ├─ video.uploaded ─► video.uploaded.queue ─► transcoder-service
  │                        └─ dead-letters to video.dlx / video.uploaded.dlq
  ├─ video.ready    ─► video.ready.queue    ─► video-service
  └─ video.failed   ─► video.failed.queue   ─► video-service
```

`video-service` and `transcoder-service` both run listeners. The transcoder's consumer uses
`prefetch: 1` (Spring's default of 250 would let one worker hoard hundreds of unacked
multi-minute jobs and lose them all on a crash), bounded concurrency, a 3-attempt backoff
retry, and a dead-letter chain so a poison message lands in the DLQ instead of hot-looping.
Terminal failures (`DURATION_EXCEEDED`, `SOURCE_CORRUPT`, …) are published and swallowed rather
than requeued, because retrying a permanently invalid input can never succeed.

The feed, interaction, and notification consumers are still not connected.

## Known limitations and security notes

These are important before exposing the services outside a trusted development machine:

1. **Playback URLs are unsigned.** Manifests and segments are publicly fetchable by anyone with the key. Add CloudFront signed cookies/URLs before public launch, or accept the exposure for a feed app.
2. **Uploads are not resumable.** A single presigned `PUT`; a network drop on cellular restarts the transfer. Acceptable at the intended 15-40 MB with client-side retry. Presigned multipart is the fix, and is not yet built.
3. **The upload size ceiling is not bucket-enforced.** AWS SDK v2 has no presigned POST, so the 500 MB limit is enforced at three application layers rather than by a bucket policy. See [docs/transcoding.md §3](docs/transcoding.md).
4. **Messaging has no outbox.** `video-service` publishes inside its `@Transactional` boundary with publisher confirms but no outbox, so a commit/publish failure can still diverge. Events are idempotent on both sides, which bounds the damage but does not eliminate the window.
5. **The reserved services are not API-ready.** Their gateway routes, ports, and web dependencies are not wired consistently.
6. **Operational controls are absent.** There is no CI, OpenAPI contract, rate limiting, DLQ replay tooling, or reconciliation job for videos stuck in `PROCESSING`. S3 lifecycle rules for the `raw/` prefix are recommended but not provisioned.
7. **Static AWS credentials are used for real buckets.** A local `video-service/.env` holds them and is gitignored, so nothing has leaked into the repository — but the service is configured with long-lived keys rather than an IAM role, which is what production should use.
8. **The application config ships default secrets and a LocalStack endpoint.** `JWT_SIGNING_SECRET`, `GATEWAY_INTERNAL_SECRET`, the database credentials, and `AWS_ENDPOINT_URL` all have development defaults in the per-service `application.yaml` files, so a local Run button works with nothing to export. They must be overridden by real environment variables everywhere else. The `test` AWS keys and the localhost endpoint are the sharpest edge: against real S3, `AWS_ENDPOINT_URL` has to be explicitly emptied.
9. **Authentication is still incomplete** — refresh tokens are Postgres-only, so there is no "sign out everywhere" across a fleet; there is no per-IP rate limiting; and there is no email verification, password reset, or MFA. The client now refreshes automatically, so this list is one item shorter than it was. See [docs/authentication.md §7](docs/authentication.md).
10. **Display-matrix rotation is unverified end to end.** The probe logic is unit-tested, but ffmpeg's own decode-time rotation is not exercised, because ffmpeg 8 cannot write a display matrix to generate a fixture with. **Test with a real portrait iPhone clip before trusting it.** See [docs/transcoding.md](docs/transcoding.md).

## Roadmap

The next milestones, in recommended order, are:

1. **Finish the session surface** — a Redis denylist for "sign out everywhere", a 401 refresh interceptor for the iOS client, per-IP rate limiting at the gateway, and email verification with password reset.
2. **Wire the iOS client** — the backend is ready: `AuthResponse.token` is still the access token, so the existing `AuthSession` decodes unchanged, and the added refresh fields are ignored by Swift.
3. **Implement interactions** — add idempotent likes, follows, and comments with ownership rules, persistence, and event publishing.
4. **Build the feed** — add video-service integration, cursor pagination and ranking, personalization, Redis caching, and invalidation.
5. **Implement notifications** — consume domain events, persist notification state, add WebSocket delivery, and configure APNs/device tokens.
6. **Prepare for delivery** — add migrations, service containers, CI, functional/integration tests, API documentation, observability, CORS, rate limits, and production configuration profiles.

Until these milestones are complete, this repository is a working foundation for the first platform slice—not a complete short-video product.
