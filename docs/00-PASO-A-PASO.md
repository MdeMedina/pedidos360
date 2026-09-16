# Pedidos360 · Paso a paso para estudiar la Evaluación 1

Este documento es la **guía de estudio**. No repite las capturas de las guías del ramo:
explica **en qué orden se hace cada cosa, por qué se hace así, y qué pregunta de
defensa responde cada paso**.

Está ordenado igual que las guías que te entregaron:

| # | Guía del ramo | Qué construye | Dónde se ve en el repo |
|---|---|---|---|
| 1 | 1.1.2 Creando nuestro primer API Manager | AWS API Gateway (HTTP API) con una ruta y un stage | `docs/02-aws-api-gateway.md` |
| 2 | Crear una aplicación para usuarios externos | Tenant de Entra ID + App Registrations | `docs/01-azure-entra-id.md` |
| 3 | 1.3.2 Configurar MSAL en el frontend | Angular obtiene el token | `frontend-pedidos360/src/app/core/msal.config.ts` |
| 4 | 1.3.3 Configurar Spring Security en el backend | Spring valida el token | `pedidos360-common/.../security/ResourceServerConfig.java` |
| 5 | 1.3.6 API Gateway + microservicio (versionado) | `/v1/*` y `/v2/*` coexistiendo | `docs/02-aws-api-gateway.md` §6 |
| 6 | 1.3.7 IDaaS + API Manager en la solución FullStack | JWT Authorizer uniendo todo | `docs/02-aws-api-gateway.md` §4 |

---

## Antes de empezar: el mapa mental completo

Todo el Caso 0 gira alrededor de **una sola pregunta**: *¿cómo sé que quien llama a mi
API es quien dice ser, y que puede hacer lo que está pidiendo?*

La respuesta tiene cuatro piezas, y cada guía construye una:

```
┌─────────────┐   1. "quiero entrar"    ┌──────────────────────┐
│  Angular    │ ──────────────────────▶ │  Microsoft Entra ID  │
│  + MSAL     │ ◀────────────────────── │       (IDaaS)        │
└──────┬──────┘   2. access_token (JWT) └──────────────────────┘
       │
       │ 3. Authorization: Bearer <token>
       ▼
┌──────────────────────┐   4. ¿firma OK? ¿issuer OK? ¿audiencia OK?
│  AWS API Gateway     │      (JWT Authorizer — el "portero")
│  HTTP API            │
└──────┬───────────────┘
       │ 5. si el token es válido, recién enruta
       ▼
┌──────────────────────┐   6. vuelve a validar el token
│  Spring Boot (BFF)   │      + decide por ROL (@PreAuthorize)
│  → microservicios    │      + decide por DATO (¿es tu pedido?)
└──────────────────────┘
```

**La pregunta de defensa número uno** suele ser: *«si el API Gateway ya validó el token,
¿para qué lo valida otra vez Spring?»*

Respuesta: porque validan **cosas distintas**.

- El Gateway valida **autenticación**: la firma, el emisor y la audiencia. Responde
  *"este token es auténtico"*. No sabe nada de tu negocio.
- Spring valida **autorización**: si ese usuario, con ese rol, puede ejecutar esa
  operación **sobre ese registro concreto**. Ningún authorizer genérico puede saber que
  el pedido `abc-123` pertenece a `cliente@pedidos360.cl`.
- Y además: el Gateway protege el **perímetro**. Si alguien entra a la VPC (una instancia
  comprometida, un contenedor mal configurado) y llama directo al puerto 8081, el gateway
  no está en el camino. Spring sí. Eso se llama **defensa en profundidad**.

---

## Paso 1 · Crear el API Manager (guía 1.1.2)

**Qué haces:** en AWS Console → API Gateway → Crear API → **HTTP API** → ruta `GET /datos`
→ integración HTTP hacia `https://mindicador.cl/api` → stage → deploy.

**Por qué este paso existe:** para entender que un API Gateway **no aloja tu API**, la
*fachada*. Por eso el ejercicio funciona apuntando a una API externa que tú no controlas:
demuestra que el gateway es una capa de indirección pura.

**Qué te da esa indirección (esto es lo que hay que saber responder):**

