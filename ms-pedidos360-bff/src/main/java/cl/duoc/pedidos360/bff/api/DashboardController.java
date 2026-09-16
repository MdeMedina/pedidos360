package cl.duoc.pedidos360.bff.api;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cl.duoc.pedidos360.bff.proxy.DownstreamProperties;
import cl.duoc.pedidos360.common.security.CurrentUser;
import cl.duoc.pedidos360.common.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Agregacion para /dashboard. El navegador hace UNA llamada; el BFF hace las que
 * necesite y arma la vista segun el rol, tal como pide el Caso 0:
 *   Admin    -> KPIs globales
 *   Operator -> pedidos en curso y pendientes
 *   Customer -> ultimos pedidos y estado actual
 */
@RestController
@Tag(name = "Dashboard", description = "Resumen agregado segun el rol del usuario")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final RestClient restClient;
    private final DownstreamProperties downstream;

    public DashboardController(RestClient.Builder builder, DownstreamProperties downstream) {
        this.restClient = builder.build();
        this.downstream = downstream;
    }

    @GetMapping("/api/dashboard")
    @PreAuthorize(Roles.HAS_ANY_BUSINESS_ROLE)
    @Operation(summary = "Resumen de actividad para la pantalla inicial")
    public Map<String, Object> dashboard(Authentication authentication) {
        String token = ((JwtAuthenticationToken) authentication).getToken().getTokenValue();

        List<Map<String, Object>> orders = getJsonList(token, "orders", "/api/orders");

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> order : orders) {
            byStatus.merge(String.valueOf(order.get("status")), 1L, Long::sum);
        }

        BigDecimal revenue = orders.stream()
                .filter(o -> !"CANCELLED".equals(o.get("status")))
                .map(o -> new BigDecimal(String.valueOf(o.getOrDefault("total", "0"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", CurrentUser.username());
        response.put("roles", CurrentUser.roles());
        response.put("totalOrders", orders.size());
        response.put("ordersByStatus", byStatus);
        response.put("activeOrders", byStatus.entrySet().stream()
                .filter(e -> !"DELIVERED".equals(e.getKey()) && !"CANCELLED".equals(e.getKey()))
                .mapToLong(Map.Entry::getValue).sum());
        response.put("revenue", revenue);
        response.put("recentOrders", orders.stream().limit(5).toList());

        // Solo el Admin ve los KPIs de reporteria; ademas ms-report puede estar
        // caido sin que eso tumbe el dashboard del resto.
        if (CurrentUser.hasRole(Roles.ADMIN)) {
            Map<String, Object> kpis = getJsonMap(token, "report", "/api/report/kpis");
            // Si ms-report no responde se OMITE la clave en lugar de enviarla vacia:
            // un {} obligaria al frontend a distinguir "sin datos" de "servicio caido".
            if (!kpis.isEmpty()) {
                response.put("kpis", kpis);
            }
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getJsonList(String token, String service, String path) {
        Object body = get(token, service, path, List.class);
        return body == null ? List.of() : (List<Map<String, Object>>) body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJsonMap(String token, String service, String path) {
        Object body = get(token, service, path, Map.class);
        return body == null ? Map.of() : (Map<String, Object>) body;
    }

    private <T> T get(String token, String service, String path, Class<T> type) {
        String baseUrl = downstream.getServices().get(service);
        if (baseUrl == null) {
            return null;
        }
        try {
            return restClient.get()
                    .uri(baseUrl + path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(type);
        } catch (RuntimeException ex) {
            log.warn("El dashboard no pudo obtener {} desde {}: {}", path, service, ex.getMessage());
            return null;
        }
    }
}
