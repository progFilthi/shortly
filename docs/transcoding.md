# Video Transcoding Pipeline

How an uploaded video becomes a playable adaptive HLS ladder, and the reasoning behind every
non-obvious decision in it.

---

## 1. Shape of the system

```
  iOS client
     │  ① POST /api/v1/videos  {title, contentType}
     ▼
  api-gateway :8080 ──► injects X-User-Id
     ▼
  video-service :8082
     │  id = UUID.randomUUID();  key = raw/{userId}/{id}.mp4
     │  INSERT videos (status = UPLOADING)
     │  presigned PUT, 15 min TTL
     ▼
  client ──② HTTP PUT bytes straight to S3──►  s3://bucket/raw/{userId}/{id}.mp4
     │  ③ POST /api/v1/videos/{id}/complete
     ▼
  video-service
     │  HeadObject  ──► 404 / empty / >500 MB ?  → FAILED + 4xx, never enqueued
     │  status = PROCESSING
     │  publish VideoUploadedEvent  ──────────────┐
     ▼                                            │
  RabbitMQ  video.exchange / video.uploaded       │
     ▼                                            │
  transcoder-service (no DB)                      │
     │  1. download source to ephemeral disk      │
     │  2. ffprobe  → reject on duration/size/resolution
     │  3. one ffmpeg process → 6 renditions + manifest
     │  4. poster + scrub sprite sheet            │
     │  5. upload 187 objects, master manifest LAST
     │  6. write ready.json sidecar               │
     └──────────────── VideoReadyEvent ◄──────────┘
                    or VideoFailedEvent
                             │
                             ▼
  video-service  ── READY (+ ladder, geometry, artwork URLs)
                ── FAILED (+ machine-readable reason)
```

The transcoder holds **no database connection and no opinion about the `videos` table**. It
publishes an outcome and video-service, which owns the aggregate, applies the transition. A
transcoder outage therefore cannot corrupt video metadata, and the two remain independently
deployable.

---

## 2. Format policy: normalise on device, enforce on output

**We do not require users to upload 1080x1920.** We require that everything *we emit* is 9:16.

| Layer | What it does | Why |
|---|---|---|
| **iOS client** | `AVAssetExportSession` → 1080x1920 H.264 before upload | Cuts upload size 5-10x and removes an entire class of server-side decode risk. The single highest-leverage thing in the pipeline, and it is not in the transcoder. |
| **video-service** | MIME allow-list, size ceiling, title limits | Cheap rejections, before any bytes are stored. |
| **transcoder** | ffprobe → reject on duration / size / resolution | The only trustworthy measurement of a video is taken from its bytes. |
| **transcoder** | Output is always exact 9:16 | The feed is vertical-only. |

**Non-9:16 sources are centre-cropped, not padded.** The transform is always
`scale=W:H:force_original_aspect_ratio=increase, crop=W:H` — "cover", not "contain". This
matches user expectation for shortform (TikTok and Reels both crop) and costs nothing. The
trade-off is that landscape uploads lose their left and right edges. Blurred padding was
rejected: it softens every frame and adds roughly 15% to encode time.

### Why not enforce 9:16 at upload

Rejecting at the boundary is the worst possible moment — after the user has already spent their
bandwidth — and it breaks legitimate input. Screen recordings are landscape, old posts are
square, and a Photos picker offers everything. The output is what the platform actually
controls.

---

## 3. Limits

| Limit | Value | Enforced at |
|---|---|---|
| Max duration | **60 s** | transcoder, after ffprobe |
| Max upload size | **500 MB** | client pre-check → video-service `HeadObject` → transcoder |
| Max source resolution | 3840x2160 | transcoder, after ffprobe |
| Max source frame rate | 60 (output is always 30) | transcoder |
| Concurrent uploads per user | 3 | *not yet implemented — see §10* |

500 MB is a **ceiling, not a target**. It exists to accommodate the fallback path where the
client did *not* normalise. On the intended path uploads are 15-40 MB.

### The size limit is not bucket-enforced, and that is a real limitation