1. **Un único punto de entrada.** Diez microservicios, una sola URL pública y un solo
   certificado TLS que renovar.
2. **Un único punto donde aplicar políticas transversales:** autenticación, rate limiting,
   CORS, logging, WAF. Si eso viviera en cada microservicio, sería diez implementaciones
   que se desincronizan.
3. **Desacoplar la URL pública de la topología interna.** Puedes mover un microservicio de
   EC2 a Lambda y el cliente no se entera.
4. **Versionar sin tocar backend** (eso es exactamente el paso 5).

**Por qué HTTP API y no REST API:** la HTTP API es más barata (~70 % menos), más rápida, y
—lo importante para el Caso 0— trae **JWT Authorizer nativo**. Con REST API tendrías que
escribir un Lambda Authorizer a mano: más código, más latencia y más cosas que pueden fallar.

> ⚠️ Ojo con la guía 1.3.7: en un punto dice "Create API → REST API". Para el Caso 0 usa
> **HTTP API**, porque el propio enunciado exige *"AWS API Gateway HTTP API con JWT
> authorizer (Azure AD)"*. El JWT Authorizer nativo solo existe en HTTP API.

**Detalle del stage:** en HTTP API conviene crear el stage `$default` con auto-deploy. Si
creas un stage con nombre (`dev`, `prod`), la URL queda `https://<id>.execute-api.../dev/datos`
y ese prefijo **también viaja al backend**, lo que rompe las rutas si no lo contemplas.

---

## Paso 2 · Registrar las aplicaciones en Entra ID

Detalle completo, clic por clic: **`docs/01-azure-entra-id.md`**.

Lo conceptual que tienes que tener claro antes de tocar el portal:

**Son DOS registros, no uno.** Y a casi todos se les olvida por qué.

| Registro | Qué representa | Tipo de cliente |
|---|---|---|
| `pedidos360-spa` | el Angular que corre en el navegador | **Public client** |
| `pedidos360-api` | el backend Spring Boot | **Confidential client / Resource** |

**Por qué el SPA es "public client":** el código JavaScript vive en el navegador del
usuario. Cualquiera abre DevTools y lo lee. Por lo tanto **no puede guardar un secreto**:
un `client_secret` en Angular es un secreto publicado. De ahí se deriva todo lo demás:

- El SPA usa **Authorization Code Flow + PKCE**, no *client credentials*.
- **PKCE** existe justo porque no hay secreto: el cliente genera un `code_verifier`
  aleatorio, manda su hash (`code_challenge`) al pedir el código, y al canjearlo presenta
  el verifier original. Quien intercepte el código no puede canjearlo sin el verifier.
- El **Implicit Flow está obsoleto** porque devolvía el token en el *fragmento de la URL*:
  quedaba en el historial, en los logs del proxy y en el `Referer`. PKCE lo reemplazó.

MSAL hace todo esto por dentro. Tú solo configuras cuatro datos (guía 1.3.2):
*quién soy* (`clientId`), *en qué tenant* (`authority`), *dónde volver* (`redirectUri`)
y *qué API quiero llamar* (`scopes`).

**Roles vs Scopes** — la otra pregunta clásica de defensa:

| | `roles` | `scp` (scopes) |
|---|---|---|
| Responde a | **quién eres** | **qué le permitiste a la app hacer en tu nombre** |
| Lo define | el admin del tenant (App Roles) | el desarrollador (Expose an API) |
| Ejemplo | `Admin`, `Operator`, `Customer` | `orders.read`, `orders.write` |
| Se usa para | autorización de negocio | delegación / consentimiento |

Los dos viajan en el mismo access token y **no son intercambiables**. Un usuario con rol
`Admin` que usa una app a la que solo le dio `orders.read` **no debería poder escribir**:
el rol dice que la persona puede, el scope dice que esa aplicación no fue autorizada.
En el código eso se ve en `JwtAuthoritiesConverter`, que mapea `roles` → `ROLE_*` y
`scp` → `SCOPE_*`.

---

## Paso 3 · MSAL en el frontend (guía 1.3.2)

**Archivos del repo que corresponden a esta guía:**

