package cl.duoc.pedidos360.notify.consumer;

import java.time.Instant;
import java.util.Map;

import cl.duoc.pedidos360.common.events.EventEnvelope;
import cl.duoc.pedidos360.common.events.Messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Consumidores de los tres flujos de trabajo del Caso 0.
 *
 * Politica de errores:
 *  - Error TRANSITORIO (el proveedor de correo no responde): se relanza la excepcion
 *    para que el contenedor reintente segun la politica de retry.
 *  - Error PERMANENTE (mensaje mal formado, destinatario invalido): se lanza
 *    AmqpRejectAndDontRequeueException -> el broker lo manda a la DLQ. Reintentar
 *    un mensaje irreparable solo consume recursos y bloquea la cola.
 */
@Component
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "rabbit-enabled", havingValue = "true")
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final Outbox outbox;

    public NotificationListener(Outbox outbox) {
        this.outbox = outbox;
    }

    @RabbitListener(queues = Messaging.Q_EMAIL)
    public void onEmail(EventEnvelope envelope) {
        if (!outbox.markProcessed(envelope.eventId())) {
            log.info("Mensaje {} ya procesado, se descarta (idempotencia)", envelope.eventId());
            return;
        }
        Map<String, Object> payload = envelope.payload();
        String to = str(payload, "to");
        if (to == null || to.isBlank()) {
            throw new AmqpRejectAndDontRequeueException(
                    "Comando email.send sin destinatario; va a " + Messaging.Q_EMAIL_DLQ);
        }
        // Aqui iria la integracion real (SMTP, SendGrid, Web Push).
        log.info("[EMAIL] para={} asunto={}", to, str(payload, "subject"));
        outbox.record(new Outbox.Sent(envelope.eventId(), "email", to,
                str(payload, "subject"), str(payload, "body"), Instant.now()));
    }

    @RabbitListener(queues = Messaging.Q_KITCHEN)
    public void onKitchenTicket(EventEnvelope envelope) {
        if (!outbox.markProcessed(envelope.eventId())) {
            return;
        }
        log.info("[COCINA] ticket para el pedido {}", envelope.aggregateId());
        outbox.record(new Outbox.Sent(envelope.eventId(), "kitchen", "cocina",
                "Ticket " + envelope.aggregateId(), "Impresion de comanda", Instant.now()));
    }

    @RabbitListener(queues = Messaging.Q_INVOICE)
    public void onInvoice(EventEnvelope envelope) {
        if (!outbox.markProcessed(envelope.eventId())) {
            return;
        }
        log.info("[BOLETA] generando documento para el pedido {}", envelope.aggregateId());
        outbox.record(new Outbox.Sent(envelope.eventId(), "invoice", "facturacion",
                "Boleta " + envelope.aggregateId(), "Generacion de PDF", Instant.now()));
    }

    @RabbitListener(queues = {Messaging.Q_EMAIL_DLQ, Messaging.Q_KITCHEN_DLQ, Messaging.Q_INVOICE_DLQ})
    public void onDeadLetter(EventEnvelope envelope) {
        // En produccion aqui se dispara la alerta por tasa de DLQ que pide el Caso 0.
        log.error("[DLQ] mensaje no procesable type={} eventId={} aggregate={}",
                envelope.type(), envelope.eventId(), envelope.aggregateId());
    }

    private static String str(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
