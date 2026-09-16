# Pedidos360 · Evaluación 1 — Cloud Native I (DSY1107)

Solución completa del **Caso 0**: frontend Angular con MSAL, microservicios Spring Boot
protegidos con JWT de Microsoft Entra ID, mensajería con RabbitMQ y Kafka, y despliegue con
Docker Compose listo para EC2.

## Empieza por aquí

| Documento | Para qué |
|---|---|
| **[docs/00-PASO-A-PASO.md](docs/00-PASO-A-PASO.md)** | **La guía de estudio.** Cada paso de las guías del ramo, en orden, con el porqué y las preguntas de defensa |
| **[docs/04-DEPLOY-RUNBOOK.md](docs/04-DEPLOY-RUNBOOK.md)** | **El runbook de despliegue.** Comandos exactos, en orden, de local → Azure → EC2 → API Gateway, con checkpoints |
| [docs/01-azure-entra-id.md](docs/01-azure-entra-id.md) | Configurar el tenant, los dos App Registrations, scopes y roles |
| [docs/02-aws-api-gateway.md](docs/02-aws-api-gateway.md) | Crear la HTTP API, el JWT Authorizer y el versionado `/v1` `/v2` |
| [docs/03-arquitectura.md](docs/03-arquitectura.md) | Decisiones de diseño, alternativas descartadas y limitaciones |
| [infra/README.md](infra/README.md) | Levantar todo con Docker y los Security Groups de AWS |

## Arranque rápido (2 comandos, sin Azure ni AWS)

```bash
./scripts/run-local.sh
```

```bash
cd frontend-pedidos360 && npm start
```

Abre <http://localhost:4200> y entra con cualquiera de los cuatro perfiles de demo. El BFF
emite tokens locales con la misma forma que los de Entra ID, así que toda la cadena de
seguridad funciona igual.

Para verificar que la seguridad realmente bloquea:

```bash
./scripts/smoke-test.sh
```

## Estructura

```
pedidos360/
├── pedidos360-common/        Seguridad compartida, envelope de eventos, manejo de errores
├── ms-pedidos360-bff/        Puerta única: valida, propaga el token y agrega respuestas
├── ms-pedidos360-orders/     Pedidos, máquina de estados, coordinación de stock
├── ms-pedidos360-catalog/    Productos y stock
├── ms-pedidos360-notify/     Consumidor RabbitMQ (notificaciones)
├── ms-pedidos360-report/     Consumidor Kafka (KPIs, lead time)
├── ms-pedidos360-audit/      Consumidor Kafka (timeline, solo lectura)
├── frontend-pedidos360/      Angular 20 + MSAL, guards por rol
├── infra/
│   ├── apps/compose.yml      → VM ec2-apps
│   ├── mq/compose.yml        → VM ec2-mq (RabbitMQ)
│   └── kafka/compose.yml     → VM ec2-kafka (ZooKeeper + Kafka)
├── scripts/                  run-local.sh · stop-local.sh · smoke-test.sh · package-for-ec2.sh
└── docs/
```

## Stack

| Capa | Tecnología |
|---|---|
| Identidad | Microsoft Entra ID (Workforce o External ID) |
| Frontend | Angular 20, MSAL Angular 6, standalone components, signals |
| API Manager | AWS API Gateway HTTP API + JWT Authorizer |
| Backend | Spring Boot 3.5, Java 21, Spring Security OAuth2 Resource Server |
| Documentación de APIs | springdoc-openapi (Swagger UI por servicio) |
| Persistencia | JPA + H2 en memoria (migración a Oracle documentada) |
| Mensajería | RabbitMQ (comandos, con DLQ) y Kafka + ZooKeeper (eventos, con DLT) |
| Despliegue | Docker multi-stage + Docker Compose |

## Puertos

| Servicio | Puerto | URL útil |
|---|---|---|
| Frontend | 4200 | <http://localhost:4200> |
| BFF | 8080 | <http://localhost:8080/swagger-ui.html> |
| orders | 8081 | <http://localhost:8081/swagger-ui.html> |
| catalog | 8082 | <http://localhost:8082/swagger-ui.html> |
| notify | 8083 | — |
| report | 8084 | <http://localhost:8084/swagger-ui.html> |
| audit | 8085 | <http://localhost:8085/swagger-ui.html> |
| RabbitMQ | 15672 | <http://localhost:15672> (`pedidos360`/`pedidos360`) |
| Kafka UI | 8090 | <http://localhost:8090> |

## Roles y permisos

| Ruta | Admin | Operator | Customer | Auditor |
|---|:-:|:-:|:-:|:-:|
| `/dashboard` | ✅ | ✅ | ✅ | ✅ |
| `/orders` (ver) | todos | todos | **solo los suyos** | todos |
| `/orders` (crear) | ✅ | ✅ | ✅ | ❌ |
| `/orders` (cambiar estado) | ✅ | ✅ | solo cancelar el propio, sin aceptar | ❌ |
| `/catalog` (ver) | ✅ | ✅ | ❌ | ❌ |
| `/catalog` (escribir) | ✅ | ❌ | ❌ | ❌ |
| `/reports` | ✅ | ❌ | ❌ | ❌ |
| `/audit` | ✅ | ❌ | ❌ | ✅ |

## Desplegar en AWS

El procedimiento completo, comando por comando, está en
**[docs/04-DEPLOY-RUNBOOK.md](docs/04-DEPLOY-RUNBOOK.md)**: 6 fases, ~2 h 30 min la primera
vez, con hoja de valores, checkpoints por fase y tabla de diagnóstico.

Resumen de las piezas que intervienen:

```bash
./scripts/package-for-ec2.sh     # compila en local y arma el .tgz para la instancia
```

| Fase | Qué se hace |
|---|---|
| 0 | Compilar y empaquetar en local (nunca dentro de la EC2: se queda sin memoria) |
| 1 | Entra ID: 2 App Registrations, 6 scopes, 4 App Roles, consentimiento |
| 2 | 3 instancias EC2 + security groups + IP elástica |
| 3 | Levantar `mq` → `kafka` → `apps` (en ese orden) |
| 4 | HTTP API + JWT Authorizer + rutas + CORS |
| 5 | Frontend en `localhost:4200` apuntando al API Gateway |
| 6 | Verificación end-to-end |

## Requisitos

Java 21, Maven 3.9+, Node 20+, Docker (solo para RabbitMQ, Kafka y el despliegue completo).
