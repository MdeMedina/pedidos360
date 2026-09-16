import {
  BrowserCacheLocation,
  IPublicClientApplication,
  InteractionType,
  LogLevel,
  PublicClientApplication,
} from '@azure/msal-browser';
import {
  MsalGuardConfiguration,
  MsalInterceptorConfiguration,
} from '@azure/msal-angular';

import { environment } from '../../environments/environment';

/**
 * Guia 1.3.2 — "Crear la instancia de MSAL (factory function)".
 *
 * Las cuatro cosas que MSAL necesita saber:
 *   1. Quien soy       -> clientId
 *   2. En que tenant   -> authority
 *   3. Donde volver    -> redirectUri
 *   4. Que API llamar  -> scopes (ver MSALInterceptorConfigFactory)
 */
export function MSALInstanceFactory(): IPublicClientApplication {
  return new PublicClientApplication({
    auth: {
      clientId: environment.msal.clientId,
      authority: environment.msal.authority,
      knownAuthorities: environment.msal.knownAuthorities,
      redirectUri: environment.msal.redirectUri,
      postLogoutRedirectUri: environment.msal.postLogoutRedirectUri,
    },
    cache: {
      /**
       * sessionStorage y no localStorage: el token muere al cerrar la pestana.
       * localStorage lo dejaria disponible para cualquier script que se cuele
       * en la pagina, indefinidamente y en todas las pestanas.
       */
      cacheLocation: BrowserCacheLocation.SessionStorage,
    },
    system: {
      loggerOptions: {
        loggerCallback: (level, message) => {
          if (level === LogLevel.Error) {
            console.error('[MSAL]', message);
          }
        },
        logLevel: environment.production ? LogLevel.Error : LogLevel.Warning,
        piiLoggingEnabled: false,
      },
    },
  });
}

/**
 * Guia 1.3.2 — "Configurar MSAL Interceptor para las peticiones HTTP".
 *
 * El mapa dice: "a ESTA url, adjunta un token con ESTOS scopes". MSAL intenta
 * primero adquisicion silenciosa (acquireTokenSilent) desde su cache y solo
 * redirige al usuario si no puede renovar. Es el patron que exige Microsoft:
 * silencioso primero, interactivo como fallback.
 *
 * Importante: solo se listan NUESTRAS urls. Adjuntar el token a un dominio ajeno
 * seria filtrar la credencial del usuario a un tercero.
 */
export function MSALInterceptorConfigFactory(): MsalInterceptorConfiguration {
  const protectedResourceMap = new Map<string, Array<string> | null>();

  /**
   * El comodin final NO es opcional.
   *
   * MSAL compara el pathname de la clave con un patron ANCLADO (matchPatternStrict,
   * el modo por defecto desde msal-browser v5): la clave tiene que cubrir el pathname
   * completo. Con `${environment.apiBaseUrl}/api` la clave describe exactamente la ruta
   * "/api", asi que NO coincide con "/api/orders" ni con "/api/catalog/products", y el
   * interceptor deja pasar esas peticiones sin adjuntar token.
   *
   * El sintoma es enganoso: el login funciona, el token existe y es valido, pero el
   * backend responde 401 porque nunca le llega la cabecera Authorization. En el log del
   * servidor se ve "Set SecurityContextHolder to anonymous" en vez de un error de token.
   */
  protectedResourceMap.set(`${environment.apiBaseUrl}/api/*`, environment.msal.apiScopes);
  return {
    interactionType: InteractionType.Redirect,
    protectedResourceMap,
  };
}

/**
 * Guia 1.3.2 — "MsalGuard protege rutas, obliga a login antes de entrar".
 * authRequest define los scopes que se piden en el login inicial, de modo que el
 * primer access token ya sirva para llamar a la API sin un segundo consentimiento.
 */
export function MSALGuardConfigFactory(): MsalGuardConfiguration {
  return {
    interactionType: InteractionType.Redirect,
    authRequest: {
      scopes: environment.msal.apiScopes,
    },
    loginFailedRoute: '/login',
  };
}

/** Marcador para saber si MSAL esta activo sin duplicar la condicion en cada archivo. */
export const msalEnabled = environment.authMode === 'msal';
