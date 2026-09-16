package cl.duoc.pedidos360.report;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-pedidos360-report :: consume orders.events y mantiene proyecciones de lectura.
 *
 * Es un caso de CQRS: el modelo de escritura vive en ms-orders y el de lectura aqui.
 * El Caso 0 lo exige con estas palabras: "Datos por streaming (Kafka) sin bloquear core".
 * Calcular los KPIs a punta de consultas contra la base de pedidos degradaria el
 * servicio que atiende a los clientes.
 */
@SpringBootApplication(scanBasePackages = "cl.duoc.pedidos360")
public class ReportApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportApplication.class, args);
    }
}
