# AWS desde cero · laboratorio nuevo

Guía condensada para rehacer **solo la parte de AWS**. Azure ya está hecho y no
se toca: los registros, scopes, App Roles y usuarios siguen siendo los mismos.

**Tiempo:** ~70 min siguiendo esto al pie de la letra. La primera vez fueron 3 h
porque fuimos descubriendo los errores; aquí ya están todos corregidos.

## Valores que vienen de Azure (no cambian)

| | |
|---|---|
| Tenant ID | `41f0982b-bfd4-40ea-8b24-cd683cd0f362` |
| Client ID de la API | `1e8d01fa-bd60-4403-ae34-4288007ed4dd` |
| Application ID URI | `api://pedidos360-api` |
| Client ID del SPA | `d19179d8-5de0-491a-a16f-20b8dc658709` |
| Issuer | `https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362/v2.0` |

## Valores nuevos que irás anotando

| | |
|---|---|
| **F** · IP elástica de `ec2-apps` | `____________________` |
| **G** · IP privada de `ec2-mq` | `____________________` |
| **H** · IP privada de `ec2-kafka` | `____________________` |
| **I** · URL de invocación del gateway | `____________________` |

---

# A · Security Groups

EC2 → **Security groups** → **Create**. Tres grupos, **en este orden** porque los
dos últimos referencian al primero.

**Todas las reglas son de ENTRADA (inbound).** El outbound se deja por defecto
—todo permitido— porque las instancias necesitan salir a Docker Hub y a
`login.microsoftonline.com` para descargar el JWKS.

### `sg-pedidos360-apps`
| Tipo | Puerto | Origen |
|---|---|---|
| SSH | 22 | **My IP** |
| Custom TCP | 8080 | `0.0.0.0/0` |

> El 8080 abierto se defiende así: API Gateway no tiene IPs fijas, así que no se
> puede restringir por origen. Mitigaciones: el BFF exige JWT válido —puerto
> abierto no es acceso abierto— y en producción se usaría VPC Link con un ALB
> privado.

### `sg-pedidos360-mq`
| Tipo | Puerto | Origen |
|---|---|---|
| SSH | 22 | My IP |
| Custom TCP | 5672 | `sg-pedidos360-apps` |
| Custom TCP | 15672 | My IP |

### `sg-pedidos360-kafka`
| Tipo | Puerto | Origen |
|---|---|---|
| SSH | 22 | My IP |
| Custom TCP | 9092 | `sg-pedidos360-apps` |
| Custom TCP | 8090 | My IP |

> En 5672 y 9092 el origen es **otro security group**, no un CIDR: si la IP de
> `ec2-apps` cambia, la regla sigue valiendo. Y nunca `0.0.0.0/0` ahí: ni AMQP ni
> Kafka llevan autenticación fuerte por defecto.

---

# B · Las tres instancias

EC2 → **Launch instances**. Mismos valores salvo lo indicado:

| Campo | Valor |
|---|---|
| AMI | **Amazon Linux 2023** |
| Key pair | el del laboratorio nuevo — **descarga el `.pem`** |
| User data | contenido de `infra/ec2-user-data.sh` |

| Instancia | Tipo | Security group | Disco |
|---|---|---|---|
| `ec2-apps` | **t3.medium** | `sg-pedidos360-apps` | 20 GiB |
| `ec2-mq` | t3.small | `sg-pedidos360-mq` | 10 GiB |
| `ec2-kafka` | **t3.medium** | `sg-pedidos360-kafka` | 20 GiB |

> **t3.micro no sirve.** Siete JVM en `ec2-apps` y Kafka+ZooKeeper en `ec2-kafka`
> no caben en 1 GB: los contenedores mueren con `OOMKilled` minutos después, y
> el `docker compose up` aparenta haber funcionado.

**El user data va en Detalles avanzados → Datos de usuario**, al final del
formulario de lanzamiento. Se pega el **contenido** del archivo, no el comando
`cat`. Va en las tres.

```bash
cat infra/ec2-user-data.sh    # para verlo y copiarlo
```

Ese campo solo existe al lanzar. Si se te olvida, hay que entrar por SSH e
instalar Docker a mano.

### B.1 · IP elástica

EC2 → **Elastic IPs** → **Allocate** → **Associate** → `ec2-apps`. 📝 → **F**

> Sin esto la IP cambia cada vez que el laboratorio se detiene, y con ella hay
> que reconfigurar la integración del gateway en cada sesión.

### B.2 · Anotar las IP privadas

EC2 → Instances → clic en el Instance ID → pestaña **Details** → *Private IPv4
addresses*. Empiezan por `172.31.`.

📝 `ec2-mq` → **G** · `ec2-kafka` → **H**

### B.3 · Comprobar

```bash
chmod 400 ~/<tu-clave>.pem
ssh -i ~/<tu-clave>.pem ec2-user@<IP_PUBLICA>
ls ~/.pedidos360-ready && docker compose version
```

Si falta `.pedidos360-ready`, el user data falló:
`sudo tail -40 /var/log/cloud-init-output.log`

---

# C · Desplegar

