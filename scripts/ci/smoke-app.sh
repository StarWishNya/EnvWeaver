#!/usr/bin/env bash
# Start the packaged app on SERVER_PORT, wait until /actuator/health is UP,
# then run scripts/smoke-test.sh. Stops only the process this script started.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PORT="${SERVER_PORT:-18080}"
BASE_URL="${BASE_URL:-http://127.0.0.1:${PORT}}"
BASE_URL="${BASE_URL%/}"
LOG_FILE="${APP_LOG:-${RUNNER_TEMP:-/tmp}/sentinel-app.log}"
PID_FILE="${APP_PID_FILE:-${RUNNER_TEMP:-/tmp}/sentinel-app.pid}"
JAR="${JAR_PATH:-${ROOT}/target/sentinel-1.0.0-SNAPSHOT.jar}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

if [[ ! "$PORT" =~ ^[0-9]+$ ]]; then
  echo "SERVER_PORT must be numeric" >&2
  exit 1
fi
if [[ ! -f "$JAR" ]]; then
  echo "jar not found: $JAR" >&2
  exit 1
fi

port_in_use() {
  if ! command -v ss >/dev/null 2>&1; then
    return 1
  fi
  ss -H -ltn "sport = :${PORT}" | grep -q .
}

if port_in_use; then
  echo "port ${PORT} is already in use; refusing to start or stop any process" >&2
  exit 1
fi

mkdir -p "$(dirname "$LOG_FILE")"
: >"$LOG_FILE"

cleanup() {
  local status=$?
  local pid="" cmdline=""
  if [[ -f "$PID_FILE" ]]; then
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    rm -f "$PID_FILE"
  fi
  if [[ -n "$pid" && -r "/proc/${pid}/cmdline" ]]; then
    cmdline="$(tr '\0' ' ' <"/proc/${pid}/cmdline" || true)"
    case "$cmdline" in
      *"${JAR}"*)
        kill "$pid" 2>/dev/null || true
        for _ in 1 2 3 4 5 6 7 8 9 10; do
          kill -0 "$pid" 2>/dev/null || break
          sleep 1
        done
        if kill -0 "$pid" 2>/dev/null; then
          kill -9 "$pid" 2>/dev/null || true
        fi
        ;;
      *)
        echo "refusing to signal pid ${pid}" >&2
        ;;
    esac
  fi
  if [[ "$status" -ne 0 && -f "$LOG_FILE" ]]; then
    echo "===== application log (tail) =====" >&2
    tail -n 200 "$LOG_FILE" >&2 || true
  fi
  exit "$status"
}
trap cleanup EXIT

java -jar "$JAR" --spring.profiles.active=dev --server.port="${PORT}" >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
app_pid="$(cat "$PID_FILE")"

deadline=$((SECONDS + HEALTH_TIMEOUT))
while true; do
  if ! kill -0 "$app_pid" 2>/dev/null; then
    echo "application exited before becoming healthy" >&2
    exit 1
  fi
  body="$(curl -fsS --max-time 5 "${BASE_URL}/actuator/health" 2>/dev/null || true)"
  case "$body" in
    *'"status":"UP"'*)
      echo "actuator health is UP"
      break
      ;;
  esac
  if (( SECONDS >= deadline )); then
    echo "timed out waiting for ${BASE_URL}/actuator/health" >&2
    printf '%s\n' "$body" >&2
    exit 1
  fi
  sleep 3
done

BASE_URL="$BASE_URL" bash "${ROOT}/scripts/smoke-test.sh"
