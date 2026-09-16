# Arquitectura y decisiones de diseño

Documento de referencia: **qué se decidió, por qué, y qué alternativa se descartó**.
Es el material para responder "¿por qué lo hiciste así?" en la defensa.

---

## Vista general

```
                        ┌──────────────────────┐
                        │  Microsoft Entra ID  │
                        │       (IDaaS)        │
                        └──────────┬───────────┘
                          token JWT│
┌──────────────┐                   │
│   Angular    │◀──────────────────┘
│   + MSAL     │
└──────┬───────┘
       │ Authorization: Bearer <jwt>
       ▼
┌──────────────────────┐
│  AWS API Gateway     │  JWT Authorizer: firma + iss + aud
└──────────┬───────────┘
           ▼
┌──────────────────────┐
│  ms-pedidos360-bff   │  valida de nuevo + agrega + token relay
└───┬────┬────┬────┬───┘
    │    │    │    │
    ▼    ▼    ▼    ▼
 orders catalog report audit          notify
    │      ▲       ▲     ▲              ▲
    │      │       │     │              │
    └──────┘       └──┬──┘              │
    HTTP síncrono     │                 │
                      │ Kafka           │ RabbitMQ
              orders.events        q.cmd.email
              audit.timeline       q.cmd.kitchen
                                   q.cmd.invoice
```

**Lo que hay que leer en este diagrama:** hay **tres formas** de comunicación y cada una
responde a una necesidad distinta.

| Comunicación | Tecnología | Cuándo | Ejemplo |
|---|---|---|---|
| Síncrona | HTTP + token relay | el que llama **necesita la respuesta ahora** | orders → catalog para descontar stock |
| Comando async | RabbitMQ | hay que **hacer algo**, pero puede esperar | enviar el correo de confirmación |
| Evento async | Kafka | **ya pasó algo** y otros querrán enterarse | `OrderAccepted` → reportería y auditoría |

Si el descuento de stock fuera asíncrono, se podrían aceptar pedidos sin stock. Si el envío
de correo fuera síncrono, un proveedor SMTP lento haría fallar la aceptación del pedido.
La elección no es estilística.

---

## Microservicios

| Servicio | Puerto | Dominio | Expone | Consume |
|---|---|---|---|---|
| `ms-pedidos360-bff` | 8080 | — | `/api/**`, `/api/me`, `/api/dashboard` | HTTP a todos |
| `ms-pedidos360-orders` | 8081 | Pedidos | `/api/orders/**` | HTTP a catalog; produce Kafka + RabbitMQ |
| `ms-pedidos360-catalog` | 8082 | Productos y stock | `/api/catalog/**` | — |
| `ms-pedidos360-notify` | 8083 | Notificaciones | *(nada público)* | RabbitMQ |
| `ms-pedidos360-report` | 8084 | KPIs | `/api/report/**` (lectura) | Kafka |
| `ms-pedidos360-audit` | 8085 | Auditoría | `/api/audit/**` (lectura) | Kafka |

Más `pedidos360-common`: no es un microservicio, es una librería con la configuración de
seguridad, el envelope de eventos y el manejo de errores. **Por qué existe:** sin ella, la
configuración de Resource Server estaría copiada seis veces, y a la séptima alguien
olvidaría el `AudienceValidator` en un servicio. La seguridad debe vivir en un solo lugar.

---

## Decisiones y sus alternativas

### 1 · BFF propio en vez de Spring Cloud Gateway

**Se eligió:** un Spring Boot normal que proxea con `RestClient`.

**Por qué:** el gateway *real* del Caso 0 es AWS API Gateway; el BFF solo necesita reenviar,
agregar y propagar el token. Un Spring Boot plano no añade la matriz de compatibilidad de
versiones de Spring Cloud, y el código es leíble línea por línea — importante cuando hay
que defenderlo.

**Qué se pierde:** rate limiting y circuit breaker de fábrica. Si hicieran falta, Spring
Cloud Gateway sería la elección correcta.

### 2 · Token relay en vez de credencial de servicio

**Se eligió:** el BFF y orders reenvían **el mismo access token del usuario**.

