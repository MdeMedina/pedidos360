package cl.duoc.pedidos360.orders.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import cl.duoc.pedidos360.common.web.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Llamada servicio a servicio orders -> catalog.
 *
 * Punto clave de seguridad: se PROPAGA el mismo access token que llego desde el
 * frontend (token relay). No se usa una credencial tecnica compartida, porque eso
 * haria que catalog perdiera de vista quien es el usuario real y no pudiera aplicar
 * sus propias reglas de rol. Con el relay, catalog vuelve a validar el JWT y vuelve
 * a exigir rol Admin u Operador para tocar stock.
 */
@Component
public class CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogClient.class);

    private final RestClient restClient;

    public CatalogClient(RestClient.Builder builder,
                         @Value("${pedidos360.clients.catalog-url:http://localhost:8082}") String catalogUrl) {
        this.restClient = builder.baseUrl(catalogUrl).build();
    }

    public record ProductView(Long id, String sku, String name, String description,
                              BigDecimal price, Integer stock, boolean active, Instant updatedAt) {
    }

    public record StockLine(String sku, Integer quantity) {
    }

    public record StockOperationRequest(String orderId, List<StockLine> lines) {
    }

    public record StockOperationResponse(String orderId, String result, List<ProductView> products) {
    }

    public ProductView findBySku(String bearerToken, String sku) {
        List<ProductView> products = listProducts(bearerToken);
        return products.stream()
                .filter(p -> p.sku().equalsIgnoreCase(sku))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Producto con SKU", sku));
    }

    public List<ProductView> listProducts(String bearerToken) {
        return restClient.get()
                .uri("/api/catalog/products?onlyActive=true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new BusinessException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                            "ms-catalog respondio " + res.getStatusCode() + " al listar productos");
                })
                .body(new org.springframework.core.ParameterizedTypeReference<List<ProductView>>() {
                });
    }

    public void reserveStock(String bearerToken, String orderId, List<StockLine> lines) {
        callStock(bearerToken, "/api/catalog/stock/reserve", orderId, lines);
    }

    public void releaseStock(String bearerToken, String orderId, List<StockLine> lines) {
        try {
            callStock(bearerToken, "/api/catalog/stock/release", orderId, lines);
        } catch (RuntimeException ex) {
            // La devolucion de stock es una compensacion: si falla no debe impedir
            // que el pedido quede cancelado. Se registra para reproceso manual.
            log.error("No se pudo devolver el stock del pedido {}: {}", orderId, ex.getMessage());
        }
    }

    private StockOperationResponse callStock(String bearerToken, String path, String orderId, List<StockLine> lines) {
        return restClient.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .body(new StockOperationRequest(orderId, lines))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String body = new String(res.getBody().readAllBytes());
                    throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,
                            "ms-catalog rechazo la operacion de stock: " + body);
                })
                .body(StockOperationResponse.class);
    }
}
