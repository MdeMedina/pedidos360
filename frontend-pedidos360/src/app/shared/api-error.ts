import { HttpErrorResponse } from '@angular/common/http';

/**
 * Traduce el error HTTP a un mensaje que el usuario pueda entender.
 *
 * El 401 y el 403 se distinguen a proposito: 401 significa "tu token expiró o no
 * existe" (hay que volver a autenticarse), mientras que 403 significa "tu token es
 * válido pero tu rol no alcanza" (volver a loguearse no cambia nada).
 */
export function describeApiError(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const backendMessage = err.error?.message as string | undefined;
    switch (err.status) {
      case 0:
        return 'No hay conexión con el backend. ¿Está levantado ms-pedidos360-bff en el puerto 8080?';
      case 401:
        return 'Tu sesión expiró o el token no es válido. Vuelve a iniciar sesión.';
      case 403:
        return backendMessage ?? 'Tu rol no tiene permiso para esta operación.';
      case 404:
        return backendMessage ?? 'El recurso no existe o no es visible para tu usuario.';
      case 409:
        return backendMessage ?? 'La operación choca con una regla de negocio.';
      case 502:
      case 503:
        return backendMessage ?? 'Un microservicio no está disponible en este momento.';
      default:
        return backendMessage ?? `Error ${err.status} al llamar al backend.`;
    }
  }
  return 'Ocurrió un error inesperado.';
}
