# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S -g 1001 app && \
    adduser -S -u 1001 -G app -H -s /sbin/nologin app

WORKDIR /app

# Build: ./mvnw -pl video-service -am package
COPY --chown=app:app target/video-service-0.0.1-SNAPSHOT.jar app.jar

USER app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8082/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
