# syntax=docker/dockerfile:1
# Smart Topics Studio as a static page behind nginx (unprivileged, port 8080).
# The page is generated during the image build from the same jars as the tools image:
#   StudioExport (all twin scenarios) -> studio.json -> tools/build_studio.py -> index.html
#
#   docker build -f deploy/docker/studio.Dockerfile -t opentsx/studio:3.0.1-local deploy/docker/.stage
#
# The two generator stages run on the build platform ($BUILDPLATFORM) and only produce HTML, so an arm64 image
# (docker buildx build --platform linux/arm64 ...) needs no emulation either.
ARG JRE_IMAGE=eclipse-temurin:21.0.8_9-jre-noble
ARG PYTHON_IMAGE=python:3.12.9-slim-bookworm
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.27.4-alpine

FROM --platform=$BUILDPLATFORM ${JRE_IMAGE} AS export
COPY opt/opentsx /opt/opentsx
ENV OPENTSX_HOME=/opt/opentsx
RUN ["/opt/opentsx/bin/opentsx", "studio-export", "/tmp/studio.json"]

FROM --platform=$BUILDPLATFORM ${PYTHON_IMAGE} AS page
COPY studio /src
COPY --from=export /tmp/studio.json /tmp/studio.json
RUN ["python3", "/src/tools/build_studio.py", "/tmp/studio.json"]

FROM ${NGINX_IMAGE}
LABEL org.opencontainers.image.title="opentsx-studio" \
      org.opencontainers.image.description="OpenTSx Smart Topics Studio (static prototype)" \
      org.opencontainers.image.source="https://github.com/kamir/OpenTSx" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=page /src/docs/prototypes/smart-topics-studio.html /usr/share/nginx/html/index.html
EXPOSE 8080
