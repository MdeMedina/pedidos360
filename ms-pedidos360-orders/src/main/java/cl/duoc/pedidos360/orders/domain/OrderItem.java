package cl.duoc.pedidos360.orders.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(nullable = false, length = 40)
    private String sku;

    @Column(nullable = false, length = 120)
    private String name;

    /** Precio congelado al momento del pedido: si el catalogo sube el precio manana,
     *  el pedido historico no cambia. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private Integer quantity;

    protected OrderItem() {
    }

    public OrderItem(Order order, String sku, String name, BigDecimal unitPrice, Integer quantity) {
        this.order = order;
        this.sku = sku;
        this.name = name;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public Integer getQuantity() {
        return quantity;
    }
}
