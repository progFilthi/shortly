# Shortly

Shortly is a backend foundation for a short-video and reels platform. The product vision is for users to upload videos, watch an endless feed, and interact with creators through likes, follows, and comments, with asynchronous media processing and notifications.

> **Current status:** This repository is an early backend prototype. The API gateway, user registration/JWT issuance, and the initial video upload flow have source-level implementations. The video module currently has a clean-build annotation-processing failure, documented below. The feed, interaction, notification, and transcoding services are still application scaffolds.

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
├── api-gateway/          JWT validation and HTTP routing
├── auth-service/         User registration, persistence, and JWT issuance
├── video-service/        Video metadata, S3 presigned uploads, and event publishing
├── feed-service/         Feed service scaffold
├── interaction-service/  Likes, follows, and comments scaffold
├── notification-service/ Notifications, WebSocket, and push scaffold
├── transcoder-service/   FFmpeg/media-processing worker scaffold
├── docs/init-db.sql      Development database bootstrap
├── docker-compose.yaml   Local PostgreSQL, Redis, and RabbitMQ
└── pom.xml               Maven reactor
```

All services are independently packaged Spring Boot applications. The root `pom.xml` aggregates them but is not their parent POM.

## Architecture

### HTTP request flow

```text
Client
  │
  ▼
