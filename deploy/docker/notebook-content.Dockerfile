# syntax=docker/dockerfile:1
# OpenTSx for the notebook of a stack: the Python package "opentsx" with the two libraries it needs beyond a
# scientific notebook image (fastavro, xxhash), and the OpenTSx notebooks. The image runs nothing: an init container
# of the notebook copies /opentsx into the notebook's home, and the notebook puts /home/jovyan/opentsx/site-packages
# on its PYTHONPATH (deploy/k3s/notebook/). numpy, pandas, scipy and matplotlib come from the notebook image.
#
#   docker build -f deploy/docker/notebook-content.Dockerfile -t opentsx/notebook-content:<tag> python-package
#
# PYTHON_IMAGE must have the Python version of the notebook (3.13 for quay.io/jupyter/scipy-notebook 2026-10), since
# fastavro and xxhash are compiled per version.
ARG PYTHON_IMAGE=python:3.13-slim-bookworm
ARG BASE_IMAGE=alpine:3.20

FROM ${PYTHON_IMAGE} AS build
COPY . /src
RUN pip install --no-cache-dir --target /opentsx/site-packages --no-deps /src \
 && pip install --no-cache-dir --target /opentsx/site-packages "fastavro>=1.9,<2" "xxhash>=3,<4" \
 && mkdir -p /opentsx/notebooks && cp /src/notebooks/*.ipynb /opentsx/notebooks/ \
 && printf 'OpenTSx package and notebooks, copied on every start of the notebook.\nCopy a notebook before you change it: this folder is replaced.\n' > /opentsx/README.txt

FROM ${BASE_IMAGE}
LABEL org.opencontainers.image.title="opentsx-notebook-content" \
      org.opencontainers.image.description="OpenTSx Python package and notebooks for the notebook of a stack" \
      org.opencontainers.image.source="https://github.com/kamir/OpenTSx" \
      org.opencontainers.image.licenses="Apache-2.0"
COPY --from=build /opentsx /opentsx
USER 1000:100
