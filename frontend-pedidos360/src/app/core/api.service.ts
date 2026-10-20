import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  AuditEvent,
  BindingRequest,
  CreateExchangeRequest,
  CreateQueueRequest,
  CreateOrderRequest,
  DashboardSummary,
  Kpis,
  LeadTimeReport,
  OperationResponse,
  Order,
  OrderStatus,
  Product,
  ProductRequest,
  QueueInfo,
  SalesByHour,
  TopProduct,
} from './models';

/**
 * Cliente HTTP de la aplicacion.
 *
 * Todas las llamadas salen contra UNA sola base (apiBaseUrl), que en local es el
 * BFF y en la nube es la URL de invocacion de AWS API Gateway. El frontend no
 * conoce ni una sola URL de microservicio: cambiar la topologia del backend no
 * obliga a recompilar el bundle.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBaseUrl;

  // ---- dashboard ----
  dashboard(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${this.base}/api/dashboard`);
  }

  // ---- pedidos ----
  listOrders(status?: OrderStatus | null): Observable<Order[]> {
    let params = new HttpParams();
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<Order[]>(`${this.base}/api/orders`, { params });
  }

  getOrder(id: string): Observable<Order> {
    return this.http.get<Order>(`${this.base}/api/orders/${id}`);
  }

  createOrder(body: CreateOrderRequest): Observable<Order> {
    return this.http.post<Order>(`${this.base}/api/orders`, body);
  }

  changeOrderStatus(id: string, status: OrderStatus, reason?: string): Observable<Order> {
    return this.http.patch<Order>(`${this.base}/api/orders/${id}/status`, { status, reason });
  }

  deleteOrder(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/api/orders/${id}`);
  }

  // ---- catalogo ----
  listProducts(onlyActive = true): Observable<Product[]> {
    const params = new HttpParams().set('onlyActive', onlyActive);
    return this.http.get<Product[]>(`${this.base}/api/catalog/products`, { params });
  }

  createProduct(body: ProductRequest): Observable<Product> {
    return this.http.post<Product>(`${this.base}/api/catalog/products`, body);
  }

  updateProduct(id: number, body: ProductRequest): Observable<Product> {
    return this.http.put<Product>(`${this.base}/api/catalog/products/${id}`, body);
  }

  deactivateProduct(id: number): Observable<Product> {
    return this.http.delete<Product>(`${this.base}/api/catalog/products/${id}`);
  }

  // ---- reporteria ----
  kpis(): Observable<Kpis> {
    return this.http.get<Kpis>(`${this.base}/api/report/kpis`);
  }

  salesByHour(hours = 24): Observable<SalesByHour[]> {
    const params = new HttpParams().set('hours', hours);
    return this.http.get<SalesByHour[]>(`${this.base}/api/report/sales-by-hour`, { params });
  }

  leadTime(): Observable<LeadTimeReport> {
    return this.http.get<LeadTimeReport>(`${this.base}/api/report/lead-time`);
  }

  topProducts(limit = 5): Observable<TopProduct[]> {
    const params = new HttpParams().set('limit', limit);
    return this.http.get<TopProduct[]>(`${this.base}/api/report/top-products`, { params });
  }

  // ---- auditoria ----
  auditEvents(filters: {
    actor?: string | null;
    type?: string | null;
    orderId?: string | null;
    from?: string | null;
    to?: string | null;
  }): Observable<AuditEvent[]> {
    let params = new HttpParams();
    Object.entries(filters).forEach(([key, value]) => {
      if (value) {
        params = params.set(key, value);
      }
    });
    return this.http.get<AuditEvent[]>(`${this.base}/api/audit/events`, { params });
  }

  auditTypes(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/api/audit/types`);
  }

  auditTimeline(orderId: string): Observable<AuditEvent[]> {
    return this.http.get<AuditEvent[]>(`${this.base}/api/audit/orders/${orderId}/timeline`);
  }

  // ---- administración de RabbitMQ ----

  rabbitQueues(): Observable<QueueInfo[]> {
    return this.http.get<QueueInfo[]>(`${this.base}/api/rabbitadmin/queues`);
  }

  rabbitQueue(name: string): Observable<QueueInfo> {
    return this.http.get<QueueInfo>(`${this.base}/api/rabbitadmin/queues/${name}`);
  }

  createQueue(body: CreateQueueRequest): Observable<QueueInfo> {
    return this.http.post<QueueInfo>(`${this.base}/api/rabbitadmin/queues`, body);
  }

  deleteQueue(name: string): Observable<OperationResponse> {
    return this.http.delete<OperationResponse>(`${this.base}/api/rabbitadmin/queues/${name}`);
  }

  purgeQueue(name: string): Observable<OperationResponse> {
    return this.http.post<OperationResponse>(`${this.base}/api/rabbitadmin/queues/${name}/purge`, {});
  }

  createExchange(body: CreateExchangeRequest): Observable<OperationResponse> {
    return this.http.post<OperationResponse>(`${this.base}/api/rabbitadmin/exchanges`, body);
  }

  deleteExchange(name: string): Observable<OperationResponse> {
    return this.http.delete<OperationResponse>(`${this.base}/api/rabbitadmin/exchanges/${name}`);
  }

  createBinding(body: BindingRequest): Observable<OperationResponse> {
    return this.http.post<OperationResponse>(`${this.base}/api/rabbitadmin/bindings`, body);
  }

  /** DELETE con cuerpo: un binding se identifica por cola + exchange + routing key. */
  deleteBinding(body: BindingRequest): Observable<OperationResponse> {
    return this.http.delete<OperationResponse>(`${this.base}/api/rabbitadmin/bindings`, { body });
  }
}
