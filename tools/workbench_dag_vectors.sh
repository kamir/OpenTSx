#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root/opentsx-saas-frontend"
node node_modules/vitest/vitest.mjs run src/workbench/workbench.test.ts src/workbench/clean.test.ts --update
