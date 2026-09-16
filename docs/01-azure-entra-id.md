# Configurar Microsoft Entra ID para Pedidos360

Procedimiento completo. Al final tendrás los cinco valores que necesitan el frontend, el
backend y el API Gateway.

## 0 · Antes de nada: ¿qué tipo de tenant tienes?

**Decídelo ahora.** Cambia el `issuer`, cambia si hace falta User Flow y cambia cómo creas
los usuarios. Descubrirlo a mitad de camino cuesta una hora.

### Cómo saberlo en 10 segundos

Entra a **Entra ID → External Identities → User flows**:

| Lo que ves | Tu tenant es | Qué hacer |
|---|---|---|
| **New user flow** deshabilitado y un aviso sobre *"self-service sign up for **guest users**"* | **Workforce** (Azure AD normal) | **Sáltate la sección 4.** Crea los usuarios a mano (sección 3.1) |
| **New user flow** habilitado, sin mención a invitados | **External ID (CIAM)** | Haz la sección 4 |

La palabra clave es **guest users**: ese aviso solo aparece en tenants workforce.

### Diferencias

| | Workforce (Azure AD) | External ID (CIAM) |
|---|---|---|
| Usuarios | los creas tú en el tenant | externos, se auto-registran |
| Authority | `https://login.microsoftonline.com/<TENANT_ID>` | `https://<tenant>.ciamlogin.com/<TENANT_ID>` |
| Issuer (`iss`) | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` | `https://<tenant>.ciamlogin.com/<TENANT_ID>/v2.0` |
| Necesita User Flow | **no** | sí |
| `knownAuthorities` en MSAL | vacío | `["<tenant>.ciamlogin.com"]` |

> **La ruta workforce es la que el proyecto trae por defecto.** `application.yml`,
> `.env.example` y `environment.ts` ya apuntan a `login.microsoftonline.com`: si tu tenant
> es workforce no tienes que cambiar ninguna de esas tres cosas.

> **El Caso 0 dice "Login corporativo con Azure AD", que es exactamente workforce.** La
> guía 1.3.7 usa CIAM porque su ejemplo es de usuarios externos, pero para esta evaluación
> workforce cumple el enunciado igual de bien y tiene menos piezas que puedan fallar.

---

## 1 · Registrar el BACKEND (`pedidos360-api`)

**Se registra primero.** El frontend va a pedir permisos *sobre* esta API, así que la API
tiene que existir antes.

1. Portal de Azure → **Microsoft Entra ID** → **Registros de aplicaciones** → **Nuevo registro**.
2. Nombre: `pedidos360-api`.
3. Tipos de cuenta: *Solo cuentas de este directorio organizativo* (o *External ID* si usas CIAM).
4. **Sin URI de redirección** — una API nunca recibe redirecciones de navegador; no es un
   cliente interactivo.
5. Registrar.

**Anota de la pantalla Información general:**

| Dato | Se usa en |
|---|---|
| **Application (client) ID** | `audience` del JWT Authorizer de AWS |
| **Directory (tenant) ID** | construir el `issuer` en todas partes |

### 1.1 Poner la API en tokens v2 (hazlo ANTES del Application ID URI)

**Administrar → Manifiesto** → busca `requestedAccessTokenVersion` (en el manifiesto nuevo
de Microsoft Graph está dentro del objeto `"api"`) y cámbialo de `null` a `2`:

```json
"requestedAccessTokenVersion": 2
```

**Guardar.**

**Por qué esto va primero y por qué importa tanto:** la versión del access token decide el
`iss` y el `aud` que tendrán TODOS los tokens de tu API, y por lo tanto lo que hay que
configurar en Spring y en el JWT Authorizer de AWS.

