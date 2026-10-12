#!/usr/bin/env bash
# Makes the two images available to k3s' containerd.
#
#   deploy/k3s/11-import-images.sh [--method docker|podman|nerdctl|buildah|registry|ssh|none] [--ssh user@host]
#
# Methods (default IMAGE_METHOD from .env, "docker"):
#   docker    docker save <images> | sudo k3s ctr images import -          (k3s on this host)
#   podman    podman save ... | sudo k3s ctr images import -
#   nerdctl   nerdctl save ... | sudo k3s ctr images import -               (images built in another namespace)
#   buildah   buildah push <image> docker-archive:<tmp>; sudo k3s ctr images import <tmp>
#   ssh       docker|podman save ... | ssh <user@host> sudo k3s ctr images import -   (k3s on another machine, e.g. a Pi)
#   registry  <builder> push to OPENTSX_IMAGE_REPO (e.g. registry.lan:5000/opentsx); k3s pulls from there.
#             An insecure/plain-HTTP registry must be listed in /etc/rancher/k3s/registries.yaml on every node.
#   none      nothing to do (e.g. built with nerdctl directly into the k8s.io namespace of k3s' containerd)
# Air-gapped alternative: copy a `docker save` tarball to /var/lib/rancher/k3s/agent/images/ and restart k3s.
#
# The manifests use imagePullPolicy IfNotPresent and a fixed tag: after re-importing the same tag run
# 30-deploy.sh --restart so the pods pick up the new image.
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

METHOD=""; SSH_TARGET="${K3S_SSH:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --method) METHOD="${2:?--method needs a value}"; shift 2 ;;
    --ssh) SSH_TARGET="${2:?--ssh needs user@host}"; METHOD="${METHOD:-ssh}"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
done
load_env
METHOD="${METHOD:-$IMAGE_METHOD}"
IMAGES=("$TOOLS_IMAGE" "$STUDIO_IMAGE")

saver() {   # first available of docker/podman
  if have docker && docker info >/dev/null 2>&1; then echo docker
  elif have podman; then echo podman
  else die "neither a running docker nor podman found to save the images"; fi
}
local_ctr() {
  have k3s || die "k3s not found on this host - use --method ssh --ssh user@k3s-host or --method registry"
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
    [[ -n "$SSH_TARGET" ]] || die "--method ssh needs --ssh user@host (or K3S_SSH)"
    s="$(saver)"
    info "importing ${IMAGES[*]} on $SSH_TARGET ($s save | ssh ... k3s ctr images import)"
    "$s" save "${IMAGES[@]}" | ssh "$SSH_TARGET" sudo k3s ctr images import -
    echo "check on the node: ssh $SSH_TARGET sudo k3s ctr images ls | grep $OPENTSX_IMAGE_REPO"
    exit 0 ;;
  registry)
    [[ "$OPENTSX_IMAGE_REPO" == */* ]] || die "OPENTSX_IMAGE_REPO=$OPENTSX_IMAGE_REPO has no registry host - e.g. 20-configure.sh OPENTSX_IMAGE_REPO=registry.lan:5000/opentsx, then rebuild"
    s="$(saver)"
    for img in "${IMAGES[@]}"; do info "pushing $img"; "$s" push "$img"; done
    echo "k3s pulls from ${OPENTSX_IMAGE_REPO%%/*}; a plain-HTTP registry needs /etc/rancher/k3s/registries.yaml on each node"
    exit 0 ;;
  none) info "IMAGE_METHOD=none - nothing to import"; exit 0 ;;
  *) die "unknown method $METHOD (see --help)" ;;
esac

info "images in k3s containerd:"
as_root k3s ctr images ls -q | grep -F -e "${TOOLS_IMAGE##*/}" -e "${STUDIO_IMAGE##*/}" || die "images not found in k3s containerd after import"
echo "next: deploy/k3s/20-configure.sh (if not done) and deploy/k3s/30-deploy.sh"
