#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ENV_FILE="${1:-$ROOT/.env}"
ARGS=( -f docker-compose.prod.yml )
if [[ -f "$ENV_FILE" ]]; then
  ARGS+=( --env-file "$ENV_FILE" )
fi
echo "==> docker compose up (${ARGS[*]})"
docker compose "${ARGS[@]}" up -d --build
echo "==> 默认 UI 端口见 .env 中 CHAT_UI_PORT（未设置则为 80），网关 CHAT_GATEWAY_PORT（默认 8080）"