**Por qué:** cada microservicio sigue sabiendo quién es el usuario real y puede aplicar sus
propias reglas. Con una credencial técnica compartida, `catalog` solo vería "me llamó
orders" y perdería toda capacidad de autorizar.

**Qué se pierde:** si el token expira a mitad de una cadena larga, la llamada interna falla.
La alternativa profesional es el flujo **On-Behalf-Of** (escenario D del sprint de MSAL):
el BFF cambia el token del usuario por otro dirigido al servicio downstream. Es más
correcto y más complejo; para el alcance de la EV1 el relay directo es suficiente y se
puede explicar sin ambigüedad.

### 3 · La máquina de estados vive en el dominio

**Se eligió:** el enum `OrderStatus` declara `allowedTransitions()`.

**Por qué:** una regla escrita con `if` en un controlador se puede saltar desde cualquier
otro punto del código (un job, un endpoint nuevo, un test). En el enum es **imposible**
ejecutar una transición no declarada sin modificar el dominio.

**Bonus:** `GET /api/orders/{id}` devuelve `allowedTransitions`, y el frontend dibuja los
botones **a partir de esa lista**. Resultado: la UI no puede ofrecer una transición que el
backend vaya a rechazar, y si mañana cambia la máquina de estados en Java, el frontend se
adapta solo. Cero duplicación de la regla.

### 4 · Autorización en tres capas

| Capa | Qué decide | Dónde |
|---|---|---|
| Perímetro | ¿el token es auténtico? | JWT Authorizer de AWS |
| Funcional | ¿este **rol** puede llamar este endpoint? | `@PreAuthorize` |
| Por dato | ¿este **usuario** puede tocar **este registro**? | `OrderService` |

La tercera capa es la que ningún gateway puede resolver, porque depende del contenido de la
base de datos. `OrderService.findVisibleById` devuelve **404 y no 403** para un pedido
ajeno: un 403 le confirmaría al atacante que ese pedido existe.

### 5 · Perfil `dev` con tokens locales

**Se eligió:** un endpoint `/dev/token` en el BFF que emite JWT firmados con HMAC, con los
mismos claims que Entra ID.

**Por qué:** hace la solución demostrable sin depender de la disponibilidad del tenant de
Azure ni de AWS Academy, y permite cambiar de rol en un clic para mostrar que la
autorización bloquea de verdad.

**Los límites, y hay que decirlos:** no hay login real, ni consentimiento, ni MFA, ni
rotación de claves. El controlador está anotado con `@Profile("dev")`, así que con
`SPRING_PROFILES_ACTIVE=azure` ni siquiera se registra en el contexto de Spring. La ruta
`/dev/**` nunca debe publicarse en el API Gateway.

### 6 · Mensajería apagada por defecto

**Se eligió:** `pedidos360.messaging.rabbit-enabled` y `kafka-enabled` en `false`, y un
`DefaultDomainEventPublisher` que escribe en el log cuando están apagados.

**Por qué:** el núcleo de la EV1 (identidad, gateway, CRUD, reglas) debe poder levantarse y
defenderse sin brokers. Con los flags en `true` (lo que hace `infra/apps/compose.yml`) la
mensajería real entra en funcionamiento sin tocar una línea de código.

**Efecto visible:** con Kafka apagado, las pantallas de Reportería y Auditoría salen vacías
**a propósito**, y cada una lo explica en pantalla. Eso mismo demuestra de dónde vienen los
datos: no de la base de pedidos.

---

## Persistencia

Hoy: **H2 en memoria**, una base por servicio (`orders`, `catalog`, `report`, `audit`).
`notify` no tiene base, tal como especifica el Caso 0.

**Una base por servicio, no una compartida.** Si dos servicios comparten tablas, cualquier
cambio de esquema los acopla y ya no son independientes: son un monolito distribuido, que
es lo peor de los dos mundos.

### Migrar a Oracle

El enunciado pide Oracle. La capa de persistencia es **JPA puro**, sin SQL propietario, así
que la migración es de configuración:

**1 · Dependencia** en el `pom.xml` del servicio:

```xml
<dependency>
  <groupId>com.oracle.database.jdbc</groupId>
  <artifactId>ojdbc11</artifactId>
  <scope>runtime</scope>
</dependency>
```

