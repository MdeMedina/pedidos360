package cl.duoc.pedidos360.notify.support;

import java.io.IOException;

import com.rabbitmq.client.Channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Politica de confirmacion de mensajes, compartida por todos los consumidores.
 *
 * Guia 2.2.1: con ACK automatico RabbitMQ da el mensaje por entregado en cuanto
 * lo escribe en el socket TCP. Si el consumidor muere mientras lo procesa, el
 * mensaje se pierde para siempre. Con ACK manual el broker solo lo borra cuando
 * el consumidor confirma explicitamente, y si la conexion cae sin confirmacion
 * lo reencola. Para comandos de negocio —un correo al cliente, una boleta— la
 * perdida silenciosa no es aceptable.
 *
 * Tres caminos, y la diferencia entre ellos es lo que evita tanto perder
 * mensajes como bloquear la cola:
 *
 *   ack()        procesado correctamente. El broker lo borra.
 *
 *   retry()      fallo TRANSITORIO (el proveedor SMTP no responde, timeout de
 *                red). nack con requeue=true: vuelve a la cola y se reintenta.
 *
 *   deadLetter() fallo PERMANENTE (mensaje mal formado, destinatario invalido).
 *                nack con requeue=false: el broker lo manda al dead-letter
 *                exchange configurado en la cola. Reintentar algo irreparable
 *                solo consume recursos y, si la cola tiene un solo consumidor,
 *                la bloquea indefinidamente: es el problema del "poison pill".
 *
 * Distinguir los dos tipos de fallo es la decision importante. Tratar todo como
 * transitorio bloquea la cola; tratar todo como permanente tira a la DLQ
 * mensajes perfectamente validos ante un hipo de red.
 */
@Component
public class AckPolicy {

    private static final Logger log = LoggerFactory.getLogger(AckPolicy.class);

    /** multiple=false: se confirma solo este mensaje, no todos los anteriores. */
    public void ack(Channel channel, long deliveryTag, String contexto) {
        try {
            channel.basicAck(deliveryTag, false);
            log.debug("ACK {} (tag={})", contexto, deliveryTag);
        } catch (IOException ex) {
            // Si el ACK falla, el canal ya esta roto. El broker reencolara el
            // mensaje al detectar la conexion caida, asi que no se pierde.
            log.error("No se pudo confirmar {} (tag={}): {}", contexto, deliveryTag, ex.getMessage());
        }
    }

    /** Fallo transitorio: requeue=true devuelve el mensaje a la cola. */
    public void retry(Channel channel, long deliveryTag, String contexto, Exception causa) {
        log.warn("Fallo transitorio en {}: {}. Se reencola para reintento.", contexto, causa.getMessage());
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("No se pudo reencolar {} (tag={}): {}", contexto, deliveryTag, ex.getMessage());
        }
    }

    /** Fallo permanente: requeue=false envia el mensaje al DLX de la cola. */
    public void deadLetter(Channel channel, long deliveryTag, String contexto, String motivo) {
        log.error("Mensaje no procesable en {}: {}. Se envia a la DLQ.", contexto, motivo);
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException ex) {
            log.error("No se pudo enviar a DLQ {} (tag={}): {}", contexto, deliveryTag, ex.getMessage());
        }
    }
}
