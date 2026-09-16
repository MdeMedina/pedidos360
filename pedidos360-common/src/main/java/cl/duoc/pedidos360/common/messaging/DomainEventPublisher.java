package cl.duoc.pedidos360.common.messaging;

import cl.duoc.pedidos360.common.events.EventEnvelope;

/**
 * Separa los dos tipos de mensaje del Caso 0, que NO son intercambiables:
 *
 *  - COMMAND (RabbitMQ): "haz esto". Tiene un destinatario esperado, debe ejecutarse
 *    una sola vez y si falla hay que reintentarlo o mandarlo a una DLQ.
 *    Ejemplo: enviar el correo de confirmacion.
 *
 *  - EVENT (Kafka): "esto ya paso". No tiene destinatario: cualquiera se suscribe.
 *    Es inmutable, se puede releer desde el offset 0 y por eso sirve para
 *    reconstruir reporteria y auditoria.
 */
public interface DomainEventPublisher {

    /** Publica un hecho consumado en Kafka (orders.events / audit.timeline). */
    void publishEvent(String topic, String key, EventEnvelope envelope);

    /** Encola un comando en RabbitMQ usando el exchange direct y su routing key. */
    void publishCommand(String routingKey, EventEnvelope envelope);
}
