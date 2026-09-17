FROM eclipse-temurin:25-jdk-alpine
RUN apk add --no-cache ffmpeg
COPY target/transcoder-service-1.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]