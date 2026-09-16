#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Prepara un paquete listo para subir a la instancia ec2-apps.
#
#   ./scripts/package-for-ec2.sh
#   scp -i labsuser.pem dist-ec2/pedidos360-apps.tgz ec2-user@<IP>:~
#
# Contiene: los 6 jars ya compilados, el Dockerfile de ejecucion, el compose
# que los usa y el .env de ejemplo. NO contiene codigo fuente ni Maven: la
# instancia solo tiene que ejecutar, no compilar.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/dist-ec2"
SERVICES=(bff orders catalog notify report audit)

echo "==> Compilando los jars"
(cd "$ROOT" && mvn -q clean package -DskipTests)

echo "==> Armando el paquete"
rm -rf "$OUT"
mkdir -p "$OUT/pedidos360/jars"

for svc in "${SERVICES[@]}"; do
  cp "$ROOT/ms-pedidos360-$svc/target/ms-pedidos360-$svc-1.0.0.jar" "$OUT/pedidos360/jars/$svc.jar"
  printf '    %-10s %s\n' "$svc" "$(du -h "$OUT/pedidos360/jars/$svc.jar" | cut -f1)"
done

cp "$ROOT/Dockerfile.runtime"              "$OUT/pedidos360/"
cp "$ROOT/infra/apps/compose.prebuilt.yml" "$OUT/pedidos360/compose.yml"
cp "$ROOT/infra/apps/.env.example"         "$OUT/pedidos360/.env.example"
cp "$ROOT/infra/mq/compose.yml"            "$OUT/pedidos360/compose.mq.yml"
cp "$ROOT/infra/kafka/compose.yml"         "$OUT/pedidos360/compose.kafka.yml"

tar -czf "$OUT/pedidos360-apps.tgz" -C "$OUT" pedidos360
rm -rf "$OUT/pedidos360"

cat <<TXT

Paquete listo: $OUT/pedidos360-apps.tgz  ($(du -h "$OUT/pedidos360-apps.tgz" | cut -f1))

Siguiente paso:
  scp -i ~/labsuser.pem $OUT/pedidos360-apps.tgz ec2-user@<IP_EC2_APPS>:~
  ssh -i ~/labsuser.pem ec2-user@<IP_EC2_APPS>
  tar -xzf pedidos360-apps.tgz && cd pedidos360
  cp .env.example .env && nano .env
  docker compose --env-file .env up -d
TXT
