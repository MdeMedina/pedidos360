package cl.duoc.pedidos360.notify.api;

import java.util.List;

import cl.duoc.pedidos360.common.security.Roles;
import cl.duoc.pedidos360.notify.consumer.Outbox;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El Caso 0 define este servicio como "no publico". Este endpoint existe solo para
 * poder DEMOSTRAR en la defensa que los comandos llegaron y se procesaron; por eso
 * queda restringido a Admin y no se publica como ruta en AWS API Gateway.
 */
@RestController
@RequestMapping("/api/notify")
@Tag(name = "Notificaciones", description = "Inspeccion del consumidor RabbitMQ (uso interno)")
public class NotifyController {

    private final Outbox outbox;

    public NotifyController(Outbox outbox) {
        this.outbox = outbox;
    }

    @GetMapping("/outbox")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Ultimas notificaciones procesadas desde las colas")
    public List<Outbox.Sent> outbox() {
        return outbox.recent();
    }
}
