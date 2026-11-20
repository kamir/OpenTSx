#!/usr/bin/env bash
# Makes the two images available to k3s' containerd.
#
#   deploy/k3s/11-import-images.sh [--method docker|podman|nerdctl|buildah|kind|registry|ssh|none] [--ssh user@host]
#                                  [--kind-cluster <name>]
#
# Methods (default IMAGE_METHOD from .env, "docker"):
#   docker    docker save <images> | sudo k3s ctr images import -          (k3s on this host)
#   podman    podman save ... | sudo k3s ctr images import -
#   nerdctl   nerdctl save ... | sudo k3s ctr images import -               (images built in another namespace)
#   buildah   buildah push <image> docker-archive:<tmp>; sudo k3s ctr images import <tmp>
#   kind      kind load docker-image <images> --name <cluster>                      (a kind cluster instead of k3s,
#             e.g. for a trial run on macOS, where k3s does not run natively; the cluster name is required,
#             via --kind-cluster or KIND_CLUSTER, so that no unrelated cluster is written to by accident)
#   ssh       docker|podman save ... | ssh <user@host> sudo k3s ctr images import -   (k3s on another machine, e.g. a Pi)
#   registry  <builder> push to OPENTSX_IMAGE_REPO (e.g. registry.lan:5000/opentsx); k3s pulls from there.
#             An insecure/plain-HTTP registry must be listed in /etc/rancher/k3s/registries.yaml on every node.
#   none      nothing to do (e.g. built with nerdctl directly into the k8s.io namespace of k3s' containerd)
# Air-gapped alternative: copy a `docker save` tarball to /var/lib/rancher/k3s/agent/images/ and restart k3s.
#
# The manifests use imagePullPolicy IfNotPresent and a fixed tag: after re-importing the same tag run
# 30-deploy.sh --restart so the pods pick up the new image.
#
# Exit codes:
#   0  imported (or nothing to do with the method none)
#   1  the tool of the method is missing, saving, importing or pushing failed, or the images are not in k3s'
#      containerd afterwards
#   2  usage error: an unknown option, an option without its value, an argument, a method that is not one of the
#      list (from --method or IMAGE_METHOD), the method ssh without --ssh or K3S_SSH, the method kind without
#      --kind-cluster or KIND_CLUSTER, or a line of deploy/k3s/.env that is not a comment or KEY=VALUE with a known key
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

METHODS="docker, podman, nerdctl, buildah, kind, registry, ssh or none"
METHOD=""; METHOD_FROM=--method; SSH_TARGET="${K3S_SSH:-}"; KIND_CLUSTER="${KIND_CLUSTER:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --method) [[ -n "${2:-}" ]] || usage_error "--method: missing value; expected $METHODS"; METHOD="$2"; shift 2 ;;
    --ssh)
      [[ -n "${2:-}" ]] || usage_error "--ssh: missing value; expected user@host"
      SSH_TARGET="$2"; METHOD="${METHOD:-ssh}"; shift 2 ;;
    --kind-cluster)
      [[ -n "${2:-}" ]] || usage_error "--kind-cluster: missing value; expected the name of a kind cluster"
      KIND_CLUSTER="$2"; METHOD="${METHOD:-kind}"; shift 2 ;;
    -h|--help) usage 0 ;;
    -*) usage_error "$1: unknown option; expected --method, --ssh, --kind-cluster or --help" ;;
    *) usage_error "$1: unexpected argument; 11-import-images.sh takes options only" ;;
  esac
done
load_env
[[ -n "$METHOD" ]] || { METHOD="$IMAGE_METHOD"; METHOD_FROM=IMAGE_METHOD; }
IMAGES=("$TOOLS_IMAGE" "$STUDIO_IMAGE")
if is_true "$SAAS"; then IMAGES+=("$SAAS_FRONTEND_IMAGE" "$SAAS_BACKEND_IMAGE"); fi

