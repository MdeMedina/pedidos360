package cl.duoc.pedidos360.orders.api;

import java.util.List;

import cl.duoc.pedidos360.common.security.Roles;
import cl.duoc.pedidos360.orders.api.OrderDtos.ChangeStatusRequest;
import cl.duoc.pedidos360.orders.api.OrderDtos.CreateOrderRequest;
import cl.duoc.pedidos360.orders.api.OrderDtos.OrderResponse;
import cl.duoc.pedidos360.orders.domain.OrderStatus;
import cl.duoc.pedidos360.orders.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/orders/* — Caso 0: CRUD de pedidos y cambio de estado.
 *
 * Se usan DOS capas de autorizacion y cada una resuelve algo distinto:
 *  - @PreAuthorize aqui: "este ROL puede invocar este endpoint" (autorizacion funcional).
 *  - OrderService: "este USUARIO puede tocar ESTE registro" (autorizacion por dato).
 * Ningun JWT Authorizer de API Gateway puede hacer lo segundo, porque no conoce
 * el contenido de la base de datos.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Pedidos", description = "CRUD y ciclo de vida de los pedidos")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(Roles.HAS_ANY_BUSINESS_ROLE)
    @Operation(summary = "Lista pedidos. Admin/Operador/Auditor ven todos, Cliente solo los suyos")
    public List<OrderResponse> list(@RequestParam(name = "status", required = false) OrderStatus status) {
        return service.findVisible(status).stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.HAS_ANY_BUSINESS_ROLE)
    @Operation(summary = "Detalle de un pedido")
    public OrderResponse get(@PathVariable String id) {
        return OrderResponse.from(service.findVisibleById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','CUSTOMER')")
    @Operation(summary = "Crea un pedido. El Auditor no puede: su acceso es solo lectura")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(service.create(request)));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','CUSTOMER')")
    @Operation(summary = "Cambia el estado respetando la maquina de estados del Caso 0")
    public OrderResponse changeStatus(@PathVariable String id, @Valid @RequestBody ChangeStatusRequest request) {
        return OrderResponse.from(service.changeStatus(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Elimina un pedido no iniciado (solo Admin)")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status-machine")
    @PreAuthorize(Roles.HAS_ANY_BUSINESS_ROLE)
    @Operation(summary = "Devuelve las transiciones permitidas por estado, para que el frontend "
            + "solo muestre los botones validos")
    public List<StatusTransition> statusMachine() {
        return java.util.Arrays.stream(OrderStatus.values())
                .map(s -> new StatusTransition(s, List.copyOf(s.allowedTransitions())))
                .toList();
    }

    public record StatusTransition(OrderStatus from, List<OrderStatus> to) {
    }
}
