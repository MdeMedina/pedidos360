package cl.duoc.pedidos360.catalog.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySku(String sku);

    List<Product> findByActiveTrueOrderByNameAsc();

    boolean existsBySku(String sku);
}