Orden obligatorio: **mq → kafka → apps**.

### C.1 · `ec2-mq`

```bash
scp -i ~/<clave>.pem pedidos360/infra/mq/compose.yml ec2-user@<IP_MQ>:~/
ssh -i ~/<clave>.pem ec2-user@<IP_MQ>
docker network create pedidos360-net 2>/dev/null
docker compose -f compose.yml up -d
docker compose -f compose.yml ps        # debe quedar (healthy)
```

### C.2 · `ec2-kafka`

```bash
scp -i ~/<clave>.pem pedidos360/infra/kafka/compose.yml ec2-user@<IP_KAFKA>:~/
ssh -i ~/<clave>.pem ec2-user@<IP_KAFKA>
```

```bash
sed -i "s|EXTERNAL://localhost:9092|EXTERNAL://<H>:9092|" compose.yml
grep ADVERTISED compose.yml                 # confirma el cambio
docker network create pedidos360-net 2>/dev/null
docker compose -f compose.yml up -d
```

> **El `sed` no es opcional.** Si el advertised listener queda en `localhost`,
> los clientes conectan y fallan **después** del handshake, intentando hablar
> con un host que no existe. Es el error de Kafka más desconcertante que hay.

### C.3 · `ec2-apps`

```bash
./scripts/package-for-ec2.sh                # genera el .tgz con los 7 jars
scp -i ~/<clave>.pem pedidos360/dist-ec2/pedidos360-apps.tgz ec2-user@<F>:~/
ssh -i ~/<clave>.pem ec2-user@<F>
```

```bash
tar -xzf pedidos360-apps.tgz && cd pedidos360
cp .env.example .env && nano .env
```

Contenido del `.env`:

```bash
SPRING_PROFILES_ACTIVE=azure
PEDIDOS360_ISSUER_URI=https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362/v2.0
PEDIDOS360_AUDIENCES=api://pedidos360-api,1e8d01fa-bd60-4403-ae34-4288007ed4dd
PEDIDOS360_JWK_SET_URI=https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362/discovery/v2.0/keys
PEDIDOS360_CORS_ENABLED=false
RABBITMQ_HOST=<G>
RABBITMQ_USER=pedidos360
RABBITMQ_PASSWORD=pedidos360
KAFKA_BOOTSTRAP=<H>:9092
PEDIDOS360_RABBIT_ENABLED=true
PEDIDOS360_KAFKA_ENABLED=true
```

```bash
docker compose --env-file .env up -d --build
docker compose ps                           # los 7 en (healthy)
```

> **`PEDIDOS360_CORS_ENABLED=false` es obligatorio.** Detrás del gateway, si la
> app y el gateway añaden cabeceras CORS, la respuesta llega con dos
> `Access-Control-Allow-Origin` y el navegador la descarta.

> **Dos audiencias.** El claim `aud` será el GUID o el URI según la versión del
> token. Aceptar ambas evita un 401 intermitente difícil de rastrear.

### C.4 · Verificar desde tu máquina

```bash
curl -i http://<F>:8080/actuator/health     # 200 UP
curl -i http://<F>:8080/api/orders          # 401 ← correcto
```

Ese 401 es el resultado que buscas: el backend está vivo y exige token.

---

# D · API Gateway

### D.1 · Crear la API

API Gateway → **Create API** → **HTTP API** → Build

- Nombre: `pedidos360-api`
- Sin rutas ni integraciones → *Next*
- **Stages**: deja `$default` con **auto-deploy** activado

> Con una etapa con nombre la URL lleva prefijo (`/dev/api/orders`) y ese prefijo
> **también llega a Spring**, que responde 404 en todo.

📝 **Invoke URL** → **I**

### D.2 · Integración

**Develop → Integrations → Manage integrations → Create**

| Campo | Valor |
|---|---|
| Type | **HTTP URI** |
| Method | **ANY** |
| URL | `http://<F>:8080/api/{proxy}` |

> **`/api/{proxy}`, no `/{proxy}`.** En la ruta `/api/{proxy+}` la variable
> captura solo lo que va **después** de `/api/`. Con `/{proxy}`, una petición a
> `/api/catalog/products` llega al backend como `/catalog/products` y Spring
> responde 404 con `"path": "/catalog/products"`. Y no lo notas con `curl` sin
> token, porque el authorizer corta antes.

Crea una **segunda integración** para la ruta de salud, con la URL completa y
sin variables:

| Campo | Valor |
|---|---|
| Type | HTTP URI · Method | **GET** |
| URL | `http://<F>:8080/actuator/health` |

> Son dos integraciones obligatoriamente. Reutilizar la de `{proxy}` en una ruta
> sin `{proxy+}` da: *"The following path variables in the integration URI are
> not present in the route key: proxy"*.

### D.3 · JWT Authorizer

**Develop → Authorization → Create and attach an authorizer → JWT**

| Campo | Valor |
|---|---|
| Name | `entra-id-authorizer` |
| Identity source | `$request.header.Authorization` |
| Issuer URL | `https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362/v2.0` |
| Audience 1 | `api://pedidos360-api` |
| Audience 2 | `1e8d01fa-bd60-4403-ae34-4288007ed4dd` |

