package cl.duoc.pedidos360.rabbitadmin.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuracion de la gestion programatica de RabbitMQ (guia 2.3.1).
 *
 * RabbitAdmin es la clase de Spring AMQP que permite declarar y eliminar colas,
 * exchanges y bindings EN TIEMPO DE EJECUCION, a diferencia de los @Bean de
 * RabbitTopology, que describen la topologia fija del sistema y se declaran una
 * sola vez al arrancar.
 *
 * Los dos mecanismos conviven a proposito y responden a necesidades distintas:
 *
 *   RabbitTopology  -> la topologia que el Caso 0 define y que siempre debe
 *                      existir: q.cmd.email, q.cmd.kitchen, q.cmd.invoice y sus
 *                      DLQ. Declarativa, versionada en el repositorio.
 *
 *   RabbitAdmin     -> topologia dinamica que se crea segun el negocio: una cola
 *                      por sucursal nueva, un binding temporal para una campana.
 *                      Imperativa, decidida en ejecucion.
 *
 * Esta clase NO contiene logica de negocio: solo publica el bean. Toda la
 * operativa vive en RabbitAdminService.
 */
@Configuration
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "rabbit-enabled", havingValue = "true")
public class RabbitAdminConfig {

    /**
     * setIgnoreDeclarationExceptions(false): si una declaracion falla —por
     * ejemplo, redeclarar una cola existente con argumentos distintos— queremos
     * la excepcion para devolver un 409 al cliente, no un fallo silencioso.
     * El comportamiento por defecto de la guia registra el error en el log y
     * sigue, lo que deja al usuario creyendo que la operacion funciono.
     */
    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setIgnoreDeclarationExceptions(false);
        return admin;
    }
}
