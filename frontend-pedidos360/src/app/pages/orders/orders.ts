import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { CreateOrderRequest, Order, OrderStatus, Product } from '../../core/models';
import { describeApiError } from '../../shared/api-error';
import { StatusBadge } from '../../shared/status-badge';

interface DraftLine {
  sku: string;
  quantity: number;
}

/**
 * Caso 0 — /orders: OrdersComponent con OrderListComponent, OrderDetailComponent
 * y OrderStatusBadgeComponent. Aqui se resuelven en una sola pantalla con tres
 * zonas (lista, detalle lateral y formulario de creacion) para no obligar al
 * operador a navegar ida y vuelta mientras atiende.
 *
 * Detalle importante: los botones de cambio de estado NO estan cableados a mano.
 * Se dibujan a partir de order.allowedTransitions, que viene del backend. Si
 * manana cambia la maquina de estados en Java, el frontend se adapta solo y es
 * imposible que ofrezca una transicion que el backend vaya a rechazar.
 */
@Component({
  selector: 'app-orders',
  imports: [FormsModule, StatusBadge, CurrencyPipe, DatePipe],
  templateUrl: './orders.html',
  styleUrl: './orders.css',
})
export class OrdersPage {
  private readonly api = inject(ApiService);
  readonly auth = inject(AuthService);

  readonly orders = signal<Order[]>([]);
  readonly products = signal<Product[]>([]);
  readonly selected = signal<Order | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly info = signal<string | null>(null);

  readonly statusFilter = signal<OrderStatus | ''>('');
  readonly showForm = signal(false);
  readonly draftAddress = signal('');
  readonly draftNotes = signal('');
  readonly draftCustomer = signal('');
  readonly draftLines = signal<DraftLine[]>([{ sku: '', quantity: 1 }]);

  readonly statuses: OrderStatus[] = [
    'CREATED', 'ACCEPTED', 'PREPARING', 'DISPATCHED', 'DELIVERED', 'CANCELLED',
  ];

  readonly canManage = computed(() => this.auth.hasRole('ADMIN', 'OPERATOR'));
  readonly canCreate = computed(() => this.auth.hasRole('ADMIN', 'OPERATOR', 'CUSTOMER'));

  readonly draftTotal = computed(() =>
    this.draftLines().reduce((sum, line) => {
      const product = this.products().find((p) => p.sku === line.sku);
      return sum + (product ? product.price * (line.quantity || 0) : 0);
    }, 0),
  );

  readonly statusLabels: Record<OrderStatus, string> = {
    CREATED: 'Creado',
    ACCEPTED: 'Aceptar',
    PREPARING: 'Preparar',
    DISPATCHED: 'Despachar',
    DELIVERED: 'Entregar',
    CANCELLED: 'Cancelar',
  };

  constructor() {
    this.load();
    this.api.listProducts().subscribe({
      next: (products) => this.products.set(products),
      // Un Auditor no tiene acceso de escritura al catalogo; que falle esta
      // llamada no debe romper la pantalla de pedidos.
      error: () => this.products.set([]),
    });
  }

  load(): void {
    this.loading.set(true);
    this.api.listOrders(this.statusFilter() || null).subscribe({
      next: (orders) => {
        this.orders.set(orders);
        const current = this.selected();
        if (current) {
          this.selected.set(orders.find((o) => o.id === current.id) ?? null);
        }
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.loading.set(false);
      },
    });
  }

  onFilterChange(value: string): void {
    this.statusFilter.set(value as OrderStatus | '');
    this.load();
  }

  select(order: Order): void {
    this.selected.set(order);
  }

  /**
   * El Cliente solo puede cancelar su pedido antes de que lo acepten; el resto de
   * transiciones son de Operador/Admin. Se replica aqui la regla del backend para
   * no mostrar un boton que siempre devolveria 403.
   */
  canTransition(order: Order, target: OrderStatus): boolean {
    if (this.canManage()) {
      return true;
    }
    return target === 'CANCELLED' && order.status === 'CREATED';
  }

  changeStatus(order: Order, status: OrderStatus): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.changeOrderStatus(order.id, status).subscribe({
      next: (updated) => {
        this.selected.set(updated);
        this.info.set(`Pedido ${updated.id.slice(0, 8)} → ${updated.status}`);
        this.busy.set(false);
        this.load();
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.busy.set(false);
      },
    });
  }

  remove(order: Order): void {
    this.busy.set(true);
    this.api.deleteOrder(order.id).subscribe({
      next: () => {
        this.selected.set(null);
        this.info.set('Pedido eliminado.');
        this.busy.set(false);
        this.load();
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.busy.set(false);
      },
    });
  }

  // ---- formulario de creacion ----

  addLine(): void {
    this.draftLines.update((lines) => [...lines, { sku: '', quantity: 1 }]);
  }

  removeLine(index: number): void {
    this.draftLines.update((lines) => lines.filter((_, i) => i !== index));
  }

  updateLineSku(index: number, sku: string): void {
    this.draftLines.update((lines) =>
      lines.map((line, i) => (i === index ? { ...line, sku } : line)),
    );
  }

  updateLineQuantity(index: number, quantity: number): void {
    this.draftLines.update((lines) =>
      lines.map((line, i) => (i === index ? { ...line, quantity } : line)),
    );
  }

  submit(): void {
    const items = this.draftLines()
      .filter((line) => line.sku && line.quantity > 0)
      .map((line) => ({ sku: line.sku, quantity: Number(line.quantity) }));

    if (!items.length) {
      this.error.set('Agrega al menos un producto con cantidad mayor a cero.');
      return;
    }

    const body: CreateOrderRequest = {
      deliveryAddress: this.draftAddress() || null,
      notes: this.draftNotes() || null,
      items,
    };
    if (this.canManage() && this.draftCustomer()) {
      body.customer = this.draftCustomer();
    }

    this.busy.set(true);
    this.api.createOrder(body).subscribe({
      next: (order) => {
        this.info.set(`Pedido ${order.id.slice(0, 8)} creado.`);
        this.error.set(null);
        this.resetForm();
        this.busy.set(false);
        this.load();
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.busy.set(false);
      },
    });
  }

  resetForm(): void {
    this.showForm.set(false);
    this.draftAddress.set('');
    this.draftNotes.set('');
    this.draftCustomer.set('');
    this.draftLines.set([{ sku: '', quantity: 1 }]);
  }
}
