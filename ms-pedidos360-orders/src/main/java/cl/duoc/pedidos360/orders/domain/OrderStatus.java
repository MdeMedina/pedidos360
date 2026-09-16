package cl.duoc.pedidos360.orders.domain;

import java.util.Set;

/**
 * Maquina de estados del Caso 0:
 *   CREATED -> ACCEPTED -> PREPARING -> DISPATCHED -> DELIVERED
 * con CANCELLED disponible mientras el pedido no haya salido a reparto.
 *
 * La regla "no se puede despachar sin aceptar" no se implementa con ifs sueltos
 * en el controlador sino aqui, en el dominio: cada estado declara a que estados
 * puede moverse. Asi la regla es imposible de saltar desde otro punto del codigo.
 */
public enum OrderStatus {

    CREATED,
    ACCEPTED,
    PREPARING,
    DISPATCHED,
    DELIVERED,
    CANCELLED;

    public Set<OrderStatus> allowedTransitions() {
        return switch (this) {
            case CREATED -> Set.of(ACCEPTED, CANCELLED);
            case ACCEPTED -> Set.of(PREPARING, CANCELLED);
            case PREPARING -> Set.of(DISPATCHED, CANCELLED);
            case DISPATCHED -> Set.of(DELIVERED);
            case DELIVERED, CANCELLED -> Set.of();
        };
    }

    public boolean canMoveTo(OrderStatus target) {
        return allowedTransitions().contains(target);
    }

    /** Estados que siguen "vivos" para el panel de KPIs. */
    public boolean isActive() {
        return this != DELIVERED && this != CANCELLED;
    }

    /** Evento de negocio publicado en Kafka al entrar a este estado. */
    public String eventType() {
        return switch (this) {
            case CREATED -> "OrderCreated";
            case ACCEPTED -> "OrderAccepted";
            case PREPARING -> "OrderPreparing";
            case DISPATCHED -> "OrderDispatched";
            case DELIVERED -> "OrderDelivered";
            case CANCELLED -> "OrderCancelled";
        };
    }
}
