package cl.duoc.pedidos360.report.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderProjectionRepository extends JpaRepository<OrderProjection, String> {
}
