# Runbook de despliegue · Pedidos360

Procedimiento **ejecutable**, en el orden real en que se hace. No explica teoría: para eso
está [00-PASO-A-PASO.md](00-PASO-A-PASO.md). Aquí solo se ejecuta y se verifica.

**Tiempo estimado:** 2 h 30 min la primera vez. Repetirlo después toma ~30 min.

## Orden de las fases y por qué ese orden

```
FASE 0  Local            compilar y empaquetar            20 min
FASE 1  Azure Entra ID   identidad: 2 apps, scopes, roles 40 min   ← primero: todo depende de esto
FASE 2  AWS EC2          3 instancias + security groups   30 min
FASE 3  Desplegar        mq → kafka → apps                25 min
FASE 4  API Gateway      HTTP API + JWT Authorizer        30 min   ← necesita el issuer (fase 1) y la IP (fase 2)
FASE 5  Frontend         apuntar al gateway               10 min
FASE 6  Verificar        end-to-end                       15 min
```

No se puede alterar: el Authorizer de la fase 4 necesita el `issuer` de la fase 1 y la IP
de la fase 2. Si empiezas por AWS vas a tener que volver atrás.

---

## Hoja de valores

Imprímela o cópiala en un archivo aparte. **Todo el despliegue se cae si un solo valor
está mal**, y el 90 % de los errores son un carácter de diferencia aquí.

| # | Valor | Lo obtienes en | Tu valor |
|---|---|---|---|
| A | Directory (tenant) ID | Fase 1.1 | `________________________________` |
| B | Client ID de la API | Fase 1.1 | `________________________________` |
| C | Application ID URI | Fase 1.3 | `________________________________` |
| D | Client ID del SPA | Fase 1.6 | `________________________________` |
| E | Issuer URL | Fase 1.9 | `________________________________` |
| F | IP elástica de `ec2-apps` | Fase 2.4 | `________________________________` |
| G | IP privada de `ec2-mq` | Fase 2.3 | `________________________________` |
| H | IP privada de `ec2-kafka` | Fase 2.3 | `________________________________` |
| I | URL de invocación del API Gateway | Fase 4.2 | `________________________________` |

---

## ⚠️ Lo que tienes que saber de AWS Academy antes de empezar

| Restricción | Consecuencia práctica |
|---|---|
| La sesión del laboratorio dura ~4 h | Al expirar, las instancias se **detienen**. No se borran, pero se apagan |
| Las IP públicas cambian al detener/arrancar | **Usa IP elástica** en `ec2-apps` (fase 2.4) o tendrás que reconfigurar el API Gateway cada vez |
| No puedes crear roles IAM | Usa siempre `LabRole` donde te pidan un rol |
| Solo `us-east-1` | Crea todo ahí |
| Key pair | Se llama `vockey`; el `.pem` se descarga desde "AWS Details → Download PEM" |
| Tipos de instancia limitados | `t2.micro` … `t3.medium` suelen estar permitidos |

**Antes de cada sesión de trabajo:** entra al laboratorio, pulsa *Start Lab*, espera el
círculo verde y **descarga de nuevo el `.pem` si lo perdiste**.

---

# FASE 0 · Preparar el paquete en tu máquina

Se compila **en local**, no en la EC2. Compilar con Maven dentro de una `t3.small`
consume ~2 GB solo para el compilador y el proceso muere con un escueto `Killed`.

```bash
cd "EV 1/pedidos360"
./scripts/package-for-ec2.sh
```

Resultado: `dist-ec2/pedidos360-apps.tgz` (~400 MB) con los 6 jars, el
`Dockerfile.runtime`, los tres compose y el `.env.example`.

**Verificación antes de seguir:**

```bash
./scripts/run-local.sh && ./scripts/smoke-test.sh
```

Los 8 chequeos tienen que pasar. **Si algo falla en local, va a fallar en AWS igual pero
con diez veces menos información para diagnosticarlo.** No sigas hasta que esté verde.

```bash
./scripts/stop-local.sh
```

---

# FASE 1 · Microsoft Entra ID

