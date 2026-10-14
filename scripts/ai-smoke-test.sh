#!/usr/bin/env bash
# Live AI diagnosis smoke test. Exits non-zero on the first failed check.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

if command -v jq >/dev/null 2>&1; then
  json_get() {
    printf '%s' "$2" | jq -er "$1" 2>/dev/null || true
  }
else
  json_get() {
    local filter="$1" json="$2" key quoted
    key="${filter##*.}"
    quoted="$(printf '%s' "$json" | grep -o "\"${key}\":\"[^\"]*\"" | head -n 1 | sed -E 's/.*:"([^"]*)"/\1/' || true)"
    if [[ -n "$quoted" ]]; then
      printf '%s' "$quoted"
      return 0
    fi
    printf '%s' "$json" | grep -o "\"${key}\":[0-9][0-9]*" | head -n 1 | sed -E 's/.*://' || true
  }
fi

request() {
  local method="$1" url="$2"
  shift 2
  local tmp
  tmp="$(mktemp)"
  RESP_CODE="$(curl -sS --max-time 30 -o "$tmp" -w '%{http_code}' -X "$method" "$url" "$@")"
  RESP_BODY="$(cat "$tmp")"
  rm -f "$tmp"
}

expect_http() {
  local name="$1" expected="$2"
  if [[ "$RESP_CODE" =~ $expected ]]; then
    pass "$name (HTTP ${RESP_CODE})"
  else
    printf '%s\n' "$RESP_BODY" >&2
    fail "$name expected HTTP ${expected}, got ${RESP_CODE}"
  fi
}

LOG="$(cat <<'EOF'
2026-10-09T10:15:01.123Z ERROR 1 --- [main] com.zaxxer.hikari.pool.HikariPool : HikariPool-1 - Exception during pool initialization.
com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure
The last packet sent successfully to the server was 0 milliseconds ago. The driver has not received any packets from the server.
Caused by: java.net.ConnectException: Connection refused
	at java.base/sun.nio.ch.Net.pollConnect(Native Method)
	at java.base/sun.nio.ch.Net.connect(Net.java:579)
Caused by: java.net.ConnectException: Connection refused: mysql/10.0.0.8:3306
2026-10-09T10:15:02.456Z ERROR 1 --- [main] o.s.boot.SpringApplication : Application run failed
org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'dataSourceScriptDatabaseInitializer' defined in class path resource
org.springframework.context.ApplicationContextException: Unable to start web server; nested exception is org.springframework.boot.web.server.WebServerException
ApplicationContext failed to start because the DataSource at mysql:3306 refused the connection.
EOF
)"

if command -v jq >/dev/null 2>&1; then
  DIAGNOSIS_BODY="$(jq -n --arg log "$LOG" '{log:$log}')"
elif command -v python3 >/dev/null 2>&1; then
  DIAGNOSIS_BODY="$(printf '%s' "$LOG" | python3 -c 'import json,sys; print(json.dumps({"log": sys.stdin.read()}))')"
else
  fail "jq or python3 is required to build the diagnosis request body"
fi

RUN_ID="$(date +%s)-${RANDOM}"
TASK_CODE="ai-smoke-${RUN_ID}"
SERVICE_ID="svc-${RUN_ID}"

request POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
expect_http "POST /api/auth/login" '^200$'
ACCESS_TOKEN="$(json_get '.data.accessToken' "$RESP_BODY")"
if [[ -z "$ACCESS_TOKEN" || "$ACCESS_TOKEN" == "null" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "login response did not include data.accessToken"
fi
pass "login returned access token"

request POST "${BASE_URL}/api/tasks" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "{\"taskCode\":\"${TASK_CODE}\",\"serviceId\":\"${SERVICE_ID}\",\"testType\":\"UNIT_TEST\",\"priority\":5}"
expect_http "POST /api/tasks" '^201$'
TASK_ID="$(json_get '.data.id' "$RESP_BODY")"
if [[ -z "$TASK_ID" || "$TASK_ID" == "null" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "create task did not return data.id"
fi
pass "created task ${TASK_CODE} id=${TASK_ID}"
# 创建任务会自动供给环境。退出时取消任务，环境随之释放，避免残留 RUNNING 任务与占用的环境
cleanup_task() {
  curl -sS -o /dev/null -X DELETE "${BASE_URL}/api/tasks/${TASK_ID}" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" || true
}
trap cleanup_task EXIT

request POST "${BASE_URL}/api/diagnosis/${TASK_ID}" \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "$DIAGNOSIS_BODY"
expect_http "POST /api/diagnosis/${TASK_ID}" '^202$'
POST_STATUS="$(json_get '.data.status' "$RESP_BODY")"
case "$POST_STATUS" in
  PENDING|PROCESSING|COMPLETED) pass "diagnosis accepted with status ${POST_STATUS}" ;;
  *)
    printf '%s\n' "$RESP_BODY" >&2
    fail "unexpected diagnosis status ${POST_STATUS}"
    ;;
esac

deadline=$((SECONDS + 90))
DIAG_STATUS=""
while (( SECONDS < deadline )); do
  request GET "${BASE_URL}/api/diagnosis/${TASK_ID}" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}"
  if [[ "$RESP_CODE" != "200" ]]; then
    printf '%s\n' "$RESP_BODY" >&2
    fail "GET /api/diagnosis/${TASK_ID} expected HTTP 200, got ${RESP_CODE}"
  fi
  DIAG_STATUS="$(json_get '.data.status' "$RESP_BODY")"
  if [[ "$DIAG_STATUS" == "COMPLETED" ]]; then
    break
  fi
  if [[ "$DIAG_STATUS" == "FAILED" ]]; then
    DIAG_ERROR="$(json_get '.data.error' "$RESP_BODY")"
    printf '%s\n' "$RESP_BODY" >&2
    fail "diagnosis FAILED: ${DIAG_ERROR}"
  fi
  sleep 2
done

if [[ "$DIAG_STATUS" != "COMPLETED" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "diagnosis did not COMPLETE within 90s (last status: ${DIAG_STATUS:-unknown})"
fi
pass "diagnosis COMPLETED"

SUMMARY="$(json_get '.data.summary' "$RESP_BODY")"
ROOT_CAUSE="$(json_get '.data.rootCause' "$RESP_BODY")"
PROVIDER="$(json_get '.data.provider' "$RESP_BODY")"
MODEL="$(json_get '.data.model' "$RESP_BODY")"
ERROR_TYPE="$(json_get '.data.errorType' "$RESP_BODY")"
SEVERITY="$(json_get '.data.severity' "$RESP_BODY")"

if [[ -z "$SUMMARY" || "$SUMMARY" == "null" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "summary is empty"
fi
if [[ -z "$ROOT_CAUSE" || "$ROOT_CAUSE" == "null" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "rootCause is empty"
fi
if [[ -z "$PROVIDER" || "$PROVIDER" == "null" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "provider is empty"
fi

printf 'provider=%s\nmodel=%s\nerrorType=%s\nseverity=%s\nsummary=%s\n' \
  "$PROVIDER" "$MODEL" "$ERROR_TYPE" "$SEVERITY" "$SUMMARY"
pass "ai smoke test completed"
exit 0
