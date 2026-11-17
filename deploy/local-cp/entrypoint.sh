#!/bin/sh
set -eu
lock_hash=$(sha256sum /artifacts/runtime.lock | cut -d ' ' -f 1)
runtime="/runtime/$lock_hash"
if [ ! -f "$runtime/ready" ]; then
    python -m venv "$runtime"
    "$runtime/bin/python" -m pip install --no-cache-dir --no-index --find-links /artifacts/wheels --require-hashes -r /artifacts/runtime.lock
    touch "$runtime/ready"
fi
exec "$runtime/bin/python" -u -m opentsx.client.research "$@"
