import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../../core/auth.service';

/**
 * Componente auxiliar /auth/callback que menciona el Caso 0.
 *
 * Cuando el navegador llega aqui, el initializer de la aplicacion ya ejecuto
 * handleRedirectPromise() y la cuenta esta activa. Esta pantalla solo decide a
 * donde mandar al usuario, y existe para que el redirectUri registrado en Entra ID
 * sea una ruta dedicada y no la home.
 */
@Component({
  selector: 'app-callback',
  template: `
    <div class="callback">
      <span class="spinner"></span>
      <p class="muted">Completando el inicio de sesión…</p>
    </div>
  `,
  styles: [
    `
      .callback {
        min-height: 100vh;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 0.75rem;
      }
    `,
  ],
})
export class CallbackPage {
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  constructor() {
    void this.resolve();
  }

  private async resolve(): Promise<void> {
    if (!this.auth.isAuthenticated()) {
      await this.auth.loadMe();
    }
    await this.router.navigateByUrl(this.auth.isAuthenticated() ? '/dashboard' : '/login');
  }
}
