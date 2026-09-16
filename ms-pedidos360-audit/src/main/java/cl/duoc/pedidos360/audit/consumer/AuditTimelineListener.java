package cl.duoc.pedidos360.audit.consumer;

import cl.duoc.pedidos360.audit.service.AuditRecorder;
import cl.duoc.pedidos360.common.events.EventEnvelope;
import cl.duoc.pedidos360.common.events.Messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "kafka-enabled", havingValue = "true")
public class AuditTimelineListener {

    private final AuditRecorder recorder;

    public AuditTimelineListener(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @KafkaListener(
            topics = Messaging.TOPIC_AUDIT_TIMELINE,
            groupId = "${spring.kafka.consumer.group-id:pedidos360-audit}",
            containerFactory = "envelopeListenerFactory")
    public void onAuditEvent(EventEnvelope envelope) {
        recorder.record(envelope);
    }
}
