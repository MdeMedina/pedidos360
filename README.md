# Pedidos360 — Cloud Native I (DSY1107)

Solución del **Caso 0**: frontend Angular con MSAL, microservicios Spring Boot protegidos con
JWT de Microsoft Entra ID detrás de AWS API Gateway, y mensajería asíncrona con RabbitMQ
(comandos) y Kafka (eventos).

Este repositorio contiene el encargo de dos evaluaciones:

| | Alcance |
|---|---|
| **EP1** | Identidad con MSAL, validación de JWT en el backend, API Gateway, despliegue en EC2 |
| **EP3** | RabbitMQ: colas, exchanges, DLQ, ACK manual y microservicio administrador |

---

# Evaluación Parcial 3 — RabbitMQ

## Dónde está cada indicador de la pauta

| # | Indicador | Dónde |
|---|---|---|
| 1 | Nombres de colas, exchanges y bindings centralizados | [`Messaging.java`](pedidos360-common/src/main/java/cl/duoc/pedidos360/common/events/Messaging.java) — constantes únicas, sin literales en el código de negocio |
| 2 | Beans `Queue`, `Exchange` y `Binding` por caso de uso | [`RabbitTopology.java`](pedidos360-common/src/main/java/cl/duoc/pedidos360/common/messaging/RabbitTopology.java) — 3 exchanges, 6 colas, 12 bindings |
| 3 | Configuración separada de la lógica de negocio | `common/messaging/` y [`RabbitAdminConfig`](ms-pedidos360-rabbitadmin/src/main/java/cl/duoc/pedidos360/rabbitadmin/config/RabbitAdminConfig.java), que solo publica el bean |
| 4 | Consumidores `@RabbitListener` agrupados por dominio | [`consumer/email`](ms-pedidos360-notify/src/main/java/cl/duoc/pedidos360/notify/consumer/email) · [`kitchen`](ms-pedidos360-notify/src/main/java/cl/duoc/pedidos360/notify/consumer/kitchen) · [`invoice`](ms-pedidos360-notify/src/main/java/cl/duoc/pedidos360/notify/consumer/invoice) · [`deadletter`](ms-pedidos360-notify/src/main/java/cl/duoc/pedidos360/notify/consumer/deadletter) |
| 5 | ACK explícito y manejo de errores | [`AckPolicy.java`](ms-pedidos360-notify/src/main/java/cl/duoc/pedidos360/notify/support/AckPolicy.java) — `ack`, `retry`, `deadLetter` |
| 6 | Endpoints REST del administrador | [`RabbitAdminController`](ms-pedidos360-rabbitadmin/src/main/java/cl/duoc/pedidos360/rabbitadmin/api/RabbitAdminController.java) |
| 7 | Lógica encapsulada en un servicio | [`RabbitAdminService`](ms-pedidos360-rabbitadmin/src/main/java/cl/duoc/pedidos360/rabbitadmin/service/RabbitAdminService.java) — el controlador no conoce RabbitMQ |
| 8 | Validación de parámetros de entrada | [`RabbitAdminDtos`](ms-pedidos360-rabbitadmin/src/main/java/cl/duoc/pedidos360/rabbitadmin/api/RabbitAdminDtos.java) — DTO con `@NotBlank`, `@Pattern`, `@Size` |

## Topología

Tres flujos de trabajo, cada uno con su cola y su DLQ, más tres exchanges.

```
                  ┌──────────────┐  email.send    ┌───────────────┐
ms-orders ───────▶│  cmd.direct  │───────────────▶│  q.cmd.email  │──▶ EmailListener
                  │   (direct)   │  kitchen.ticket│  q.cmd.kitchen│──▶ KitchenListener
                  │              │  invoice.gen   │  q.cmd.invoice│──▶ InvoiceListener
                  └──────────────┘                └───────┬───────┘
                  ┌──────────────┐                        │ nack(requeue=false)
                  │  cmd.topic   │  email.*               ▼
                  │   (topic)    │  kitchen.#     ┌─────────────────┐
                  └──────────────┘  invoice.*     │ cmd.dead.dlx    │
                                                  │   (direct)      │
                                                  └────────┬────────┘
                                                           ▼
                                           q.cmd.email.dlq · kitchen.dlq · invoice.dlq
                                                           │
                                                           ▼
                                                   DeadLetterListener
```

