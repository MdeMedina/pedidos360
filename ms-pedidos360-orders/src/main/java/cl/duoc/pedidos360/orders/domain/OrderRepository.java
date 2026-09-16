package cl.duoc.pedidos360.orders.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {

    List<Order> findByCustomerOrderByCreatedAtDesc(String customer);

    List<Order> findAllByOrderByCreatedAtDesc();

    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    List<Order> findByCustomerAndStatusOrderByCreatedAtDesc(String customer, OrderStatus status);
}
