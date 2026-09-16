package cl.duoc.pedidos360.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-pedidos360-notify :: consumidor RabbitMQ. No expone API publica (Caso 0).
 * Solo mantiene un endpoint interno de inspeccion para la demo.
 */
@SpringBootApplication(scanBasePackages = "cl.duoc.pedidos360")
public class NotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}
