#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUT_DIR="$PROJECT_ROOT/target"

DOCKER_IMAGE="opentsx-docs-pdf:node20"
DOCKERFILE_PATH="$PROJECT_ROOT/bin/Dockerfile.docs-pdf"

run_in_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "[ERROR] docker is not installed or not in PATH."
    echo "[HINT] Install honkit (npm install -g honkit) or use Node 18/20 via nvm."
    exit 1
  fi

  if ! docker image inspect "$DOCKER_IMAGE" >/dev/null 2>&1; then
    echo "[INFO] Building cached Docker image $DOCKER_IMAGE"
    docker build -t "$DOCKER_IMAGE" -f "$DOCKERFILE_PATH" "$PROJECT_ROOT"
  fi

  echo "[INFO] Running PDF build in Docker ($DOCKER_IMAGE)"
  docker run --rm \
    -v "$PROJECT_ROOT:/work" \
    -w /work \
    -e OPENTSX_PDF_IN_DOCKER=1 \
    "$DOCKER_IMAGE" \
    bash -lc "./bin/build_docs_pdfs.sh"
  exit 0
}

BOOK_CMD=""
NEED_DOCKER=false

if command -v honkit >/dev/null 2>&1; then
  BOOK_CMD="honkit"
elif command -v gitbook >/dev/null 2>&1; then
  BOOK_CMD="gitbook"
  if command -v node >/dev/null 2>&1; then
    NODE_MAJOR=$(node -v | sed 's/^v//' | cut -d. -f1)
    if [ "$NODE_MAJOR" -gt 18 ]; then
      if [ "${OPENTSX_PDF_IN_DOCKER:-}" = "1" ]; then
        echo "[ERROR] gitbook-cli is incompatible with Node ${NODE_MAJOR} inside container."
        exit 1
      fi
      NEED_DOCKER=true
    fi
  fi
else
  NEED_DOCKER=true
fi

if [ "$NEED_DOCKER" = true ]; then
  if [ "${OPENTSX_PDF_IN_DOCKER:-}" = "1" ]; then
    echo "[ERROR] honkit or gitbook not found in container."
    exit 1
  fi
  run_in_docker
fi

if ! command -v calibredb >/dev/null 2>&1; then
  if [ -x "/Applications/calibre.app/Contents/MacOS/calibredb" ]; then
    export PATH="/Applications/calibre.app/Contents/MacOS:$PATH"
  else
    if [ "${OPENTSX_PDF_IN_DOCKER:-}" = "1" ]; then
      echo "[ERROR] Calibre (calibredb) not found in container."
      exit 1
    fi
    run_in_docker
  fi
fi

if [ "${OPENTSX_PDF_IN_DOCKER:-}" = "1" ]; then
  export QTWEBENGINE_CHROMIUM_FLAGS="--no-sandbox"
  export XDG_RUNTIME_DIR="/tmp/runtime-root"
  mkdir -p "$XDG_RUNTIME_DIR"
  chmod 700 "$XDG_RUNTIME_DIR"
fi

mkdir -p "$OUT_DIR"

build_pdf() {
  local dir="$1"
  local out="$2"
  if [ ! -f "$dir/README.md" ] || [ ! -f "$dir/SUMMARY.md" ]; then
    echo "[ERROR] Missing README.md or SUMMARY.md in $dir"
    exit 1
  fi
  echo "[INFO] Building PDF for $dir"
  (cd "$dir" && "$BOOK_CMD" pdf . "$out")
}

build_pdf "$PROJECT_ROOT/docs/manual" "$OUT_DIR/OpenTSx-Manual.pdf"
build_pdf "$PROJECT_ROOT/docs/onboarding" "$OUT_DIR/OpenTSx-Onboarding.pdf"
build_pdf "$PROJECT_ROOT/docs/devguide" "$OUT_DIR/OpenTSx-DevGuide.pdf"

echo "[INFO] PDFs written to $OUT_DIR"