Guías: *"Crear una aplicación para usuarios externos"*, **1.3.2**, **1.3.3**, **1.3.7**.
El detalle con capturas está en [01-azure-entra-id.md](01-azure-entra-id.md). Aquí va la
secuencia mínima.

### 1.1 · Registrar el backend

Portal Azure → **Microsoft Entra ID** → **Registros de aplicaciones** → **Nuevo registro**

- Nombre: `pedidos360-api`
- Tipos de cuenta: *Solo cuentas de este directorio*
- **Sin URI de redirección** (una API no recibe redirecciones de navegador)

📝 Anota **Directory (tenant) ID** → **A** y **Application (client) ID** → **B**

### 1.2 · Poner la API en tokens v2 (antes que nada)

`pedidos360-api` → **Manifiesto** → cambia `requestedAccessTokenVersion` de `null` a `2`
(en el manifiesto nuevo de Microsoft Graph está dentro del objeto `"api"`) → **Guardar**.

```json
"requestedAccessTokenVersion": 2
```

> **Esto no es opcional.** La versión del token decide el `iss` y el `aud`, es decir,
> exactamente lo que vas a configurar en el `.env` (fase 3.3) y en el JWT Authorizer
> (fase 4.3):
>
> | | v1 (`null`, por defecto) | v2 |
> |---|---|---|
> | `iss` | `https://sts.windows.net/<A>/` | `https://login.microsoftonline.com/<A>/v2.0` |
> | `aud` | el Application ID URI | el **client ID** (GUID) |
>
> Todo este runbook asume v2. Con `null`, el backend rechazará tokens que el portal muestra
> como válidos, porque el `iss` no coincide — y el error no dice nada del token version.

### 1.3 · Exponer la API

`pedidos360-api` → **Exponer una API** → **Application ID URI** → *Agregar*.

> ⚠️ **Si sale `Failed to add identifier URI … must contain a tenant verified domain,
> tenant ID, or app ID`:** es una política del tenant. `api://pedidos360-api` no contiene
> ninguno de los tres. Usa una de estas dos, que siempre se aceptan:
>
> ```
> api://<B>                                    ← con el app ID; la que Azure propone
> https://<tuTenant>.onmicrosoft.com/pedidos360-api   ← con el dominio verificado
> ```
>
> **Los scopes heredan ese prefijo**, así que el valor que guardes aquí es el que llevarán
> `apiScopes` en la fase 5.

📝 Anota el URI que el tenant **sí** aceptó → **C**

### 1.4 · Crear los 6 scopes

Mismo panel → **Agregar un ámbito**, uno por cada uno. Consentimiento: **Administradores**.

```
orders.read   orders.write
catalog.read  catalog.write
report.read   audit.read
```

### 1.5 · Crear los 4 App Roles

`pedidos360-api` → **Roles de aplicación** → **Crear rol de aplicación**.
Tipos de miembros permitidos: **Usuarios/Grupos**.

| Valor | Nombre para mostrar |
|---|---|
| `Admin` | Administrador |
| `Operator` | Operador |
| `Customer` | Cliente |
| `Auditor` | Auditor |

> El campo **Valor** es lo que viaja en el claim `roles`. Tiene que ser exactamente eso.

### 1.6 · Registrar el frontend

**Nuevo registro** → `pedidos360-spa`

- Plataforma: **Single-page application (SPA)** ← *no* "Web"
- URI de redirección: `http://localhost:4200/auth/callback`

📝 Anota **Application (client) ID** → **D**

> **Si eliges "Web" en vez de SPA**, Entra ID exige un `client_secret` al canjear el código
> y MSAL falla con `AADSTS9002326`. Es el error más común de esta fase.

> **Entra ID solo acepta `http://` en redirect URIs de `localhost`.** Todo lo demás debe
> ser `https://`. Por eso el frontend se ejecuta en tu máquina (fase 5) y no en la EC2:
> `http://<ip-ec2>:4200/auth/callback` **no es registrable**.

### 1.7 · Dar permisos al frontend sobre la API

