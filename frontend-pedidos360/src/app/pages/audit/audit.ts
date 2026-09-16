import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { AuditEvent } from '../../core/models';
import { describeApiError } from '../../shared/api-error';

/**
 * Caso 0 — /audit: "Mostrar la trazabilidad completa de pedidos o acciones de
 * usuarios. Permite filtros: usuario, rango de fechas, tipo de evento."
 *
 * La pantalla no tiene ni un boton de escritura, y no es un olvido: el servicio
 * de auditoria solo se alimenta del topico Kafka audit.timeline. Un registro de
 * auditoria editable desde la UI no sirve como evidencia ante nadie.
 */
@Component({
  selector: 'app-audit',
  imports: [FormsModule, DatePipe],
  templateUrl: './audit.html',
  styleUrl: './audit.css',
})
export class AuditPage {
  private readonly api = inject(ApiService);

  readonly events = signal<AuditEvent[]>([]);
  readonly types = signal<string[]>([]);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly expanded = signal<string | null>(null);

  readonly filterActor = signal('');
  readonly filterType = signal('');
  readonly filterOrderId = signal('');
  readonly filterFrom = signal('');
  readonly filterTo = signal('');

  constructor() {
    this.search();
    this.api.auditTypes().subscribe({
      next: (types) => this.types.set(types),
      error: () => this.types.set([]),
    });
  }

  search(): void {
    this.loading.set(true);
    this.api
      .auditEvents({
        actor: this.filterActor() || null,
        type: this.filterType() || null,
        orderId: this.filterOrderId() || null,
        // datetime-local entrega "2026-09-15T10:30"; el backend espera ISO-8601 con zona.
        from: this.filterFrom() ? new Date(this.filterFrom()).toISOString() : null,
        to: this.filterTo() ? new Date(this.filterTo()).toISOString() : null,
      })
      .subscribe({
        next: (events) => {
          this.events.set(events);
          this.error.set(null);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(describeApiError(err));
          this.loading.set(false);
        },
      });
  }

  clear(): void {
    this.filterActor.set('');
    this.filterType.set('');
    this.filterOrderId.set('');
    this.filterFrom.set('');
    this.filterTo.set('');
    this.search();
  }

  toggle(eventId: string): void {
    this.expanded.set(this.expanded() === eventId ? null : eventId);
  }

  prettyPayload(payload: string): string {
    try {
      return JSON.stringify(JSON.parse(payload), null, 2);
    } catch {
      return payload;
    }
  }
}