| Guía dice | Archivo |
|---|---|
| "Instalar MSAL" | `package.json` → `@azure/msal-browser`, `@azure/msal-angular` |
| "Configurar los valores de Entra ID en environment.ts" | `src/environments/environment.ts` |
| "Crear la instancia de MSAL (factory function)" | `src/app/core/msal.config.ts` → `MSALInstanceFactory` |
| "Configurar MSAL Interceptor" | `msal.config.ts` → `MSALInterceptorConfigFactory` |
| "Registrar MSAL en el bootstrap" | `src/app/app.config.ts` |
| "Botones de Login / Logout" | `src/app/pages/login/login.html`, `src/app/app.html` |
| "MsalGuard protege rutas" | `src/app/core/guards.ts` |

**Por qué se instalan dos librerías:** `msal-browser` es la implementación del protocolo
(no sabe nada de Angular). `msal-angular` es el pegamento: guards, interceptor y servicios
inyectables. Separarlas permite que React y Vue reutilicen el mismo motor.

**Las tres piezas y qué hace cada una:**

- **`MsalService`** → login / logout / adquirir token. Es el único que habla con Entra ID.
- **`MsalGuard`** → antes de entrar a una ruta, verifica que haya sesión; si no, redirige
  al login. **Es experiencia de usuario, no seguridad**: el usuario puede quitarlo desde
  DevTools. Por eso el backend siempre vuelve a validar.
- **`MsalInterceptor`** → adjunta `Authorization: Bearer <token>` automáticamente **solo a
  las URLs del `protectedResourceMap`**. Ese mapa es importante: si adjuntaras el token a
  cualquier dominio, estarías filtrando la credencial del usuario a terceros.

> ### ⚠️ En este proyecto NO se usa MsalInterceptor, y hay que saber explicar por qué
>
> `MsalInterceptor` solo pide el token cuando `MsalBroadcastService` reporta
> `InteractionStatus.None`. Ese estado se alimenta de eventos de MSAL que hay que estar
> escuchando en el instante en que ocurren. Como aquí el ciclo de vida arranca desde un
> `provideAppInitializer` —para resolver la sesión antes de pintar la primera pantalla—,
> el estado se queda en `Startup` y el interceptor **deja pasar todas las peticiones sin
> cabecera `Authorization`**.
>
> El síntoma es desconcertante y vale la pena contarlo: el login funciona, el token existe
> y es válido, pero el backend responde 401. Se diagnostica mirando el log del servidor,
> no el del navegador: con `logging.level.org.springframework.security=DEBUG` se ve
> `Set SecurityContextHolder to anonymous`, que significa "no llegó ningún token", en vez
> de un `Failed to process authentication request`, que significaría "llegó uno malo".
>
> La solución: `core/auth.interceptor.ts` pide el token con `acquireTokenSilent` y lo
> adjunta, sin depender de ningún estado global. Se mantiene el patrón que enseña la guía
> —adquisición silenciosa primero, interactiva como fallback— y el `protectedResourceMap`
> de `msal.config.ts` sigue documentando qué scopes corresponden a qué URL.

**La estrategia de adquisición que hay que saber explicar:**
`acquireTokenSilent` **primero**, interactivo **solo como fallback**. MSAL guarda un
refresh token en su caché y renueva el access token sin molestar al usuario. Solo si la
renovación falla (sesión expirada, cambio de contraseña, MFA requerido) redirige al login.
Pedir login en cada request sería inutilizable.

**Decisiones del repo que tal vez te pregunten:**

- `cacheLocation: sessionStorage` y no `localStorage` → el token muere al cerrar la
  pestaña. En `localStorage` quedaría indefinidamente y accesible a cualquier script
  inyectado en la página.
- `logoutRedirect` y no borrar la caché a mano → borrar solo la caché local deja la sesión
  viva en Microsoft; el siguiente "login" entraría solo, sin pedir credenciales.
- El menú lateral se filtra por rol (`app.ts`), pero **los roles se leen de `/api/me`**,
  es decir, los calcula el backend desde el token ya validado. Nunca se confía en un valor
  que el navegador pueda editar.

