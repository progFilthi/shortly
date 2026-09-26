# syntax=docker/dockerfile:1
#
# Transcoder worker image.
#
# Reproducibility note: ffmpeg is PINNED deliberately. An unpinned `apk add ffmpeg` means an
# Alpine base bump silently changes your encoder, which changes output bitrates, GOP structure
# and possibly master-playlist metadata. For a media pipeline you want that to fail the build,
# not to ship quietly. Bump FFMPEG_VERSION and re-verify the ladder when upgrading.

ARG JAVA_IMAGE=eclipse-temurin:25-jre-alpine
ARG FFMPEG_VERSION=8.1.2-r0

FROM ${JAVA_IMAGE}

ARG FFMPEG_VERSION

# ffmpeg's apk package ships /usr/bin/ffmpeg AND /usr/bin/ffprobe; there is no separate
# ffprobe package on Alpine.
# shellcheck disable=SC2086
RUN apk add --no-cache ffmpeg=${FFMPEG_VERSION} && \
    ffmpeg -version | head -1 && \
    ffprobe -version | head -1

# Unprivileged runtime user. ffmpeg never needs root; running the worker as root would
# widen the blast radius of any parsing bug in a hostile media file.
RUN addgroup -S -g 1001 transcoder && \
    adduser  -S -u 1001 -G transcoder -H -s /sbin/nologin transcoder && \
    mkdir -p /work && \
    chown -R transcoder:transcoder /work

WORKDIR /app

# Built as: ./mvnw -pl transcoder-service -am package
# finalName is pinned in pom.xml so this path does not drift with the project version.
COPY --chown=transcoder:transcoder target/transcoder-service.jar app.jar

USER transcoder

# ffmpeg scratch space is configured via transcoder.work-dir (TRANSCODER_WORK_DIR), NOT via
# java.io.tmpdir. Pointing java.io.tmpdir at the same mount makes Tomcat's temp directory
# collide with the transcoder's job directories, and it fails at startup with an opaque
# AccessDeniedException the moment that mount is not world-writable.
ENV TRANSCODER_WORK_DIR=/work \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# The worker has no business endpoints, only liveness/readiness. Marked unhealthy if the
# AMQP connection is down, which is what actually stops it making progress.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
