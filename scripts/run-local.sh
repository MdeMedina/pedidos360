#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Levanta los 6 microservicios en perfil dev, sin Docker y sin brokers.
# Es la forma mas rapida de tener la solucion corriendo para desarrollar o
# demostrar: H2 en memoria y tokens locales emitidos por el BFF.
#
#   ./scripts/run-local.sh          arranca todo
#   ./scripts/stop-local.sh         apaga todo
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/.logs"
PID_FILE="$ROOT/.logs/pids"
mkdir -p "$LOG_DIR"

SERVICES=(catalog orders notify report audit bff)

echo "==> Compilando (mvn install -DskipTests)"
(cd "$ROOT" && mvn -q install -DskipTests)

: > "$PID_FILE"
for svc in "${SERVICES[@]}"; do
  jar="$ROOT/ms-pedidos360-$svc/target/ms-pedidos360-$svc-1.0.0.jar"
  echo "==> Iniciando ms-pedidos360-$svc"
  nohup java -jar "$jar" > "$LOG_DIR/$svc.log" 2>&1 &
  echo "$! ms-pedidos360-$svc" >> "$PID_FILE"
done

echo "==> Esperando a que todos respondan UP"
ports=(8082 8081 8083 8084 8085 8080)
for i in {1..60}; do
  up=0
  for p in "${ports[@]}"; do
    curl -s "http://localhost:$p/actuator/health" 2>/dev/null | grep -q '"UP"' && up=$((up+1))
  done
  [ "$up" -eq "${#ports[@]}" ] && break
  sleep 2
done

cat <<TXT

Servicios arriba ($up/${#ports[@]}):
  BFF      http://localhost:8080   (puerta unica del frontend)
  orders   http://localhost:8081/swagger-ui.html
  catalog  http://localhost:8082/swagger-ui.html
  notify   http://localhost:8083
  report   http://localhost:8084/swagger-ui.html
  audit    http://localhost:8085/swagger-ui.html

Token de demo:
  curl "http://localhost:8080/dev/token?username=admin@pedidos360.cl&roles=ADMIN"

Frontend:
  cd frontend-pedidos360 && npm start   ->  http://localhost:4200

Logs en $LOG_DIR
TXT