**Modo demo (`authMode: 'dev'`):** este repo puede funcionar sin Azure. El BFF emite en
`/dev/token` un JWT firmado con HMAC que tiene **exactamente los mismos claims** que uno
de Entra ID (`iss`, `aud`, `sub`, `roles`, `scp`). Sirve para desarrollar y para demostrar
en vivo que la autorización bloquea de verdad, cambiando de rol en un clic. **No es un
reemplazo de Entra ID** y nunca debe activarse fuera del laboratorio: no hay login real,
ni consentimiento, ni rotación de claves, ni MFA.

---

## Paso 4 · Spring Security en el backend (guía 1.3.3)

**Archivo central:** `pedidos360-common/src/main/java/cl/duoc/pedidos360/common/security/ResourceServerConfig.java`

**El concepto:** el backend es un **Resource Server**. No inicia sesión, no pide
contraseñas, no habla con el usuario. Solo recibe un JWT y decide si lo acepta.

**Qué valida Spring automáticamente cuando configuras `issuer-uri`:**

1. Descarga `https://<issuer>/.well-known/openid-configuration`.
2. De ahí saca la `jwks_uri` y descarga las claves públicas.
3. Con esa clave verifica la **firma** → el token no fue alterado y lo emitió Entra ID.
4. Verifica `iss` (emisor) y `exp` / `nbf` (vigencia).

**Lo que Spring NO valida solo, y por eso está `AudienceValidator`:** el claim **`aud`**.
Sin esa clase, cualquier token válido del mismo tenant —incluido uno emitido para Microsoft
Graph— sería aceptado por tu API. Es el equivalente en el backend al campo `audiences` del
JWT Authorizer de AWS.

**Las tres capas de autorización del repo, y por qué hacen falta las tres:**

| Capa | Dónde | Pregunta que responde | Ejemplo |
|---|---|---|---|
| Perímetro | AWS JWT Authorizer | ¿el token es auténtico? | firma, `iss`, `aud` |
| Funcional | `@PreAuthorize` | ¿este **rol** puede llamar este endpoint? | `hasRole('ADMIN')` en `POST /api/catalog/products` |
| Por dato | `OrderService` | ¿este **usuario** puede tocar **este registro**? | un Cliente solo ve sus pedidos |

La tercera capa es la que se olvida y la que más nota da defender. En
`OrderService.findVisibleById` el pedido ajeno devuelve **404 y no 403**, a propósito: un
403 le confirmaría al atacante que ese pedido existe.

**Otras decisiones explicables:**

- `csrf.disable()` → es una API **stateless** con token en cabecera. CSRF explota cookies
  enviadas automáticamente por el navegador; aquí no hay cookie de sesión que robar.
  Deshabilitarlo en una app con sesión por cookie sería un error grave; aquí es correcto.
- `SessionCreationPolicy.STATELESS` → sin sesión de servidor. Cualquier instancia puede
  atender cualquier request; es lo que permite escalar horizontalmente detrás del gateway.
- `@EnableMethodSecurity` → habilita `@PreAuthorize`. Sin esta anotación, los
  `@PreAuthorize` **se ignoran silenciosamente**: el endpoint queda abierto y no hay ningún
  error que lo delate. Es el bug de seguridad más común del ramo.

---

## Paso 5 · Versionado en el API Gateway (guía 1.3.6)

**Qué haces:** agregas `GET /v1/datos` y `GET /v2/datos` apuntando a integraciones
distintas (la guía usa `mindicador.cl` y `jsonplaceholder.typicode.com` para simular un
*breaking change*).

**Por qué importa:** una API pública es un **contrato**. Si cambias el modelo de datos en
sitio, rompes a todos los consumidores a la vez: frontends, apps móviles, integradores.
El versionado permite que `v1` siga funcionando mientras `v2` evoluciona, y que cada equipo
migre cuando pueda.

**Lo valioso del ejercicio:** el versionado se resuelve **en el Gateway**, sin tocar ni una
línea de backend. El Gateway es el punto donde se decide qué implementación atiende qué
contrato.

**Qué es un breaking change** (te lo pueden preguntar): renombrar o eliminar un campo,
cambiar su tipo, volver obligatorio un campo opcional, cambiar el significado de un valor.
**No** es breaking change agregar un campo opcional nuevo.