`pedidos360-spa` → **Permisos de API** → **Agregar un permiso** → **Mis API** →
`pedidos360-api` → **Permisos delegados** → marca los 6 → **Agregar permisos**

Luego: **Conceder consentimiento de administrador**. Las 6 filas tienen que quedar en verde.

### 1.8 · Crear usuarios y asignarles roles

**Entra ID → Usuarios → Nuevo usuario → Crear usuario nuevo.** Uno por rol:

```
admin@<tenant>.onmicrosoft.com      → Admin
operador@<tenant>.onmicrosoft.com   → Operator
cliente@<tenant>.onmicrosoft.com    → Customer
auditor@<tenant>.onmicrosoft.com    → Auditor
```

Anota las contraseñas autogeneradas y **haz el primer login de los cuatro ahora**, en
incógnito: Azure obliga a cambiar la contraseña la primera vez, y ese diálogo apareciendo
en medio de la demostración es un mal momento para enterarse.

Después: **Entra ID → Aplicaciones empresariales** (no "Registros") → `pedidos360-api` →
**Usuarios y grupos** → **Agregar usuario/grupo** → usuario + rol.

> Buscar esto en *Registros de aplicaciones* es la causa nº 1 de *"mi token no trae `roles`"*.

> **¿Y el User Flow de la guía 1.3.7?** Solo aplica a tenants **External ID (CIAM)**. Si al
> abrir *External Identities → User flows* ves **New user flow** en gris junto a un aviso
> sobre *"self-service sign up for guest users"*, tu tenant es **workforce**: no necesitas
> User Flow, los usuarios son los que acabas de crear y tu issuer es
> `https://login.microsoftonline.com/<A>/v2.0`, que es lo que el proyecto trae por defecto.

### 1.9 · Obtener el issuer y verificarlo

```bash
TENANT_ID="<valor A>"
curl -s "https://login.microsoftonline.com/$TENANT_ID/v2.0/.well-known/openid-configuration" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['issuer']); print(d['jwks_uri'])"
```

📝 Copia el `issuer` **literal** que devuelve → **E**

> Con External ID / CIAM el host es `https://<tenant>.ciamlogin.com/...` y además hay que
> crear el **User Flow** (fase 4 de [01-azure-entra-id.md](01-azure-entra-id.md)).

**Checkpoint de la fase 1:** tienes A, B, C, D y E anotados y el `curl` responde.

---

# FASE 2 · Instancias EC2

### 2.1 · Crear los Security Groups

EC2 → **Grupos de seguridad** → **Crear**. Tres grupos, en este orden.

> **Las tres tablas son reglas de ENTRADA (inbound).** El **outbound se deja como viene
> por defecto**: todo el tráfico de salida permitido. No es pereza, hace falta:
> `ec2-apps` sale a `login.microsoftonline.com` a descargar el JWKS para validar tokens,
> y las tres instancias salen a Docker Hub y a los repos de Amazon Linux. Si cierras el
> outbound sin abrir esos destinos, el backend arranca pero rechaza todos los tokens.

**`sg-pedidos360-apps`**

| Tipo | Puerto | Origen | Por qué |
|---|---|---|---|
| SSH | 22 | **Mi IP** | administración |
| TCP personalizado | 8080 | `0.0.0.0/0` | el API Gateway no tiene IPs fijas |

> **8080 abierto a Internet suena mal y hay que saber defenderlo.** API Gateway HTTP API
> con integración a un endpoint público sale desde el rango de AWS, que no es fijo, así que
> no se puede restringir por IP. Mitigaciones reales: (1) el BFF **exige JWT válido**, así
> que un puerto abierto no es un acceso abierto; (2) en producción se usa un **VPC Link**
> con un ALB privado y el puerto no queda expuesto en absoluto. Dilo tú antes de que lo
> pregunten — es exactamente el tipo de decisión que evalúan.

**`sg-pedidos360-mq`**

| Tipo | Puerto | Origen |
|---|---|---|
| SSH | 22 | Mi IP |
| TCP personalizado | 5672 | `sg-pedidos360-apps` |
| TCP personalizado | 15672 | **Mi IP** |

**`sg-pedidos360-kafka`**

