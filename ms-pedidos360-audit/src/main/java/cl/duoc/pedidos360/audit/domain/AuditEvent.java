package cl.duoc.pedidos360.audit.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_event_id", columnList = "eventId", unique = true),
        @Index(name = "idx_audit_aggregate", columnList = "aggregateId"),
        @Index(name = "idx_audit_actor", columnList = "actor"),
        @Index(name = "idx_audit_occurred", columnList = "occurredAt")
})
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unico: es lo que hace idempotente el consumo. Si Kafka reentrega el mismo
     *  evento, el insert choca contra el indice unico y se descarta. */
    @Column(nullable = false, length = 64)
    private String eventId;

    @Column(nullable = false, length = 60)
    private String type;

    @Column(length = 64)
    private String aggregateId;

    @Column(length = 160)
    private String actor;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(length = 64)
    private String traceId;

    @Column(length = 64)
    private String correlationId;

    @Lob
    @Column(length = 4000)
    private String payload;

    @Column(nullable = false)
    private Instant recordedAt = Instant.now();

    protected AuditEvent() {
    }

    public AuditEvent(String eventId, String type, String aggregateId, String actor,
                      Instant occurredAt, String traceId, String correlationId, String payload) {
        this.eventId = eventId;
        this.type = type;
        this.aggregateId = aggregateId;
        this.actor = actor;
        this.occurredAt = occurredAt;
        this.traceId = traceId;
        this.correlationId = correlationId;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getType() {
        return type;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
