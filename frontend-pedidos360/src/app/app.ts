import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { AuthService } from './core/auth.service';
import { Role } from './core/models';

interface NavItem {
  path: string;
  label: string;
  icon: string;
  roles: Role[];
}

/**
 * Layout base del Caso 0: header + menu lateral + area de contenido.
 *
 * El menu se filtra por rol, pero eso es SOLO presentacion. Cada ruta ademas
 * tiene su guard, y cada endpoint su @PreAuthorize. Ocultar un boton no es
 * seguridad; es cortesia.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  private readonly router = inject(Router);
  readonly auth = inject(AuthService);

  private readonly allItems: NavItem[] = [
    { path: '/dashboard', label: 'Dashboard', icon: '▤', roles: ['ADMIN', 'OPERATOR', 'CUSTOMER', 'AUDITOR'] },
    { path: '/orders', label: 'Pedidos', icon: '🧾', roles: ['ADMIN', 'OPERATOR', 'CUSTOMER'] },
    { path: '/catalog', label: 'Catálogo', icon: '📦', roles: ['ADMIN', 'OPERATOR'] },
    { path: '/reports', label: 'Reportería', icon: '📈', roles: ['ADMIN'] },
    { path: '/audit', label: 'Auditoría', icon: '🔍', roles: ['ADMIN', 'AUDITOR'] },
    { path: '/admin-colas', label: 'Colas', icon: '🐇', roles: ['ADMIN'] },
  ];

  readonly navItems = computed(() =>
    this.allItems.filter((item) => this.auth.hasRole(...item.roles)),
  );

  /**
   * Rutas que se dibujan a pantalla completa, sin el layout de la aplicacion.
   * Son las del flujo de autenticacion: mostrar el menu lateral junto a la
   * pantalla de login no tiene sentido y confunde, porque sugiere que hay
   * sesion cuando lo que se esta pidiendo es iniciarla.
   */
  private static readonly RUTAS_SIN_SHELL = ['/login', '/auth/callback'];

  /** URL actual como signal, para que showShell reaccione a la navegacion. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /**
   * El shell necesita DOS condiciones, no una.
   *
   * Con solo isAuthenticated(), estar logueado y tener el router en /login
   * dibujaba el menu lateral alrededor de la tarjeta de inicio de sesion.
   * Pasa siempre que se llega a una ruta de autenticacion con la sesion ya
   * resuelta: al volver de Entra ID por /auth/callback, o al navegar a /login
   * a mano.
   */
  readonly showShell = computed(() => {
    const ruta = this.url().split('?')[0];
    const esRutaDeAuth = App.RUTAS_SIN_SHELL.some((r) => ruta.startsWith(r));
    return this.auth.isAuthenticated() && !esRutaDeAuth;
  });

  logout(): void {
    this.auth.logout();
  }
}