| Tipo | Puerto | Origen |
|---|---|---|
| SSH | 22 | Mi IP |
| TCP personalizado | 9092 | `sg-pedidos360-apps` |
| TCP personalizado | 8090 | Mi IP |

> **Origen = otro security group, no un CIDR.** Así, si la IP de `ec2-apps` cambia, la
> regla sigue valiendo. Y **nunca** `0.0.0.0/0` en 5672, 9092 ni 2181: ninguno de esos
> protocolos lleva autenticación fuerte por defecto.

### 2.2 · Lanzar las tres instancias

EC2 → **Lanzar instancias**. Mismos valores en las tres salvo lo indicado:

| Campo | Valor |
|---|---|
| AMI | **Amazon Linux 2023** |
| Par de claves | `vockey` |
| Datos de usuario | contenido de `infra/ec2-user-data.sh` |

| Instancia | Tipo | Security group | Almacenamiento |
|---|---|---|---|
| `ec2-apps` | **t3.medium** | `sg-pedidos360-apps` | 20 GiB |
| `ec2-mq` | t3.small | `sg-pedidos360-mq` | 10 GiB |
| `ec2-kafka` | **t3.medium** | `sg-pedidos360-kafka` | 20 GiB |

> **Por qué t3.medium y no t3.micro:** seis JVM en `ec2-apps` y Kafka+ZooKeeper en
> `ec2-kafka` no caben en 1 GB. Con `t3.micro` los contenedores mueren con `OOMKilled`
> minutos después de arrancar, que es un fallo desconcertante porque el `docker compose up`
> aparenta haber funcionado.

**Datos de usuario** — pega esto en *Detalles avanzados → Datos de usuario*:

```bash
cat "EV 1/pedidos360/infra/ec2-user-data.sh"
```

Instala Docker, el plugin compose y crea la red `pedidos360-net`.

### 2.3 · Anotar las IP privadas

EC2 → Instancias → columna *Dirección IPv4 privada*.

📝 `ec2-mq` → **G**  ·  `ec2-kafka` → **H**

> Se usan las **privadas** para el tráfico entre instancias: no sale a Internet, no paga
> transferencia y no requiere abrir nada hacia fuera.

### 2.4 · IP elástica para `ec2-apps`

EC2 → **IP elásticas** → **Asignar** → seleccionar → **Acciones → Asociar** → `ec2-apps`.

📝 Anota → **F**

> **Sin IP elástica, la IP pública cambia cada vez que el laboratorio se detiene**, y con
> ella hay que reconfigurar la integración del API Gateway. Es 1 minuto de trabajo ahora
> contra 15 de frustración en cada sesión.

### 2.5 · Comprobar el acceso

```bash
chmod 400 ~/labsuser.pem
ssh -i ~/labsuser.pem ec2-user@<F>

# dentro de la instancia:
ls ~/.pedidos360-ready     # lo crea el user data al terminar
docker --version && docker compose version
```

Si `.pedidos360-ready` no existe, el user data falló:

```bash
sudo cat /var/log/cloud-init-output.log | tail -40
```

**Checkpoint de la fase 2:** entras por SSH a las tres y `docker compose version` responde.

---

# FASE 3 · Desplegar los contenedores

Orden obligatorio: **mq → kafka → apps**. Las apps tienen healthchecks que dependen de los
brokers; al revés arrancan degradadas.

### 3.1 · `ec2-mq` — RabbitMQ

```bash
scp -i ~/labsuser.pem "EV 1/pedidos360/infra/mq/compose.yml" ec2-user@<IP_MQ_PUBLICA>:~/
ssh -i ~/labsuser.pem ec2-user@<IP_MQ_PUBLICA>
```

```bash
docker network create pedidos360-net 2>/dev/null || true
docker compose -f compose.yml up -d
docker compose -f compose.yml ps        # rabbitmq debe quedar (healthy)
```

Verifica desde tu navegador: `http://<IP_MQ_PUBLICA>:15672` → `pedidos360` / `pedidos360`.

### 3.2 · `ec2-kafka` — ZooKeeper + Kafka

