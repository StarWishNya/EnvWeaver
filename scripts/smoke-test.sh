#!/usr/bin/env bash
# Sentinel HTTP smoke test. Exits non-zero on the first failed check.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

if command -v jq >/dev/null 2>&1; then
  json_get() {
    # $1 jq filter, $2 JSON text. Prints empty string when the value is null/missing.
    printf '%s' "$2" | jq -er "$1" 2>/dev/null || true
  }
else
  json_get() {
    # Minimal fallback for the flat string/number fields this script reads.
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
  # Usage: request METHOD URL [curl args...]
  # Sets RESP_CODE and RESP_BODY. Does not follow redirects.
  local method="$1" url="$2"
  shift 2
  local tmp
  tmp="$(mktemp)"
  RESP_CODE="$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$url" "$@")"
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

RUN_ID="$(date +%s)-${RANDOM}"
TASK_CODE="smoke-${RUN_ID}"
SERVICE_ID="svc-${RUN_ID}"

# 1. Actuator health is UP
request GET "${BASE_URL}/actuator/health"
expect_http "GET /actuator/health" '^200$'
if printf '%s' "$RESP_BODY" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
  pass "actuator health status is UP"
else
  printf '%s\n' "$RESP_BODY" >&2
  fail "actuator health status is not UP"
fi

# 2. Protected task endpoint rejects anonymous callers
request GET "${BASE_URL}/api/tasks"
expect_http "unauthenticated GET /api/tasks" '^(401|403)$'

