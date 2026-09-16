package cl.duoc.pedidos360.orders.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import cl.duoc.pedidos360.orders.domain.Order;
import cl.duoc.pedidos360.orders.domain.OrderStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record OrderLineRequest(
            @NotBlank(message = "El SKU es obligatorio") String sku,
            @NotNull @Min(value = 1, message = "La cantidad minima es 1") Integer quantity) {
    }

    public record CreateOrderRequest(
            @Size(max = 200) String deliveryAddress,
            @Size(max = 400) String notes,
            @NotEmpty(message = "El pedido debe tener al menos una linea")
            List<@Valid OrderLineRequest> items,
            /** Solo Admin/Operador pueden crear un pedido a nombre de otro cliente. */
            String customer) {
    }

    public record ChangeStatusRequest(
            @NotNull(message = "Debe indicar el estado destino") OrderStatus status,
            @Size(max = 400) String reason) {
    }

    public record OrderLineResponse(String sku, String name, BigDecimal unitPrice, Integer quantity,
                                    BigDecimal subtotal) {
    }

    public record OrderResponse(
            String id,
            String customer,
            String deliveryAddress,
            String notes,
            OrderStatus status,
            List<OrderStatus> allowedTransitions,
            BigDecimal total,
            Instant createdAt,
            Instant acceptedAt,
            Instant deliveredAt,
            Instant updatedAt,
            String lastUpdatedBy,
            Long leadTimeMinutes,
            List<OrderLineResponse> items) {

        public static OrderResponse from(Order order) {
            List<OrderLineResponse> lines = order.getItems().stream()
                    .map(i -> new OrderLineResponse(i.getSku(), i.getName(), i.getUnitPrice(),
                            i.getQuantity(), i.subtotal()))
                    .toList();
            return new OrderResponse(
                    order.getId(),
                    order.getCustomer(),
                    order.getDeliveryAddress(),
                    order.getNotes(),
                    order.getStatus(),
                    List.copyOf(order.getStatus().allowedTransitions()),
                    order.getTotal(),
                    order.getCreatedAt(),
                    order.getAcceptedAt(),
                    order.getDeliveredAt(),
                    order.getUpdatedAt(),
                    order.getLastUpdatedBy(),
                    order.leadTimeMinutes(),
                    lines);
        }
    }
}
