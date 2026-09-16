import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

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
  ];

  readonly navItems = computed(() =>
    this.allItems.filter((item) => this.auth.hasRole(...item.roles)),
  );

  readonly showShell = computed(() => this.auth.isAuthenticated());

  logout(): void {
    this.auth.logout();
  }
}
