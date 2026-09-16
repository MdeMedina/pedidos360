import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap } from 'rxjs';

import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';
import { msalEnabled } from './msal.config';

/**
 * Unico punto donde se adjunta el access token a las peticiones.
 *
 * Por que un interceptor propio en lugar de MsalInterceptor, que es el que
 * enseña la guia 1.3.2:
 *
 * MsalInterceptor depende de MsalBroadcastService y solo pide el token cuando
 * inProgress$ vale InteractionStatus.None. Ese estado se alimenta de eventos
 * que hay que estar escuchando en el momento exacto en que ocurren; si el
 * servicio se construye tarde —o el ciclo de vida arranca desde un app
 * initializer, como aqui— el estado se queda en Startup y el interceptor deja
 * pasar TODAS las peticiones sin cabecera Authorization. El sintoma es
 * desconcertante: el login funciona, el token existe y es valido, pero el
 * backend responde 401 porque nunca le llega nada que validar.
 *
 * Este interceptor no depende de ningun estado global: pide el token con
 * acquireTokenSilent —adquisicion silenciosa primero, que es el patron que
 * recomienda Microsoft— y lo adjunta. Es determinista y se depura leyendo
 * quince lineas.
 *
 * MSALInterceptorConfigFactory se conserva en msal.config.ts porque documenta
 * que scopes corresponden a que URL, que es la parte conceptual de la guia.
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

  if (!msalEnabled) {
    const token = auth.devToken();
    return token
      ? next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }))
      : next(req);
  }

  return from(auth.acquireApiToken()).pipe(
    switchMap((token) =>
      next(token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req),
    ),
  );
};
