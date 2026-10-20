package cl.duoc.pedidos360.notify.consumer.email;

import java.time.Instant;
import java.util.Map;

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

/**
 * Dominio NOTIFICACIONES AL CLIENTE. Consume q.cmd.email.
 *
 * Cada dominio funcional tiene su propio paquete y su propia clase: email,
 * kitchen, invoice y deadletter. Agruparlos asi hace evidente a que parte del
 * negocio pertenece cada consumidor, y permite que el correo y la impresion de
 * comandas evolucionen por separado sin tocarse.
 */
@Component
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "rabbit-enabled", havingValue = "true")
public class EmailListener {

    private static final Logger log = LoggerFactory.getLogger(EmailListener.class);
    private static final String CONTEXTO = "q.cmd.email";

    private final Outbox outbox;
    private final AckPolicy ack;

    public EmailListener(Outbox outbox, AckPolicy ack) {
        this.outbox = outbox;
        this.ack = ack;
    }

    @RabbitListener(queues = Messaging.Q_EMAIL, ackMode = "MANUAL")
    public void onEmail(EventEnvelope envelope,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            // Idempotencia: RabbitMQ garantiza at-least-once, no exactly-once.
            // Si el consumidor murio despues de enviar el correo pero antes del
            // ACK, el broker lo reentrega y el cliente recibiria dos correos.
            if (!outbox.markProcessed(envelope.eventId())) {
                log.info("Mensaje {} ya procesado, se confirma sin reenviar", envelope.eventId());
                ack.ack(channel, deliveryTag, CONTEXTO);
                return;
            }

            Map<String, Object> payload = envelope.payload();
            String to = str(payload, "to");
            if (to == null || to.isBlank()) {
                throw new PermanentFailureException("comando email.send sin destinatario");
            }

            // Aqui iria la integracion real (SMTP, SendGrid, Web Push).
            log.info("[EMAIL] para={} asunto={}", to, str(payload, "subject"));
            outbox.record(new Outbox.Sent(envelope.eventId(), "email", to,
                    str(payload, "subject"), str(payload, "body"), Instant.now()));

            ack.ack(channel, deliveryTag, CONTEXTO);

        } catch (PermanentFailureException ex) {
            ack.deadLetter(channel, deliveryTag, CONTEXTO, ex.getMessage());
        } catch (Exception ex) {
            ack.retry(channel, deliveryTag, CONTEXTO, ex);
        }
    }

    private static String str(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
