#!/usr/bin/env bash
# shellcheck disable=SC2015  # ok/warn/bad always return 0, so "check && ok || bad" is a safe if-then-else
# Checks the prerequisites for running OpenTSx on k3s against an existing KafScale cluster.
#
#   deploy/k3s/00-prereqs.sh [--no-build] [--no-cluster]
#
#   --no-build     skip the build tools (JDK, Maven, Python, image builder), e.g. on a k3s node that only imports images
#   --no-cluster   skip the k3s checks, e.g. on a build machine without cluster access
#
# Reads deploy/k3s/.env (see env.example; KEY=VALUE in the environment overrides it). Checks:
#   tools (kubectl or k3s, bash >= 4, JDK 17+, Maven 3.9+, Python 3.10+, docker|podman|nerdctl|buildah),
#   k3s reachable, node architectures, default StorageClass, KafScale bootstrap reachable from this host
#   (TCP, plus advertised broker addresses via kafscale-check when the image context is staged), optional S3.
# Exit code 0 when nothing failed (warnings allowed), 1 otherwise. Changes nothing.
set -euo pipefail
# shellcheck source=deploy/k3s/lib.sh
source "$(dirname "$0")/lib.sh"

BUILD=true; CLUSTER=true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) BUILD=false; shift ;;
    --no-cluster) CLUSTER=false; shift ;;
    -h|--help) usage 0 ;;
    *) die "unknown option $1 (see --help)" ;;
  esac
done
load_env
FAILS=0
bad() { fail "$*"; FAILS=$((FAILS + 1)); }

info "shell and base tools"
(( BASH_VERSINFO[0] >= 4 )) && ok "bash $BASH_VERSION" || bad "bash >= 4 required (found $BASH_VERSION)"
for t in timeout sed awk; do have "$t" && ok "$t" || bad "$t missing"; done

