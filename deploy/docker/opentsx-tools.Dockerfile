# syntax=docker/dockerfile:1
# OpenTSx tools image: one JRE image with every v2 command (windpark-sim, stats, metadata, retrieval-server,
# twin, house, boat, studio-export, kafscale-check). Build context = the staged directory:
#
#   deploy/docker/stage.sh [--with-s3]                                   # after mvn install -DskipTests
#   docker build -f deploy/docker/opentsx-tools.Dockerfile -t opentsx/tools:3.0.0-local deploy/docker/.stage
#
# There is no RUN step, so cross-building for the Raspberry Pi needs no emulation:
#   docker buildx build --platform linux/arm64 -f ... -t opentsx/tools:3.0.0-local-arm64 --load deploy/docker/.stage
# (the jars are platform-independent; eclipse-temurin publishes amd64 and arm64 variants).
ARG JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble
FROM ${JRE_IMAGE}

LABEL org.opencontainers.image.title="opentsx-tools" \
      org.opencontainers.image.description="OpenTSx v2 command-line tools and services" \
      org.opencontainers.image.source="https://github.com/kamir/OpenTSx" \
      org.opencontainers.image.licenses="Apache-2.0"

ENV OPENTSX_HOME=/opt/opentsx \
    PATH=/opt/opentsx/bin:$PATH \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

COPY opt/opentsx /opt/opentsx

# numeric non-root user (no useradd -> no RUN step); writable state lives in mounted volumes (/data, /state)
USER 10001:10001
WORKDIR /tmp
ENTRYPOINT ["/opt/opentsx/bin/opentsx"]
CMD ["help"]
