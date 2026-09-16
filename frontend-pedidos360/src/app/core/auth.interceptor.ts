import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';
import { msalEnabled } from './msal.config';

/**
 * Adjunta el token del MODO DEMO. En modo MSAL no hace nada.
 *
 * Guia 1.3.2: de adjuntar el token en el flujo real se encarga MsalInterceptor,
 * que ademas renueva silenciosamente el access token cuando expira. Duplicar la
 * cabecera aqui romperia esa renovacion, asi que este interceptor se aparta.
 *
 * Solo existe para el perfil de demostracion, donde el token lo emite el BFF en
 * /dev/token y MSAL no interviene.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  // Solo se adjunta token a NUESTRA API. Mandarlo a un dominio de terceros
  // seria filtrar la credencial del usuario.
  const isOwnApi = req.url.startsWith(environment.apiBaseUrl);
  const isTokenEndpoint = req.url.includes('/dev/token');
  if (!isOwnApi || isTokenEndpoint) {
    return next(req);
  }

  // En modo MSAL se aparta: el token lo pone MsalInterceptor.
  if (msalEnabled) {
    return next(req);
  }

  const token = auth.devToken();
  return token
    ? next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))
    : next(req);
};
