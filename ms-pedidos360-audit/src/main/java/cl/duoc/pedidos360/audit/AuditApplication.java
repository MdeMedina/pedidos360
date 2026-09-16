package cl.duoc.pedidos360.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-pedidos360-audit :: consume audit.timeline y persiste el "quien / que /
 * cuando / desde donde" de cada evento de negocio. Expone /api/audit/* en modo
 * SOLO LECTURA: un registro de auditoria que se puede editar no sirve como evidencia.
 */
@SpringBootApplication(scanBasePackages = "cl.duoc.pedidos360")
public class AuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditApplication.class, args);
    }
}