## Comandos y eventos no son lo mismo

| | **RabbitMQ** | **Kafka** |
|---|---|---|
| Transporta | comandos: *"haz esto"* | eventos: *"esto ya pasó"* |
| Destinatario | uno, conocido | ninguno; quien quiera se suscribe |
| ¿Se puede rechazar? | sí → va a la DLQ | no, ya ocurrió |
| Al consumirse | desaparece de la cola | permanece; otro grupo lo relee |

Aceptar un pedido **manda un comando** a `q.cmd.email` (envía el correo) y **publica un
evento** en `orders.events` (que reportería y auditoría leen por su cuenta).

## Las tres rutas del ACK

Con ACK automático, RabbitMQ da el mensaje por entregado en cuanto lo escribe en el socket:
si el consumidor muere procesándolo, se pierde. Con ACK manual el broker solo lo borra
cuando el consumidor confirma. `AckPolicy` concentra las tres decisiones:

| Camino | Cuándo | Qué hace |
|---|---|---|
| `ack` | procesado correctamente | `basicAck` — el broker lo borra |
| `retry` | fallo **transitorio** (SMTP caído, timeout) | `basicNack(requeue=true)` — vuelve a la cola |
| `deadLetter` | fallo **permanente** (sin destinatario, JSON corrupto) | `basicNack(requeue=false)` — va al DLX |

Distinguirlos es la decisión importante: tratar todo como transitorio bloquea la cola con un
*poison pill*; tratar todo como permanente manda a la DLQ mensajes válidos por un hipo de red.

`DeadLetterListener` siempre hace `ack`: un mensaje que ya está en la DLQ no tiene dónde
seguir cayendo, y hacerle `nack` lo descartaría o crearía un bucle dentro de la propia DLQ.

## API del administrador

Base: `/api/rabbitadmin` · todos los endpoints exigen rol **Admin**.

| Método | Ruta | Qué hace |
|---|---|---|
| `GET` | `/queues` | Topología base + colas creadas por la API, con mensajes y consumidores |
| `GET` | `/queues/{name}` | Estado de una cola concreta |
| `POST` | `/queues` | Crea una cola (`durable`, `exclusive`, `autoDelete`, `arguments`) |
| `DELETE` | `/queues/{name}` | Elimina una cola |
| `POST` | `/queues/{name}/purge` | Vacía una cola sin eliminarla |
| `POST` | `/exchanges` | Crea un exchange `direct`, `topic`, `fanout` o `headers` |
| `DELETE` | `/exchanges/{name}` | Elimina un exchange |
| `POST` | `/bindings` | Enlaza cola y exchange con una routing key |
| `DELETE` | `/bindings` | Elimina el binding (cuerpo: cola + exchange + routing key) |

Tres decisiones que conviene saber defender:

- **Los errores no se tragan en el log.** Se traducen a códigos con sentido: `409` si la cola
  ya existe con otra definición, `404` si no existe, `503` si el broker no responde.
- **Las colas y exchanges del Caso 0 están protegidos.** Borrar `q.cmd.email` dejaría sin
  notificaciones a todos los clientes, así que la API responde `409`.
- **El listado lleva un registro en memoria.** AMQP no tiene ninguna operación para enumerar
  colas —solo declarar, consultar una concreta y eliminar— y por eso RabbitMQ ofrece la
  Management HTTP API aparte. `RabbitAdmin` habla AMQP puro y no puede listar.

## Probarlo

