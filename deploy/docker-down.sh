#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ENV_FILE="${1:-$ROOT/.env}"
ARGS=( -f docker-compose.prod.yml )
if [[ -f "$ENV_FILE" ]]; then
  ARGS+=( --env-file "$ENV_FILE" )
fi
docker compose "${ARGS[@]}" down