El `KAFKA_ADVERTISED_LISTENERS` del compose apunta a `localhost` para el listener externo.
En EC2 hay que cambiarlo por la **IP privada**, o los clientes de `ec2-apps` se conectarán
y luego intentarán hablar con `localhost` — y fallarán **después** del handshake, que es el
error de Kafka más confuso que existe.

```bash
scp -i ~/labsuser.pem "EV 1/pedidos360/infra/kafka/compose.yml" ec2-user@<IP_KAFKA_PUBLICA>:~/
ssh -i ~/labsuser.pem ec2-user@<IP_KAFKA_PUBLICA>
```

```bash
PRIVATE_IP=$(curl -s -H "X-aws-ec2-metadata-token: $(curl -sX PUT \
  'http://169.254.169.254/latest/api/token' \
  -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')" \
  http://169.254.169.254/latest/meta-data/local-ipv4)
echo "IP privada: $PRIVATE_IP"

sed -i "s|EXTERNAL://localhost:9092|EXTERNAL://$PRIVATE_IP:9092|" compose.yml
grep ADVERTISED compose.yml          # confirma el cambio

docker network create pedidos360-net 2>/dev/null || true
docker compose -f compose.yml up -d
docker compose -f compose.yml ps     # zookeeper y kafka en (healthy)
```

```bash
docker exec pedidos360-kafka kafka-topics --bootstrap-server localhost:29092 --list
```

### 3.3 · `ec2-apps` — los microservicios

```bash
scp -i ~/labsuser.pem "EV 1/pedidos360/dist-ec2/pedidos360-apps.tgz" ec2-user@<F>:~/
ssh -i ~/labsuser.pem ec2-user@<F>
```

```bash
tar -xzf pedidos360-apps.tgz && cd pedidos360
cp .env.example .env
nano .env
```

Contenido del `.env` con **tus** valores:

```bash
SPRING_PROFILES_ACTIVE=azure
PEDIDOS360_ISSUER_URI=<E>
PEDIDOS360_AUDIENCES=<C>,<B>
PEDIDOS360_CORS_ENABLED=false

RABBITMQ_HOST=<G>
RABBITMQ_USER=pedidos360
RABBITMQ_PASSWORD=pedidos360
KAFKA_BOOTSTRAP=<H>:9092

PEDIDOS360_RABBIT_ENABLED=true
PEDIDOS360_KAFKA_ENABLED=true
```

> **`PEDIDOS360_AUDIENCES` lleva los dos valores (C y B).** Entra ID emite el claim `aud`
> con el *Application ID URI* o con el *client id* según cómo se haya pedido el token.
> Aceptar ambos evita un 401 intermitente extremadamente difícil de diagnosticar.

> **`PEDIDOS360_CORS_ENABLED=false` es obligatorio detrás del gateway.** Si la aplicación y
> el gateway agregan cabeceras CORS, la respuesta viaja con dos
> `Access-Control-Allow-Origin` y el navegador la descarta con un mensaje que no menciona
> la duplicación.

```bash
docker compose --env-file .env up -d --build
docker compose ps
```

Las seis tienen que quedar `(healthy)`. Si alguna no:

```bash
docker compose logs --tail 60 bff-svc
```

### 3.4 · Verificar desde tu máquina

```bash
curl -i http://<F>:8080/actuator/health     # 200 {"status":"UP"}
curl -i http://<F>:8080/api/orders          # 401  ← correcto: la API está cerrada
```

> Ese **401 es el resultado que buscas**. Significa que el backend está vivo y exige token.

**Checkpoint de la fase 3:** health responde UP y `/api/orders` responde 401.

---

# FASE 4 · AWS API Gateway

Guías **1.1.2**, **1.3.6** y **1.3.7**, aplicadas al BFF real.

### 4.1 · Crear la HTTP API

API Gateway → **Crear API** → **HTTP API** → *Compilar*

- Nombre: `pedidos360-api`
- Sin integraciones ni rutas todavía → *Siguiente*
- **Definir etapas**: deja `$default` con **implementación automática** activada

