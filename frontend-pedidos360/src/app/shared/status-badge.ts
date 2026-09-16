import { Component, input } from '@angular/core';

import { OrderStatus } from '../core/models';

/**
 * OrderStatusBadgeComponent del Caso 0: muestra el estado con color propio.
 * Un componente y no una clase CSS suelta, para que el mapeo estado -> etiqueta
 * viva en un solo lugar y las cinco pantallas no se contradigan.
 */
@Component({
  selector: 'app-status-badge',
  template: `<span class="badge badge-{{ status() }}">{{ label() }}</span>`,
})
export class StatusBadge {
  readonly status = input.required<OrderStatus>();

  private readonly labels: Record<OrderStatus, string> = {
    CREATED: 'Creado',
    ACCEPTED: 'Aceptado',
    PREPARING: 'En preparación',
    DISPATCHED: 'Despachado',
    DELIVERED: 'Entregado',
    CANCELLED: 'Cancelado',
  };

  label(): string {
    return this.labels[this.status()] ?? this.status();
  }
}
