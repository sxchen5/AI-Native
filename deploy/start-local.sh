#!/usr/bin/env bash
# 非 Docker：本机启动 Nacos（若未起）、编译并以后台进程启动 gateway + service
# 用法：
#   export NACOS_SERVER=127.0.0.1:8848
#   export OPENAI_API_KEY=...
#   export LOGBACK_LOG_DIR=/var/log/doubao-ai   # 可选，默认 项目根/logs
#   ./deploy/start-local.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export NACOS_SERVER="${NACOS_SERVER:-127.0.0.1:8848}"
export LOGBACK_LOG_DIR="${LOGBACK_LOG_DIR:-$ROOT/logs}"
mkdir -p "$LOGBACK_LOG_DIR"

PID_DIR="${PID_DIR:-$ROOT/logs/pids}"
mkdir -p "$PID_DIR"

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn not found" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found (need JDK 17+)" >&2
  exit 1
fi

echo "==> Build"
mvn -q -DskipTests package

SVC_JAR=$(ls chat-service/target/chat-service-*.jar | head -1)
GW_JAR=$(ls chat-gateway/target/chat-gateway-*.jar | head -1)

JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"
PROFILE="${SPRING_PROFILES_ACTIVE:-}"
PROFILE_ARG=()
if [[ -n "$PROFILE" ]]; then
  PROFILE_ARG=( -Dspring.profiles.active="$PROFILE" )
fi

start_jar() {
  local name="$1"
  local jar="$2"
  local pidf="$PID_DIR/${name}.pid"
  if [[ -f "$pidf" ]] && kill -0 "$(cat "$pidf")" 2>/dev/null; then
    echo "WARN: $name already running (pid $(cat "$pidf"))"
    return
  fi
  echo "==> Starting $name"
  nohup java $JAVA_OPTS "${PROFILE_ARG[@]}" -DLOGBACK_LOG_DIR="$LOGBACK_LOG_DIR" -jar "$jar" \
    >>"$LOGBACK_LOG_DIR/${name}-console.log" 2>&1 &
  echo $! >"$pidf"
  echo "    pid $(cat "$pidf") log dir $LOGBACK_LOG_DIR (main.log / main.yyyyMMdd.log)"
}

start_jar chat-service "$SVC_JAR"
sleep 2
start_jar chat-gateway "$GW_JAR"

echo "==> Done. Gateway http://127.0.0.1:8080  Logs: $LOGBACK_LOG_DIR"
echo "    Stop: ./deploy/stop-local.sh"