> El issuer termina en `/v2.0` **sin barra final**. AWS lo compara carácter a
> carácter contra el claim `iss`.

### D.4 · Rutas

**Develop → Routes → Create.** Seis rutas:

| Método | Ruta | Authorizer | Integración |
|---|---|---|---|
| `GET` `POST` `PUT` `PATCH` `DELETE` | `/api/{proxy+}` | `entra-id-authorizer` | la de `/api/{proxy}` |
| `GET` | `/actuator/health` | *(ninguno)* | la de `/actuator/health` |

A cada una: **Attach authorization** y **Attach integration**.

> **Métodos explícitos, NUNCA `ANY`.** El CORS automático de API Gateway solo
> responde el preflight **cuando ninguna ruta hace match con el `OPTIONS`**. Con
> `ANY` el preflight cae en la ruta protegida, llega sin token —el navegador
> nunca lo manda en un preflight— y recibe 401. Y crear una ruta `OPTIONS`
> explícita tampoco sirve: devuelve `403 Invalid CORS request`.
>
> Con métodos explícitos el `OPTIONS` queda huérfano y el gateway lo contesta él
> mismo con 204, sin invocar al authorizer. Los cinco métodos siguen protegidos.

> La ruta `/api/{proxy+}` ya cubre `/api/rabbitadmin/...`, así que el
> administrador de colas de la EP3 no necesita rutas aparte.

> **Nunca publiques `/dev/{proxy+}`.** Es el emisor de tokens de demo. Con el
> perfil `azure` ni existe, pero no lo expongas igual.

### D.5 · CORS

**Develop → CORS → Configure**

| Campo | Valor |
|---|---|
| Allow-Origin | `http://localhost:4200` |
| Allow-Headers | `authorization, content-type` |
| Allow-Methods | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| Max-Age | `600` |

Nunca `*` con credenciales.

### D.6 · Verificar

```bash
API="<I>"
curl -s -o /dev/null -w "health      → %{http_code}\n" "$API/actuator/health"   # 200
curl -s -o /dev/null -w "sin token   → %{http_code}\n" "$API/api/orders"        # 401
curl -s -o /dev/null -w "token malo  → %{http_code}\n" "$API/api/orders" -H "Authorization: Bearer x"  # 401
curl -s -o /dev/null -w "preflight   → %{http_code}\n" -X OPTIONS "$API/api/orders" \
  -H "Origin: http://localhost:4200" -H "Access-Control-Request-Method: GET"    # 204
```

**200 · 401 · 401 · 204.** Si el preflight da 401, tienes una ruta `ANY`.

---

# E · Frontend

En `frontend-pedidos360/src/environments/environment.ts`:

```ts
authMode: 'msal',
apiBaseUrl: '<I>',          // la URL del gateway, NO el BFF directo
```

El resto ya está puesto. `npm start` y entras en `http://localhost:4200`.

> El frontend corre en local a propósito: Entra ID solo acepta `http://` en
> redirect URIs de `localhost`. Servirlo desde `http://<ip-ec2>:4200` haría
> imposible registrar el `redirectUri`.

---

# Diagnóstico

| Síntoma | Causa | Arreglo |
|---|---|---|
| 401 siempre, con token bueno | issuer mal escrito | cópialo de la metadata OIDC |
| 403 del gateway | `aud` no coincide | añade la otra audiencia |
| 404 en todo | falta `$default` o falta `{proxy}` | D.1 / D.2 |
| 404 con `"path"` sin `/api` | integración en `/{proxy}` | ponla en `/api/{proxy}` |
| preflight 401 | ruta `ANY` | métodos explícitos |
| `path variables not present` | integración con `{proxy}` en ruta fija | integración aparte |
| 500/503 del gateway | no alcanza la EC2 | security group del 8080 |
| Contenedores `Exited (137)` | sin memoria | `t3.medium` |
| Kafka conecta y luego falla | advertised listener en `localhost` | el `sed` de C.2 |
| `docker` pide sudo | sesión antigua | sal y vuelve a entrar por SSH |

# Checklist

```
A  □ 3 security groups, reglas inbound, origen = sg en 5672 y 9092
B  □ 3 instancias con user data · .pedidos360-ready existe en las tres
   □ IP elástica en ec2-apps → F
   □ IP privadas anotadas → G, H
C  □ ec2-mq (healthy) · consola 15672 accesible
   □ ec2-kafka con advertised listener en IP privada · (healthy)
   □ ec2-apps: .env completo · 7 contenedores (healthy)
   □ curl http://<F>:8080/api/orders → 401
D  □ HTTP API, stage $default con auto-deploy → I
   □ 2 integraciones: /api/{proxy} y /actuator/health
   □ JWT Authorizer: issuer + 2 audiencias
   □ 5 rutas con método explícito + 1 de salud
   □ CORS con http://localhost:4200
   □ 200 · 401 · 401 · 204
E  □ environment.ts con apiBaseUrl = I
   □ login real y dashboard cargando
```
