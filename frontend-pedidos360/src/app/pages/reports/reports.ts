import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { forkJoin } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { Kpis, LeadTimeReport, SalesByHour, TopProduct } from '../../core/models';
import { describeApiError } from '../../shared/api-error';

/**
 * Caso 0 — /reports (solo Admin), con SalesChartComponent, LeadTimeChartComponent
 * y TopProductsChartComponent.
 *
 * Los datos NO salen de ms-orders: salen de ms-report, que los construyo
 * consumiendo el topico Kafka orders.events. Esa es la exigencia del enunciado:
 * "Datos por streaming (Kafka) sin bloquear core". Si Kafka esta apagado, esta
 * pantalla queda vacia a proposito, y eso mismo demuestra de donde vienen los datos.
 *
 * Los graficos son SVG/CSS puros: sin librerias externas no hay dependencias que
 * auditar ni bundle extra que descargar.
 */
@Component({
  selector: 'app-reports',
  imports: [CurrencyPipe, DecimalPipe, DatePipe],
  templateUrl: './reports.html',
  styleUrl: './reports.css',
})
export class ReportsPage {
  private readonly api = inject(ApiService);

  readonly kpis = signal<Kpis | null>(null);
  readonly sales = signal<SalesByHour[]>([]);
  readonly leadTime = signal<LeadTimeReport | null>(null);
  readonly topProducts = signal<TopProduct[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly maxSales = computed(() => Math.max(1, ...this.sales().map((s) => s.amount)));
  readonly maxQuantity = computed(() => Math.max(1, ...this.topProducts().map((p) => p.quantity)));
  readonly maxLeadTime = computed(() =>
    Math.max(1, ...(this.leadTime()?.detail ?? []).map((d) => d.leadTimeMinutes)),
  );

  readonly hasData = computed(
    () => (this.kpis()?.totalOrders ?? 0) > 0 || this.sales().length > 0,
  );

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    forkJoin({
      kpis: this.api.kpis(),
      sales: this.api.salesByHour(24),
      leadTime: this.api.leadTime(),
      topProducts: this.api.topProducts(6),
    }).subscribe({
      next: ({ kpis, sales, leadTime, topProducts }) => {
        this.kpis.set(kpis);
        this.sales.set(sales);
        this.leadTime.set(leadTime);
        this.topProducts.set(topProducts);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.loading.set(false);
      },
    });
  }

  salesPercent(value: number): number {
    return Math.round((value / this.maxSales()) * 100);
  }

  quantityPercent(value: number): number {
    return Math.round((value / this.maxQuantity()) * 100);
  }

  leadPercent(value: number): number {
    return Math.round((value / this.maxLeadTime()) * 100);
  }
}