**Deprecación ordenada:** cuando `v1` va a morir, se anuncian las cabeceras HTTP
`Deprecation: true` y `Sunset: <fecha>` (RFC 8594). Así el consumidor se entera por el
protocolo y no por un correo que nadie leyó.

En Pedidos360 este patrón se aplica en `docs/02-aws-api-gateway.md` §6.

---

## Paso 6 · Unir todo: IDaaS + API Manager (guía 1.3.7)

Este es el paso que integra los cinco anteriores. Detalle completo en
**`docs/02-aws-api-gateway.md`**. El resumen conceptual:

1. **Entra ID expone la API** (`Application ID URI` + scopes) y **el frontend pide permiso**
   sobre ella (API permissions + *Grant admin consent*).
   - *Por qué el "Grant admin consent":* sin él, cada usuario vería una pantalla de
     consentimiento la primera vez. Aceptable para una app de consumo; inaceptable para una
     app corporativa.
2. **El JWT Authorizer del Gateway** se configura con dos valores y solo dos:
   - `issuer`: de dónde debe venir el token.
     - Workforce (Azure AD): `https://login.microsoftonline.com/<TENANT_ID>/v2.0`
     - External ID / CIAM (el que crea *User Flow*): `https://<tenant>.ciamlogin.com/<TENANT_ID>/v2.0`
   - `audience`: para quién fue emitido (el `Application ID URI` o el client id del backend).
3. **El User Flow** de External ID existe porque es el mecanismo por el que un usuario
   *externo* se registra e inicia sesión. Es el que define el `iss` del token y, por lo
   tanto, el que tiene que coincidir con lo que configuraste en el Authorizer.

**El error de integración más común, y cómo diagnosticarlo:**

| Síntoma | Causa casi segura |
|---|---|
| `401 Unauthorized` en el Gateway | el `issuer` configurado no coincide con el `iss` del token |
| `403 Forbidden` en el Gateway | la `audience` no coincide con el `aud` del token |
| El Gateway pasa pero Spring devuelve 401 | `issuer-uri` del backend apunta a otro tenant o falta `/v2.0` |
| Spring devuelve 403 con token válido | el claim `roles` está vacío: falta asignar el App Role al usuario |

**Cómo se diagnostica en 30 segundos:** pega el token en <https://jwt.ms> y compara
`iss` y `aud` con lo que pusiste en el Authorizer. El 90 % de los problemas se ven ahí.

---

## Paso 7 · Lo que agrega el Caso 0 sobre las guías

Las guías cubren identidad y gateway. El Caso 0 pide además mensajería. El repo lo trae
implementado y funcionando; esto es lo que hay que poder explicar.

### RabbitMQ = comandos · Kafka = eventos

No son intercambiables, y confundirlos es la respuesta equivocada más frecuente.

| | **Comando** (RabbitMQ) | **Evento** (Kafka) |
|---|---|---|
| Significa | "haz esto" | "esto ya pasó" |
| Tiempo verbal | imperativo: `email.send` | pasado: `OrderDelivered` |
| Destinatario | uno, conocido | ninguno; quien quiera se suscribe |
| Se puede rechazar | sí (va a la DLQ) | no, ya ocurrió |
| Al consumirse | desaparece de la cola | permanece; otro grupo lo relee |
| Sirve para | ejecutar trabajo | reconstruir estado, auditar, analizar |

En Pedidos360: aceptar un pedido **manda un comando** a `q.cmd.email` (envía el correo) y
**publica un evento** en `orders.events` (esto pasó, que cada uno haga lo suyo).

### Por qué la reportería NO consulta la base de pedidos

Es literal en el enunciado: *"Datos por streaming (Kafka) sin bloquear core"*. Calcular
KPIs con `GROUP BY` contra la base transaccional degrada el servicio que atiende clientes.
La solución es **CQRS**: el modelo de escritura vive en `ms-orders`; el de lectura, en
`ms-report`, alimentado por eventos. Con `auto-offset-reset: earliest`, la reportería se
puede **reconstruir desde cero** releyendo el tópico, sin tocar producción.

**Pruébalo tú mismo en la defensa:** apaga `ms-report`, crea pedidos, vuelve a encenderlo.
Los KPIs aparecen completos. Eso no se puede hacer con una cola.

