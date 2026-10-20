package cl.duoc.pedidos360.notify.consumer.kitchen;

import java.time.Instant;

import cl.duoc.pedidos360.common.events.EventEnvelope;
import cl.duoc.pedidos360.common.events.Messaging;
import cl.duoc.pedidos360.notify.consumer.Outbox;
import cl.duoc.pedidos360.notify.support.AckPolicy;
import cl.duoc.pedidos360.notify.support.PermanentFailureException;

import com.rabbitmq.client.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Dominio COCINA. Consume q.cmd.kitchen: impresion de la comanda. */
@Component
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "rabbit-enabled", havingValue = "true")
public class KitchenListener {

    private static final Logger log = LoggerFactory.getLogger(KitchenListener.class);
    private static final String CONTEXTO = "q.cmd.kitchen";

    private final Outbox outbox;
    private final AckPolicy ack;

    public KitchenListener(Outbox outbox, AckPolicy ack) {
        this.outbox = outbox;
        this.ack = ack;
    }

    @RabbitListener(queues = Messaging.Q_KITCHEN, ackMode = "MANUAL")
    public void onKitchenTicket(EventEnvelope envelope,
                                Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            if (!outbox.markProcessed(envelope.eventId())) {
                ack.ack(channel, deliveryTag, CONTEXTO);
                return;
            }
            if (envelope.aggregateId() == null || envelope.aggregateId().isBlank()) {
                throw new PermanentFailureException("ticket de cocina sin pedido asociado");
            }

            log.info("[COCINA] ticket para el pedido {}", envelope.aggregateId());
            outbox.record(new Outbox.Sent(envelope.eventId(), "kitchen", "cocina",
                    "Ticket " + envelope.aggregateId(), "Impresion de comanda", Instant.now()));

            ack.ack(channel, deliveryTag, CONTEXTO);

        } catch (PermanentFailureException ex) {
            ack.deadLetter(channel, deliveryTag, CONTEXTO, ex.getMessage());
        } catch (Exception ex) {
            ack.retry(channel, deliveryTag, CONTEXTO, ex);
        }
    }
}
