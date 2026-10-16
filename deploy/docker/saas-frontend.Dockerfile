# syntax=docker/dockerfile:1
# OpenTSx SaaS frontend (React, including the web workbench at /workbench) as static files behind nginx
# (unprivileged, port 8080). The build context is the frontend directory:
#
#   docker build -f deploy/docker/saas-frontend.Dockerfile -t opentsx/saas-frontend:3.0.1-local opentsx-saas-frontend
#
# The build stage runs on the build platform ($BUILDPLATFORM) and only produces static files, so an arm64 image
# needs no emulation. VITE_API_BASE_URL is the address of the SaaS backend as seen from the BROWSER; the workbench
# itself needs no backend.
ARG NODE_IMAGE=node:22.14.0-alpine3.21
ARG NGINX_IMAGE=nginxinc/nginx-unprivileged:1.27.4-alpine

FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

FROM ${NGINX_IMAGE}
LABEL org.opencontainers.image.title="opentsx-saas-frontend" \
      org.opencontainers.image.description="OpenTSx SaaS frontend with the web workbench (static build)" \
      org.opencontainers.image.source="https://github.com/kamir/OpenTSx" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
