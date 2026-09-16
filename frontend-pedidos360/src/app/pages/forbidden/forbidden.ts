import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-forbidden',
  imports: [RouterLink],
  template: `
    <div class="page">
      <div class="card" style="max-width: 640px">
        <h1>403 · Acceso denegado</h1>
        <p class="muted">
          Tu sesión es válida, pero tu rol no alcanza para esta pantalla. Esto lo decidió el guard
          de rutas; si forzaras la navegación, el backend respondería igualmente 403.
        </p>
        <table style="margin: 1rem 0">
          <tbody>
            <tr><th>Ruta solicitada</th><td class="mono">{{ attempted }}</td></tr>
            <tr><th>Rol requerido</th><td class="mono">{{ required }}</td></tr>
            <tr><th>Tus roles</th><td class="mono">{{ auth.roles().join(', ') || '—' }}</td></tr>
          </tbody>
        </table>
        <a class="btn btn-primary" routerLink="/dashboard">Volver al dashboard</a>
      </div>
    </div>
  `,
})
export class ForbiddenPage {
  private readonly route = inject(ActivatedRoute);
  readonly auth = inject(AuthService);

  readonly attempted = this.route.snapshot.queryParamMap.get('attempted') ?? '—';
  readonly required = this.route.snapshot.queryParamMap.get('required') ?? '—';
}
