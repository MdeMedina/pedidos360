package cl.duoc.pedidos360.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ms-pedidos360-catalog :: dominio Productos/Stock, expuesto en /api/catalog/*.
 *
 * scanBasePackages incluye cl.duoc.pedidos360 para heredar del modulo common
 * la configuracion de Resource Server, el manejo de errores y OpenAPI.
 */
@SpringBootApplication(scanBasePackages = "cl.duoc.pedidos360")
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
