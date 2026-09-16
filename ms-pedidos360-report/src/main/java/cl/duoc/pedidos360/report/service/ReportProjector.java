package cl.duoc.pedidos360.report.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import cl.duoc.pedidos360.common.events.EventEnvelope;
import cl.duoc.pedidos360.report.domain.OrderProjection;
import cl.duoc.pedidos360.report.domain.OrderProjectionRepository;
import cl.duoc.pedidos360.report.domain.ProductSales;
import cl.duoc.pedidos360.report.domain.ProductSalesRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica un evento de orders.events sobre las proyecciones de lectura.
 *
 * El metodo es idempotente por construccion: como guarda el ULTIMO estado conocido
 * por orderId (upsert), reprocesar el mismo evento deja el mismo resultado. Lo unico
 * que no se puede acumular dos veces es la venta por producto, y por eso solo se
 * suma en OrderCreated, que ocurre una sola vez por pedido.
 */
@Service
public class ReportProjector {

    private static final Logger log = LoggerFactory.getLogger(ReportProjector.class);

    private final OrderProjectionRepository orders;
    private final ProductSalesRepository productSales;

    public ReportProjector(OrderProjectionRepository orders, ProductSalesRepository productSales) {
        this.orders = orders;
        this.productSales = productSales;
    }

    @Transactional
    public void apply(EventEnvelope envelope) {
        String orderId = envelope.aggregateId();
        if (orderId == null) {
            log.warn("Evento {} sin aggregateId, se ignora", envelope.type());
            return;
        }
        Map<String, Object> payload = envelope.payload() == null ? Map.of() : envelope.payload();

        boolean isNew = !orders.existsById(orderId);
        OrderProjection projection = orders.findById(orderId).orElseGet(() -> new OrderProjection(orderId));

        projection.setCustomer(str(payload, "customer", projection.getCustomer()));
        projection.setStatus(str(payload, "status", projection.getStatus()));
        projection.setTotal(decimal(payload.get("total"), projection.getTotal()));
        projection.setItemCount(integer(payload.get("itemCount"), projection.getItemCount()));
        projection.setLastEventAt(envelope.occurredAt());
        if (projection.getCreatedAt() == null) {
            projection.setCreatedAt(instant(payload.get("createdAt"), envelope.occurredAt()));
        }
        if ("OrderDelivered".equals(envelope.type())) {
            projection.setDeliveredAt(envelope.occurredAt());
            projection.setLeadTimeMinutes(longValue(payload.get("leadTimeMinutes"),
                    java.time.Duration.between(projection.getCreatedAt(), envelope.occurredAt()).toMinutes()));
        }
        orders.save(projection);

        if (isNew && "OrderCreated".equals(envelope.type())) {
            accumulateProductSales(payload);
        }
    }

    @SuppressWarnings("unchecked")
    private void accumulateProductSales(Map<String, Object> payload) {
        Object raw = payload.get("items");
        if (!(raw instanceof List<?> items)) {
            return;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> line = (Map<String, Object>) map;
            String sku = String.valueOf(line.get("sku"));
            ProductSales sales = productSales.findById(sku)
                    .orElseGet(() -> new ProductSales(sku, String.valueOf(line.getOrDefault("name", sku))));
            sales.add(integer(line.get("quantity"), 0), decimal(line.get("subtotal"), BigDecimal.ZERO));
            productSales.save(sales);
        }
    }

    private static String str(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Integer integer(Object value, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Long longValue(Object value, Long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Instant instant(Object value, Instant fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
