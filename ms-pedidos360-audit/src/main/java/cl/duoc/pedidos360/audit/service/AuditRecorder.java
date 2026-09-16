package cl.duoc.pedidos360.audit.service;

import cl.duoc.pedidos360.audit.domain.AuditEvent;
import cl.duoc.pedidos360.audit.domain.AuditEventRepository;
import cl.duoc.pedidos360.common.events.EventEnvelope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditRecorder(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(EventEnvelope envelope) {
        // Idempotencia: Kafka garantiza at-least-once. Sin esta comprobacion, un
        // rebalanceo del grupo de consumo duplicaria filas en el timeline.
        if (repository.existsByEventId(envelope.eventId())) {
            log.debug("Evento {} ya registrado, se ignora", envelope.eventId());
            return;
        }
        repository.save(new AuditEvent(
                envelope.eventId(),
                envelope.type(),
                envelope.aggregateId(),
                envelope.actor(),
                envelope.occurredAt(),
                envelope.traceId(),
                envelope.correlationId(),
                toJson(envelope.payload())));
    }

    private String toJson(Object payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"error\":\"payload no serializable\"}";
        }
    }
}
