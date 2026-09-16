package cl.duoc.pedidos360.report.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import cl.duoc.pedidos360.common.security.Roles;
import cl.duoc.pedidos360.report.domain.OrderProjection;
import cl.duoc.pedidos360.report.domain.OrderProjectionRepository;
import cl.duoc.pedidos360.report.domain.ProductSalesRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/report/* — solo lectura, solo Admin (Caso 0).
 * KPIs: ventas por hora, lead time y estados activos.
 */
@RestController
@RequestMapping("/api/report")
@Tag(name = "Reporteria", description = "KPIs construidos desde el stream de eventos")
public class ReportController {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
    private static final ZoneId ZONE = ZoneId.of("America/Santiago");

    private final OrderProjectionRepository orders;
    private final ProductSalesRepository productSales;

    public ReportController(OrderProjectionRepository orders, ProductSalesRepository productSales) {
        this.orders = orders;
        this.productSales = productSales;
    }

    @GetMapping("/kpis")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Resumen general de KPIs")
    public Map<String, Object> kpis() {
        List<OrderProjection> all = orders.findAll();

        BigDecimal revenue = all.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()))
                .map(OrderProjection::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Long> leadTimes = all.stream()
                .map(OrderProjection::getLeadTimeMinutes)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<String, Long> byStatus = new TreeMap<>();
        for (OrderProjection order : all) {
            byStatus.merge(order.getStatus() == null ? "UNKNOWN" : order.getStatus(), 1L, Long::sum);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalOrders", all.size());
        result.put("activeOrders", all.stream().filter(OrderProjection::isActive).count());
        result.put("deliveredOrders", byStatus.getOrDefault("DELIVERED", 0L));
        result.put("cancelledOrders", byStatus.getOrDefault("CANCELLED", 0L));
        result.put("revenue", revenue);
        result.put("averageTicket", all.isEmpty() ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(all.size()), 2, RoundingMode.HALF_UP));
        result.put("averageLeadTimeMinutes", leadTimes.isEmpty() ? null
                : leadTimes.stream().mapToLong(Long::longValue).average().orElse(0));
        result.put("ordersByStatus", byStatus);
        result.put("generatedAt", Instant.now());
        return result;
    }

    @GetMapping("/sales-by-hour")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Ventas agrupadas por hora, para el grafico de ventas")
    public List<Map<String, Object>> salesByHour(@RequestParam(defaultValue = "24") int hours) {
        Instant from = Instant.now().minus(java.time.Duration.ofHours(hours));
        Map<String, BigDecimal> amountByHour = new TreeMap<>();
        Map<String, Long> countByHour = new TreeMap<>();

        for (OrderProjection order : orders.findAll()) {
            if (order.getCreatedAt() == null || order.getCreatedAt().isBefore(from)
                    || "CANCELLED".equals(order.getStatus())) {
                continue;
            }
            String bucket = ZonedDateTime.ofInstant(order.getCreatedAt(), ZONE).format(HOUR);
            amountByHour.merge(bucket, order.getTotal(), BigDecimal::add);
            countByHour.merge(bucket, 1L, Long::sum);
        }
        return amountByHour.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "hour", e.getKey(),
                        "amount", e.getValue(),
                        "orders", countByHour.getOrDefault(e.getKey(), 0L)))
                .toList();
    }

    @GetMapping("/lead-time")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Lead time por pedido entregado (creacion -> entrega)")
    public Map<String, Object> leadTime() {
        List<Map<String, Object>> detail = orders.findAll().stream()
                .filter(o -> o.getLeadTimeMinutes() != null)
                .sorted(Comparator.comparing(OrderProjection::getDeliveredAt))
                .map(o -> Map.<String, Object>of(
                        "orderId", o.getOrderId(),
                        "deliveredAt", o.getDeliveredAt(),
                        "leadTimeMinutes", o.getLeadTimeMinutes()))
                .toList();

        java.util.DoubleSummaryStatistics stats = detail.stream()
                .mapToDouble(m -> ((Number) m.get("leadTimeMinutes")).doubleValue())
                .summaryStatistics();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", detail.size());
        result.put("averageMinutes", detail.isEmpty() ? null : stats.getAverage());
        result.put("minMinutes", detail.isEmpty() ? null : stats.getMin());
        result.put("maxMinutes", detail.isEmpty() ? null : stats.getMax());
        result.put("detail", detail);
        return result;
    }

    @GetMapping("/top-products")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Productos mas vendidos")
    public List<Map<String, Object>> topProducts(@RequestParam(defaultValue = "5") int limit) {
        return productSales.findAll().stream()
                .sorted(Comparator.comparing(p -> -p.getQuantity()))
                .limit(limit)
                .map(p -> Map.<String, Object>of(
                        "sku", p.getSku(),
                        "name", p.getName(),
                        "quantity", p.getQuantity(),
                        "revenue", p.getRevenue()))
                .toList();
    }
}
