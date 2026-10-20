package cl.duoc.pedidos360.notify.consumer.deadletter;

import java.time.Instant;

import cl.duoc.pedidos360.common.events.EventEnvelope;
import cl.duoc.pedidos360.common.events.Messaging;
import cl.duoc.pedidos360.notify.consumer.Outbox;
import cl.duoc.pedidos360.notify.support.AckPolicy;

import com.rabbitmq.client.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Dominio OBSERVABILIDAD DE FALLOS. Consume las tres DLQ.
 *
 * Aqui llegan los mensajes que ningun consumidor pudo procesar. La politica es
 * distinta a la de los dominios de negocio y por eso vive en su propia clase:
 *
 *  - Siempre se hace ACK, nunca NACK. Un mensaje que ya esta en la DLQ no tiene
 *    a donde seguir cayendo: hacerle NACK con requeue=false lo descartaria, y
 *    con requeue=true crearia un bucle infinito dentro de la propia DLQ.
 *
 *  - Se registra con nivel ERROR y con los metadatos del envelope, que es lo
 *    que permite reconstruir que paso: eventId para rastrear el mensaje
 *    original, traceId para cruzarlo con los logs del resto de microservicios,
 *    y aggregateId para saber que pedido quedo sin notificar.
 *
 * El Caso 0 pide ademas metricas y alertas por tasa de DLQ. Este es el punto
 * donde se engancharian: un contador de Micrometer aqui alimenta la alerta.
 */
@Component
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "rabbit-enabled", havingValue = "true")
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final Outbox outbox;
    private final AckPolicy ack;

    public DeadLetterListener(Outbox outbox, AckPolicy ack) {
        this.outbox = outbox;
        this.ack = ack;
    }

    @RabbitListener(
            queues = {Messaging.Q_EMAIL_DLQ, Messaging.Q_KITCHEN_DLQ, Messaging.Q_INVOICE_DLQ},
            ackMode = "MANUAL")
    public void onDeadLetter(EventEnvelope envelope,
                             Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                             @Header(name = AmqpHeaders.CONSUMER_QUEUE, required = false) String cola) {

        log.error("[DLQ {}] mensaje no procesable · type={} eventId={} traceId={} pedido={} actor={}",
                cola, envelope.type(), envelope.eventId(), envelope.traceId(),
                envelope.aggregateId(), envelope.actor());

        // Se deja rastro consultable desde /api/notify/outbox para poder
        // demostrar en vivo que el mensaje fallido termino en la DLQ.
        outbox.record(new Outbox.Sent(envelope.eventId(), "dlq", cola == null ? "desconocida" : cola,
                "DLQ: " + envelope.type(), "Mensaje descartado tras agotar reintentos", Instant.now()));

        ack.ack(channel, deliveryTag, "DLQ " + cola);
    }
}