> **Usa `$default`.** Una etapa con nombre añade un prefijo a la URL (`/dev/api/orders`)
> que **también llega al backend** y rompe el enrutamiento de Spring.

📝 Anota la **URL de invocación** → **I**

### 4.2 · Crear la integración hacia el BFF

**Desarrollar → Integraciones → Administrar integraciones → Crear**

| Campo | Valor |
|---|---|
| Tipo | **URI de HTTP** |
| Método | **ANY** |
| URL | `http://<F>:8080/{proxy}` |

> **`{proxy}` no es opcional.** Sin él todas las rutas caerían en la misma URL fija del
> backend. Con `{proxy}` el gateway reenvía el resto del path tal cual y una sola
> integración cubre todos los endpoints.

### 4.3 · Crear el JWT Authorizer

**Desarrollar → Autorización → Crear y adjuntar un autorizador → JWT**

| Campo | Valor |
|---|---|
| Nombre | `entra-id-authorizer` |
| Origen de identidad | `$request.header.Authorization` |
| Emisor | **E** |
| Audiencia | **C** (y añade **B** como segunda) |

> **Pega el issuer que devolvió el `curl` de la fase 1.9**, no lo escribas de memoria. Un
> `/v2.0` de más o de menos produce un 401 que cuesta una tarde encontrar.

### 4.4 · Crear las rutas

**Desarrollar → Rutas → Crear**

| Método | Ruta | Authorizer | Integración |
|---|---|---|---|
| `ANY` | `/api/{proxy+}` | `entra-id-authorizer` | la de 4.2 |
| `GET` | `/actuator/health` | *(ninguno)* | la de 4.2 |

Para cada una: selecciónala → **Adjuntar autorización**, luego → **Adjuntar integración**.

> **Nunca publiques `/dev/{proxy+}`.** Es el emisor de tokens de demo. Con
> `SPRING_PROFILES_ACTIVE=azure` el controlador ni siquiera se registra, pero no lo
> expongas igualmente.

### 4.5 · Configurar CORS

**Desarrollar → CORS → Configurar**

| Campo | Valor |
|---|---|
| `Access-Control-Allow-Origin` | `http://localhost:4200` |
| `Access-Control-Allow-Headers` | `authorization, content-type` |
| `Access-Control-Allow-Methods` | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| `Access-Control-Max-Age` | `600` |

> **Nunca `*` con credenciales**: el navegador rechaza esa combinación, y aunque no lo
> hiciera sería abrir tu API a cualquier página.

### 4.6 · Verificar el gateway

```bash
API="<I>"

curl -i "$API/api/orders"                                   # 401 ← el authorizer bloquea
curl -i "$API/api/orders" -H "Authorization: Bearer basura" # 401 ← firma inválida
curl -i "$API/actuator/health"                              # 200 ← ruta sin authorizer
```

Los tres casos son excelente material de defensa: demuestran las tres respuestas del
authorizer sin abrir una sola línea de código.

**Checkpoint de la fase 4:** los tres `curl` dan 401, 401 y 200.

---

# FASE 5 · Frontend

El frontend se ejecuta **en tu máquina** apuntando al API Gateway. No es un atajo: Entra ID
solo acepta `http://` en redirect URIs de `localhost`, así que servirlo desde
`http://<ip-ec2>:4200` haría imposible registrar el `redirectUri`.

> **Si necesitas el frontend también en la nube**, hay que ponerle HTTPS: S3 + CloudFront, o
> nginx con certificado. Entonces registras `https://<dominio>/auth/callback` como segundo
> redirect URI y agregas ese origen al CORS del gateway. Para la evaluación, local es
> suficiente y es lo que la guía 1.3.2 muestra (`ng serve` en `localhost:4200`).

### 5.1 · Configurar el entorno

`frontend-pedidos360/src/environments/environment.ts`:

