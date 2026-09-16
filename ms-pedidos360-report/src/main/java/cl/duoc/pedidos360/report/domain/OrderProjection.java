package cl.duoc.pedidos360.report.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Vista materializada de un pedido, reconstruida solo a partir de eventos Kafka. */
@Entity
@Table(name = "order_projection")
public class OrderProjection {

    @Id
    @Column(length = 36)
    private String orderId;

    @Column(length = 160)
    private String customer;

    @Column(length = 20)
    private String status;

    @Column(precision = 12, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    private Integer itemCount = 0;

    private Instant createdAt;

    private Instant deliveredAt;

    private Long leadTimeMinutes;

    private Instant lastEventAt;

    protected OrderProjection() {
    }

    public OrderProjection(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCustomer() {
        return customer;
    }

    public void setCustomer(String customer) {
        this.customer = customer;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Long getLeadTimeMinutes() {
        return leadTimeMinutes;
    }

    public void setLeadTimeMinutes(Long leadTimeMinutes) {
        this.leadTimeMinutes = leadTimeMinutes;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(Instant lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public boolean isActive() {
        return !"DELIVERED".equals(status) && !"CANCELLED".equals(status);
    }
}