```bash
docker compose -f infra/mq/compose.yml up -d
./scripts/run-local.sh
cd frontend-pedidos360 && npm start
```

Entra en <http://localhost:4200> como **Administrador** y abre **Colas** en el menú lateral
(`/admin-colas`): estado real del broker y formularios para crear colas, exchanges y bindings.

### Ver la DLQ funcionando

Publica un comando sin destinatario y observa cómo acaba en la DLQ:

```bash
curl -u pedidos360:pedidos360 -H "Content-Type: application/json" \
  -X POST http://localhost:15672/api/exchanges/%2F/cmd.direct/publish \
  -d '{"properties":{"content_type":"application/json"},"routing_key":"email.send",
       "payload":"{\"type\":\"email.send\",\"eventId\":\"demo-1\",\"occurredAt\":1760000000.0,\"traceId\":\"t\",\"correlationId\":\"c\",\"actor\":\"demo\",\"aggregateId\":\"pedido-1\",\"payload\":{\"subject\":\"Sin destinatario\"}}",
       "payload_encoding":"string"}'
```

En el log de `ms-pedidos360-notify`:

```
AckPolicy        : Mensaje no procesable en q.cmd.email: comando email.send
                   sin destinatario. Se envia a la DLQ.
DeadLetterListener: [DLQ q.cmd.email.dlq] mensaje no procesable · type=email.send
                   eventId=demo-1 traceId=t pedido=pedido-1
```

La consola de RabbitMQ está en <http://localhost:15672> (`pedidos360` / `pedidos360`).

---

# Evaluación Parcial 1 — Identidad y API Gateway

| # | Indicador | Dónde |
|---|---|---|
| 1 | MSAL en Angular: login, guards, interceptor, roles y scopes | [`msal.config.ts`](frontend-pedidos360/src/app/core/msal.config.ts) · [`guards.ts`](frontend-pedidos360/src/app/core/guards.ts) · [`app.config.ts`](frontend-pedidos360/src/app/app.config.ts) |
| 2 | El BFF valida issuer, audiencia, firma y vigencia, y autoriza por rol | [`ResourceServerConfig`](pedidos360-common/src/main/java/cl/duoc/pedidos360/common/security/ResourceServerConfig.java) · [`AudienceValidator`](pedidos360-common/src/main/java/cl/duoc/pedidos360/common/security/AudienceValidator.java) · [`JwtAuthoritiesConverter`](pedidos360-common/src/main/java/cl/duoc/pedidos360/common/security/JwtAuthoritiesConverter.java) |

La autorización tiene **tres capas**, y cada una responde algo que las otras no pueden:

| Capa | Pregunta | Dónde |
|---|---|---|
| Perímetro | ¿el token es auténtico? | JWT Authorizer de AWS API Gateway |
| Funcional | ¿este **rol** puede llamar este endpoint? | `@PreAuthorize` en los controladores |
| Por dato | ¿este **usuario** puede tocar **este registro**? | `OrderService` |

La tercera es la que ningún gateway puede resolver, porque depende del contenido de la base
de datos. Un pedido ajeno responde **404 y no 403**: un 403 le confirmaría al atacante que
ese pedido existe.

```bash
./scripts/smoke-test.sh   # verifica 401, 403, 409 y el descuento de stock
```

---

# Documentación

| Documento | Para qué |
|---|---|
| [docs/00-PASO-A-PASO.md](docs/00-PASO-A-PASO.md) | Guía de estudio: cada paso de las guías del ramo con el porqué y las preguntas de defensa |
| [docs/05-AWS-DESDE-CERO.md](docs/05-AWS-DESDE-CERO.md) | Rehacer solo AWS en un laboratorio nuevo, ~70 min |
| [docs/04-DEPLOY-RUNBOOK.md](docs/04-DEPLOY-RUNBOOK.md) | Runbook completo de despliegue, con checkpoints y diagnóstico |
| [docs/01-azure-entra-id.md](docs/01-azure-entra-id.md) | Tenant, App Registrations, scopes y App Roles |
| [docs/02-aws-api-gateway.md](docs/02-aws-api-gateway.md) | HTTP API, JWT Authorizer y versionado `/v1` `/v2` |
| [docs/03-arquitectura.md](docs/03-arquitectura.md) | Decisiones de diseño, alternativas descartadas y limitaciones |
| [infra/README.md](infra/README.md) | Docker Compose y Security Groups |