**2 · Datasource** — `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:oracle:thin:@//oracle-host:1521/XEPDB1
    username: ${ORACLE_USER}
    password: ${ORACLE_PASSWORD}
    driver-class-name: oracle.jdbc.OracleDriver
  jpa:
    database-platform: org.hibernate.dialect.OracleDialect
    hibernate:
      ddl-auto: validate      # en producción nunca 'update'
```

**3 · Contenedor** — añadir a `infra/apps/compose.yml`:

```yaml
oracle:
  image: gvenzl/oracle-free:23-slim
  environment:
    ORACLE_PASSWORD: pedidos360
  ports: ["1521:1521"]
  healthcheck:
    test: ["CMD", "healthcheck.sh"]
    interval: 15s
    retries: 20
    start_period: 120s
```

**4 · Migraciones.** `ddl-auto: update` es aceptable en un laboratorio, pero en producción
es peligroso: Hibernate nunca borra ni renombra columnas, así que el esquema real y el
esperado divergen en silencio. Lo correcto es **Flyway** o **Liquibase**, con los scripts
versionados en el repositorio.

---

## Observabilidad

| Qué | Dónde | Estado |
|---|---|---|
| Health checks | `/actuator/health` en cada servicio | ✅ usado por los healthchecks de Docker |
| OpenAPI | `/swagger-ui.html` en cada servicio | ✅ requisito del Caso 0 |
| Trazabilidad de negocio | `traceId` / `correlationId` en el envelope | ✅ |
| Timeline de auditoría | `ms-audit` | ✅ |
| Métricas Prometheus | — | ⏳ `micrometer-registry-prometheus` |
| Trazas distribuidas | — | ⏳ OpenTelemetry + Zipkin/Jaeger |
| Alertas de tasa de DLQ | — | ⏳ el Caso 0 lo menciona como buena práctica |

Los tres últimos están fuera del alcance de la EV1, pero conviene tenerlos identificados:
si el evaluador pregunta "¿qué le falta?", una respuesta concreta vale más que un silencio.

---

## Seguridad: resumen defendible

| Control | Implementación | Ataque que previene |
|---|---|---|
| Validación de firma JWT | JWKS del issuer, en gateway y backend | tokens falsificados |
| Validación de audiencia | `AudienceValidator` + `audiences` en AWS | reutilizar un token de otra API del tenant |
| Autorización por rol | `@PreAuthorize` | escalación horizontal de privilegios |
| Autorización por dato | filtro por `customer` en `OrderService` | un cliente leyendo pedidos ajenos |
| 404 en vez de 403 en recursos ajenos | `findVisibleById` | enumeración de recursos |
| Stateless, sin cookie de sesión | `SessionCreationPolicy.STATELESS` | CSRF, fijación de sesión |
| CORS con origen explícito | gateway + `ResourceServerConfig` | lectura desde sitios de terceros |
| `sessionStorage` y no `localStorage` | `msal.config.ts` | persistencia del token tras cerrar |
| Bloqueo optimista (`@Version`) | `Product` | stock negativo por escrituras concurrentes |
| Validación completa antes de descontar | `CatalogService.reserve` | pedidos parcialmente descontados |
| Contenedores con usuario sin privilegios | `Dockerfile` | escalada dentro del contenedor |
| Un solo puerto público | Security Groups | exposición de brokers y servicios internos |

---

## Lo que queda fuera y por qué

| Tema | Estado | Comentario |
|---|---|---|
| Oracle | H2 en memoria | JPA puro; migración documentada arriba |
| On-Behalf-Of | token relay directo | suficiente para el alcance; OBO documentado en decisión 2 |
| Rate limiting | — | se resolvería en el API Gateway, no en código |
| Tests automatizados | `scripts/smoke-test.sh` | pruebas de integración end-to-end; faltan unitarias |
| CI/CD | — | el Caso 0 no lo exige en esta evaluación |
| Alta disponibilidad de Kafka | 1 broker | el propio enunciado dice "1 broker para MVP, luego escalar" |

Decir esto en voz alta en la defensa **suma**. Una limitación reconocida y acotada resta
mucho menos que una que el evaluador descubre por su cuenta.
