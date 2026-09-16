package cl.duoc.pedidos360.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;

import cl.duoc.pedidos360.catalog.domain.Product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ProductDtos {

    private ProductDtos() {
    }

    public record ProductRequest(
            @NotBlank(message = "El SKU es obligatorio")
            @Size(max = 40, message = "El SKU no puede superar 40 caracteres")
            String sku,

            @NotBlank(message = "El nombre es obligatorio")
            @Size(max = 120, message = "El nombre no puede superar 120 caracteres")
            String name,

            @Size(max = 400, message = "La descripcion no puede superar 400 caracteres")
            String description,

            @NotNull(message = "El precio es obligatorio")
            @DecimalMin(value = "0.0", message = "El precio no puede ser negativo")
            BigDecimal price,

            @NotNull(message = "El stock es obligatorio")
            @Min(value = 0, message = "El stock no puede ser negativo")
            Integer stock,

            Boolean active) {
    }

    public record ProductResponse(
            Long id,
            String sku,
            String name,
            String description,
            BigDecimal price,
            Integer stock,
            boolean active,
            Instant updatedAt) {

        public static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(),
                    p.getPrice(), p.getStock(), p.isActive(), p.getUpdatedAt());
        }
    }

    /** Linea de stock a descontar cuando un pedido pasa a ACCEPTED. */
    public record StockLine(
            @NotBlank(message = "El SKU es obligatorio") String sku,
            @NotNull @Min(value = 1, message = "La cantidad debe ser al menos 1") Integer quantity) {
    }

    public record StockOperationRequest(
            @NotNull(message = "El id del pedido es obligatorio") String orderId,
            @NotNull(message = "Debe indicar al menos una linea") java.util.List<StockLine> lines) {
    }

    public record StockOperationResponse(String orderId, String result, java.util.List<ProductResponse> products) {
    }
}