# Estructura

```
pedidos360/
├── pedidos360-common/          Seguridad, envelope de eventos, topología AMQP y de Kafka
├── ms-pedidos360-bff/          Puerta única: valida, propaga el token y agrega respuestas
├── ms-pedidos360-orders/       Pedidos, máquina de estados, coordinación de stock
├── ms-pedidos360-catalog/      Productos y stock
├── ms-pedidos360-notify/       Consumidores RabbitMQ por dominio + ACK manual   ← EP3
├── ms-pedidos360-rabbitadmin/  Administrador de colas, exchanges y bindings      ← EP3
├── ms-pedidos360-report/       Consumidor Kafka (KPIs, lead time)
├── ms-pedidos360-audit/        Consumidor Kafka (timeline, solo lectura)
├── frontend-pedidos360/        Angular 20 + MSAL, guards por rol
├── infra/                      compose por VM: apps · mq · kafka
├── scripts/                    run-local · stop-local · smoke-test · package-for-ec2
└── docs/
```

# Puertos

| Servicio | Puerto | URL útil |
|---|---|---|
| Frontend | 4200 | <http://localhost:4200> |
| BFF | 8080 | <http://localhost:8080/swagger-ui.html> |
| orders | 8081 | <http://localhost:8081/swagger-ui.html> |
| catalog | 8082 | <http://localhost:8082/swagger-ui.html> |
| notify | 8083 | — |
| report | 8084 | <http://localhost:8084/swagger-ui.html> |
| audit | 8085 | <http://localhost:8085/swagger-ui.html> |
| **rabbitadmin** | **8086** | <http://localhost:8086/swagger-ui.html> |
| RabbitMQ | 15672 | <http://localhost:15672> (`pedidos360`/`pedidos360`) |
| Kafka UI | 8090 | <http://localhost:8090> |

# Roles y permisos

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
| **`/admin-colas`** | ✅ | ❌ | ❌ | ❌ |

# Stack

| Capa | Tecnología |
|---|---|
| Identidad | Microsoft Entra ID |
| Frontend | Angular 20, MSAL Angular 6, standalone components, signals |
| API Manager | AWS API Gateway HTTP API + JWT Authorizer |
| Backend | Spring Boot 3.5, Java 21, Spring Security OAuth2 Resource Server |
| Documentación de APIs | springdoc-openapi (Swagger UI por servicio) |
| Persistencia | JPA + H2 en memoria |
| Mensajería | RabbitMQ con DLQ · Kafka + ZooKeeper con DLT |
| Despliegue | Docker multi-stage + Docker Compose |

# Limitaciones conocidas

- **H2 en memoria en vez de una base de datos cloud.** La capa de persistencia es JPA puro,
  sin SQL propietario, así que migrar es cambiar la dependencia y el datasource. El
  procedimiento está en [docs/03-arquitectura.md](docs/03-arquitectura.md).
- **Token relay directo en vez de On-Behalf-Of.** Suficiente para el alcance; el flujo OBO
  está documentado como la alternativa correcta en producción.
- **Un broker de Kafka.** El propio Caso 0 lo plantea así: *"1 broker para MVP, luego escalar"*.
- **Sin pruebas unitarias.** La verificación es de integración, con `scripts/smoke-test.sh`.

# Requisitos

Java 21 · Maven 3.9+ · Node 20+ · Docker (para RabbitMQ, Kafka y el despliegue).
