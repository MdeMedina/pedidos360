package cl.duoc.pedidos360.rabbitadmin.api;

import java.util.List;

import cl.duoc.pedidos360.common.security.Roles;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.BindingRequest;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.CreateExchangeRequest;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.CreateQueueRequest;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.OperationResponse;
import cl.duoc.pedidos360.rabbitadmin.api.RabbitAdminDtos.QueueInfoResponse;
import cl.duoc.pedidos360.rabbitadmin.service.RabbitAdminService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API REST de administracion de la topologia de RabbitMQ.
 *
 * Diseno de los recursos: cada tipo de objeto AMQP es un recurso con su
 * sustantivo en plural —queues, exchanges, bindings— y el verbo HTTP expresa la
 * accion. Crear es POST sobre la coleccion, eliminar es DELETE sobre el
 * elemento, consultar es GET. Purgar no encaja en ningun verbo estandar porque
 * no crea ni borra el recurso, asi que se modela como una sub-accion:
 * POST /queues/{name}/purge.
 *
 * El controlador no conoce RabbitMQ: solo llama a RabbitAdminService.
 *
 * Todo exige rol Admin. Administrar la mensajeria es una operacion de
 * infraestructura: quien pueda borrar q.cmd.email deja sin notificaciones a
 * todos los clientes.
 */
@RestController
@RequestMapping("/api/rabbitadmin")
@PreAuthorize(Roles.HAS_ADMIN)
@Tag(name = "Administracion RabbitMQ", description = "Gestion de colas, exchanges y bindings")
public class RabbitAdminController {

    private final RabbitAdminService service;

    public RabbitAdminController(RabbitAdminService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- colas

    @GetMapping("/queues")
    @Operation(summary = "Estado de las colas de la topologia del Caso 0")
    public List<QueueInfoResponse> listarColas() {
        return service.listarColasDelCaso();
    }

    @GetMapping("/queues/{name}")
    @Operation(summary = "Mensajes y consumidores de una cola")
    public QueueInfoResponse consultarCola(@PathVariable String name) {
        return service.consultarCola(name);
    }

    @PostMapping("/queues")
    @Operation(summary = "Crea una cola")
    public ResponseEntity<QueueInfoResponse> crearCola(@Valid @RequestBody CreateQueueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crearCola(request));
    }

    @DeleteMapping("/queues/{name}")
    @Operation(summary = "Elimina una cola que no pertenezca a la topologia base")
    public OperationResponse eliminarCola(@PathVariable String name) {
        service.eliminarCola(name);
        return new OperationResponse("queue", name, "deleted", "Cola eliminada del broker");
    }

    @PostMapping("/queues/{name}/purge")
    @Operation(summary = "Vacia una cola sin eliminarla")
    public OperationResponse purgarCola(@PathVariable String name) {
        int purgados = service.purgarCola(name);
        return new OperationResponse("queue", name, "purged", purgados + " mensaje(s) eliminados");
    }

    // ----------------------------------------------------------- exchanges

    @PostMapping("/exchanges")
    @Operation(summary = "Crea un exchange direct, topic, fanout o headers")
    public ResponseEntity<OperationResponse> crearExchange(
            @Valid @RequestBody CreateExchangeRequest request) {
        service.crearExchange(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new OperationResponse(
                "exchange", request.name(), "created", "Tipo " + request.type()));
    }

    @DeleteMapping("/exchanges/{name}")
    @Operation(summary = "Elimina un exchange que no pertenezca a la topologia base")
    public OperationResponse eliminarExchange(@PathVariable String name) {
        service.eliminarExchange(name);
        return new OperationResponse("exchange", name, "deleted", "Exchange eliminado del broker");
    }

    // ------------------------------------------------------------ bindings

    @PostMapping("/bindings")
    @Operation(summary = "Enlaza una cola a un exchange con una routing key")
    public ResponseEntity<OperationResponse> crearBinding(@Valid @RequestBody BindingRequest request) {
        service.crearBinding(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(new OperationResponse(
                "binding", request.queue() + " -> " + request.exchange(), "created",
                "Routing key: '" + request.routingKeyOrEmpty() + "'"));
    }

    /**
     * DELETE con cuerpo porque un binding no tiene identificador propio: se
     * identifica por la terna cola + exchange + routing key. Meter los tres en
     * la URL daria una ruta fragil con caracteres que hay que escapar.
     */
    @DeleteMapping("/bindings")
    @Operation(summary = "Elimina el binding identificado por cola, exchange y routing key")
    public OperationResponse eliminarBinding(@Valid @RequestBody BindingRequest request) {
        service.eliminarBinding(request);
        return new OperationResponse("binding", request.queue() + " -> " + request.exchange(),
                "deleted", "Routing key: '" + request.routingKeyOrEmpty() + "'");
    }
}
