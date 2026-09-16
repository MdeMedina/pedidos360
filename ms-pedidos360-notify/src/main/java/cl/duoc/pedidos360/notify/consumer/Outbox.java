package cl.duoc.pedidos360.notify.consumer;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Registro en memoria de lo "enviado" y de los eventId ya procesados.
 *
 * IDEMPOTENCIA: RabbitMQ garantiza entrega at-least-once, no exactly-once. Si el
 * consumidor procesa el mensaje y muere antes del ACK, el broker lo reentrega. Sin
 * este control el cliente recibiria el mismo correo dos veces.
 *
 * En produccion esto no seria un Set en memoria sino Redis o una tabla con el
 * eventId como clave unica; el patron es el mismo.
 */
@Component
public class Outbox {

    private static final int MAX_HISTORY = 200;

    private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();
    private final Deque<Sent> history = new ArrayDeque<>();

    public record Sent(String eventId, String channel, String to, String subject, String body, Instant sentAt) {
    }

    /** @return true si el mensaje es nuevo; false si ya fue procesado antes. */
    public synchronized boolean markProcessed(String eventId) {
        return processedEventIds.add(eventId);
    }

    public synchronized void record(Sent sent) {
        history.addFirst(sent);
        while (history.size() > MAX_HISTORY) {
            history.removeLast();
        }
    }

    public synchronized List<Sent> recent() {
        return List.copyOf(history);
    }
}
