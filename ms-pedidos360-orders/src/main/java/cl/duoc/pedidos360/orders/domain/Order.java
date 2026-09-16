package cl.duoc.pedidos360.orders.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import cl.duoc.pedidos360.common.web.BusinessException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.springframework.http.HttpStatus;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(length = 36)
    private String id;

    /** Usuario Entra ID (preferred_username) que creo el pedido. */
    @Column(nullable = false, length = 160)
    private String customer;

    @Column(length = 200)
    private String deliveryAddress;

    @Column(length = 400)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.CREATED;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant acceptedAt;

    private Instant deliveredAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(length = 160)
    private String lastUpdatedBy;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderItem> items = new ArrayList<>();

    @Version
    private Long version;

    protected Order() {
    }

    public Order(String customer, String deliveryAddress, String notes) {
        this.id = UUID.randomUUID().toString();
        this.customer = customer;
        this.deliveryAddress = deliveryAddress;
        this.notes = notes;
    }

    public void addItem(String sku, String name, BigDecimal unitPrice, int quantity) {
        items.add(new OrderItem(this, sku, name, unitPrice, quantity));
        recalculateTotal();
    }

    public void recalculateTotal() {
        this.total = items.stream()
                .map(OrderItem::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Unico punto por donde puede cambiar el estado. Si la transicion no esta
     * permitida devuelve 409 con un mensaje explicito, que es lo que el frontend
     * muestra al operador.
     */
    public void transitionTo(OrderStatus target, String actor) {
        if (status == target) {
            throw new BusinessException(HttpStatus.CONFLICT, "El pedido ya esta en estado " + target);
        }
        if (!status.canMoveTo(target)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Transicion no permitida: " + status + " -> " + target
                            + ". Desde " + status + " solo se puede pasar a " + status.allowedTransitions() + ".");
        }
        this.status = target;
        this.updatedAt = Instant.now();
        this.lastUpdatedBy = actor;
        if (target == OrderStatus.ACCEPTED) {
            this.acceptedAt = this.updatedAt;
        }
        if (target == OrderStatus.DELIVERED) {
            this.deliveredAt = this.updatedAt;
        }
    }

    /**
     * Lead time del Caso 0: tiempo entre la creacion del pedido y su entrega.
     * Null mientras no este entregado (no se puede medir lo que no termino).
     */
    public Long leadTimeMinutes() {
        if (deliveredAt == null) {
            return null;
        }
        return Duration.between(createdAt, deliveredAt).toMinutes();
    }

    public boolean stockAlreadyReserved() {
        return acceptedAt != null;
    }

    public String getId() {
        return id;
    }

    public String getCustomer() {
        return customer;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public void setDeliveryAddress(String deliveryAddress) {
        this.deliveryAddress = deliveryAddress;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getLastUpdatedBy() {
        return lastUpdatedBy;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public void clearItems() {
        items.clear();
    }

    public void touch(String actor) {
        this.updatedAt = Instant.now();
        this.lastUpdatedBy = actor;
    }
}
