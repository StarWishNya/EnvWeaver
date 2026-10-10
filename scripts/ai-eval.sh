#!/usr/bin/env bash
# Live AI diagnosis eval. No Docker. Default surefire does not run this.
# Usage: scripts/ai-eval.sh [label] [runs] [cases-dir]
#   label      run label (default tuned)
#   runs       repetitions of the case set (default 2)
#   cases-dir  filesystem path or classpath dir (default ai-eval/cases)
# Optional environment:
#   AI_EVAL_RUNS_DIR        JSON output directory (default reports/ai-eval-runs)
#   AI_EVAL_REWRITE_REPORT  true (default) or false. false leaves reports/ai-diagnosis-eval.md
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
CASES="${3:-}"

args=(mvn -B -o -Pai-eval test -Dai.eval.label="$LABEL" -Dai.eval.runs="$RUNS")
if [[ -n "$CASES" ]]; then
  args+=(-Dai.eval.cases="$CASES")
fi
if [[ -n "${AI_EVAL_RUNS_DIR:-}" ]]; then
  args+=(-Dai.eval.runsDir="$AI_EVAL_RUNS_DIR")
fi
if [[ -n "${AI_EVAL_REWRITE_REPORT:-}" ]]; then
  args+=(-Dai.eval.rewriteReport="$AI_EVAL_REWRITE_REPORT")
fi

exec "${args[@]}"
