# AWS API Gateway como API Manager de Pedidos360

Continuación de las guías **1.1.2**, **1.3.6** y **1.3.7**. Aquí se aplica el mismo
procedimiento pero apuntando al BFF real en vez de a `mindicador.cl`.

**Requisito previo:** tener los valores de `docs/01-azure-entra-id.md` (tenant id, issuer,
audience) y el BFF accesible en una URL pública (EC2, ngrok o similar).

---

## 1 · Por qué HTTP API y no REST API

| | HTTP API | REST API |
|---|---|---|
| JWT Authorizer nativo | ✅ | ❌ (requiere Lambda Authorizer) |
| Costo | ~70 % menor | mayor |
| Latencia | menor | mayor |
| WAF, caché, planes de uso | ❌ | ✅ |

El Caso 0 exige literalmente *"AWS API Gateway HTTP API con JWT authorizer (Azure AD)"*.
La HTTP API es la opción correcta y además la más simple: el authorizer se configura
rellenando dos campos, sin escribir código.

---

## 2 · Crear la API

1. AWS Console → **API Gateway** → **Crear API** → **HTTP API** → *Compilar*.
2. Nombre: `pedidos360-api`. Sin integraciones todavía.
3. **Configurar rutas** → *Siguiente* (se agregan después).
4. **Definir etapas** → deja `$default` con **Implementación automática** activada.
   - *Por qué `$default`:* una etapa con nombre añade un prefijo a la URL
     (`/dev/api/orders`) que **también llega al backend** y rompe el enrutamiento de Spring.
     Con `$default` la URL queda limpia: `https://<id>.execute-api.<region>.amazonaws.com/api/orders`.
5. Crear.

Anota la **URL de invocación**. Esa es la que va en `apiBaseUrl` del frontend en producción.

---

## 3 · Crear la integración hacia el BFF

**Desarrollar → Integraciones → Administrar integraciones → Crear**

| Campo | Valor |
|---|---|
| Tipo de integración | **URI de HTTP** |
| Método | **ANY** |
| URL del punto de conexión | `http://<IP-o-DNS-de-ec2-apps>:8080/{proxy}` |

> **`{proxy}` es la pieza clave.** Sin él, todas las rutas caerían en la misma URL fija del
> backend. Con `{proxy}` el gateway reenvía el resto del path tal cual, y una sola
> integración sirve para las decenas de endpoints del BFF.

**Una sola integración, hacia el BFF.** No se crean cinco integraciones (una por
microservicio) porque el BFF ya es el punto único de entrada: menos configuración, menos
superficie expuesta y un solo puerto que abrir en el Security Group.

---

## 4 · Crear el JWT Authorizer (guía 1.3.7)

**Desarrollar → Autorización → Crear y adjuntar un autorizador → JWT**

