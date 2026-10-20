package cl.duoc.pedidos360.rabbitadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-pedidos360-rabbitadmin :: microservicio administrador de RabbitMQ.
 *
 * Expone en /api/rabbitadmin/* las operaciones de gestion de la topologia:
 * crear y eliminar colas, exchanges y bindings, consultar el estado de una
 * cola y purgarla.
 *
 * Por que un microservicio aparte y no un endpoint dentro de ms-notify:
 * administrar la infraestructura de mensajeria es una responsabilidad distinta
 * de consumir mensajes de negocio. Separarlos permite protegerlo con un rol
 * propio (solo Admin), desplegarlo o apagarlo de forma independiente, y evita
 * que un bug en la administracion afecte al procesamiento de notificaciones.
 */
@SpringBootApplication(scanBasePackages = "cl.duoc.pedidos360")
public class RabbitAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitAdminApplication.class, args);
    }
}
