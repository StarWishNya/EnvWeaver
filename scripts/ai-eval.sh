#!/usr/bin/env bash
# Live AI diagnosis eval. No Docker. Default surefire does not run this.
# Usage: scripts/ai-eval.sh [label] [runs]
#   label  baseline or tuned (default tuned)
#   runs   repetitions of the full case set (default 2)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

# .env 里的 AI_PROVIDER 往往只是 ollama。所有者实际启动参数是 deepseek 优先。
if [[ -z "${AI_PROVIDER_ORDER:-}" ]]; then
  export AI_PROVIDER_ORDER=deepseek,ollama
fi

LABEL="${1:-tuned}"
RUNS="${2:-2}"

exec mvn -B -o -Pai-eval test -Dai.eval.label="$LABEL" -Dai.eval.runs="$RUNS"
