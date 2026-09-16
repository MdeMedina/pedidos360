package cl.duoc.pedidos360.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String sku;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 400)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(nullable = false)
    private Integer stock = 0;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Bloqueo optimista. Dos operadores aceptando pedidos a la vez sobre el mismo
     * producto no pueden dejar el stock negativo: la segunda transaccion falla y
     * se reintenta en vez de sobreescribir el valor de la primera.
     */
    @Version
    private Long version;

    protected Product() {
    }

    public Product(String sku, String name, String description, BigDecimal price, Integer stock) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
    }

    public boolean hasStock(int quantity) {
        return active && stock != null && stock >= quantity;
    }

    public void decreaseStock(int quantity) {
        if (!hasStock(quantity)) {
            throw new IllegalStateException(
                    "Stock insuficiente para " + sku + ": disponible " + stock + ", solicitado " + quantity);
        }
        this.stock -= quantity;
        this.updatedAt = Instant.now();
    }

    public void increaseStock(int quantity) {
        this.stock += quantity;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getVersion() {
        return version;
    }
}
