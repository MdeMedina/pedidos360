package cl.duoc.pedidos360.notify.consumer.invoice;

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

/** Dominio FACTURACION. Consume q.cmd.invoice: generacion de boleta o factura. */
@Component
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "rabbit-enabled", havingValue = "true")
public class InvoiceListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceListener.class);
    private static final String CONTEXTO = "q.cmd.invoice";

    private final Outbox outbox;
    private final AckPolicy ack;

    public InvoiceListener(Outbox outbox, AckPolicy ack) {
        this.outbox = outbox;
        this.ack = ack;
    }

    @RabbitListener(queues = Messaging.Q_INVOICE, ackMode = "MANUAL")
    public void onInvoice(EventEnvelope envelope,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            if (!outbox.markProcessed(envelope.eventId())) {
                ack.ack(channel, deliveryTag, CONTEXTO);
                return;
            }
            if (envelope.aggregateId() == null || envelope.aggregateId().isBlank()) {
                throw new PermanentFailureException("boleta sin pedido asociado");
            }

            log.info("[BOLETA] generando documento para el pedido {}", envelope.aggregateId());
            outbox.record(new Outbox.Sent(envelope.eventId(), "invoice", "facturacion",
                    "Boleta " + envelope.aggregateId(), "Generacion de PDF", Instant.now()));

            ack.ack(channel, deliveryTag, CONTEXTO);

        } catch (PermanentFailureException ex) {
            ack.deadLetter(channel, deliveryTag, CONTEXTO, ex.getMessage());
        } catch (Exception ex) {
            ack.retry(channel, deliveryTag, CONTEXTO, ex);
        }
    }
}
