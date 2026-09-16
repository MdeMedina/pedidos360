package cl.duoc.pedidos360.common.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envelope comun exigido por el Caso 0 ("Definir un envelope comun: type, eventId,
 * timestamp, traceId, correlationId").
 *
 * Por que un envelope y no el objeto de negocio pelado:
 *  - eventId permite IDEMPOTENCIA: si el broker reentrega el mensaje, el consumidor
 *    reconoce que ya lo proceso y no duplica el efecto (no manda dos correos).
 *  - correlationId agrupa todo lo derivado de una misma accion del usuario.
 *  - traceId permite seguir la peticion entre microservicios en los logs.
 *  - type permite enrutar/deserializar sin adivinar por la forma del payload.
 *
 * @param type          nombre del evento o comando, p.ej. OrderCreated / email.send
 * @param eventId       identificador unico e inmutable de ESTE mensaje
 * @param occurredAt    momento en que ocurrio el hecho de negocio
 * @param traceId       id de la traza distribuida
 * @param correlationId id de la accion de negocio que origino la cadena
 * @param actor         usuario que provoco el evento (el "quien" de la auditoria)
 * @param aggregateId   entidad afectada, p.ej. el id del pedido
 * @param payload       datos propios del evento
 */
public record EventEnvelope(
        String type,
        String eventId,
        Instant occurredAt,
        String traceId,
        String correlationId,
        String actor,
        String aggregateId,
        Map<String, Object> payload) {

    public static EventEnvelope of(String type, String aggregateId, String actor, Map<String, Object> payload) {
        String id = UUID.randomUUID().toString();
        return new EventEnvelope(type, id, Instant.now(), id, aggregateId, actor, aggregateId, payload);
    }

    public EventEnvelope withCorrelation(String correlationId, String traceId) {
        return new EventEnvelope(type, eventId, occurredAt, traceId, correlationId, actor, aggregateId, payload);
    }
}
