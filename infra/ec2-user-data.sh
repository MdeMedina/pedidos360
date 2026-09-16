#!/bin/bash
# ============================================================================
# User data para las tres instancias EC2 (Amazon Linux 2023).
#
# Se pega en "Detalles avanzados -> Datos de usuario" al lanzar la instancia.
# Se ejecuta UNA sola vez, como root, en el primer arranque. Al terminar, la
# instancia ya tiene Docker, el plugin compose y la red pedidos360-net creada.
#
# Comprobar que termino bien:   sudo cat /var/log/cloud-init-output.log
# ============================================================================
set -euxo pipefail

dnf update -y
dnf install -y docker git

systemctl enable --now docker
# Permite usar docker sin sudo. Requiere cerrar y reabrir la sesion SSH.
usermod -aG docker ec2-user

# El plugin compose v2 no viene en los repos de Amazon Linux 2023.
DOCKER_CONFIG=/usr/local/lib/docker/cli-plugins
mkdir -p "$DOCKER_CONFIG"
ARCH=$(uname -m)
curl -SL "https://github.com/docker/compose/releases/download/v2.32.4/docker-compose-linux-${ARCH}" \
  -o "$DOCKER_CONFIG/docker-compose"
chmod +x "$DOCKER_CONFIG/docker-compose"

# Red comun a los tres compose (apps, mq, kafka).
docker network create pedidos360-net || true

# Marca para saber desde SSH que el user data termino.
touch /home/ec2-user/.pedidos360-ready
chown ec2-user:ec2-user /home/ec2-user/.pedidos360-ready
