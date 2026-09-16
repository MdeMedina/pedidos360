package cl.duoc.pedidos360.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** ms-pedidos360-orders :: nucleo del dominio Pedidos, expuesto en /api/orders/*. */
@SpringBootApplication(scanBasePackages = "cl.duoc.pedidos360")
public class OrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
