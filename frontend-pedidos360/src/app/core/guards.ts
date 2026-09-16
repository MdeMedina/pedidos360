import { inject } from '@angular/core';
import { CanActivateFn, GuardResult, Router } from '@angular/router';

import { AuthService } from './auth.service';
import { Role } from './models';

/**
 * Guia 1.3.2: "MsalGuard protege rutas, obliga a login antes de entrar".
 *
 * Aqui se usa un guard propio porque debe funcionar en los dos modos y porque
 * ademas de "estas logueado?" hay que responder "tienes el rol?".
 *
 * Que hace y que NO hace un guard: evita que el usuario navegue a una pantalla
 * que no le corresponde. No es una medida de seguridad — el usuario puede quitar
 * el guard desde las devtools. La seguridad real esta en @PreAuthorize del backend
 * y en el JWT Authorizer de API Gateway. El guard es experiencia de usuario.
 */
export const authGuard: CanActivateFn = async (route, state): Promise<GuardResult> => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (!auth.isAuthenticated()) {
    // Puede que la sesion exista pero aun no se haya cargado /api/me.
    await auth.loadMe();
  }
  if (auth.isAuthenticated()) {
    return true;
  }

  /**
   * Se manda a /login SIEMPRE, tambien en modo MSAL, en vez de lanzar
   * loginRedirect() aqui mismo.
   *
   * Por que: si el login no puede completarse (falta el consentimiento de
   * administrador, el usuario cancela, la cuenta no existe en el tenant),
   * Entra ID devuelve el control a la aplicacion sin sesion. Si el guard
   * relanzara el login automaticamente, volveria a fallar y a relanzarlo:
   * un bucle infinito de redirecciones del que el usuario no puede salir
   * ni leyendo el error, porque la pagina se recarga sola.
   *
   * /login no tiene guard, asi que es un punto de parada seguro: ahi se
   * muestra el motivo del fallo y el login se reintenta con un clic, es
   * decir, con una accion deliberada del usuario.
   */
  return router.createUrlTree(['/login'], { queryParams: { redirect: state.url } });
};

/**
 * Guard por rol. Se usa en las rutas del Caso 0:
 *   /catalog -> Admin, Operator
 *   /reports -> Admin
 *   /audit   -> Admin, Auditor
 */
export function roleGuard(...allowed: Role[]): CanActivateFn {
  return async (route, state): Promise<GuardResult> => {
    const auth = inject(AuthService);
    const router = inject(Router);

    // authGuard se reutiliza en vez de duplicar la logica de sesion: primero
    // "estas autenticado", despues "tienes el rol".
    const authenticated = (await authGuard(route, state)) as GuardResult;
    if (authenticated !== true) {
      return authenticated;
    }
    if (auth.hasRole(...allowed)) {
      return true;
    }
    return router.createUrlTree(['/forbidden'], {
      queryParams: { required: allowed.join(', '), attempted: state.url },
    });
  };
}
