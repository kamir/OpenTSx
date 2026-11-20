#!/usr/bin/env bash
# Builds OpenTSx (Maven), stages the image context and builds the two images.
#
#   deploy/k3s/10-build.sh [--skip-maven] [--with-s3] [--builder docker|podman|nerdctl|buildah]
#                          [--platform linux/arm64] [--tag TAG] [--no-images]
#
#   --skip-maven   reuse an existing `mvn install` (the staging step still needs the installed jars)
#   --with-s3      add iceberg-aws + AWS SDK bundle (S3FileIO), required for the lake profile (s3:// warehouse)
#   --builder      image builder (default: first of docker, podman, nerdctl, buildah)
#   --platform     target platform, e.g. linux/arm64 for a Raspberry Pi (docker buildx / podman --platform);
#                  no emulation needed: the images have no RUN step for the target platform
#   --tag          image tag (default OPENTSX_TAG from .env, 3.0.1-local)
#   --no-images    build and stage only
#
# Images: <OPENTSX_IMAGE_REPO>/tools:<tag> (JRE + all jars + entrypoint) and <OPENTSX_IMAGE_REPO>/studio:<tag>
# (nginx + the Studio page generated during the build). nerdctl tip: to build straight into k3s' containerd use
#   NERDCTL="nerdctl --address /run/k3s/containerd/containerd.sock --namespace k8s.io" (then IMAGE_METHOD=none).
#
# Exit codes:
#   0  built (and staged)
#   1  the Maven build, the staging or an image build failed, or no JDK, Maven or image builder was found
#   2  usage error: an unknown option, an option without its value, a --builder that is not one of the four, an
#      argument, or a line of deploy/k3s/.env that is not a comment or KEY=VALUE with a known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

MAVEN=true; WITH_S3=false; BUILDER=""; IMAGES=true; PLATFORM_OPT=""; TAG_OPT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-maven) MAVEN=false; shift ;;
    --with-s3) WITH_S3=true; shift ;;
    --builder)
      [[ -n "${2:-}" ]] || usage_error "--builder: missing value; expected docker, podman, nerdctl or buildah"
      [[ "$2" =~ ^(docker|podman|nerdctl|buildah)$ ]] \
        || usage_error "--builder: \"$2\" is not a builder; expected docker, podman, nerdctl or buildah"
      BUILDER="$2"; shift 2 ;;
    --platform)
      [[ -n "${2:-}" ]] || usage_error "--platform: missing value; expected a platform such as linux/arm64"
      PLATFORM_OPT="$2"; shift 2 ;;
    --tag) [[ -n "${2:-}" ]] || usage_error "--tag: missing value; expected an image tag such as 3.0.1-local"; TAG_OPT="$2"; shift 2 ;;
    --no-images) IMAGES=false; shift ;;
    -h|--help) usage 0 ;;
    -*) usage_error "$1: unknown option; expected --skip-maven, --with-s3, --builder, --platform, --tag, --no-images or --help" ;;
    *) usage_error "$1: unexpected argument; 10-build.sh takes options only" ;;
  esac
done
load_env
CONFIGURED_TAG="$OPENTSX_TAG"
[[ -n "$TAG_OPT" ]] && OPENTSX_TAG="$TAG_OPT"
[[ -n "$PLATFORM_OPT" ]] && OPENTSX_PLATFORM="$PLATFORM_OPT"
TOOLS_IMAGE="$OPENTSX_IMAGE_REPO/tools:$OPENTSX_TAG"
STUDIO_IMAGE="$OPENTSX_IMAGE_REPO/studio:$OPENTSX_TAG"
if is_true "$LAKE" && ! $WITH_S3; then
  warn "LAKE=true but --with-s3 not given: windpark-sim/retrieval cannot open an s3:// warehouse without S3FileIO"
fi

{ have java && have mvn; } || die "JDK 17+ and Maven are required (see 00-prereqs.sh)"
if $MAVEN; then
  info "mvn install -DskipTests (all modules)"
  (cd "$ROOT" && mvn -q -B install -DskipTests)
fi

info "staging image context"
stage_args=()
$WITH_S3 && stage_args+=(--with-s3)
"$ROOT/deploy/docker/stage.sh" ${stage_args[@]+"${stage_args[@]}"}
CTX="$ROOT/deploy/docker/.stage"
$IMAGES || { info "done (no images built)"; exit 0; }

if [[ -z "$BUILDER" ]]; then
  for b in docker podman nerdctl buildah; do have "$b" && { BUILDER="$b"; break; }; done
fi
[[ -n "$BUILDER" ]] || die "no image builder found (docker, podman, nerdctl or buildah)"

build() {   # dockerfile image [context]
  local file="$1" image="$2" CTX="${3:-$CTX}"
  info "building $image with $BUILDER${OPENTSX_PLATFORM:+ for $OPENTSX_PLATFORM}"
  case "$BUILDER" in
    docker)
      if [[ -n "$OPENTSX_PLATFORM" ]]; then
        docker buildx build --platform "$OPENTSX_PLATFORM" --load -f "$file" -t "$image" "$CTX"
      else
        docker build -f "$file" -t "$image" "$CTX"
      fi ;;
    podman)  podman build ${OPENTSX_PLATFORM:+--platform "$OPENTSX_PLATFORM"} -f "$file" -t "$image" "$CTX" ;;
    nerdctl)
      local -a n; read -r -a n <<< "${NERDCTL:-nerdctl}"
      "${n[@]}" build ${OPENTSX_PLATFORM:+--platform "$OPENTSX_PLATFORM"} -f "$file" -t "$image" "$CTX" ;;
    buildah) buildah bud ${OPENTSX_PLATFORM:+--platform "$OPENTSX_PLATFORM"} -f "$file" -t "$image" "$CTX" ;;
    *) die "unsupported builder $BUILDER" ;;
  esac
}
build "$ROOT/deploy/docker/opentsx-tools.Dockerfile" "$TOOLS_IMAGE"
build "$ROOT/deploy/docker/studio.Dockerfile" "$STUDIO_IMAGE"

if is_true "$SAAS"; then
  build "$ROOT/deploy/docker/saas-backend.Dockerfile" "$SAAS_BACKEND_IMAGE" "$ROOT/opentsx-saas-backend"
  build "$ROOT/deploy/docker/saas-frontend.Dockerfile" "$SAAS_FRONTEND_IMAGE" "$ROOT/opentsx-saas-frontend"
fi

info "built $TOOLS_IMAGE and $STUDIO_IMAGE"
echo "next: deploy/k3s/11-import-images.sh   (or IMAGE_METHOD=registry / none, see --help there)"
if [[ "$OPENTSX_TAG" != "$CONFIGURED_TAG" ]]; then
  warn "--tag: \"$OPENTSX_TAG\" differs from OPENTSX_TAG \"$CONFIGURED_TAG\"; run 20-configure.sh OPENTSX_TAG=$OPENTSX_TAG so the manifests use it"
fi