A presigned `PUT` cannot constrain upload size. Only a presigned `POST` policy can, via
`content-length-range` — and **AWS SDK v2 does not implement presigned POST at all** (its `s3`
artifact ships presigners for PUT, GET, HEAD, DELETE and multipart, and nothing else). Since
moving to SDK v1 purely for `PresignedPostPolicy` would reintroduce the 388 MB umbrella artifact,
we enforce at three layers instead:

1. **Client-side**, before the presign is even requested — the cheapest rejection.
2. **`HeadObject` at `/complete`** — authoritative. An oversized object is deleted immediately
   and the row is marked `FAILED` with a 413 carrying both `actualBytes` and `maxBytes`.
3. **In the transcoder**, after probing, on the actual local file size.

If bucket-level enforcement is ever genuinely required, the clean route is presigned
**multipart**: pre-sign exactly `500MB / 5MB = 100` `UploadPart` URLs, and the client simply
runs out of URLs. That is a hard cap by construction. It is real client work and is deferred
until mobile failure data justifies it.

---

## 4. The ladder

| Rung | Resolution | Video | Maxrate | VBV buffer | Audio |
|---|---|---|---|---|---|
| v0 | 1080x1920 | 4500k | 4725k | 9450k | 128k AAC-LC |
| v1 | 720x1280 | 2200k | 2310k | 4620k | 128k |
| v2 | 540x960 | 1100k | 1155k | 2310k | 128k |
| v3 | 360x640 | 700k | 735k | 1470k | 128k |
| v4 | 270x480 | 350k | 367k | 734k | 128k |
| v5 | 180x320 | 150k | 158k | 316k | 128k |

Every rung is an exact 9:16 ratio with even dimensions, so the transform never rounds and
chroma subsampling never smears on the last row or column.

**On "144p".** 144p conventionally means 256x144 *landscape*. The vertical equivalent is 81x144,
which is too small to be worth serving. The lowest sensible rung is **180x320** — a sixth of
1080p's pixels. That is v5. If storage becomes a concern, dropping v5 costs almost nothing in
real-world QoE; most players never select below v3.

**Common encoder settings**, and why each is load-bearing:

| Setting | Reason |
|---|---|
| `libx264`, `-preset veryfast` | Broadest decoder support. AV1/VP9 would multiply CPU cost and break older iOS. |
| `-profile:v main` | Reaches `avc1.4d4028` in the master playlist — universally supported. |
| `-sc_threshold 0` | Disables scene-cut keyframes. A keyframe in one rendition but not its neighbours is exactly what makes a player stall mid-switch. |
| `-g 60 -keyint_min 60` | 2s GOP at 30fps, constant on every rendition. |
| `-force_key_frames expr:gte(t,n_forced*2)` | Keyframes at identical *wall-clock instants* across all renditions. This is what allows switching at a segment boundary without buffering a new keyframe. |
| `-hls_time 2` | Fast startup and responsive ABR switching; the clip is short enough that the extra segments cost nothing. |
| `-hls_playlist_type vod` | Not EVENT. Content is immutable, and EVENT also makes the segment count unbounded. |
| `-hls_flags independent_segments+temp_file` | Declares the alignment property; `temp_file` means a killed job leaves `.tmp` files, which the uploader refuses to publish. |
| `-pix_fmt yuv420p` | Required for hardware decoders. |

**The ladder is capped at source resolution.** A 720p source does not get a 1080p rung:
upscaling costs CPU on every job *and* bandwidth on every view, and looks worse than the 720p
rung. The smallest rung is always kept, since a 320p source still needs something it can fill.

---

## 5. One ffmpeg process, not six

This is the most important performance decision in the pipeline.

```
-filter_complex "
  [0:v]split=6[s0][s1][s2][s3][s4][s5];
  [s0]fps=30,scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setsar=1[v0];
  ...
  [0:a]aresample=48000,asplit=6[a0][a1][a2][a3][a4][a5]
"
-var_stream_map "v:0,a:0 v:1,a:1 v:2,a:2 v:3,a:3 v:4,a:4 v:5,a:5"
```

