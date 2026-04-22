#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="${PID_DIR:-$ROOT/logs/pids}"
stop_one() {
  local name="$1"
  local pidf="$PID_DIR/${name}.pid"
  if [[ -f "$pidf" ]]; then
    local pid
    pid="$(cat "$pidf")"
    if kill -0 "$pid" 2>/dev/null; then
      echo "==> Stopping $name ($pid)"
      kill "$pid" || true
      sleep 1
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" || true
    fi
    rm -f "$pidf"
  else
    echo "WARN: no pid file for $name"
  fi
}
stop_one chat-gateway
stop_one chat-service
echo "==> Stopped"
