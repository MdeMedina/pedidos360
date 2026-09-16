package cl.duoc.pedidos360.catalog.service;

import java.math.BigDecimal;
import java.util.List;

import cl.duoc.pedidos360.catalog.domain.Product;
import cl.duoc.pedidos360.catalog.domain.ProductRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Datos de ejemplo para poder demostrar la solucion sin cargar el catalogo a mano. */
@Configuration
@Profile("dev")
public class CatalogSeeder {

    @Bean
    CommandLineRunner seedProducts(ProductRepository repository) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            repository.saveAll(List.of(
                    new Product("SKU-001", "Pizza Napolitana", "Masa madre, tomate, mozzarella y albahaca",
                            new BigDecimal("9990"), 40),
                    new Product("SKU-002", "Pizza Pepperoni", "Pepperoni y doble mozzarella",
                            new BigDecimal("11990"), 35),
                    new Product("SKU-003", "Ensalada Cesar", "Pollo, crutones y aderezo cesar",
                            new BigDecimal("6990"), 25),
                    new Product("SKU-004", "Bebida 500ml", "Linea de bebidas gaseosas",
                            new BigDecimal("1990"), 120),
                    new Product("SKU-005", "Postre Brownie", "Brownie con helado de vainilla",
                            new BigDecimal("4990"), 18)));
        };
    }
}
