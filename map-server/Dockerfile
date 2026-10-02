# syntax=docker/dockerfile:1
#
# Build from the repository root:
#
#   docker build -f map-server/Dockerfile -t curvemaps-map-server .
#
# The image contains the Go server plus the JVM toolchain the generation
# pipeline needs (JDK 17 for GraphHopper, JDK 21 for Planetiler) and a
# pre-built standalone geocoder distribution. It contains no Android SDK.

FROM golang:1.24-alpine AS gobuild
WORKDIR /src
COPY map-server/go.mod ./
COPY map-server/cmd ./cmd
COPY map-server/internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/map-server ./cmd/map-server

# Standalone geocoder build (JVM only; the root Gradle build would require an
# Android SDK, which is exactly what this image must not need).
FROM eclipse-temurin:17-jdk-jammy AS geocoder
WORKDIR /repo
COPY gradlew ./
COPY gradle ./gradle
COPY geocoder-tool ./geocoder-tool
RUN chmod +x gradlew \
 && ./gradlew -p geocoder-tool/standalone --quiet --no-daemon installDist

FROM eclipse-temurin:21-jdk-jammy AS runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash curl ca-certificates coreutils unzip \
 && rm -rf /var/lib/apt/lists/*
# Mirror the verified local pipeline: GraphHopper import runs on JDK 17,
# Planetiler on JDK 21.
COPY --from=eclipse-temurin:17-jdk-jammy /opt/java/openjdk /opt/java17

COPY --from=gobuild /out/map-server /app/map-server
COPY --from=geocoder /repo/geocoder-tool/build/install/geocoder-tool /app/geocoder
COPY tools/gh/config.yml /app/repo/tools/gh/config.yml
COPY tools/gh/motorcycle.json /app/repo/tools/gh/motorcycle.json
COPY tools/gh/MotoGraphImport.java /app/repo/tools/gh/MotoGraphImport.java
COPY tools/tiles/build-tiles.sh /app/repo/tools/tiles/build-tiles.sh
# The artifact fingerprint hashes every generation input, including
# geocoder-tool/build.gradle.kts and all of geocoder-tool/src/**. Those bytes
# must be present in the runtime image, byte-identical to the context the
# standalone distribution was built from, or FingerprintFor fails and every
# build request fails. (The runnable distribution itself is /app/geocoder.)
COPY geocoder-tool/build.gradle.kts /app/repo/geocoder-tool/build.gradle.kts
COPY geocoder-tool/src /app/repo/geocoder-tool/src
COPY map-server/catalog.example.json /app/catalog.example.json
# Safe heap defaults for a small shared host; operators can mount a different
# JSON config over /app/config.json and override the command to use it.
COPY map-server/config.docker.json /app/config.json

# Pinned generation tools, fetched and verified at image build time (same
# supply-chain policy as the app pipeline).
ARG PLANETILER_VERSION=0.10.2
ARG PLANETILER_SHA256=f310bd0413e2e4512b27f4046d418664e8e1d3bf31603c2a70e23de06c167e4d
ARG GRAPHHOPPER_VERSION=11.0
ARG GRAPHHOPPER_SHA256=b59c024afe172ec6ec85b6327006c3138ec58c7d0bcd26253d0e42853f613def
RUN set -eux; \
    curl -fL --retry 3 -o /app/repo/tools/tiles/planetiler.jar \
      "https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"; \
    echo "${PLANETILER_SHA256}  /app/repo/tools/tiles/planetiler.jar" | sha256sum -c -; \
    curl -fL --retry 3 -o "/app/repo/tools/gh/graphhopper-web-${GRAPHHOPPER_VERSION}.jar" \
      "https://github.com/graphhopper/graphhopper/releases/download/${GRAPHHOPPER_VERSION}/graphhopper-web-${GRAPHHOPPER_VERSION}.jar"; \
    echo "${GRAPHHOPPER_SHA256}  /app/repo/tools/gh/graphhopper-web-${GRAPHHOPPER_VERSION}.jar" | sha256sum -c -

RUN useradd --create-home --uid 10001 maps \
 && mkdir -p /data \
 && chown -R maps:maps /data /app

USER maps
ENV MOTO_REPO_ROOT=/app/repo \
    MOTO_STATE_DIR=/data \
    MOTO_JAVA17=/opt/java17/bin/java \
    MOTO_JAVA21=/opt/java/openjdk/bin/java \
    MOTO_GEOCODER_DIST=/app/geocoder \
    MOTO_ADDR=:8080
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1
ENTRYPOINT ["/app/map-server"]
CMD ["serve", "--config", "/app/config.json"]
