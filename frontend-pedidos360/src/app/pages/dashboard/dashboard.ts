import { CurrencyPipe, DatePipe, DecimalPipe, KeyValuePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DashboardSummary } from '../../core/models';
import { describeApiError } from '../../shared/api-error';
import { StatusBadge } from '../../shared/status-badge';

/**
 * Caso 0 — "Vista inicial tras login. Resumen de actividad según rol".
 *
 * La pantalla hace UNA sola llamada (/api/dashboard). La agregacion ocurre en el
 * BFF, que es quien puede hablar con orders y report sin pagar latencia de red
 * publica por cada salto.
 */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, StatusBadge, CurrencyPipe, DatePipe, DecimalPipe, KeyValuePipe],
  templateUrl: './dashboard.html',
})
export class DashboardPage {
  private readonly api = inject(ApiService);
  readonly auth = inject(AuthService);

  readonly data = signal<DashboardSummary | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly greeting = computed(() => {
    if (this.auth.hasRole('ADMIN')) return 'Panel de administración';
    if (this.auth.hasRole('OPERATOR')) return 'Pedidos en curso y pendientes';
    if (this.auth.hasRole('AUDITOR')) return 'Actividad registrada';
    return 'Tus últimos pedidos';
  });

  readonly maxStatusCount = computed(() => {
    const byStatus = this.data()?.ordersByStatus ?? {};
    return Math.max(1, ...Object.values(byStatus));
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.dashboard().subscribe({
      next: (data) => {
        this.data.set(data);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.loading.set(false);
      },
    });
  }

  percent(value: number): number {
    return Math.round((value / this.maxStatusCount()) * 100);
  }
}