if $BUILD; then
  info "build tools"
  if have java; then
    jv="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2}')"
    if [[ "${jv%%.*}" =~ ^[0-9]+$ ]] && (( ${jv%%.*} >= 17 )); then ok "JDK $jv"; else bad "JDK 17+ required (found $jv)"; fi
    have javac && ok "javac" || bad "javac missing (a JDK, not a JRE, is needed to build)"
  else bad "java missing (JDK 17 or 21)"; fi
  if have mvn; then
    mv="$(mvn -v 2>/dev/null | awk '/Apache Maven/{print $3; exit}')"
    [[ "$mv" =~ ^3\.(9|[1-9][0-9])|^[4-9] ]] && ok "Maven $mv" || warn "Maven 3.9+ recommended (found ${mv:-?})"
  else bad "mvn missing (Maven 3.9+)"; fi
  if have python3; then
    pv="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
    python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' && ok "Python $pv" \
      || warn "Python 3.10+ recommended for tools/build_studio.py (found $pv); the studio image builds it in a container anyway"
  else warn "python3 missing (only needed to build the Studio page outside the image build)"; fi
  builders=()
  for b in docker podman nerdctl buildah; do have "$b" && builders+=("$b"); done
  if (( ${#builders[@]} )); then ok "image builder(s): ${builders[*]}"; else bad "no image builder (docker, podman, nerdctl or buildah)"; fi
  if have docker && ! docker info >/dev/null 2>&1; then warn "docker CLI found but the daemon is not reachable (permissions? not started?)"; fi
  if [[ -n "$OPENTSX_PLATFORM" ]] && have docker && ! docker buildx version >/dev/null 2>&1; then
    bad "OPENTSX_PLATFORM=$OPENTSX_PLATFORM needs docker buildx"
  fi
fi

if $CLUSTER; then
  info "k3s cluster"
  if have k3s; then ok "k3s $(k3s --version 2>/dev/null | awk 'NR==1{print $3}')"; else warn "k3s binary not on this host (fine if the cluster is remote; images then go via IMAGE_METHOD=ssh or registry)"; fi
  if kc version --client >/dev/null 2>&1; then ok "kubectl: ${KUBECTL}"; else bad "kubectl not usable (KUBECTL=\"$KUBECTL\"; on a k3s host try KUBECTL=\"sudo k3s kubectl\" or export KUBECONFIG=/etc/rancher/k3s/k3s.yaml)"; fi
  if nodes="$(kc get nodes -o 'jsonpath={range .items[*]}{.metadata.name} {.status.nodeInfo.architecture} {.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null)"; then
    while read -r n arch ready; do
      [[ -z "$n" ]] && continue
      [[ "$ready" == "True" ]] && ok "node $n ($arch) Ready" || bad "node $n ($arch) not Ready"
      if [[ -n "$OPENTSX_PLATFORM" && "$OPENTSX_PLATFORM" != "linux/$arch" ]]; then warn "node $n is $arch but OPENTSX_PLATFORM=$OPENTSX_PLATFORM"; fi
      if [[ -z "$OPENTSX_PLATFORM" ]] && have uname && [[ "$(uname -m)" =~ ^(x86_64|amd64)$ && "$arch" == "arm64" ]]; then
        warn "node $n is arm64, this host is amd64: set OPENTSX_PLATFORM=linux/arm64"; fi
    done <<< "$nodes"
    sc="$(kc get storageclass -o 'jsonpath={range .items[?(@.metadata.annotations.storageclass\.kubernetes\.io/is-default-class=="true")]}{.metadata.name}{end}' 2>/dev/null || true)"
    if [[ -n "$STORAGE_CLASS" ]]; then
      kc get storageclass "$STORAGE_CLASS" >/dev/null 2>&1 && ok "StorageClass $STORAGE_CLASS" || bad "StorageClass $STORAGE_CLASS not found"
    elif [[ -n "$sc" ]]; then ok "default StorageClass $sc"
    else bad "no default StorageClass (k3s ships local-path; set STORAGE_CLASS)"; fi
  else
    bad "cluster not reachable with '$KUBECTL get nodes'"
  fi
fi

info "KafScale (existing cluster)"
if [[ -z "$KAFSCALE_BOOTSTRAP" || "$KAFSCALE_BOOTSTRAP" == "192.168.1.50:9092" ]]; then
  warn "KAFSCALE_BOOTSTRAP is the example value - set it: deploy/k3s/20-configure.sh KAFSCALE_BOOTSTRAP=<host:port>"
fi
IFS=',' read -r -a boots <<< "$KAFSCALE_BOOTSTRAP"
for b in "${boots[@]}"; do
  if [[ "${b%:*}" =~ ^(localhost|127\.) && "$KAFSCALE_NETWORK" != "hostnetwork" ]]; then
    bad "bootstrap $b is loopback: pods cannot reach it. Use the host's LAN IP, or KAFSCALE_NETWORK=hostnetwork"
  fi
  if tcp_check "$b" 5; then ok "bootstrap $b reachable from this host (TCP)"; else bad "bootstrap $b NOT reachable from this host (TCP)"; fi
done
STAGED="$ROOT/deploy/docker/.stage/opt/opentsx/bin/opentsx"
if [[ -x "$STAGED" ]] && have java; then
  echo "  kafscale-check from this host (advertised broker addresses, topics of domain $OPENTSX_DOMAIN):"
  if OPENTSX_HOME="$ROOT/deploy/docker/.stage/opt/opentsx" JAVA_TOOL_OPTIONS="" "$STAGED" kafscale-check \
       --kafscale "$KAFSCALE_BOOTSTRAP" --domain "$OPENTSX_DOMAIN" 2>/dev/null | sed 's/^/    /'; then
    ok "kafscale-check passed (repeat it from inside the cluster: 40-demo.sh kafscale-check)"
  else
    bad "kafscale-check failed - see above (most common: the broker advertises an address the clients cannot reach)"
  fi
else
  warn "advertised broker addresses not checked (run 10-build.sh first, then repeat; or later: 40-demo.sh kafscale-check)"
fi

if is_true "$LAKE"; then
  info "lake profile (Iceberg REST + S3)"
  if is_true "$LAKE_MINIO"; then ok "S3: in-cluster MinIO (nothing to check from here)"
  else
    [[ -n "$S3_ENDPOINT" ]] || bad "LAKE=true and LAKE_MINIO=false need S3_ENDPOINT"
    hp="${S3_ENDPOINT#*://}"; hp="${hp%%/*}"
    [[ "$hp" == *:* ]] || { [[ "$S3_ENDPOINT" == https* ]] && hp="$hp:443" || hp="$hp:80"; }
    tcp_check "$hp" 5 && ok "S3 endpoint $S3_ENDPOINT reachable (TCP)" || bad "S3 endpoint $S3_ENDPOINT not reachable"
    [[ -n "$S3_ACCESS_KEY" && -n "$S3_SECRET_KEY" ]] || bad "S3_ACCESS_KEY / S3_SECRET_KEY missing"
  fi
fi

echo
if (( FAILS )); then die "$FAILS check(s) failed"; fi
info "all required checks passed"