The source is decoded **once** and the decoded frames fan out to six encoders in the same
process. ffmpeg then generates `master.m3u8` itself, with correct `BANDWIDTH`, `RESOLUTION` and
`CODECS` per variant.

### Two non-obvious constraints, both verified against ffmpeg 8.1.2

**1. Every variant needs its own audio stream index.** Reusing one index across variants fails:

```
Same elementary stream found more than once in two different variant definitions #0 and #1
Variant stream info update failed with status ffffffea
Could not write header (incorrect codec parameters ?): Invalid argument
```

It must be `a:0 a:1 a:2 ...`, not `a:0 a:0 a:0`. Consequently `asplit` is required.

**2. The filtergraph must be built conditionally on there being an audio stream.** A video with
no audio is a normal case, not an edge case, and `[0:a]asplit` against a video-only input aborts
the entire graph:

```
Stream specifier ':a' in filtergraph description ... matches no streams.
Error initializing complex filters: Invalid argument
```

When the source is silent we inject `anullsrc` as a second input and split *that*, so every
variant is still audio+video. No `EXT-X-MEDIA` audio group is used: every rendition carries its
own audio, which costs ~128k per rendition and buys maximum player compatibility.

Both of these are pinned by assertions in `HlsCommandBuilderTest`, because the failure mode is a
total pipeline outage with an error message that does not obviously point at the cause.

---

## 6. Handling awkward sources

| Case | Handling |
|---|---|
| **Portrait metadata** | iPhones store portrait video as 1920x1080 with a ~90° display matrix. ffmpeg applies the matrix during decode, so the encoder sees the rotated picture — but ffprobe reports the *unrotated* dimensions. `MediaProbeService` reads the display-matrix side data, normalises the counter-clockwise angle ffprobe reports into clockwise degrees, and validates against the rotated size. See the caveat in §13: our own logic is unit-tested, ffmpeg's decode-time rotation is **not** yet verified on a real device clip. |
| **HDR (PQ/HLG)** | Newer iPhones record 10-bit BT.2020. Without a tone map the clip reaches the feed washed out and flat. Detected via ffprobe `color_transfer`, then `zscale → tonemap(hable) → zscale` to BT.709 limited range, applied identically to every rung *before* scaling so renditions do not drift in colour across an ABR switch. |
| **10-bit SDR** | Same `format=yuv420p` down-convert as any high-bit-depth source. |
| **Variable frame rate** | Normal for phone capture. Forced to a constant rate, and never *upsampled* — a 24fps source stays 24fps rather than having frames duplicated, which would inflate bitrate for no visible gain. |
| **Upscaling** | Rungs larger than the source are dropped, not upscaled. |
| **Non-square pixels** | `setsar=1` on every rung, or a non-1:1 sample aspect ratio produces visibly stretched output. |
| **No audio** | Silent track synthesised (§5). |
| **Corrupt / non-video** | ffprobe fails → `FAILED` with `SOURCE_CORRUPT` or `NO_VIDEO_STREAM`. |
| **Over 60s** | `FAILED` with `DURATION_EXCEEDED`. Terminal — retrying a 70s clip never succeeds. |

---

## 7. Queue topology and consumer tuning

```
video.exchange (topic, durable)
  └─ video.uploaded  ──► video.uploaded.queue
                            │ x-dead-letter-exchange = video.dlx
                            ▼
                        video.dlx ──► video.uploaded.dlq
  └─ video.ready    ──► video.ready.queue    ──► video-service
  └─ video.failed   ──► video.failed.queue   ──► video-service
```

Three settings carry the weight:

| Setting | Value | Why |
|---|---|---|
| **`prefetch`** | **1** | Spring's default is 250. With CPU-bound work taking minutes per message, a high prefetch means one worker hoards hundreds of unacked jobs in memory and a crash silently loses every one of them. This is a correctness requirement, not a tuning knob. |
| **`concurrency`** | `floor(vCPU / 2)` | Each ffmpeg process otherwise saturates every core. More listeners than cores makes every job slower without shortening the queue. |
| **`requeueRejected`** | `false` | The default (`true`) turns any unhandled exception into an infinite hot redelivery loop. Paired with a 3-attempt exponential-backoff retry and a dead-letter chain, a poison message lands in the DLQ. |