# 3. Login
request POST "${BASE_URL}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
expect_http "POST /api/auth/login" '^200$'
ACCESS_TOKEN="$(json_get '.data.accessToken' "$RESP_BODY")"
REFRESH_TOKEN="$(json_get '.data.refreshToken' "$RESP_BODY")"
if [[ -z "$ACCESS_TOKEN" || "$ACCESS_TOKEN" == "null" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "login response did not include data.accessToken"
fi
if [[ -z "$REFRESH_TOKEN" || "$REFRESH_TOKEN" == "null" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "login response did not include data.refreshToken"
fi
pass "login returned access and refresh tokens"

AUTH=(-H "Authorization: Bearer ${ACCESS_TOKEN}" -H 'Content-Type: application/json')

# 4. Create a task
request POST "${BASE_URL}/api/tasks" "${AUTH[@]}" \
  -d "{\"taskCode\":\"${TASK_CODE}\",\"serviceId\":\"${SERVICE_ID}\",\"testType\":\"UNIT_TEST\",\"priority\":5}"
expect_http "POST /api/tasks" '^200$'
TASK_ID="$(json_get '.data.id' "$RESP_BODY")"
CREATED_CODE="$(json_get '.data.taskCode' "$RESP_BODY")"
if [[ -z "$TASK_ID" || "$TASK_ID" == "null" || "$CREATED_CODE" != "$TASK_CODE" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "create task did not return id and taskCode ${TASK_CODE}"
fi
pass "created task ${TASK_CODE} id=${TASK_ID}"

# 5. Get by id
request GET "${BASE_URL}/api/tasks/${TASK_ID}" -H "Authorization: Bearer ${ACCESS_TOKEN}"
expect_http "GET /api/tasks/${TASK_ID}" '^200$'
GOT_CODE="$(json_get '.data.taskCode' "$RESP_BODY")"
GOT_STATUS="$(json_get '.data.status' "$RESP_BODY")"
if [[ "$GOT_CODE" != "$TASK_CODE" || "$GOT_STATUS" != "PENDING" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "get task expected taskCode ${TASK_CODE} status PENDING"
fi
pass "get task by id returned PENDING ${TASK_CODE}"

# 6. List and find it
request GET "${BASE_URL}/api/tasks?pageNum=1&pageSize=50" -H "Authorization: Bearer ${ACCESS_TOKEN}"
expect_http "GET /api/tasks" '^200$'
if printf '%s' "$RESP_BODY" | grep -q "\"taskCode\":\"${TASK_CODE}\""; then
  pass "list tasks contains ${TASK_CODE}"
else
  printf '%s\n' "$RESP_BODY" >&2
  fail "list tasks did not contain ${TASK_CODE}"
fi

# 7. Cancel and confirm status
request POST "${BASE_URL}/api/tasks/${TASK_ID}/cancel" -H "Authorization: Bearer ${ACCESS_TOKEN}"
expect_http "POST /api/tasks/${TASK_ID}/cancel" '^200$'
request GET "${BASE_URL}/api/tasks/${TASK_ID}" -H "Authorization: Bearer ${ACCESS_TOKEN}"
expect_http "GET cancelled task" '^200$'
GOT_STATUS="$(json_get '.data.status' "$RESP_BODY")"
if [[ "$GOT_STATUS" != "CANCELLED" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "cancelled task status is ${GOT_STATUS}, expected CANCELLED"
fi
pass "cancelled task status is CANCELLED"

# 8. Invalid create payload
request POST "${BASE_URL}/api/tasks" "${AUTH[@]}" \
  -d '{"taskCode":"","serviceId":"","testType":"","priority":null}'
expect_http "invalid create payload" '^400$'
pass "invalid create payload rejected"

# 9. Current user
request GET "${BASE_URL}/api/auth/me" "${AUTH[@]}"
expect_http "GET /api/auth/me" '^200$'
if [[ "$(json_get '.data.username' "$RESP_BODY")" != "admin" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "auth/me returned a different user"
fi
if printf '%s' "$RESP_BODY" | grep -qi 'password'; then
  fail "auth/me leaks a password field"
fi
pass "auth/me returns the logged-in user without password"

# 10. Task status and logs
request GET "${BASE_URL}/api/tasks/${TASK_ID}/status" "${AUTH[@]}"
expect_http "GET /api/tasks/${TASK_ID}/status" '^200$'
if [[ "$(json_get '.data.status' "$RESP_BODY")" != "CANCELLED" || "$(json_get '.data.progress' "$RESP_BODY")" != "100" ]]; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "task status expected CANCELLED with progress 100"
fi
request GET "${BASE_URL}/api/tasks/${TASK_ID}/logs?tail=20" "${AUTH[@]}"
expect_http "GET /api/tasks/${TASK_ID}/logs" '^200$'
request GET "${BASE_URL}/api/tasks/${TASK_ID}/logs?tail=0" "${AUTH[@]}"
expect_http "task logs with tail=0" '^400$'
request GET "${BASE_URL}/api/tasks/999999999999/status" "${AUTH[@]}"
expect_http "status of missing task" '^404$'

# 11. Environments
request GET "${BASE_URL}/api/environments" "${AUTH[@]}"
expect_http "GET /api/environments" '^200$'
request GET "${BASE_URL}/api/environments?status=BOGUS" "${AUTH[@]}"
expect_http "environments with invalid status" '^400$'
request GET "${BASE_URL}/api/environments/999999999999" "${AUTH[@]}"
expect_http "missing environment" '^404$'
request POST "${BASE_URL}/api/environments/999999999999/release" "${AUTH[@]}"
expect_http "release missing environment" '^404$'

# 12. Metrics
request GET "${BASE_URL}/api/metrics" "${AUTH[@]}"
expect_http "GET /api/metrics" '^200$'
request GET "${BASE_URL}/api/metrics"
expect_http "anonymous GET /api/metrics" '^401$'
request GET "${BASE_URL}/api/metrics/health"
expect_http "anonymous GET /api/metrics/health" '^(200|503)$'
if ! printf '%s' "$RESP_BODY" | grep -q '"db":"UP"' || ! printf '%s' "$RESP_BODY" | grep -q '"redis":"UP"'; then
  printf '%s\n' "$RESP_BODY" >&2
  fail "metrics health expected db and redis UP"
fi
pass "metrics health reports db and redis UP"

# 13. Logout
request POST "${BASE_URL}/api/auth/logout" -H "Authorization: Bearer ${ACCESS_TOKEN}"
expect_http "POST /api/auth/logout" '^200$'

# 14. Old access token is rejected
request GET "${BASE_URL}/api/tasks" -H "Authorization: Bearer ${ACCESS_TOKEN}"
expect_http "blacklisted access token" '^(401|403)$'

# 15. Refresh token cannot call a protected endpoint
request GET "${BASE_URL}/api/tasks" -H "Authorization: Bearer ${REFRESH_TOKEN}"
expect_http "refresh token on protected endpoint" '^(401|403)$'

pass "smoke test completed"