| | v1 (`null`, el valor por defecto) | v2 |
|---|---|---|
| `iss` | `https://sts.windows.net/<TENANT_ID>/` | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` |
| `aud` | el Application ID URI | el **client ID** (GUID) |

Todo este proyecto asume **v2**. Si dejas `null`, el backend rechazará tokens que el
portal muestra como perfectamente válidos, porque el `iss` no coincide.

Efecto secundario útil: con `requestedAccessTokenVersion: 2` el tenant suele relajar la
restricción sobre el formato del identifier URI (ver el paso siguiente).

### 1.2 Exponer la API y crear los scopes

**Administrar → Exponer una API**

1. **Application ID URI** → *Agregar*.

   > ⚠️ **Si aparece "Failed to add identifier URI … All newly added URIs must contain a
   > tenant verified domain, tenant ID, or app ID":** es una política del tenant, no un
   > error tuyo. `api://pedidos360-api` no contiene ninguno de los tres. Usa una de estas,
   > que siempre se aceptan:
   >
   > | Forma | Ejemplo | Cuándo |
   > |---|---|---|
   > | Con el app ID | `api://22222222-2222-2222-2222-222222222222` | la que Azure propone por defecto; a prueba de balas |
   > | Con el dominio verificado | `https://tuTenant.onmicrosoft.com/pedidos360-api` | más legible; es la forma que muestra la guía 1.3.7 |
   >
   > **Los scopes heredan ese prefijo.** Si eliges `api://<client-id>`, tus scopes quedan
   > `api://22222222-…/orders.read`, y ese es el valor exacto que va en `apiScopes` del
   > frontend.

   Este valor es la **audiencia** (`aud`) de los tokens v1. Con tokens v2 el `aud` es el
   client ID, así que conviene aceptar ambos en el backend (ver §5).
2. **Agregar un ámbito**, uno por cada uno de estos:

| Scope | Nombre visible | Consentimiento |
|---|---|---|
| `orders.read` | Leer pedidos | Administradores |
| `orders.write` | Crear y modificar pedidos | Administradores |
| `catalog.read` | Leer catálogo | Administradores |
| `catalog.write` | Administrar catálogo | Administradores |
| `report.read` | Ver reportería | Administradores |
| `audit.read` | Ver auditoría | Administradores |

Quedan como `<Application ID URI>/orders.read`, p. ej.
`api://22222222-2222-2222-2222-222222222222/orders.read`.

> **Por qué "consentimiento por administradores" y no por usuario:** con consentimiento de
> usuario, cada persona ve una pantalla de permisos la primera vez. En una app corporativa
> eso genera tickets de soporte y gente que le da a "cancelar".

### 1.3 Crear los App Roles

**Administrar → Roles de aplicación → Crear rol de aplicación.** Cuatro roles, todos con
*Tipos de miembros permitidos = **Usuarios/Grupos***:

| Valor | Nombre para mostrar | Descripción |
|---|---|---|
| `Admin` | Administrador | Gestiona catálogo, reportería y auditoría |
| `Operator` | Operador | Opera el ciclo de vida de los pedidos |
| `Customer` | Cliente | Crea y sigue sus propios pedidos |
| `Auditor` | Auditor | Acceso de solo lectura al timeline |

> ⚠️ El campo **Valor** es lo que viaja en el claim `roles` del token. Tiene que coincidir
> con lo que espera `JwtAuthoritiesConverter`. El converter normaliza a mayúsculas y
> traduce `Operador`→`OPERATOR` y `Cliente`→`CUSTOMER`, así que puedes usar el vocabulario
> en español si el tenant lo exige.

---

## 2 · Registrar el FRONTEND (`pedidos360-spa`)

1. **Nuevo registro** → nombre `pedidos360-spa`.
2. **URI de redirección** → plataforma **Single-page application (SPA)** →
   `http://localhost:4200/auth/callback`.
   - Agrega también la URL de producción cuando la tengas.
   - **Debe ser SPA y no Web.** Si eliges *Web*, Entra ID espera un `client_secret` al
     canjear el código y MSAL fallará con `AADSTS9002326` (cross-origin token redemption).
3. Registrar. Anota el **Application (client) ID** → es el `clientId` de `environment.ts`.

### 2.1 Pedir permiso sobre tu propia API

**Administrar → Permisos de API → Agregar un permiso → Mis API →
`pedidos360-api` → Permisos delegados** → marca los seis scopes → **Agregar permisos**.

Luego **Conceder consentimiento de administrador para `<tenant>`**.

> **"Permisos delegados" y no "Permisos de aplicación":** delegado = la app actúa **en
> nombre del usuario** que inició sesión. De aplicación = la app actúa **por sí misma**,
> sin usuario (flujo *client credentials*), y eso requiere un secreto, que un SPA no puede
> guardar.

### 2.2 Verificar la configuración del token

**Administrar → Autenticación** → confirma que:
- La plataforma sea **SPA**.
- **NO** estén marcadas las casillas de *Access tokens* ni *ID tokens* del flujo implícito.
  Con Authorization Code + PKCE no se usan, y dejarlas activas habilita un flujo obsoleto.

---

## 3 · Crear los usuarios y asignarles roles

