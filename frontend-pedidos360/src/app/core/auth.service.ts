import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MsalBroadcastService, MsalService } from '@azure/msal-angular';
import { AccountInfo } from '@azure/msal-browser';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../environments/environment';
import { Me, Role } from './models';
import { msalEnabled } from './msal.config';

const DEV_TOKEN_KEY = 'pedidos360.dev.token';

/**
 * Fachada unica de autenticacion para toda la aplicacion.
 *
 * Los componentes nunca hablan con MSAL directamente: preguntan aqui. Eso permite
 * tener dos modos (MSAL real contra Entra ID, o token local para la demo) sin
 * duplicar logica en cada pantalla, y deja un solo lugar donde revisar la seguridad.
 *
 * Regla que se respeta en todo el archivo: los roles que usa la UI se leen de
 * /api/me, es decir, los calcula el backend desde el token YA VALIDADO. El frontend
 * jamas decide permisos por su cuenta; solo decide que dibuja.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  /** optional: en modo dev los providers de MSAL ni siquiera estan registrados. */
  private readonly msal = inject(MsalService, { optional: true });

  /**
   * NO se usa directamente, y aun asi es imprescindible inyectarlo aqui.
   *
   * MsalBroadcastService se suscribe a los eventos de MSAL EN SU CONSTRUCTOR, y
   * Angular solo lo construye cuando alguien lo inyecta. Si el primero en hacerlo
   * es MsalInterceptor —o sea, en la primera peticion HTTP—, para entonces el
   * evento HANDLE_REDIRECT_END del login ya ocurrio y se perdio: inProgress$ se
   * queda en InteractionStatus.Startup para siempre y el interceptor, que espera
   * a que llegue a None antes de pedir el token, no adjunta nunca la cabecera
   * Authorization.
   *
   * Inyectandolo aqui se construye junto con AuthService, es decir ANTES de que
   * initialize() dispare el ciclo de vida, y llega a tiempo de escuchar.
   */
  private readonly msalBroadcast = inject(MsalBroadcastService, { optional: true });

  private readonly _me = signal<Me | null>(null);
  private readonly _ready = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly me = this._me.asReadonly();
  readonly ready = this._ready.asReadonly();
  readonly error = this._error.asReadonly();

  readonly isAuthenticated = computed(() => this._me() !== null);
  readonly roles = computed<Role[]>(() => this._me()?.roles ?? []);
  readonly displayName = computed(
    () => this._me()?.displayName || this._me()?.username || 'invitado',
  );
  readonly mode = msalEnabled ? 'msal' : 'dev';

  /**
   * Se ejecuta una vez antes de que Angular monte la aplicacion.
   *
   * En modo MSAL hay dos pasos obligatorios y en este orden:
   *  1. initialize(): MSAL v3+ exige inicializar antes de cualquier otra llamada.
   *  2. handleRedirectPromise(): procesa el #code=... con el que Entra ID devuelve
   *     al usuario. Si se omite, el login "funciona" pero nunca queda una cuenta activa.
   */
  async initialize(): Promise<void> {
    try {
      if (msalEnabled && this.msal) {
        /**
         * Todo pasa por MsalService, NUNCA por msal.instance directamente.
         *
         * Es la diferencia entre que funcione y que no: MsalBroadcastService solo
         * observa el ciclo de vida si las llamadas van por MsalService. Si se usa
         * instance.initialize() / instance.handleRedirectPromise(), el broadcast
         * se queda en InteractionStatus.Startup para siempre, y MsalInterceptor
         * —que espera a que llegue a None antes de pedir el token— nunca adjunta
         * la cabecera Authorization. El resultado es un 401 sin motivo: el backend
         * recibe la peticion sin token y no tiene nada que rechazar.
         */
        await firstValueFrom(this.msal.initialize());
        // Entra ID devuelve los fallos en el fragmento de la URL, no como excepcion.
        // Hay que leerlo ANTES de handleRedirectObservable(), que limpia el hash.
        this.captureRedirectError();
        const result = await firstValueFrom(this.msal.handleRedirectObservable());
        if (result?.account) {
          this.msal.instance.setActiveAccount(result.account);
        } else {
          const accounts = this.msal.instance.getAllAccounts();
          if (accounts.length > 0 && !this.msal.instance.getActiveAccount()) {
            this.msal.instance.setActiveAccount(accounts[0]);
          }
        }
        if (this.msal.instance.getActiveAccount()) {
          await this.loadMe();
        }
      } else if (this.devToken()) {
        await this.loadMe();
      }
    } catch (err) {
      console.error('Fallo la inicializacion de la sesion', err);
      this._error.set(describeMsalError(err));
    } finally {
      this._ready.set(true);
    }
  }

  /**
   * Traduce el error que Entra ID deja en el hash, p. ej.
   *   #error=access_denied&error_description=AADSTS65001%3A+The+user+or+administrator...
   *
   * Sin esto, el usuario solo veria una pantalla de login en blanco sin saber que
   * el problema es que falta el consentimiento de administrador.
   */
  private captureRedirectError(): void {
    const hash = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
    const search = window.location.search.startsWith('?') ? window.location.search.slice(1) : '';
    const params = new URLSearchParams(hash || search);
    const error = params.get('error');
    if (!error) {
      return;
    }
    const description = params.get('error_description') ?? '';
    this._error.set(describeAadError(error, description));
  }

  // ---------------- modo MSAL ----------------

  loginWithMicrosoft(): void {
    if (!this.msal) {
      return;
    }
    // Redirect y no popup: los bloqueadores de popups son la causa numero uno
    // de "el login no hace nada" en las demos.
    //
    // prompt: 'select_account' obliga a Entra ID a mostrar SIEMPRE el selector de
    // cuenta. Dos motivos:
    //  - Con varias sesiones abiertas en el navegador (la corporativa y la del
    //    tenant de pruebas), el SSO silencioso elige una por su cuenta y falla con
    //    un error generico que no dice cual escogio.
    //  - Para demostrar la autorizacion por rol hay que ir cambiando de usuario;
    //    sin el selector habria que cerrar sesion en Microsoft cada vez.
    this.msal.loginRedirect({
      scopes: environment.msal.apiScopes,
      prompt: 'select_account',
    });
  }

  account(): AccountInfo | null {
    return this.msal?.instance.getActiveAccount() ?? null;
  }

  // ---------------- modo dev ----------------

  /**
   * Pide al BFF un access token local con el rol elegido. Sirve para demostrar
   * que la autorizacion realmente bloquea: se entra como CUSTOMER y /reports
   * devuelve 403 aunque el menu se manipule a mano.
   */
  async loginAsDemoUser(username: string, roles: Role[]): Promise<void> {
    const response = await firstValueFrom(
      this.http.post<{ accessToken: string }>(`${environment.apiBaseUrl}/dev/token`, {
        username,
        roles,
        minutes: 120,
      }),
    );
    sessionStorage.setItem(DEV_TOKEN_KEY, response.accessToken);
    await this.loadMe();
  }

  devToken(): string | null {
    return msalEnabled ? null : sessionStorage.getItem(DEV_TOKEN_KEY);
  }

  // ---------------- comun ----------------

  async loadMe(): Promise<Me | null> {
    try {
      // El token se adjunta AQUI de forma explicita, sin depender de MsalInterceptor.
      // Esta llamada ocurre dentro del app initializer, antes de que el interceptor
      // este listo para resolver tokens; dejarsela a el produciria justo el 401 sin
      // cabecera Authorization. Para el resto de la aplicacion si trabaja el interceptor.
      const headers = await this.authorizationHeader();
      const me = await firstValueFrom(
        this.http.get<Me>(`${environment.apiBaseUrl}/api/me`, { headers }),
      );
      this._me.set(me);
      this._error.set(null);
      return me;
    } catch (err) {
      this._me.set(null);
      // Sin esto, un token rechazado por el backend se traducia en un rebote mudo
      // a /login: el usuario veia el login otra vez sin ninguna pista del motivo.
      this._error.set(describeMeError(err));
      console.error('/api/me rechazo el token', err);
      return null;
    }
  }

  /**
   * Obtiene un access token para nuestra API. Intenta primero la via silenciosa
   * (cache / refresh token), que es el patron que exige Microsoft: interactivo
   * solo como ultimo recurso.
   */
  async acquireApiToken(): Promise<string | null> {
    if (!msalEnabled || !this.msal) {
      return this.devToken();
    }
    const account = this.msal.instance.getActiveAccount();
    if (!account) {
      return null;
    }
    try {
      const result = await firstValueFrom(
        this.msal.acquireTokenSilent({ scopes: environment.msal.apiScopes, account }),
      );
      return result.accessToken;
    } catch (err) {
      console.error('acquireTokenSilent falló', err);
      this._error.set(
        'No se pudo obtener un access token para la API. Revisa que los scopes de ' +
          'environment.ts coincidan con los expuestos por pedidos360-api y que el ' +
          'consentimiento de administrador esté concedido.',
      );
      return null;
    }
  }

  private async authorizationHeader(): Promise<Record<string, string>> {
    const token = await this.acquireApiToken();
    return token ? { Authorization: `Bearer ${token}` } : {};
  }

  hasRole(...roles: Role[]): boolean {
    const mine = this.roles();
    return roles.some((role) => mine.includes(role));
  }

  logout(): void {
    this._me.set(null);
    sessionStorage.removeItem(DEV_TOKEN_KEY);
    if (msalEnabled && this.msal) {
      // logoutRedirect cierra la sesion tambien en Entra ID. Borrar solo la cache
      // local dejaria al usuario "deslogueado" pero con sesion viva en Microsoft:
      // el siguiente login entraria solo, sin pedir credenciales.
      this.msal.logoutRedirect({
        postLogoutRedirectUri: environment.msal.postLogoutRedirectUri,
      });
      return;
    }
    this.router.navigate(['/login']);
  }
}