### Idempotencia: el concepto que más se pregunta

Ni RabbitMQ ni Kafka garantizan *exactly-once*: garantizan **at-least-once**. Si el
consumidor procesa el mensaje y muere **antes** del ACK, el broker lo reentrega. Sin
protección, el cliente recibe dos correos.

Por eso el `EventEnvelope` lleva un `eventId` inmutable y cada consumidor lo verifica:
- `ms-notify` → `Outbox.markProcessed(eventId)` (en producción sería Redis o una tabla).
- `ms-audit` → índice **único** sobre `eventId` en la base.

### DLQ y DLT: por qué existen

Un mensaje irreparable (JSON corrupto, destinatario inválido) reintentado infinitamente
**bloquea la cola o la partición completa** — el clásico *poison pill*. Por eso:

- **RabbitMQ:** tras N reintentos, el mensaje va al *dead-letter exchange* → `*.dlq`.
- **Kafka:** `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` lo publican en
  `<tópico>.DLT` con el error adjunto.

En ambos casos el mensaje **no se pierde**: queda aparcado para inspección y reproceso,
y la cola sigue avanzando.

### Por qué `orders.events` se particiona por `orderId`

Kafka garantiza orden **dentro de una partición**, no dentro de un tópico. Usando el
`orderId` como clave, todos los eventos de un mismo pedido caen en la misma partición y se
consumen en orden. Sin esa clave, `OrderDelivered` podría procesarse antes que
`OrderAccepted` y el lead time saldría negativo.

---

## Paso 8 · Cómo levantar y demostrar todo

### Opción rápida (sin Docker, para desarrollar)

```bash
cd pedidos360
./scripts/run-local.sh          # compila y levanta los 6 microservicios
cd frontend-pedidos360 && npm start
```

Entra a <http://localhost:4200> y elige un rol. No necesitas Azure ni AWS.

### Opción completa (con RabbitMQ y Kafka)

```bash
docker network create pedidos360-net
docker compose -f infra/mq/compose.yml    up -d
docker compose -f infra/kafka/compose.yml up -d
cp infra/apps/.env.example infra/apps/.env
docker compose -f infra/apps/compose.yml --env-file infra/apps/.env up -d --build
```

### Guion de demostración (10 minutos, en este orden)

| # | Qué muestras | Qué demuestra |
|---|---|---|
| 1 | `curl http://localhost:8080/api/orders` sin token → **401** | la API está cerrada por defecto |
| 2 | Login como **Cliente** → el menú solo tiene Dashboard y Pedidos | autorización por rol en la UI |
| 3 | Escribes `/reports` en la barra de direcciones → **403** | el guard no depende del menú |
| 4 | Creas un pedido e intentas "Despachar" | el botón **no existe**: viene de `allowedTransitions` |
| 5 | Como **Operador**, aceptas el pedido → el stock baja en Catálogo | regla "stock decrece al aceptar" |
| 6 | Vas a Auditoría | los 2 eventos ya viajaron por Kafka |
| 7 | Abres Kafka UI (`localhost:8090`) → tópico `orders.events` | el evento existe de verdad |
| 8 | Abres RabbitMQ (`localhost:15672`) → colas y DLQ | la topología del enunciado, completa |
| 9 | `./scripts/smoke-test.sh` | 401/403/409 verificados automáticamente |

El paso 9 es el que más impresiona: **un script que prueba que la seguridad bloquea**,
en vez de una afirmación de que bloquea.

---

## Checklist de la evaluación