### 3.1 Crear los cuatro usuarios de prueba (tenant workforce)

**Entra ID → Usuarios → Nuevo usuario → Crear usuario nuevo.** Uno por rol, para poder
demostrar los cuatro comportamientos distintos en la defensa:

| Nombre principal de usuario | Nombre para mostrar | Rol que le darás en 3.2 |
|---|---|---|
| `admin@<tenant>.onmicrosoft.com` | Admin Pedidos360 | `Admin` |
| `operador@<tenant>.onmicrosoft.com` | Operador Pedidos360 | `Operator` |
| `cliente@<tenant>.onmicrosoft.com` | Cliente Pedidos360 | `Customer` |
| `auditor@<tenant>.onmicrosoft.com` | Auditor Pedidos360 | `Auditor` |

**Anota la contraseña autogenerada de cada uno.** Y **haz el primer login de los cuatro
ahora mismo** en una ventana de incógnito: Azure obliga a cambiar la contraseña la primera
vez, y ese diálogo apareciendo en medio de la demostración es un mal momento para
descubrirlo.

> Si solo puedes usar tu propia cuenta, asígnate los cuatro roles. Funciona, pero pierdes
> la parte más vistosa de la defensa: mostrar que un Cliente **no puede** entrar a
> reportería.

### 3.2 Asignar los App Roles

**Entra ID → Aplicaciones empresariales → `pedidos360-api` → Usuarios y grupos →
Agregar usuario/grupo** → elige el usuario, elige el rol, asignar.

> **Aplicaciones empresariales, no Registros de aplicaciones.** El *registro* es la
> definición de la app; la *aplicación empresarial* (service principal) es su instancia en
> tu tenant, y ahí es donde viven las asignaciones. Buscar la asignación en el lugar
> equivocado es la causa número uno de *"mi token no trae el claim `roles`"*.

Repite para un usuario por rol, así puedes demostrar los cuatro comportamientos.

---

## 4 · (Solo External ID / CIAM) Crear el User Flow

> ### ⏭️ Si tu tenant es **workforce**, salta esta sección completa.
>
> No necesitas User Flow: los usuarios ya los creaste en la sección 3.1 y tu issuer es
> `https://login.microsoftonline.com/<TENANT_ID>/v2.0`. Sigue en la sección 5.
>
> **Si abriste User flows y viste "New user flow" en gris** junto a un aviso de
> *"Self-service sign up for guest users has not been enabled"*, ese es precisamente el
> caso: tienes un tenant workforce y el botón está deshabilitado a propósito.
>
> Habilitarlo exigiría **External collaboration settings → "Enable guest self-service sign
> up via user flows" = Yes** *más* tener registrado el resource provider
> `Microsoft.AzureActiveDirectory` en una suscripción de Azure. Con cuentas de estudiante
> normalmente no hay suscripción, así que te quedarías bloqueado ahí — y no aporta nada a
> la evaluación.

Guía 1.3.7. Necesario para que usuarios externos se registren e inicien sesión.

1. Entra ID → **External Identities** → **User flows** → **New user flow**.
2. Nombre: `pedidos360-signupsignin`.
3. Identity providers: **Email with password** (y los sociales que quieras).
4. User attributes: *Display Name*, *Email*, *Given Name*, *Surname*.
5. Create.
6. Abre el flow → **Applications** → **Add application** → `pedidos360-spa`.

**Anota el issuer resultante**, que es lo que irá en el Authorizer de AWS:

```
https://<tenant>.ciamlogin.com/<TENANT_ID>/v2.0
```

Verifícalo abriendo en el navegador:

```
https://<tenant>.ciamlogin.com/<TENANT_ID>/v2.0/.well-known/openid-configuration
```

El campo `issuer` de ese JSON es el valor exacto que debes usar. No lo escribas de memoria:
un `/v2.0` de más o de menos produce un 401 que cuesta horas encontrar.

---

## 5 · Los cinco valores y dónde va cada uno

> **Los valores reales de este proyecto ya están puestos** en
> `frontend-pedidos360/src/environments/environment.ts`, en los seis
> `application.yml` y en `infra/apps/.env`. Esta tabla queda como referencia de
> dónde va cada cosa.

