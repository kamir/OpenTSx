# syntax=docker/dockerfile:1
# OpenTSx SaaS backend (FastAPI: accounts, organizations, stored flows), unprivileged, port 8000.
# The build context is the backend directory:
#
#   docker build -f deploy/docker/saas-backend.Dockerfile -t opentsx/saas-backend:3.0.0-local opentsx-saas-backend
#
# Configuration comes from the environment (app/core/config.py): POSTGRES_*, SECRET_KEY, FIRST_SUPERUSER_*.
# The defaults in config.py are for local development only; a deployment must set SECRET_KEY and the passwords.
ARG PYTHON_IMAGE=python:3.12.9-slim-bookworm

FROM ${PYTHON_IMAGE}
LABEL org.opencontainers.image.title="opentsx-saas-backend" \
      org.opencontainers.image.description="OpenTSx SaaS backend (accounts and stored flows)" \
      org.opencontainers.image.source="https://github.com/kamir/OpenTSx" \
      org.opencontainers.image.licenses="Apache-2.0"
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin opentsx
USER 10001
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
