package cl.duoc.pedidos360.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Interruptores de mensajeria.
 *
 * Por que existen: el nucleo del Caso 0 (identidad + API Gateway + CRUD) debe poder
 * levantarse y defenderse sin tener RabbitMQ ni Kafka arriba. Con los flags en false
 * los eventos se registran en el log en vez de publicarse, y la aplicacion funciona igual.
 * En infra/apps/compose.yml se activan en true.
 */
@ConfigurationProperties(prefix = "pedidos360.messaging")
public class MessagingProperties {

    private boolean rabbitEnabled = false;
    private boolean kafkaEnabled = false;

    public boolean isRabbitEnabled() {
        return rabbitEnabled;
    }

    public void setRabbitEnabled(boolean rabbitEnabled) {
        this.rabbitEnabled = rabbitEnabled;
    }

    public boolean isKafkaEnabled() {
        return kafkaEnabled;
    }

    public void setKafkaEnabled(boolean kafkaEnabled) {
        this.kafkaEnabled = kafkaEnabled;
    }
}
