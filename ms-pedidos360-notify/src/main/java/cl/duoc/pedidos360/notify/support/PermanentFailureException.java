package cl.duoc.pedidos360.notify.support;

/**
 * Fallo del que no sirve reintentar: el mensaje nunca se va a poder procesar
 * tal como esta. Un destinatario vacio no se arregla volviendolo a intentar.
 *
 * Existe para que cada consumidor exprese la naturaleza del error en el propio
 * tipo de la excepcion, en vez de decidirlo con ifs al vuelo. Todo lo que NO
 * sea esta excepcion se considera transitorio y se reintenta.
 */
public class PermanentFailureException extends RuntimeException {

    public PermanentFailureException(String message) {
        super(message);
    }
}