| Valor | El de este proyecto | Frontend | Backend | AWS |
|---|---|---|---|---|
| Tenant ID | `41f0982b-bfd4-40ea-8b24-cd683cd0f362` | dentro de `authority` | dentro de `issuer-uri` | dentro de `issuer` |
| Client ID del SPA | `d19179d8-5de0-491a-a16f-20b8dc658709` | `msal.clientId` | — | — |
| Client ID de la API | `1e8d01fa-bd60-4403-ae34-4288007ed4dd` | — | `audiences` | `audience` |
| Application ID URI | `api://pedidos360-api` | prefijo de los scopes | `pedidos360.security.audiences` | `audience` |
| Issuer | `https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362/v2.0` | — | `issuer-uri` | `issuer` |

### Frontend — `src/environments/environment.ts`

```ts
export const environment = {
  production: false,
  authMode: 'msal',                                   // <- cambia 'dev' por 'msal'
  apiBaseUrl: 'http://localhost:8080',                // en prod: la URL del API Gateway
  msal: {
    clientId: 'd19179d8-5de0-491a-a16f-20b8dc658709',
    authority: 'https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362/',
    knownAuthorities: [],                             // ['tuTenant.ciamlogin.com'] si usas CIAM
    redirectUri: 'http://localhost:4200/auth/callback',
    postLogoutRedirectUri: 'http://localhost:4200/login',
    // El prefijo es el Application ID URI que el tenant aceptó.
    apiScopes: [
      'api://pedidos360-api/orders.read',
      'api://pedidos360-api/orders.write',
      'api://pedidos360-api/catalog.read',
      'api://pedidos360-api/catalog.write',
      'api://pedidos360-api/report.read',
      'api://pedidos360-api/audit.read',
    ],
  },
};
```

### Backend — variables de entorno (`infra/apps/.env`)

```bash
SPRING_PROFILES_ACTIVE=azure
PEDIDOS360_ISSUER_URI=https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362/v2.0
PEDIDOS360_AUDIENCES=api://pedidos360-api,1e8d01fa-bd60-4403-ae34-4288007ed4dd
```

> Se aceptan **dos** audiencias porque el valor de `aud` depende de la versión del token:
> con **v2** es el *client id* (GUID) y con **v1** es el *Application ID URI*. Listar ambas
> hace que la configuración funcione en los dos casos y evita un 401 intermitente muy
> difícil de diagnosticar.

---

## 6 · Verificación

```bash
# 1. El issuer responde y su metadata es coherente
curl -s https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362/v2.0/.well-known/openid-configuration | jq .issuer

# 2. Levanta el backend en perfil azure y comprueba que arranca
SPRING_PROFILES_ACTIVE=azure PEDIDOS360_ISSUER_URI=... java -jar ms-pedidos360-bff/target/*.jar
```

Después, desde el frontend en `authMode: 'msal'`, inicia sesión y revisa `/api/me`. Debe
devolver tu usuario, tus roles, la audiencia y el issuer. Si `roles` viene vacío, vuelve al
paso 3: falta la asignación en *Aplicaciones empresariales*.

### Diagnóstico rápido

| Error | Causa | Solución |
|---|---|---|
| `AADSTS50011` redirect URI mismatch | la URI no está registrada o no coincide exactamente | revisa mayúsculas, barra final y puerto |
| `AADSTS9002326` cross-origin | la plataforma quedó como *Web* | cámbiala a *Single-page application* |
| `AADSTS65001` consent required | falta consentimiento | *Grant admin consent* en Permisos de API |
| Token sin claim `roles` | el usuario no tiene rol asignado | Aplicaciones empresariales → Usuarios y grupos |
| `New user flow` deshabilitado + aviso de *guest users* | tenant **workforce**, no CIAM | no necesitas User Flow: salta la sección 4 |
| Backend 401 con token válido | `issuer-uri` incorrecto | compáralo con el `issuer` de la metadata |
| Backend 401 `invalid_token: audiencia` | `aud` no está en la lista | agrega el otro valor a `PEDIDOS360_AUDIENCES` |
| `Failed to add identifier URI` al guardar el App ID URI | política del tenant: el URI debe contener dominio verificado, tenant ID o app ID | usa `api://<client-id>` o `https://<tenant>.onmicrosoft.com/<nombre>` (§1.2) |
| Backend 401 y el `iss` del token es `https://sts.windows.net/...` | la API emite tokens **v1** | pon `requestedAccessTokenVersion: 2` en el manifiesto (§1.1) |

**Herramienta clave:** pega cualquier access token en <https://jwt.ms> y revisa
`iss`, `aud`, `roles` y `scp`. Casi todo se resuelve ahí.