```ts
export const environment = {
  production: false,
  authMode: 'msal',                                   // ← era 'dev'
  apiBaseUrl: '<I>',                                  // ← URL del API Gateway
  msal: {
    clientId: '<D>',
    authority: 'https://login.microsoftonline.com/<A>/',
    knownAuthorities: [],                             // ['<tenant>.ciamlogin.com'] si usas CIAM
    redirectUri: 'http://localhost:4200/auth/callback',
    postLogoutRedirectUri: 'http://localhost:4200/login',
    apiScopes: [
      '<C>/orders.read',  '<C>/orders.write',
      '<C>/catalog.read', '<C>/catalog.write',
      '<C>/report.read',  '<C>/audit.read',
    ],
  },
};
```

> `apiBaseUrl` apunta al **API Gateway**, no al BFF. Si apuntara al BFF te saltarías el
> authorizer y la mitad de la evaluación dejaría de estar en el camino.

### 5.2 · Levantar

```bash
cd frontend-pedidos360
npm install
npm start
```

Abre <http://localhost:4200>, pulsa **Iniciar sesión con Microsoft**, autentica y revisa
que caiga en `/dashboard` con tus roles en la esquina superior derecha.

**Checkpoint de la fase 5:** `/api/me` devuelve tu usuario, tus roles, la audiencia y el
issuer reales de Entra ID.

---

# FASE 6 · Verificación end-to-end

### 6.1 · La cadena de seguridad

| # | Acción | Resultado esperado |
|---|---|---|
| 1 | `curl <I>/api/orders` sin token | **401** del gateway |
| 2 | Login como **Cliente** | menú solo con Dashboard y Pedidos |
| 3 | Escribir `/reports` en la barra de direcciones | pantalla **403** |
| 4 | Crear un pedido y mirar los botones | no existe «Despachar» |
| 5 | Como **Operador**, aceptar el pedido | el stock baja en Catálogo |

### 6.2 · La mensajería

| # | Dónde | Qué ves |
|---|---|---|
| 6 | Pantalla **Auditoría** | `OrderCreated` y `OrderAccepted` ya registrados |
| 7 | Kafka UI `http://<IP_KAFKA>:8090` | tópico `orders.events` con mensajes |
| 8 | RabbitMQ `http://<IP_MQ>:15672` | las 6 colas y sus DLQ |
| 9 | Pantalla **Reportería** | KPIs, lead time y productos más vendidos |

### 6.3 · Inspeccionar un token real

Copia el `Authorization` de cualquier petición desde las DevTools y pégalo en
<https://jwt.ms>. Verifica:

```
iss  → debe ser exactamente E
aud  → debe ser C o B
roles→ debe traer tu rol
scp  → debe traer los scopes que pediste
```

Esto es lo primero que hay que mirar ante cualquier 401 o 403.

---

# Apagar y volver a arrancar

**Al terminar la sesión** (AWS Academy lo hará solo al expirar):

```bash
# en cada instancia
docker compose down
```

y *End Lab* en el portal.

**Al retomar:** *Start Lab* → las instancias arrancan solas. Como pusiste IP elástica en
`ec2-apps`, **F** no cambia y el API Gateway sigue configurado. En cada instancia:

```bash
cd ~/pedidos360 && docker compose --env-file .env up -d   # ec2-apps
docker compose -f compose.yml up -d                       # ec2-mq y ec2-kafka
```

> Si las IP **privadas** de `ec2-mq` o `ec2-kafka` cambiaron, actualiza `G`/`H` en el `.env`
> de `ec2-apps` y en `KAFKA_ADVERTISED_LISTENERS`, y reinicia.

---

# Diagnóstico

## El gateway responde 401 siempre

| Verifica | Cómo |
|---|---|
| El issuer coincide | `jwt.ms` → claim `iss` vs. el campo Emisor del authorizer |
| El issuer termina en `/v2.0` | compáralo con la metadata OIDC |
| El token no expiró | claim `exp` |

## El gateway responde 403

El claim `aud` no está en la lista de audiencias. Añade el otro valor (**B** si pusiste
**C**, o al revés).

## El gateway responde 500 / 503

No alcanza tu EC2.

```bash
curl -i http://<F>:8080/actuator/health        # ¿responde desde fuera?
```

Si no: security group sin el 8080, o los contenedores caídos.

## Todo da 404