**No per-message TTL on the work queue.** A dead-letter-on-TTL policy measures age from
*enqueue*, and a 6-rung transcode takes real minutes — an unlucky job would expire mid-flight
and vanish. Messages reach the DLQ only by being rejected, which happens when the retry budget
is spent.

**Failure routing.** The listener classifies every exception into a `TranscodeFailureReason` and
splits on `isRetryable()`:

- **Retryable** (`TRANSCODE_FAILED`, `TIMEOUT`, `UPLOAD_FAILED`) → rethrown, so the interceptor
  retries. Only dead-lettered once the budget is spent.
- **Terminal** (`DURATION_EXCEEDED`, `SIZE_EXCEEDED`, `SOURCE_CORRUPT`, …) → published as
  `video.failed` and swallowed. Requeueing would spin forever on a permanently invalid input.

Both handlers on the video-service side are **idempotent**: a duplicate `video.ready` is
recognised and dropped, and a late `video.ready` for an already-`FAILED` video is ignored. A
video that failed for a recorded reason must not silently flip back to `READY`.

---

## 8. Storage layout and idempotency

```
s3://bucket/
├── raw/{userId}/{videoId}.mp4              uploaded original
└── hls/{videoId}/
    ├── master.m3u8                        uploaded LAST
    ├── v0/index.m3u8  v0/seg_00000.ts …   6 renditions x (playlist + segments)
    ├── v1/ …
    ├── v5/ …
    ├── poster.jpg                         cover frame
    ├── sprite.jpg                         scrub sprite sheet, 10x10 grid
    └── ready.json                         sidecar: the exact VideoReadyEvent published
```

**Output keys are addressed by `videoId` only, with no user segment.** Content is immutable per
video, and dropping the user from the path means one ladder serves every viewer through the CDN
rather than being duplicated per user.

**`master.m3u8` is uploaded last — this is the pipeline's correctness mechanism.** Until it
exists, a player handed the URL gets a 404, so a half-uploaded ladder is *unreachable* rather
than playable-and-broken. Cleanup of partial output is therefore a storage optimisation, not a
correctness requirement, and an S3 lifecycle rule on `hls/` is an adequate backstop.

**Idempotency has two layers:**

1. Before any work, `HEAD hls/{videoId}/master.m3u8`. If it exists, the job is skipped.
2. `ready.json` stores the exact `VideoReadyEvent` that was published, so a lost ready event is
   replayed verbatim rather than re-derived or guessed at.

Redelivery is normal — a consumer crash mid-job, a broker restart, a redeploy — and re-running a
6-rung transcode is expensive enough to be worth avoiding.

**Recommended S3 lifecycle rules:**

| Prefix | Rule | Reason |
|---|---|---|
| `raw/` | expire after N days | The original is only needed until the ladder exists. This is the single biggest storage saving. |
| `hls/` | expire incomplete uploads after 1 day | Reaps multipart parts from interrupted uploads. |
| `hls/` | optional abort rule | Cheap insurance for partial ladders. |

---

## 9. Measured performance

Measured in the actual container image (`eclipse-temurin:25-jre-alpine` + `ffmpeg 8.1.2-r0`):

| Input | Hardware | Wall clock | CPU | Output |
|---|---|---|---|---|
| 60s 1080x1920 @30fps, 6 rungs | 10 vCPU | **16.3 s** | 91 s | 74 MB, 187 objects |
| 8s 1080x1920 @30fps, 6 rungs | 4 vCPU (compose) | **3.3 s** | — | 10.7 MB, 34 objects |

That is roughly **0.27x realtime** on 10 vCPU, with ~4.5x effective parallelism across the six
encoders. On a 4 vCPU instance expect ~50-60 s per 60-second clip, so concurrency 2 sustains
about one clip every 25 s — thousands per day per instance.

