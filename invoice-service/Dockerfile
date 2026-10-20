# Build context is the REPO ROOT (see docker-compose.yml's `context: .`),
# not this directory - Gradle needs the whole multi-module project
# (settings.gradle.kts, the common module, the wrapper) to build any one
# service, not just that service's own folder.

# --- Stage 1: build --------------------------------------------------------
# Full JDK here (javac needs it); this stage is discarded after the jar is
# extracted below, so its size never reaches the running container.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew
# Cache mount: Gradle's dependency/download cache persists across builds on
# the same Docker host (BuildKit feature), so re-running this after a small
# code change doesn't re-download the internet again - without it, every
# `docker build` would start from zero.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :invoice-service:bootJar --no-daemon

# --- Stage 2: runtime --------------------------------------------------------
# JRE only - no compiler, no Gradle, no source - in the image that actually runs.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/invoice-service/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
