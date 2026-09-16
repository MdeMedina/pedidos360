# infra · despliegue de Pedidos360

Tres archivos compose, uno por VM, tal como propone el Caso 0:

| Archivo | VM del enunciado | Contiene |
|---|---|---|
| `apps/compose.yml` | `ec2-apps` | bff, orders, catalog, notify, report, audit y el frontend |
| `mq/compose.yml` | `ec2-mq` | RabbitMQ + consola de administración |
| `kafka/compose.yml` | `ec2-kafka` | ZooKeeper + Kafka (1 broker) + Kafka UI |

Están separados a propósito: en AWS cada uno vive en una instancia distinta, con
su propio Security Group. En local se conectan entre sí por una red Docker común.

## Levantar todo en local

```bash
docker network create pedidos360-net

docker compose -f infra/mq/compose.yml    up -d
docker compose -f infra/kafka/compose.yml up -d

cp infra/apps/.env.example infra/apps/.env
docker compose -f infra/apps/compose.yml --env-file infra/apps/.env up -d --build
```

| Servicio | URL |
|---|---|
| Frontend | http://localhost:4200 |
| BFF (API) | http://localhost:8080 |
| Swagger de orders | http://localhost:8081/swagger-ui.html |
| RabbitMQ | http://localhost:15672 (`pedidos360` / `pedidos360`) |
| Kafka UI | http://localhost:8090 |

## Apagar

```bash
docker compose -f infra/apps/compose.yml down
docker compose -f infra/kafka/compose.yml down
docker compose -f infra/mq/compose.yml down
docker network rm pedidos360-net
```

## Security Groups en AWS

Regla general: cada puerto abierto es una puerta más que defender. Abre lo mínimo.

| Instancia | Puerto | Origen permitido | Motivo |
|---|---|---|---|
| `ec2-apps` | 8080 | el rango del API Gateway (o la VPC) | única entrada pública, vía el BFF |
| `ec2-apps` | 8081-8085 | **nadie desde fuera** | solo tráfico interno de la VPC |
| `ec2-mq` | 5672 | SG de `ec2-apps` | AMQP |
| `ec2-mq` | 15672 | tu IP | consola de administración |
| `ec2-kafka` | 9092 | SG de `ec2-apps` | tráfico de Kafka |
| `ec2-kafka` | 2181 | SG de `ec2-kafka` | ZooKeeper, nunca desde fuera |

Nunca uses `0.0.0.0/0` en 5672, 9092 ni 2181: ninguno de esos protocolos lleva
autenticación fuerte por defecto y exponerlos equivale a regalar el bus de eventos.