saver() {   # first available of docker/podman
  if have docker && docker info >/dev/null 2>&1; then echo docker
  elif have podman; then echo podman
  else die "neither a running docker nor podman found to save the images"; fi
}
local_ctr() {
  have k3s || die "k3s: not found on this host; use --method ssh --ssh user@k3s-host or --method registry"
}

case "$METHOD" in
  docker|podman)
    have "$METHOD" || die "$METHOD not found"
    local_ctr
    info "importing ${IMAGES[*]} into k3s containerd ($METHOD save | k3s ctr images import)"
    "$METHOD" save "${IMAGES[@]}" | as_root k3s ctr images import - ;;
  nerdctl)
    local_ctr
    read -r -a N <<< "${NERDCTL:-nerdctl}"
    info "importing via nerdctl save"
    "${N[@]}" save "${IMAGES[@]}" | as_root k3s ctr images import - ;;
  buildah)
    have buildah || die "buildah not found"
    local_ctr
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
    for img in "${IMAGES[@]}"; do
      info "importing $img via buildah docker-archive"
      buildah push "$img" "docker-archive:$tmp/image.tar:$img"
      as_root k3s ctr images import "$tmp/image.tar"
      rm -f "$tmp/image.tar"
    done ;;
  ssh)
    [[ -n "$SSH_TARGET" ]] || usage_error "--ssh: missing; the method ssh needs --ssh user@host or K3S_SSH"
    s="$(saver)"
    info "importing ${IMAGES[*]} on $SSH_TARGET ($s save | ssh ... k3s ctr images import)"
    "$s" save "${IMAGES[@]}" | ssh "$SSH_TARGET" sudo k3s ctr images import -
    echo "check on the node: ssh $SSH_TARGET sudo k3s ctr images ls | grep $OPENTSX_IMAGE_REPO"
    exit 0 ;;
  kind)
    [[ -n "$KIND_CLUSTER" ]] || usage_error "--kind-cluster: missing; the method kind needs --kind-cluster <name> or KIND_CLUSTER (kind get clusters lists them)"
    have kind || die "kind: not found; install kind, or use --method ssh or --method registry for a remote k3s"
    kind get clusters 2>/dev/null | grep -qxF "$KIND_CLUSTER" || die "kind cluster '$KIND_CLUSTER' not found (kind get clusters)"
    info "loading ${IMAGES[*]} into kind cluster $KIND_CLUSTER (kind load docker-image)"
    kind load docker-image "${IMAGES[@]}" --name "$KIND_CLUSTER"
    echo "check on the node: docker exec ${KIND_CLUSTER}-control-plane crictl images | grep ${OPENTSX_IMAGE_REPO##*/}"
    exit 0 ;;
  registry)
    [[ "$OPENTSX_IMAGE_REPO" == */* ]] || die "OPENTSX_IMAGE_REPO: \"$OPENTSX_IMAGE_REPO\" has no registry host; set one with 20-configure.sh OPENTSX_IMAGE_REPO=registry.lan:5000/opentsx, then rebuild with 10-build.sh"
    s="$(saver)"
    for img in "${IMAGES[@]}"; do info "pushing $img"; "$s" push "$img"; done
    echo "k3s pulls from ${OPENTSX_IMAGE_REPO%%/*}; a plain-HTTP registry needs /etc/rancher/k3s/registries.yaml on each node"
    exit 0 ;;
  none) info "$METHOD_FROM none: nothing to import"; exit 0 ;;
  *) usage_error "$METHOD_FROM: \"$METHOD\" is not a method; expected $METHODS" ;;
esac

info "images in k3s containerd:"
as_root k3s ctr images ls -q | grep -F -e "${TOOLS_IMAGE##*/}" -e "${STUDIO_IMAGE##*/}" || die "images not found in k3s containerd after import"
echo "next: deploy/k3s/20-configure.sh (if not done) and deploy/k3s/30-deploy.sh"