API Gateway :8080
  ├── /api/v1/auth/**         ──► Auth service :8081 ──► auth_db
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

1. A client registers through `POST /api/v1/auth/register`.
2. The auth service stores the user in PostgreSQL and hashes the password with BCrypt.
3. The auth service returns a signed JWT containing the user ID as `sub` and the email as a custom claim. The configured default token lifetime is 24 hours.
4. For non-auth routes, the gateway validates the JWT and replaces the downstream `X-User-Id` header with the token subject.
5. The video service trusts that header for the current upload workflow.

The current design does not yet provide login, refresh tokens, token revocation, or a complete service-to-service authentication model.

## Current progress

| Module | Responsibility | Current state |
| --- | --- | --- |
| `api-gateway` | JWT validation and routing | **Implemented baseline**. Public auth routes; Bearer JWT required for other gateway requests. |
| `auth-service` | User registration and JWT issuance | **Partially implemented**. Registration and BCrypt persistence exist; login, refresh, and profile flows do not. |
| `video-service` | Video records and direct uploads | **Partially implemented**. Presigned S3 URLs, metadata persistence, completion, retrieval, and event publishing exist in source; the module currently has a clean-build annotation-processing failure. |
| `feed-service` | Infinite reels feed | **Scaffold**. Redis and PostgreSQL dependencies exist, but there is no feed logic, web API, persistence, or cache use. |
| `interaction-service` | Likes, follows, and comments | **Scaffold**. AMQP, Redis, and PostgreSQL dependencies exist, but there are no APIs, entities, or listeners. |
| `notification-service` | Notifications, WebSocket, and APNs | **Scaffold**. AMQP, WebSocket, PostgreSQL, and Pushy dependencies exist, but no delivery logic is implemented. |
| `transcoder-service` | Asynchronous video processing | **Scaffold**. RabbitMQ, AWS SDK, and FFmpeg dependencies exist, but no consumer or FFmpeg worker is implemented. |

### Implemented video behavior

- Video creation generates a UUID and an S3 key in the form `raw/{userId}/{videoId}.{extension}`.
- A presigned S3 `PUT` URL is valid for 15 minutes.
- The client uploads the file directly to S3; video bytes do not pass through the application.
- The completion endpoint checks the authenticated user ID, marks the record `READY`, creates a CDN URL, and publishes a `VideoUploadedEvent`.
- The service supports `PENDING`, `UPLOADING`, `PROCESSING`, `READY`, and `FAILED` enum values, but only the `PENDING` to `READY` path is currently used.
- The API does not currently verify that the object exists in S3 or that the uploaded file is a valid video.

## Requirements

### Local tooling

- JDK 25. Every service declares Java 25 in its Maven POM.
- Docker with Docker Compose.
- Network access for the first Maven dependency download and for AWS SDK access.
- An AWS S3 bucket and a CDN/base URL configured outside this repository.

The repository includes Maven Wrapper `3.9.16`; use `./mvnw` rather than relying on a system Maven installation.

### Ports

| Port | Component |
| ---: | --- |
| `5432` | PostgreSQL |
| `6379` | Redis |
| `5672` | RabbitMQ AMQP |
| `15672` | RabbitMQ management port exposed by Compose |
| `8080` | API gateway |
| `8081` | Auth service |
| `8082` | Video service |
| `8083` | Reserved gateway destination for interaction service |
| `8084` | Reserved gateway destination for feed service |
| `8086` | Reserved gateway destination for notification service |

The feed, interaction, and notification YAML files currently do not set these reserved ports. The transcoder is intended to be a worker and has no HTTP port.

## Local infrastructure

Start the development infrastructure from the repository root:

```bash
docker compose up -d
docker compose ps
```

Compose provides:

- PostgreSQL with development credentials `shortly_admin` / `shortly_password` and an initial `auth_db` database.
- Redis without authentication.
- RabbitMQ with development credentials `guest` / `guest`.

`docs/init-db.sql` creates `video_db`, `interaction_db`, `feed_db`, and `notification_db` when PostgreSQL initializes a new data volume. It does not create tables, indexes, or service schemas, and it does not rerun when an existing volume is reused.

Stop the containers without deleting their volumes:

```bash
docker compose down
```

The Compose file is for local development only. It does not build or start the Spring applications, S3, or a CDN.

## Configuration

Spring configuration is currently split between the YAML files and environment variables. The auth database URL and credentials are currently hard-coded in `auth-service/src/main/resources/application.yaml`; the video database and infrastructure settings are externalized.

### Environment variables

| Variable | Used by | Required | Notes |
| --- | --- | --- | --- |
| `JWT_SECRET` | Auth, gateway, video | Yes | Base64-encoded secret used to sign and validate HMAC JWTs. Auth and gateway must receive the same value. The video service currently declares it as `gateway.secret` but does not validate it. |
| `DB_HOST` | Video | Yes | PostgreSQL host, normally `localhost` for local development. |
| `DB_PORT` | Video | Yes | PostgreSQL port, normally `5432`. |
| `DB_USER` | Video | Yes | Video database user. |
| `DB_PASSWORD` | Video | Yes | Video database password. |
| `RABBITMQ_HOST` | Video | Yes | RabbitMQ host, normally `localhost`. |
| `RABBITMQ_PORT` | Video | No | Defaults to `5672`. |
| `RABBITMQ_USER` | Video | Yes | RabbitMQ user. |
| `RABBITMQ_PASSWORD` | Video | Yes | RabbitMQ password. |
| `AWS_REGION` | Video | Yes | Region used to build the S3 presigner. |
| `AWS_ACCESS_KEY_ID` | Video | Yes | Static AWS access key. |
| `AWS_SECRET_ACCESS_KEY` | Video | Yes | Static AWS secret key. |
| `AWS_S3_BUCKET` | Video | Yes | Existing bucket that receives raw uploads. |
| `CDN_DOMAIN` | Video | Yes | Base URL used to construct the public video URL. |

For a local shell, export values before starting an application. Spring Boot does not automatically load a module's `.env` file:

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
export DB_HOST="localhost"
export DB_PORT="5432"
export DB_USER="shortly_admin"
export DB_PASSWORD="shortly_password"
export RABBITMQ_HOST="localhost"
export RABBITMQ_PORT="5672"
export RABBITMQ_USER="guest"
export RABBITMQ_PASSWORD="guest"
export AWS_REGION="us-east-1"
export AWS_ACCESS_KEY_ID="<access-key>"
export AWS_SECRET_ACCESS_KEY="<secret-key>"
export AWS_S3_BUCKET="<existing-bucket>"
export CDN_DOMAIN="https://cdn.example.com"
```

Use the same `JWT_SECRET` in the terminals running the auth service and gateway. Generate it once and reuse that value; running the generation command independently in each terminal creates different secrets. The `openssl rand -base64 32` command produces a suitable local HMAC key; do not commit generated secrets or real AWS credentials.

The video service currently configures the AWS SDK with static credentials. Its S3 bucket, permissions, and CDN distribution must already exist. A browser client also needs an appropriate S3 CORS policy for the presigned `PUT` request.

## Build and test

Run the full Maven reactor from the repository root:

```bash
./mvnw clean verify
```

> **Build health at this snapshot:** The other six service modules compile in the reactor, but `video-service` currently fails clean compilation because Lombok-generated constructors, accessors, builder methods, and log fields are not available to `javac`. The module's POM declares Lombok with the invalid Maven `annotationProcessor` dependency scope; fix that setup before treating the build as green.

Run one module's tests or application from the root:

```bash
./mvnw -pl auth-service test
./mvnw -pl video-service test
./mvnw -pl api-gateway test
```

Start the three currently useful HTTP services in separate terminals, with the environment variables exported in each terminal:

```bash
./mvnw -f auth-service/pom.xml spring-boot:run
./mvnw -f video-service/pom.xml spring-boot:run
./mvnw -f api-gateway/pom.xml spring-boot:run
```

Start infrastructure first, then auth and video, and finally the gateway. The feed, interaction, notification, and transcoder modules do not yet provide a complete runnable platform flow.

Every module currently has only a generated Spring context-load test. There are no endpoint, persistence, JWT, S3, RabbitMQ, transcoding, or integration tests. Auth and video context tests may require PostgreSQL and their normal configuration to be available.

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

The request requires a nonblank username, a valid email, and a password of at least six characters. A successful response has this shape:

```json
{
  "token": "<jwt>",
  "userId": "<user-id>",
  "username": "alice",
  "email": "alice@example.com"
}
```

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

Supported extension mapping is currently `video/quicktime` and `video/mov` to `.mov`, `video/webm` to `.webm`, and other or missing content types to `.mp4`.

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

Configured names are:

- Exchange: `video.exchange`
- Routing key: `video.uploaded`
- Intended queue name: `video.uploaded.queue`

The service configures a JSON message converter and publishes the event, but it does not declare an exchange, queue, or binding. There is no Rabbit listener in any module. The intended transcoder, feed, interaction, and notification consumers therefore are not connected yet, and a fresh broker may require topology to be provisioned externally.

## Known limitations and security notes

These are important before exposing the services outside a trusted development machine:

1. **Gateway-only authorization is incomplete.** Backend ports should remain private. A direct caller of port `8082` can currently supply an arbitrary `X-User-Id`, bypassing gateway JWT validation.
2. **The internal gateway secret is unsafe as currently wired.** The gateway adds `JWT_SECRET` itself as `X-Gateway-Secret`, and no backend validates that header. Replace this with a separate internal secret and service-to-service authentication before production.
3. **No login flow exists.** Registration returns a token, but passwords are not currently checked by an authentication endpoint.
4. **Upload completion is client-asserted.** The service does not verify S3 existence, object size, checksum, content type, or media validity.
5. **Transcoding is not implemented.** `UPLOADING`, `PROCESSING`, and `FAILED` have no transitions; the transcoder module has no listener or FFmpeg execution.
6. **Messaging is incomplete.** Exchange/queue/binding declarations, consumers, retries, dead-letter handling, idempotency, and an outbox are absent.
7. **The reserved services are not API-ready.** Their gateway routes, ports, and web dependencies are not wired consistently.
8. **Validation and error contracts are incomplete.** Video request fields are not validated at the controller boundary, and domain errors are not mapped to a stable API error format.
9. **Schema management is development-oriented.** Hibernate updates schemas in place; Flyway, indexes, cross-service constraints, and production migration strategy are not defined.
10. **Operational controls are absent.** There is no CI, OpenAPI contract, observability setup, rate limiting, CORS policy, production profile, or secret-management setup.
11. **The transcoder Dockerfile needs correction.** It copies `target/transcoder-service-1.0.0.jar`, while the POM currently produces a `0.0.1-SNAPSHOT` artifact.
12. **The current video build is blocked.** `video-service` has source for the upload flow, but its POM declares Lombok with the invalid Maven `annotationProcessor` scope, so clean compilation does not see the generated members required by the controller, mapper, entity, and service implementation.

## Roadmap

The next milestones, in recommended order, are:

1. **Harden authentication and service boundaries** — add login and refresh-token flows, stable error responses, downstream gateway-secret validation, and private service networking.
2. **Complete the media pipeline** — verify uploaded objects, add reliable queue topology, consume upload events, process with FFmpeg, persist processing states, and expose processed outputs.
3. **Implement interactions** — add idempotent likes, follows, and comments with ownership rules, persistence, and event publishing.
4. **Build the feed** — add video-service integration, cursor pagination and ranking, personalization, Redis caching, and invalidation.
5. **Implement notifications** — consume domain events, persist notification state, add WebSocket delivery, and configure APNs/device tokens.
6. **Prepare for delivery** — add migrations, service containers, CI, functional/integration tests, API documentation, observability, CORS, rate limits, and production configuration profiles.

Until these milestones are complete, this repository is a working foundation for the first platform slice—not a complete short-video product.
