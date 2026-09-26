# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S -g 1001 app && \
    adduser -S -u 1001 -G app -H -s /sbin/nologin app

WORKDIR /app

# Build: ./mvnw -pl api-gateway -am package
COPY --chown=app:app target/api-gateway-0.0.1-SNAPSHOT.jar app.jar

USER app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