**Storage, extrapolated to a 60-second clip:** ~190 objects and **~80 MB**, of which the 1080p
rung is roughly half. That last number is the main lever: **capping the ladder at v1 (720p)
would roughly halve storage**, at the cost of the flagship quality tier. Most mobile viewers
never select 1080p.

**Per-job disk:** ~190 MB (source + ladder) at the configured limits, so ~380 MB at concurrency
2. The compose service mounts `/work` as a `size=2G,mode=1777` tmpfs, which both bounds disk
usage and keeps segment writes off the container's writable layer. `mode=1777` is required, not
decorative — the container runs as an unprivileged user and a tmpfs mounted without an explicit
mode is root-owned and unwritable by it.

---

## 10. Cover art: sprite sheet, not a synchronous crop

The IG pattern — hover the video, pick a frame — needs the client to be able to show candidates
*before* choosing, with no round trip per frame.

The transcoder is the only component with ffmpeg, so it produces:

- **`poster.jpg`** — one frame at 1080x1920, sampled past the first half-second (a frame at
  t=0 is very often a half-opened camera or black, and makes a terrible default cover).
- **`sprite.jpg`** — up to 100 candidate frames tiled 10x10, in a **single ffmpeg pass** using a
  `select` filter over an explicit frame list plus the `tile` layout filter. Sampling N frames
  individually and compositing them separately would re-decode the source N times.

The client receives `spriteSheetUrl`, `spriteFrameCount`, `spriteColumns` and a per-tile interval
array, maps a pointer position to a tile locally, and sends back **only the tile index**:

```
PUT /api/v1/videos/{id}/thumbnail   { "tileIndex": 7 }
```

An index rather than a URL or a timestamp, because the sheet is immutable for a given video — an
index is the only form that cannot be used to point the platform at an arbitrary location.
While `thumbnailTileIndex` is null, clients fall back to `posterUrl`.

---

## 11. Dependency boundaries

`common-contracts` is a **shared kernel**: event records, one failure-reason enum, and topology
constants. It has **no Spring, no Jackson, no AMQP, no AWS** — deliberately, because adding any
of them turns a contract module into a coupling vector.

```
        common-contracts  (records only, zero dependencies)
           ▲                        ▲
           │                        │
   video-service              transcoder-service
```

Neither service depends on the other. Neither has a datasource. The transcoder cannot corrupt the
`videos` table even if it wanted to.

**The `__TypeId__` problem.** `JacksonJsonMessageConverter` stamps the producer's fully-qualified
class name into message headers and tries to resolve it on the way back in. Because both sides
now compile against the shared records, that resolves today — but a rename would otherwise make
a consumer throw on a message it can perfectly well read from its JSON body. Both services
therefore configure an explicit id→class map and `TypePrecedence.INFERRED`, so an unmappable
header degrades to "deserialize from the body" instead of dead-lettering every message.

---

## 12. Running it

```bash
docker compose up -d
./scripts/e2e-pipeline-test.sh      # full pipeline, including negative cases
```

The compose stack brings up Postgres, RabbitMQ, Redis, LocalStack (S3), video-service and the
transcoder. `scripts/init-local-storage.sh` creates the bucket and applies the CORS rule a
presigned browser upload needs.

```bash
./mvnw -pl transcoder-service verify   # unit tests + real-ffmpeg integration tests
./mvnw -pl video-service test
```

`HlsCommandBuilderIT` runs the **actual command the pipeline would build** against the real
ffmpeg and inspects the real output tree. It is the only test that catches a filter ffmpeg
rejects or a variant map it mis-parses.

Three genuine bugs were caught by tests during development, all of which would have shipped:

1. **`-short` is not a valid ffmpeg option** — it is `-shortest`. Every job would have failed.
2. **A stray separator in `var_stream_map`** produced `v:0,a:0   v:1,a:1`, which ffmpeg tolerates
   but nobody should ship.
3. **Rotation detection was silently dead.** ffprobe's `side_data_type` is literally
   `"Display Matrix"` with a space, so a `contains("displaymatrix")` check on the lowercased value
   never matched. Every portrait phone clip would have been validated and laddered as landscape.
   `MediaProbeRotationTest` now asserts against the real ffprobe string.