| Campo | Valor | De dónde sale |
|---|---|---|
| Nombre | `entra-id-authorizer` | — |
| Origen de identidad | `$request.header.Authorization` | la cabecera que manda MSAL |
| Emisor (Issuer URL) | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` | Entra ID |
| Audiencia | el **Application ID URI** (valor C) **y** el `<CLIENT_ID_API>` (valor B) | Entra ID |

> **Por qué dos audiencias y no una:** el `aud` del token depende de la versión del access
> token de la API. Con **v2** (`requestedAccessTokenVersion: 2`) el `aud` es el *client id*
> (GUID); con **v1** es el *Application ID URI*. Listar ambas hace que el authorizer
> funcione en los dos casos.

Para **External ID / CIAM** el emisor es:

```
https://<tenant>.ciamlogin.com/<TENANT_ID>/v2.0
```

> **Copia el issuer de la metadata, no de la memoria.** Ábrelo en el navegador:
> `<issuer>/.well-known/openid-configuration` y usa el valor exacto del campo `issuer`.
> El `/v2.0` final y la barra son significativos. Un carácter de diferencia = 401.

**Qué hace el authorizer, exactamente:**

1. Lee la cabecera `Authorization`.
2. Descarga el JWKS del issuer (y lo cachea).
3. Verifica la **firma** del JWT.
4. Verifica `iss`, `exp`, `nbf`.
5. Verifica que `aud` esté en la lista de audiencias.
6. Si algo falla → **401/403 sin llegar nunca al backend**.

Ese último punto es el valor real: el tráfico no autenticado **ni siquiera toca tu EC2**.
La validación se paga en la infraestructura de AWS, no en tu instancia.

**Scopes (opcional pero recomendable):** al adjuntar el authorizer a una ruta puedes exigir
scopes concretos, p. ej. `<Application ID URI>/orders.write` en las rutas de escritura.
Es una segunda malla además de `@PreAuthorize`.

---

## 5 · Crear las rutas y adjuntarles el authorizer

**Desarrollar → Rutas → Crear**

| Método | Ruta | Authorizer | Notas |
|---|---|---|---|
| `ANY` | `/api/{proxy+}` | `entra-id-authorizer` | todo el tráfico de negocio |
| `GET` | `/health` | *(ninguno)* | sonda pública, sin datos sensibles |

Para cada ruta: selecciónala → **Adjuntar autorización** → elige `entra-id-authorizer`.
Luego → **Adjuntar integración** → la integración del paso 3.

> **`/api/{proxy+}` con `ANY` y no una ruta por endpoint.** `{proxy+}` es un *greedy path
> variable*: captura todo lo que siga. Así `/api/orders`, `/api/orders/123/status` y
> `/api/catalog/products` entran por la misma regla. Declarar cada endpoint a mano
> significaría volver a la consola de AWS cada vez que el backend agrega un endpoint.

> ⚠️ **Nunca publiques `/dev/{proxy+}`.** Ese prefijo es el emisor de tokens de demo y
> jamás debe existir fuera de tu máquina. Con `SPRING_PROFILES_ACTIVE=azure` el controlador
> ni siquiera se registra, pero no lo expongas en el gateway igualmente.

---

## 6 · Versionado `/v1` y `/v2` (guía 1.3.6)

El ejercicio de la guía es conceptual (dos APIs públicas distintas). Aplicado a
Pedidos360, el patrón se ve así:

| Método | Ruta | Integración | Significado |
|---|---|---|---|
| `ANY` | `/v1/{proxy+}` | BFF v1 (`:8080`) | contrato actual, estable |
| `ANY` | `/v2/{proxy+}` | BFF v2 (`:8090`) | contrato nuevo, con breaking changes |

**Cuándo se crea una v2:** solo ante un *breaking change*. Renombrar o quitar un campo,
cambiar su tipo, volver obligatorio algo opcional, cambiar el significado de un valor.
Agregar un campo opcional **no** rompe a nadie y va en la misma versión.

**Deprecación ordenada (RFC 8594):** cuando `v1` va a morir, el gateway añade cabeceras a
sus respuestas:

```
Deprecation: true
Sunset: Sat, 31 Jan 2026 23:59:59 GMT
Link: <https://docs.pedidos360.cl/migracion-v2>; rel="deprecation"
```

Así el consumidor se entera **por el protocolo**, de forma automática y verificable, en vez
de por un correo que nadie leyó.

**Lo importante del ejercicio:** todo esto se resuelve **en el gateway**, sin desplegar
frontend ni modificar los microservicios existentes. El gateway es el punto donde se decide
qué implementación atiende qué contrato.

---

## 7 · CORS

**Desarrollar → CORS → Configurar**

| Campo | Valor |
|---|---|
| `Access-Control-Allow-Origin` | `http://localhost:4200` y tu dominio de producción |
| `Access-Control-Allow-Headers` | `authorization, content-type` |
| `Access-Control-Allow-Methods` | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| `Access-Control-Max-Age` | `600` |

