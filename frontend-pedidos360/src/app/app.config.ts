import { HTTP_INTERCEPTORS, provideHttpClient, withInterceptors, withInterceptorsFromDi } from '@angular/common/http';
import {
  ApplicationConfig,
  Provider,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import {
  MSAL_GUARD_CONFIG,
  MSAL_INSTANCE,
  MSAL_INTERCEPTOR_CONFIG,
  MsalBroadcastService,
  MsalGuard,
  MsalInterceptor,
  MsalService,
} from '@azure/msal-angular';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { AuthService } from './core/auth.service';
import {
  MSALGuardConfigFactory,
  MSALInstanceFactory,
  MSALInterceptorConfigFactory,
  msalEnabled,
} from './core/msal.config';

/**
 * Guia 1.3.2 — "Registrar MSAL en el bootstrap de Angular".
 *
 * Se registran solo si authMode = 'msal'. En modo demo ni siquiera se crean, para
 * que una configuracion de Entra ID incompleta no impida levantar la aplicacion.
 */
const msalProviders: Provider[] = msalEnabled
  ? [
      { provide: MSAL_INSTANCE, useFactory: MSALInstanceFactory },
      { provide: MSAL_GUARD_CONFIG, useFactory: MSALGuardConfigFactory },
      { provide: MSAL_INTERCEPTOR_CONFIG, useFactory: MSALInterceptorConfigFactory },
      // Guia 1.3.2: MsalInterceptor adjunta el token automaticamente a las URLs
      // del protectedResourceMap y lo renueva en silencio cuando expira. Se
      // registra por DI porque es una clase, no una funcion; de ahi el
      // withInterceptorsFromDi() de mas abajo.
      { provide: HTTP_INTERCEPTORS, useClass: MsalInterceptor, multi: true },
      MsalService,
      MsalGuard,
      MsalBroadcastService,
    ]
  : [];

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor]), withInterceptorsFromDi()),
    ...msalProviders,
    // Resuelve la sesion ANTES de mostrar la primera pantalla: sin esto se veria
    // un parpadeo de "no autenticado" en cada F5.
    provideAppInitializer(() => inject(AuthService).initialize()),
  ],
};
