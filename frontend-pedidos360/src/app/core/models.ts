export type OrderStatus =
  | 'CREATED'
  | 'ACCEPTED'
  | 'PREPARING'
  | 'DISPATCHED'
  | 'DELIVERED'
  | 'CANCELLED';

export type Role = 'ADMIN' | 'OPERATOR' | 'CUSTOMER' | 'AUDITOR';

export interface Me {
  username: string;
  displayName: string | null;
  roles: Role[];
  audience: string[];
  issuer: string | null;
  expiresAt: string;
  scopes: string[];
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  description: string | null;
  price: number;
  stock: number;
  active: boolean;
  updatedAt: string;
}

export interface ProductRequest {
  sku: string;
  name: string;
  description?: string | null;
  price: number;
  stock: number;
  active?: boolean;
}

export interface OrderLine {
  sku: string;
  name: string;
  unitPrice: number;
  quantity: number;
  subtotal: number;
}

export interface Order {
  id: string;
  customer: string;
  deliveryAddress: string | null;
  notes: string | null;
  status: OrderStatus;
  allowedTransitions: OrderStatus[];
  total: number;
  createdAt: string;
  acceptedAt: string | null;
  deliveredAt: string | null;
  updatedAt: string;
  lastUpdatedBy: string | null;
  leadTimeMinutes: number | null;
  items: OrderLine[];
}

export interface CreateOrderRequest {
  deliveryAddress?: string | null;
  notes?: string | null;
  customer?: string | null;
  items: { sku: string; quantity: number }[];
}

export interface DashboardSummary {
  username: string;
  roles: Role[];
  totalOrders: number;
  ordersByStatus: Record<string, number>;
  activeOrders: number;
  revenue: number;
  recentOrders: Order[];
  kpis?: Kpis;
}

export interface Kpis {
  totalOrders: number;
  activeOrders: number;
  deliveredOrders: number;
  cancelledOrders: number;
  revenue: number;
  averageTicket: number;
  averageLeadTimeMinutes: number | null;
  ordersByStatus: Record<string, number>;
  generatedAt: string;
}

export interface SalesByHour {
  hour: string;
  amount: number;
  orders: number;
}

export interface LeadTimeReport {
  samples: number;
  averageMinutes: number | null;
  minMinutes: number | null;
  maxMinutes: number | null;
  detail: { orderId: string; deliveredAt: string; leadTimeMinutes: number }[];
}

export interface TopProduct {
  sku: string;
  name: string;
  quantity: number;
  revenue: number;
}

export interface AuditEvent {
  id: number;
  eventId: string;
  type: string;
  orderId: string | null;
  actor: string | null;
  occurredAt: string;
  traceId: string | null;
  correlationId: string | null;
  payload: string;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  detail?: string;
}
