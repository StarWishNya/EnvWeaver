#!/usr/bin/env bash
# Load src/main/resources/schema.sql into MySQL.
# Service containers start before checkout, so the schema cannot be mounted
# into the MySQL service. DELIMITER in the procedures requires the mysql CLI.
#
# Required: DB_PASSWORD
# Optional: DB_HOST (127.0.0.1), DB_PORT (3306), DB_USERNAME (root), DB_NAME (sentinel)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_NAME="${DB_NAME:-sentinel}"
SCHEMA_FILE="${SCHEMA_FILE:-${ROOT}/src/main/resources/schema.sql}"
DATA_FILE="${DATA_FILE:-${ROOT}/src/main/resources/data.sql}"

if [[ -z "${DB_PASSWORD:-}" ]]; then
  echo "DB_PASSWORD is required" >&2
  exit 1
fi
if [[ ! "$DB_HOST" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "DB_HOST must be a hostname or IP address" >&2
  exit 1
fi
if [[ ! "$DB_PORT" =~ ^[0-9]+$ ]]; then
  echo "DB_PORT must be numeric" >&2
  exit 1
fi
if [[ ! "$DB_NAME" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "DB_NAME must contain only letters, digits, and underscores" >&2
  exit 1
fi
if [[ ! "$DB_USERNAME" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "DB_USERNAME contains unsupported characters" >&2
  exit 1
fi
if [[ ! -f "$SCHEMA_FILE" ]]; then
  echo "schema file not found: $SCHEMA_FILE" >&2
  exit 1
fi
if [[ ! -f "$DATA_FILE" ]]; then
  echo "data file not found: $DATA_FILE" >&2
  exit 1
fi

cnf=""
cleanup() {
  if [[ -n "$cnf" && -f "$cnf" ]]; then
    rm -f "$cnf"
  fi
}
trap cleanup EXIT

cnf="$(mktemp)"
chmod 644 "$cnf"
cat >"$cnf" <<EOF
[client]
host=${DB_HOST}
port=${DB_PORT}
user=${DB_USERNAME}
password=${DB_PASSWORD}
protocol=TCP
default-character-set=utf8mb4
connect-timeout=5
EOF

mysql_cli() {
  docker run --rm --network host -i \
    -v "${cnf}:/ci-client.cnf:ro" \
    mysql:8.0 \
    mysql --defaults-extra-file=/ci-client.cnf "$@"
}

echo "waiting for MySQL at ${DB_HOST}:${DB_PORT}"
ready=0
for _ in $(seq 1 40); do
  if mysql_cli --batch -N -e "SELECT 1" >/dev/null 2>&1; then
    ready=$((ready + 1))
    if [[ "$ready" -ge 3 ]]; then
      break
    fi
  else
    ready=0
  fi
  sleep 3
done
if [[ "$ready" -lt 3 ]]; then
  echo "MySQL did not stay ready at ${DB_HOST}:${DB_PORT}" >&2
  exit 1
fi

echo "loading ${SCHEMA_FILE}"
mysql_cli --batch <"$SCHEMA_FILE"

# schema.sql 只建 admin。测试还要用 data.sql 里的 user/user123，语句都是 ON DUPLICATE KEY，可重复执行。
echo "loading ${DATA_FILE}"
mysql_cli --batch "$DB_NAME" <"$DATA_FILE"

tables="$(mysql_cli --batch -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}' AND table_name IN ('t_task','t_user','t_environment','t_container','t_diagnosis_cache','t_system_config')")"
routines="$(mysql_cli --batch -N -e "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='${DB_NAME}' AND routine_name IN ('sp_cleanup_expired_environments','sp_cleanup_old_diagnosis_cache')")"
admin="$(mysql_cli --batch -N -e "SELECT COUNT(*) FROM ${DB_NAME}.t_user WHERE username='admin'")"
user="$(mysql_cli --batch -N -e "SELECT COUNT(*) FROM ${DB_NAME}.t_user WHERE username='user'")"

if [[ "$tables" != "6" || "$routines" != "2" || "$admin" != "1" || "$user" != "1" ]]; then
  echo "schema verification failed: tables=${tables} routines=${routines} admin=${admin} user=${user}" >&2
  exit 1
fi
echo "schema loaded: tables=${tables} routines=${routines} admin=${admin} user=${user}"
