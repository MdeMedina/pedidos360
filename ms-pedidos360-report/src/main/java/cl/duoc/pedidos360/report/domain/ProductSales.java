package cl.duoc.pedidos360.report.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Acumulado por SKU para el grafico de productos mas vendidos. */
@Entity
@Table(name = "product_sales")
public class ProductSales {

    @Id
    @Column(length = 40)
    private String sku;

    @Column(length = 120)
    private String name;

    private Integer quantity = 0;

    @Column(precision = 14, scale = 2)
    private BigDecimal revenue = BigDecimal.ZERO;

    protected ProductSales() {
    }

    public ProductSales(String sku, String name) {
        this.sku = sku;
        this.name = name;
    }

    public void add(int quantity, BigDecimal amount) {
        this.quantity += quantity;
        this.revenue = this.revenue.add(amount);
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public BigDecimal getRevenue() {
        return revenue;
    }
}