> **Nunca `*` cuando hay credenciales.** El navegador rechaza la combinación de
> `Allow-Origin: *` con `Allow-Credentials: true`, y aunque no lo hiciera, sería abrir tu
> API a cualquier página.

> **El preflight `OPTIONS` no lleva token.** El navegador lo envía sin cabecera
> `Authorization`. Si el authorizer estuviera aplicado al `OPTIONS`, respondería 401 y el
> navegador cancelaría la petición real. API Gateway maneja el preflight antes del
> authorizer, así que basta con configurar CORS aquí — pero es exactamente el mismo motivo
> por el que en Spring hay un `requestMatchers(OPTIONS, "/**").permitAll()`.

---

## 8 · Desplegar y probar

Con `$default` y auto-deploy, los cambios se publican solos. Si no, **Implementar → Crear**.

```bash
API="https://<id>.execute-api.us-east-1.amazonaws.com"

# 1. Sin token -> 401, y el backend nunca se entera
curl -i "$API/api/orders"

# 2. Con token válido de Entra ID -> 200
TOKEN="eyJ0eXAiOiJKV1Qi..."
curl -i "$API/api/orders" -H "Authorization: Bearer $TOKEN"

# 3. Con un token manipulado -> 401 (firma inválida)
curl -i "$API/api/orders" -H "Authorization: Bearer ${TOKEN}x"
```

Los tres casos son excelente material de defensa: demuestran las tres respuestas del
authorizer sin necesidad de mirar código.

---

## 9 · Diagnóstico

| Síntoma | Causa | Solución |
|---|---|---|
| `401` siempre, incluso con token bueno | issuer mal escrito | copia el `issuer` de la metadata OIDC |
| `403 Forbidden` del gateway | `aud` no coincide | agrega el otro valor a la lista de audiencias |
| `401` y el `iss` del token es `https://sts.windows.net/...` | la API emite tokens v1 | pon `requestedAccessTokenVersion: 2` en el manifiesto de `pedidos360-api` |
| `500` o `503` | el gateway no alcanza tu EC2 | revisa el Security Group: puerto 8080 abierto hacia el gateway |
| `404` en todo | falta `{proxy}` en la URL de integración | añádelo |
| CORS en el navegador pero curl funciona | falta configurar CORS en el gateway | sección 7 |
| Funciona en curl, falla desde Angular | `apiBaseUrl` apunta al BFF y no al gateway | corrige `environment.prod.ts` |

**Activa los logs de acceso:** *Supervisar → Registro* → grupo de CloudWatch. Sin logs, un
401 del authorizer es una caja negra; con logs ves el `error` exacto que devolvió.

---

## 10 · Arquitectura de red en AWS

```
Internet
   │
   ▼
┌──────────────────────┐
│  API Gateway (HTTP)  │  JWT Authorizer  ← única superficie pública
└──────────┬───────────┘
           │ puerto 8080, solo desde el gateway
           ▼
┌─────────────────────────────────────┐
│ ec2-apps                            │
│  bff:8080  ← único puerto expuesto  │
│  orders:8081  catalog:8082          │  ← solo dentro de la VPC
│  notify:8083  report:8084           │
│  audit:8085                         │
└────────┬───────────────┬────────────┘
         │ 5672          │ 9092
         ▼               ▼
   ┌───────────┐   ┌──────────────┐
   │  ec2-mq   │   │  ec2-kafka   │
   │ RabbitMQ  │   │  ZK + Kafka  │
   └───────────┘   └──────────────┘
```

**Regla que hay que saber justificar:** cada puerto abierto es una puerta más que defender.
De todo este diagrama, **solo el 8080 del BFF** debe ser alcanzable desde fuera de la VPC, y
solo desde el API Gateway. Ni 5672, ni 9092, ni 2181, ni los puertos 8081-8085: ninguno de
esos protocolos lleva autenticación fuerte por defecto, y exponerlos equivale a regalar el
bus de eventos y la base de datos. El detalle de Security Groups está en `infra/README.md`.
