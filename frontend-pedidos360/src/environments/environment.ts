/**
 * Configuracion de DESARROLLO.
 *
 * Tenant ejemploDuoc (workforce) · registros pedidos360-spa y pedidos360-api.
 * Todos los valores de Entra ID ya son los reales: lo unico que hay que cambiar
 * para pasar del modo demo al flujo real de MSAL es la linea authMode.
 *
 *   'dev'  -> el token lo emite el BFF en /dev/token. Permite trabajar y demostrar
 *             la aplicacion sin depender del tenant de Azure. NO usa MSAL.
 *   'msal' -> flujo real: MSAL redirige a Microsoft Entra ID, obtiene el access
 *             token y lo adjunta a cada peticion (guia 1.3.2).
 */
export const environment = {
  production: false,

  // ↓↓↓ CAMBIA SOLO ESTA LINEA a 'msal' para probar el login real contra Entra ID ↓↓↓
  authMode: 'msal' as 'dev' | 'msal',

  /**
   * Unico backend que conoce el frontend.
   * En local: el BFF. Publicado: la URL de invocacion de AWS API Gateway
   * (apuntar al BFF directo se saltaria el JWT Authorizer).
   */
  // API Gateway, NO el BFF directo: apuntar al BFF se saltaria el JWT Authorizer.
  apiBaseUrl: 'https://unyofrbc70.execute-api.us-east-1.amazonaws.com',

  msal: {
    /** Application (client) ID del registro SPA "pedidos360-spa". */
    clientId: 'd19179d8-5de0-491a-a16f-20b8dc658709',

    /**
     * Tenant ejemploDuoc (workforce). Es el "en que tenant estoy" de la guia 1.3.2.
     * Con External ID / CIAM seria https://<tenant>.ciamlogin.com/<TENANT_ID>
     */
    authority: 'https://login.microsoftonline.com/41f0982b-bfd4-40ea-8b24-cd683cd0f362',

    /**
     * Solo hace falta cuando la authority NO es login.microsoftonline.com
     * (External ID / B2C). En workforce va vacio.
     */
    knownAuthorities: [] as string[],

    /** Registrado en pedidos360-spa como plataforma "Single-page application". */
    redirectUri: 'http://localhost:4200/auth/callback',
    postLogoutRedirectUri: 'http://localhost:4200/login',

    /**
     * Scopes de NUESTRA API (no de Microsoft Graph), con el prefijo del
     * Application ID URI de pedidos360-api.
     */
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