Falta `{proxy}` al final de la URL de integración (fase 4.2).

## `curl` funciona pero el navegador da error de CORS

1. CORS configurado en el gateway (fase 4.5), con el origen exacto `http://localhost:4200`.
2. `PEDIDOS360_CORS_ENABLED=false` en el `.env` de `ec2-apps`. **Cabeceras duplicadas dan el
   mismo mensaje de error que cabeceras ausentes.**

Para confirmar la duplicación:

```bash
curl -s -D - -o /dev/null "<I>/api/orders" -H "Origin: http://localhost:4200" \
  | grep -i access-control-allow-origin
```

Si aparece **dos veces**, ese es el problema.

## Spring responde 401 aunque el gateway dejó pasar

`PEDIDOS360_ISSUER_URI` del `.env` no coincide con el issuer real.

```bash
docker compose logs bff-svc | grep -i issuer
```

## Spring responde 403 con un token válido

El claim `roles` viene vacío: falta asignar el App Role en **Aplicaciones empresariales →
Usuarios y grupos** (fase 1.8).

## "New user flow" aparece deshabilitado en el portal

Tu tenant es **workforce**, no External ID. No necesitas User Flow: sigue con los usuarios
que creaste en la fase 1.8 y el issuer `https://login.microsoftonline.com/<A>/v2.0`.

## Los contenedores mueren solos

```bash
docker compose ps        # ¿Exited (137)?
free -h
```

`137` = `OOMKilled`, sin memoria. Sube el tipo de instancia a `t3.medium`.

## Kafka conecta y luego falla

`KAFKA_ADVERTISED_LISTENERS` sigue apuntando a `localhost`. Repite el `sed` de la fase 3.2
con la IP privada y reinicia.

```bash
docker exec pedidos360-kafka kafka-broker-api-versions --bootstrap-server <H>:9092
```

## `docker` pide sudo

El `usermod -aG docker` del user data necesita una sesión nueva. Sal y vuelve a entrar por
SSH.

---

# Checklist final

```
FASE 0  □ smoke-test.sh pasa en local
        □ dist-ec2/pedidos360-apps.tgz generado

FASE 1  □ pedidos360-api registrada          → A, B
        □ requestedAccessTokenVersion = 2 en el manifiesto
        □ Application ID URI aceptado por el tenant → C
        □ 6 scopes creados
        □ 4 App Roles creados
        □ pedidos360-spa registrada (SPA)    → D
        □ Permisos delegados + consentimiento de admin
        □ 4 usuarios creados y con la contraseña ya cambiada
        □ Roles asignados en Aplicaciones empresariales
        □ (Solo CIAM) User Flow creado — en workforce se salta
        □ Issuer verificado con curl         → E

FASE 2  □ 3 security groups creados
        □ 3 instancias lanzadas con user data
        □ IP elástica en ec2-apps            → F
        □ IP privadas anotadas               → G, H
        □ SSH y docker compose version OK en las tres

FASE 3  □ ec2-mq   → rabbitmq (healthy), consola 15672 accesible
        □ ec2-kafka→ advertised listeners con IP privada, (healthy)
        □ ec2-apps → .env completo, 6 servicios (healthy)
        □ curl http://<F>:8080/api/orders → 401

FASE 4  □ HTTP API creada, stage $default    → I
        □ Integración ANY hacia http://<F>:8080/{proxy}
        □ JWT Authorizer con issuer E y audiencias C,B
        □ Rutas /api/{proxy+} y /actuator/health
        □ CORS con http://localhost:4200
        □ Los 3 curl dan 401, 401, 200

FASE 5  □ environment.ts en authMode 'msal' con D, A, C e I
        □ npm start → login con Microsoft funciona
        □ /api/me devuelve roles reales

FASE 6  □ Cliente ve menú reducido y /reports da 403
        □ No aparece «Despachar» sin aceptar
        □ El stock baja al aceptar
        □ Auditoría muestra los eventos
        □ Kafka UI muestra orders.events
        □ RabbitMQ muestra las 6 colas
        □ Reportería muestra KPIs
```
