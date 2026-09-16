package cl.duoc.pedidos360.orders.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cl.duoc.pedidos360.common.events.EventEnvelope;
import cl.duoc.pedidos360.common.events.Messaging;
import cl.duoc.pedidos360.common.messaging.DomainEventPublisher;
import cl.duoc.pedidos360.common.security.CurrentUser;
import cl.duoc.pedidos360.common.security.Roles;
import cl.duoc.pedidos360.common.web.BusinessException;
import cl.duoc.pedidos360.orders.api.OrderDtos.ChangeStatusRequest;
import cl.duoc.pedidos360.orders.api.OrderDtos.CreateOrderRequest;
import cl.duoc.pedidos360.orders.client.CatalogClient;
import cl.duoc.pedidos360.orders.domain.Order;
import cl.duoc.pedidos360.orders.domain.OrderItem;
import cl.duoc.pedidos360.orders.domain.OrderRepository;
import cl.duoc.pedidos360.orders.domain.OrderStatus;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinador del dominio Pedidos.
 *
 * Responsabilidades, en el orden en que importan:
 *  1. Aplicar las reglas del Caso 0 (maquina de estados, stock al aceptar).
 *  2. Aplicar la autorizacion "por dato": un Cliente solo ve y toca SUS pedidos,
 *     aunque tenga un token valido. Esto NO lo puede resolver @PreAuthorize ni el
 *     JWT Authorizer del gateway, porque depende del contenido del registro.
 *  3. Publicar el evento de negocio en Kafka y el comando de notificacion en RabbitMQ.
 */
@Service
public class OrderService {

    private final OrderRepository repository;
    private final CatalogClient catalogClient;
    private final TokenAccessor tokenAccessor;
    private final DomainEventPublisher publisher;

    public OrderService(OrderRepository repository, CatalogClient catalogClient,
                        TokenAccessor tokenAccessor, DomainEventPublisher publisher) {
        this.repository = repository;
        this.catalogClient = catalogClient;
        this.tokenAccessor = tokenAccessor;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public List<Order> findVisible(OrderStatus status) {
        String me = CurrentUser.username();
        if (CurrentUser.canSeeAllOrders()) {
            return status == null
                    ? repository.findAllByOrderByCreatedAtDesc()
                    : repository.findByStatusOrderByCreatedAtDesc(status);
        }
        return status == null
                ? repository.findByCustomerOrderByCreatedAtDesc(me)
                : repository.findByCustomerAndStatusOrderByCreatedAtDesc(me, status);
    }

    @Transactional(readOnly = true)
    public Order findVisibleById(String id) {
        Order order = repository.findById(id).orElseThrow(() -> BusinessException.notFound("Pedido", id));
        if (!CurrentUser.canSeeAllOrders() && !order.getCustomer().equals(CurrentUser.username())) {
            // 404 y no 403: no se le confirma a un cliente que el pedido de otro existe.
            throw BusinessException.notFound("Pedido", id);
        }
        return order;
    }

    @Transactional
    public Order create(CreateOrderRequest request) {
        String actor = CurrentUser.username();
        String customer = resolveCustomer(request, actor);
        String token = tokenAccessor.currentBearerToken();

        Order order = new Order(customer, request.deliveryAddress(), request.notes());
        for (var line : request.items()) {
            // Se consulta el catalogo para congelar nombre y precio: el pedido no puede
            // depender de que el producto siga existiendo o valiendo lo mismo manana.
            var product = catalogClient.findBySku(token, line.sku());
            if (!product.active()) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "El producto " + product.sku() + " no esta disponible");
            }
            order.addItem(product.sku(), product.name(), product.price(), line.quantity());
        }
        Order saved = repository.save(order);

