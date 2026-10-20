import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';

import { ApiService } from '../../core/api.service';
import {
  BindingRequest,
  CreateExchangeRequest,
  CreateQueueRequest,
  OperationResponse,
  QueueInfo,
} from '../../core/models';
import { describeApiError } from '../../shared/api-error';

type ExchangeType = 'direct' | 'topic' | 'fanout' | 'headers';

/**
 * EP3 — pantalla de administracion de la mensajeria.
 *
 * Consume ms-pedidos360-rabbitadmin a traves del BFF. Permite ver el estado
 * real del broker —mensajes y consumidores por cola— y crear o eliminar colas,
 * exchanges y bindings sin entrar a la consola de RabbitMQ.
 *
 * Ruta protegida con rol Admin. Como siempre, el guard es solo experiencia de
 * usuario: el backend vuelve a exigir Admin en cada endpoint.
 */
@Component({
  selector: 'app-rabbitadmin',
  imports: [FormsModule],
  templateUrl: './rabbitadmin.html',
  styleUrl: './rabbitadmin.css',
})
export class RabbitAdminPage {
  private readonly api = inject(ApiService);

  readonly queues = signal<QueueInfo[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly info = signal<string | null>(null);

  /** Colas de la topologia del Caso 0: la API no deja eliminarlas. */
  private readonly protegidas = [
    'q.cmd.email', 'q.cmd.kitchen', 'q.cmd.invoice',
    'q.cmd.email.dlq', 'q.cmd.kitchen.dlq', 'q.cmd.invoice.dlq',
  ];

  readonly totalMensajes = computed(() =>
    this.queues().reduce((sum, q) => sum + q.messageCount, 0),
  );

  readonly mensajesEnDlq = computed(() =>
    this.queues().filter((q) => q.name.endsWith('.dlq'))
      .reduce((sum, q) => sum + q.messageCount, 0),
  );

  readonly sinConsumidor = computed(() => this.queues().filter((q) => q.consumerCount === 0));

  // formularios
  readonly queueForm = signal<CreateQueueRequest>({ name: '', durable: true });
  readonly exchangeForm = signal<CreateExchangeRequest>({ name: '', type: 'direct', durable: true });
  readonly bindingForm = signal<BindingRequest>({ queue: '', exchange: '', routingKey: '' });
  readonly exchangeTypes: ExchangeType[] = ['direct', 'topic', 'fanout', 'headers'];

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.rabbitQueues().subscribe({
      next: (queues) => {
        this.queues.set(queues);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.loading.set(false);
      },
    });
  }

  esProtegida(name: string): boolean {
    return this.protegidas.includes(name);
  }

  esDlq(name: string): boolean {
    return name.endsWith('.dlq');
  }

  // ---- acciones sobre colas existentes ----

  purgar(name: string): void {
    this.ejecutar(this.api.purgeQueue(name));
  }

  eliminarCola(name: string): void {
    this.ejecutar(this.api.deleteQueue(name));
  }

  // ---- creacion ----

  patchQueue(field: keyof CreateQueueRequest, value: unknown): void {
    this.queueForm.update((f) => ({ ...f, [field]: value }) as CreateQueueRequest);
  }

  patchExchange(field: keyof CreateExchangeRequest, value: unknown): void {
    this.exchangeForm.update((f) => ({ ...f, [field]: value }) as CreateExchangeRequest);
  }

  patchBinding(field: keyof BindingRequest, value: unknown): void {
    this.bindingForm.update((f) => ({ ...f, [field]: value }) as BindingRequest);
  }

  crearCola(): void {
    this.ejecutar(this.api.createQueue(this.queueForm()), () =>
      this.queueForm.set({ name: '', durable: true }),
    );
  }

  crearExchange(): void {
    this.ejecutar(this.api.createExchange(this.exchangeForm()), () =>
      this.exchangeForm.set({ name: '', type: 'direct', durable: true }),
    );
  }

  crearBinding(): void {
    this.ejecutar(this.api.createBinding(this.bindingForm()), () =>
      this.bindingForm.set({ queue: '', exchange: '', routingKey: '' }),
    );
  }

  eliminarExchange(name: string): void {
    if (!name) {
      return;
    }
    this.ejecutar(this.api.deleteExchange(name));
  }

  /**
   * Un solo camino para todas las mutaciones: ejecuta, muestra el resultado y
   * recarga el estado del broker. La validacion de fondo la hace el backend
   * con los DTO anotados, asi que los mensajes de error vienen ya redactados.
   */
  private ejecutar<T extends OperationResponse | QueueInfo>(
    operacion: Observable<T>,
    onSuccess?: () => void,
  ): void {
    this.busy.set(true);
    this.error.set(null);
    operacion.subscribe({
      next: (res) => {
        this.info.set('detail' in res ? res.detail : `Cola ${res.name} creada`);
        onSuccess?.();
        this.busy.set(false);
        this.load();
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.info.set(null);
        this.busy.set(false);
      },
    });
  }
}
