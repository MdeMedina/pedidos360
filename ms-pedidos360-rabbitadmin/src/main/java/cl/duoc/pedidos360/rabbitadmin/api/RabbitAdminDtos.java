package cl.duoc.pedidos360.rabbitadmin.api;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO de entrada del microservicio administrador.
 *
 * Por que DTO con anotaciones y no @RequestParam sueltos: sin validacion es
 * trivial crear una cola con nombre vacio, con espacios o con caracteres que
 * RabbitMQ acepta pero que luego hacen imposible referenciarla desde una
 * routing key. El patron permite letras, numeros y . _ - : que es el juego de
 * caracteres habitual en nombres de colas AMQP.
 */
public final class RabbitAdminDtos {

    private RabbitAdminDtos() {
    }

    /** Nombres AMQP: letras, numeros, punto, guion, guion bajo y dos puntos. */
    private static final String NAME_PATTERN = "^[a-zA-Z0-9._:-]+$";
    private static final String NAME_MESSAGE =
            "Solo se permiten letras, numeros y los caracteres . _ - :";

    public record CreateQueueRequest(
            @NotBlank(message = "El nombre de la cola es obligatorio")
            @Size(max = 255, message = "El nombre no puede superar 255 caracteres")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
            String name,

            /** durable=true: la definicion de la cola sobrevive al reinicio del broker. */
            Boolean durable,

            /** exclusive=true: la cola se borra cuando su conexion se cierra. */
            Boolean exclusive,

            /** autoDelete=true: la cola se borra cuando el ultimo consumidor se va. */
            Boolean autoDelete,

            /**
             * Argumentos AMQP opcionales: x-dead-letter-exchange, x-message-ttl,
             * x-max-length... Es como se asocia una DLQ a una cola nueva.
             */
            Map<String, Object> arguments) {

        public boolean durableOrDefault() {
            return durable == null || durable;
        }

        public boolean exclusiveOrDefault() {
            return exclusive != null && exclusive;
        }

        public boolean autoDeleteOrDefault() {
            return autoDelete != null && autoDelete;
        }
    }

    public record CreateExchangeRequest(
            @NotBlank(message = "El nombre del exchange es obligatorio")
            @Size(max = 255, message = "El nombre no puede superar 255 caracteres")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
            String name,

            /** direct, topic, fanout o headers. */
            @NotBlank(message = "El tipo de exchange es obligatorio")
            @Pattern(regexp = "^(direct|topic|fanout|headers)$",
                     message = "El tipo debe ser direct, topic, fanout o headers")
            String type,

            Boolean durable,
            Boolean autoDelete) {

        public boolean durableOrDefault() {
            return durable == null || durable;
        }

        public boolean autoDeleteOrDefault() {
            return autoDelete != null && autoDelete;
        }
    }

    public record BindingRequest(
            @NotBlank(message = "El nombre de la cola es obligatorio")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
            String queue,

            @NotBlank(message = "El nombre del exchange es obligatorio")
            @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
            String exchange,

            /**
             * La routing key SI admite comodines (* y #) porque es un patron de
             * enrutamiento, no un identificador. Por eso no usa NAME_PATTERN.
             * Puede ir vacia en un exchange fanout, que ignora la clave.
             */
            @Size(max = 255, message = "La routing key no puede superar 255 caracteres")
            String routingKey) {

        public String routingKeyOrEmpty() {
            return routingKey == null ? "" : routingKey;
        }
    }

    // ---- respuestas ----

    public record QueueInfoResponse(String name, int messageCount, int consumerCount) {
    }

    public record OperationResponse(String resource, String name, String operation, String detail) {
    }
}
