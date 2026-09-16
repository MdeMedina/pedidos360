#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT/.logs/pids"

if [ -f "$PID_FILE" ]; then
  while read -r pid name; do
    if kill -0 "$pid" 2>/dev/null; then
      echo "==> Deteniendo $name (pid $pid)"
      kill "$pid" 2>/dev/null || true
    fi
  done < "$PID_FILE"
  rm -f "$PID_FILE"
else
  echo "No hay archivo de pids; se detienen por nombre de jar."
  pkill -f "ms-pedidos360-.*-1.0.0.jar" || true
fi
echo "Listo."