| Requisito del Caso 0 | Estado | Dónde |
|---|---|---|
| Angular 18+ con MSAL | ✅ Angular 20 + MSAL Angular 6 | `frontend-pedidos360/` |
| Guards por rol | ✅ | `src/app/core/guards.ts` |
| Spring Boot 3+, Java 21 | ✅ Boot 3.5.16 / Java 21 | `pom.xml` |
| OpenAPI por servicio | ✅ `/swagger-ui.html` en cada uno | `common/.../OpenApiConfig.java` |
| JWT de Entra ID validado en backend | ✅ + validación de audiencia | `ResourceServerConfig.java` |
| Autorización por rol en endpoints | ✅ `@PreAuthorize` | todos los controladores |
| AWS API Gateway HTTP API + JWT Authorizer | 📄 procedimiento documentado | `docs/02-aws-api-gateway.md` |
| Versionado `/v1` y `/v2` | 📄 procedimiento documentado | `docs/02-aws-api-gateway.md` §6 |
| EC2 + Docker Compose | ✅ tres compose, uno por VM | `infra/` |
| RabbitMQ (comandos) con DLQ | ✅ 3 colas + 3 DLQ + 3 exchanges | `common/.../RabbitTopology.java` |
| Kafka (eventos) con ZooKeeper | ✅ 2 tópicos + DLT | `infra/kafka/compose.yml` |
| CRUD pedidos + máquina de estados | ✅ | `ms-pedidos360-orders` |
| CRUD catálogo + stock al aceptar | ✅ | `ms-pedidos360-catalog` |
| Notificaciones asíncronas | ✅ | `ms-pedidos360-notify` |
| Reportería (KPIs, lead time) | ✅ | `ms-pedidos360-report` |
| Auditoría (timeline, solo lectura) | ✅ | `ms-pedidos360-audit` |
| Base de datos | ⚠️ **H2 en memoria**, no Oracle | ver nota abajo |

**Nota sobre la base de datos:** el enunciado pide Oracle. El repo usa **H2 en memoria**
para que todo arranque sin instalar nada. La capa de persistencia es **JPA puro**, sin SQL
propietario, así que migrar a Oracle es cambiar la dependencia y el datasource
(`docs/03-arquitectura.md` §Persistencia trae el procedimiento). Dilo explícitamente en la
defensa: una limitación reconocida y acotada resta mucho menos que una que el evaluador
descubre solo.

---

## Preguntas de defensa y respuestas cortas

**¿Por qué MSAL y no implementar OAuth a mano?**
Porque OAuth2 + OIDC tienen decenas de detalles críticos (PKCE, validación de `nonce` y
`state`, renovación silenciosa, rotación de claves JWKS). Cada uno mal implementado es una
vulnerabilidad. MSAL es la implementación oficial, auditada y mantenida por Microsoft.

**¿MSAL protege tu API?**
No. MSAL **obtiene** tokens del lado del cliente. Quien **protege** la API es Spring
Security OAuth2 Resource Server, y en el perímetro el JWT Authorizer del Gateway. Es la
pregunta 5 del sprint de MSAL y se falla mucho.

**¿Dónde guardas el token y por qué?**
En `sessionStorage`, gestionado por MSAL. No en `localStorage` (persiste indefinidamente y
es accesible a cualquier script) ni en una cookie (implicaría manejar CSRF).

**¿Qué pasa si el token expira mientras el usuario trabaja?**
`MsalInterceptor` intenta `acquireTokenSilent` con el refresh token. Si funciona, el
usuario no se entera. Si falla, redirige al login. Y si una llamada igual sale con token
vencido, el backend responde 401 y el frontend distingue 401 (reautenticarse) de 403
(rol insuficiente) — `shared/api-error.ts`.

**¿Por qué un BFF y no llamar a los microservicios directo desde Angular?**
Tres razones: (1) una sola integración que configurar en el API Gateway en vez de cinco;
(2) las URLs internas no quedan expuestas en el bundle del navegador; (3) la agregación
—el dashboard necesita datos de dos servicios— se hace en la red interna, no con dos
viajes por Internet.

**¿Por qué el BFF reenvía el token del usuario en vez de usar una credencial de servicio?**
Porque con *token relay* cada microservicio sigue sabiendo **quién es el usuario real** y
puede aplicar sus propias reglas de rol. Con una credencial técnica compartida, `catalog`
vería "me llamó orders" y perdería toda capacidad de autorizar.

**¿Por qué la máquina de estados vive en el enum `OrderStatus` y no en el controlador?**
Porque una regla escrita con `if` en un controlador se puede saltar desde cualquier otro
punto del código. En el dominio, cada estado declara a qué estados puede moverse; es
imposible saltarse la regla sin modificar el dominio. Y como el backend expone
`allowedTransitions`, el frontend **no puede** ofrecer una transición inválida.