/**
 * Convierte un error de MSAL o de Entra ID en un mensaje accionable.
 * Los codigos AADSTS son estables y documentados: vale la pena mapear los que
 * aparecen de verdad al configurar el tenant.
 */
function describeAadError(error: string, description: string): string {
  const code = /AADSTS\d+/.exec(description)?.[0] ?? '';
  const known: Record<string, string> = {
    AADSTS65001:
      'Falta el consentimiento de administrador. En el portal: pedidos360-spa → Permisos de API → ' +
      '"Conceder consentimiento de administrador". Las 6 filas deben quedar en verde.',
    AADSTS50011:
      'La URI de redirección no coincide con ninguna registrada. Debe ser exactamente ' +
      'http://localhost:4200/auth/callback en la plataforma "Single-page application".',
    AADSTS700016:
      'La aplicación no existe en este tenant. Revisa clientId y authority en environment.ts.',
    AADSTS9002326:
      'El registro está como plataforma "Web". Debe ser "Single-page application".',
    AADSTS50105:
      'Tu usuario no tiene ningún App Role asignado en pedidos360-api. ' +
      'Asígnalo en Aplicaciones empresariales → Usuarios y grupos.',
  };
  const detail = known[code];
  if (detail) {
    return `${code}: ${detail}`;
  }
  if (error === 'access_denied') {
    return description || 'Entra ID denegó el acceso. Revisa permisos y consentimiento.';
  }
  return description || `Error de autenticación: ${error}`;
}