Two more were found later, both in auth-service, and both by the e2e suite rather than by unit
tests with mocks:

4. **Reuse detection rolled back its own revocation.** `rotate` revoked the token family and then
   threw, so the surrounding transaction rolled the revocation back with it - leaving exactly the
   token that had just been identified as stolen still working. Fixed with `REQUIRES_NEW`. A mocked
   repository cannot catch this; only a real transaction can.
5. **Account lockout never engaged at all.** The failure counter was incremented in the same
   transaction that then threw, so the count never left zero. Brute-force protection that looked
   implemented and was not. Fixed with a dedicated `LoginAttemptService` on `REQUIRES_NEW`.

**Local note:** presigned URLs are minted with the in-container endpoint (`localstack:4566`),
which the host cannot resolve, so the e2e script rewrites it to the published port. Against real
S3 no rewrite is needed.

---

## 13. Known gaps

Honest list of what is not done:

| Gap | Impact | Recommendation |
|---|---|---|
| **Display-matrix rotation unverified end to end** | Our probe logic is unit-tested, but ffmpeg's own decode-time rotation has **not** been exercised, because ffmpeg 8 cannot *write* a display matrix (`-display_rotation` is input-only and `-metadata rotate=` is silently ignored), so no rotated fixture can be produced here. A sideways feed is the symptom if the assumption is wrong. | **Test with a real portrait iPhone clip before trusting it.** If ffmpeg is not applying the matrix, add `-noautorotate` handling and a `transpose` filter driven by the probed angle. |
| **No multipart upload** | A network drop on cellular restarts the whole upload. Not resumable. | Keep single PUT for now (acceptable at 15-40 MB with client-side retry). Add presigned multipart when mobile failure data justifies it. |
| **No signed playback URLs** | Manifests and segments are publicly fetchable by anyone with the key. | Add CloudFront signed cookies/URLs before public launch, or accept the exposure for a feed app. |
| **No per-user upload rate limit** | A single user can flood the queue. | Add a Redis token bucket on `POST /api/v1/videos`. |
| **No transcoder progress reporting** | A video is `PROCESSING` with no percentage. | `-progress pipe:1` is available; needs a status column and a throttled publisher. |
| **No DLQ replay tooling** | Dead-lettered jobs need manual RabbitMQ surgery. | A small admin endpoint that re-enqueues from the DLQ with an attempt counter. |
| **No reconciliation job** | A video stuck in `PROCESSING` (e.g. event lost) never self-heals. | Scheduled job comparing `PROCESSING` rows against manifest existence. |
| **`raw/` retention unbounded** | Storage grows without limit. | Add the S3 lifecycle rule from §8. |
| **HEVC/AV1 not offered** | H.264 only. | Fine for now. HEVC is a reasonable later rung for modern devices. |
| ~~`X-Gateway-Secret` never validated~~ | **Fixed.** Split into `JWT_SIGNING_SECRET` and `GATEWAY_INTERNAL_SECRET`, with every service behind the gateway requiring the latter and controllers reading identity from an injected `CallerIdentity` rather than the raw header. | Verified by `CallerIdentityFilterTest` and the e2e suite. |
| **Static AWS credentials** | Compose injects throwaway LocalStack credentials, and a real bucket is configured through a local `video-service/.env` that is gitignored and was never committed. Nothing has leaked, but the service authenticates with long-lived keys rather than an IAM role. | **Move to an IAM role** before production. Not a leak to remediate. |
| **Display-matrix rotation unverified end to end** | Our probe logic is unit-tested, but ffmpeg's own decode-time rotation has **not** been exercised, because ffmpeg 8 cannot *write* a display matrix (`-display_rotation` is input-only and `-metadata rotate=` is silently ignored), so no rotated fixture can be produced here. A sideways feed is the symptom if the assumption is wrong. | **Test with a real portrait iPhone clip before trusting it.** If ffmpeg is not applying the matrix, add `-noautorotate` handling and a `transpose` filter driven by the probed angle. |
