#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
echo "==> Maven package (skip tests)"
if command -v mvn >/dev/null 2>&1; then
  mvn -q -DskipTests package
else
  echo "ERROR: mvn not found. Install Maven or use Docker build only." >&2
  exit 1
fi
echo "==> Artifacts:"
ls -la chat-service/target/chat-service-*.jar chat-gateway/target/chat-gateway-*.jar 2>/dev/null || true