        publishStatusEvent(saved, OrderStatus.CREATED, actor, null);
        publishNotification(saved, "Tu pedido fue recibido y esta pendiente de aceptacion.", actor);
        return saved;
    }

    @Transactional
    public Order changeStatus(String id, ChangeStatusRequest request) {
        Order order = findVisibleById(id);
        String actor = CurrentUser.username();
        OrderStatus target = request.status();

        guardStatusChangePermissions(order, target);

        String token = tokenAccessor.currentBearerToken();
        boolean releaseStock = target == OrderStatus.CANCELLED && order.stockAlreadyReserved();

        // La transicion se valida ANTES de tocar el stock: si la transicion es
        // invalida no debe haber ningun efecto lateral en el catalogo.
        order.transitionTo(target, actor);

        if (target == OrderStatus.ACCEPTED) {
            // Regla del Caso 0: "Stock decrece al aceptar pedido".
            catalogClient.reserveStock(token, order.getId(), stockLines(order));
        } else if (releaseStock) {
            catalogClient.releaseStock(token, order.getId(), stockLines(order));
        }

        Order saved = repository.save(order);
        publishStatusEvent(saved, target, actor, request.reason());
        publishNotification(saved, notificationMessage(target), actor);
        return saved;
    }

    @Transactional
    public void delete(String id) {
        Order order = findVisibleById(id);
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Solo se pueden eliminar pedidos en estado CREATED o CANCELLED. "
                            + "Un pedido en curso se cancela, no se borra.");
        }
        repository.delete(order);
        publishStatusEvent(order, OrderStatus.CANCELLED, CurrentUser.username(), "Pedido eliminado");
    }

    // ---------- reglas de autorizacion ----------

    private String resolveCustomer(CreateOrderRequest request, String actor) {
        if (request.customer() == null || request.customer().isBlank()) {
            return actor;
        }
        if (!CurrentUser.hasRole(Roles.ADMIN) && !CurrentUser.hasRole(Roles.OPERATOR)) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "Solo Admin u Operador pueden crear un pedido a nombre de otro cliente");
        }
        return request.customer();
    }

    /**
     * Caso 0: "Crear nuevos pedidos (solo cliente u operador)" y
     * "Cambiar estado del pedido (operador/admin)".
     * Al Cliente se le deja UNA excepcion: cancelar su propio pedido mientras
     * todavia no fue aceptado.
     */
    private void guardStatusChangePermissions(Order order, OrderStatus target) {
        if (CurrentUser.hasRole(Roles.ADMIN) || CurrentUser.hasRole(Roles.OPERATOR)) {
            return;
        }
        boolean cancelingOwnPendingOrder = target == OrderStatus.CANCELLED
                && order.getCustomer().equals(CurrentUser.username())
                && order.getStatus() == OrderStatus.CREATED;
        if (!cancelingOwnPendingOrder) {
            throw new BusinessException(HttpStatus.FORBIDDEN,
                    "Como Cliente solo puedes cancelar tus pedidos mientras no hayan sido aceptados");
        }
    }

    // ---------- publicacion de eventos ----------

    private List<CatalogClient.StockLine> stockLines(Order order) {
        return order.getItems().stream()
                .map(i -> new CatalogClient.StockLine(i.getSku(), i.getQuantity()))
                .toList();
    }

    private void publishStatusEvent(Order order, OrderStatus status, String actor, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", order.getId());
        payload.put("customer", order.getCustomer());
        payload.put("status", status.name());
        payload.put("total", order.getTotal());
        payload.put("createdAt", order.getCreatedAt().toString());
        payload.put("itemCount", order.getItems().stream().mapToInt(OrderItem::getQuantity).sum());
        // Las lineas viajan dentro del evento para que reporteria pueda calcular
        // "productos mas vendidos" sin tener que llamar de vuelta a orders. Un
        // consumidor de eventos no debe depender de la disponibilidad del productor.
        payload.put("items", order.getItems().stream()
                .map(i -> Map.<String, Object>of(
                        "sku", i.getSku(),
                        "name", i.getName(),
                        "quantity", i.getQuantity(),
                        "subtotal", i.subtotal()))
                .toList());
        if (order.leadTimeMinutes() != null) {
            payload.put("leadTimeMinutes", order.leadTimeMinutes());
        }
        if (reason != null) {
            payload.put("reason", reason);
        }
        EventEnvelope envelope = EventEnvelope.of(status.eventType(), order.getId(), actor, payload);
        // Mismo evento a dos topicos con proposito distinto:
        //   orders.events  -> lo consume reporteria para calcular KPIs
        //   audit.timeline -> lo consume auditoria para el "quien/que/cuando"
        publisher.publishEvent(Messaging.TOPIC_ORDERS_EVENTS, order.getId(), envelope);
        publisher.publishEvent(Messaging.TOPIC_AUDIT_TIMELINE, order.getId(), envelope);
    }

    private void publishNotification(Order order, String message, String actor) {
        Map<String, Object> payload = Map.of(
                "orderId", order.getId(),
                "to", order.getCustomer(),
                "subject", "Pedido " + order.getId().substring(0, 8) + " :: " + order.getStatus(),
                "body", message,
                "channel", "email");
        publisher.publishCommand(Messaging.RK_EMAIL_SEND,
                EventEnvelope.of("email.send", order.getId(), actor, payload));
    }

    private String notificationMessage(OrderStatus status) {
        return switch (status) {
            case CREATED -> "Tu pedido fue recibido.";
            case ACCEPTED -> "Tu pedido fue aceptado y entro en cola de preparacion.";
            case PREPARING -> "Estamos preparando tu pedido.";
            case DISPATCHED -> "Tu pedido salio a reparto.";
            case DELIVERED -> "Tu pedido fue entregado. Gracias por tu compra.";
            case CANCELLED -> "Tu pedido fue cancelado.";
        };
    }
}