function describeMsalError(err: unknown): string {
  const message = err instanceof Error ? err.message : String(err);
  return describeAadError('interaction_failed', message);
}

/**
 * Explica por que el backend no acepto el token.
 *
 * El motivo real viaja en la cabecera WWW-Authenticate segun RFC 6750, por ejemplo:
 *   Bearer error="invalid_token", error_description="The iss claim is not valid"
 * El backend la expone via CORS precisamente para poder mostrarla aqui.
 */
function describeMeError(err: unknown): string {
  if (!(err instanceof HttpErrorResponse)) {
    return 'No se pudo contactar al backend.';
  }
  if (err.status === 0) {
    return 'El backend no responde. Verifica que ms-pedidos360-bff esté corriendo en el puerto 8080.';
  }

  const challenge = err.headers.get('WWW-Authenticate') ?? '';
  const reason = /error_description="([^"]+)"/.exec(challenge)?.[1] ?? '';

  if (err.status === 401) {
    if (/iss/i.test(reason)) {
      return (
        'El backend rechazó el token por el emisor (iss). Causa habitual: pedidos360-api ' +
        'todavía emite tokens v1. Pon "requestedAccessTokenVersion": 2 en su manifiesto. ' +
        `Detalle: ${reason}`
      );
    }
    if (/aud/i.test(reason)) {
      return (
        'El backend rechazó el token por la audiencia (aud). Agrega el valor recibido a ' +
        `PEDIDOS360_AUDIENCES. Detalle: ${reason}`
      );
    }
    return `El backend rechazó el token. ${reason || 'Revisa issuer y audiencia.'}`;
  }
  if (err.status === 403) {
    return (
      'Token válido pero sin permisos. Tu usuario no tiene ningún App Role asignado en ' +
      'pedidos360-api (Aplicaciones empresariales → Usuarios y grupos).'
    );
  }
  return `El backend respondió ${err.status}. ${reason}`;
}
