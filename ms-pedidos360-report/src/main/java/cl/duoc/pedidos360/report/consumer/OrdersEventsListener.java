package cl.duoc.pedidos360.report.consumer;

import cl.duoc.pedidos360.common.events.EventEnvelope;
import cl.duoc.pedidos360.common.events.Messaging;
import cl.duoc.pedidos360.report.service.ReportProjector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Grupo de consumo propio ("report"): audit lee los MISMOS eventos con su propio
 * grupo y su propio offset. Esa es la ventaja de Kafka sobre una cola: el evento
 * no se consume, se lee, y cada consumidor avanza a su ritmo.
 */
@Component
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "kafka-enabled", havingValue = "true")
public class OrdersEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrdersEventsListener.class);

    private final ReportProjector projector;

    public OrdersEventsListener(ReportProjector projector) {
        this.projector = projector;
    }

    @KafkaListener(
            topics = Messaging.TOPIC_ORDERS_EVENTS,
            groupId = "${spring.kafka.consumer.group-id:pedidos360-report}",
            containerFactory = "envelopeListenerFactory")
    public void onOrderEvent(EventEnvelope envelope) {
        log.debug("[report] {} pedido={}", envelope.type(), envelope.aggregateId());
        projector.apply(envelope);
    }
}
