package cl.duoc.pedidos360.common.messaging;

import cl.duoc.pedidos360.common.events.EventEnvelope;
import cl.duoc.pedidos360.common.events.Messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Implementacion unica que degrada con elegancia: si RabbitMQ o Kafka no estan
 * habilitados, el evento se escribe en el log en lugar de publicarse.
 *
 * Esto permite ejecutar y demostrar todo el nucleo (identidad, API Gateway, CRUD,
 * reglas de negocio) sin levantar brokers, y activar la mensajeria real cambiando
 * dos flags en infra/apps/compose.yml.
 */
@Component
@EnableConfigurationProperties(MessagingProperties.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public class DefaultDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DefaultDomainEventPublisher.class);

    private final MessagingProperties properties;
    private final ObjectProvider<KafkaTemplate> kafkaTemplate;
    private final ObjectProvider<RabbitTemplate> rabbitTemplate;

    public DefaultDomainEventPublisher(MessagingProperties properties,
                                       ObjectProvider<KafkaTemplate> kafkaTemplate,
                                       ObjectProvider<RabbitTemplate> rabbitTemplate) {
        this.properties = properties;
        this.kafkaTemplate = kafkaTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publishEvent(String topic, String key, EventEnvelope envelope) {
        KafkaTemplate template = properties.isKafkaEnabled()
                ? kafkaTemplate.getIfAvailable() : null;
        if (template == null) {
            log.info("[kafka:off] topic={} key={} type={} eventId={}", topic, key, envelope.type(), envelope.eventId());
            return;
        }
        // La clave es el id del pedido: garantiza orden por agregado dentro de la particion.
        template.send(topic, key, envelope);
        log.debug("[kafka] publicado {} en {} key={}", envelope.type(), topic, key);
    }

    @Override
    public void publishCommand(String routingKey, EventEnvelope envelope) {
        RabbitTemplate template = properties.isRabbitEnabled() ? rabbitTemplate.getIfAvailable() : null;
        if (template == null) {
            log.info("[rabbit:off] rk={} type={} eventId={}", routingKey, envelope.type(), envelope.eventId());
            return;
        }
        template.convertAndSend(Messaging.EXCHANGE_DIRECT, routingKey, envelope, message -> {
            // messageId permite al consumidor detectar reentregas y ser idempotente.
            message.getMessageProperties().setMessageId(envelope.eventId());
            message.getMessageProperties().setCorrelationId(envelope.correlationId());
            return message;
        });
        log.debug("[rabbit] comando {} enviado con rk={}", envelope.type(), routingKey);
    }
}
