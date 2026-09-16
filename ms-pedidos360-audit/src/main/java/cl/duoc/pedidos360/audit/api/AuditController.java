package cl.duoc.pedidos360.audit.api;

import java.time.Instant;
import java.util.List;

import cl.duoc.pedidos360.audit.domain.AuditEvent;
import cl.duoc.pedidos360.audit.domain.AuditEventRepository;
import cl.duoc.pedidos360.common.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/audit/* — Caso 0: "Timeline de eventos de negocio", actor Auditor, solo lectura.
 * No existe POST/PUT/DELETE a proposito: la unica forma de escribir aqui es a
 * traves del topico Kafka.
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "Auditoria", description = "Trazabilidad de eventos (solo lectura)")
public class AuditController {

    private final AuditEventRepository repository;

    public AuditController(AuditEventRepository repository) {
        this.repository = repository;
    }

    public record AuditEventView(Long id, String eventId, String type, String orderId, String actor,
                                 Instant occurredAt, String traceId, String correlationId, String payload) {

        static AuditEventView from(AuditEvent e) {
            return new AuditEventView(e.getId(), e.getEventId(), e.getType(), e.getAggregateId(),
                    e.getActor(), e.getOccurredAt(), e.getTraceId(), e.getCorrelationId(), e.getPayload());
        }
    }

    @GetMapping("/events")
    @PreAuthorize(Roles.HAS_AUDIT_READ)
    @Operation(summary = "Busca eventos por usuario, tipo, pedido y rango de fechas")
    public List<AuditEventView> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "200") int limit) {

        return repository.search(blankToNull(actor), blankToNull(type), blankToNull(orderId), from, to,
                        PageRequest.of(0, Math.min(limit, 1000)))
                .stream().map(AuditEventView::from).toList();
    }

    @GetMapping("/orders/{orderId}/timeline")
    @PreAuthorize(Roles.HAS_AUDIT_READ)
    @Operation(summary = "Linea de tiempo completa de un pedido, en orden cronologico")
    public List<AuditEventView> timeline(@PathVariable String orderId) {
        return repository.findByAggregateIdOrderByOccurredAtAsc(orderId).stream()
                .map(AuditEventView::from).toList();
    }

    @GetMapping("/types")
    @PreAuthorize(Roles.HAS_AUDIT_READ)
    @Operation(summary = "Tipos de evento presentes, para poblar el filtro del frontend")
    public List<String> types() {
        return repository.findDistinctTypes();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
