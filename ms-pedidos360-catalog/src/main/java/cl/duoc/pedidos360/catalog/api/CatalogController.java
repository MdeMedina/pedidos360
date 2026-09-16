package cl.duoc.pedidos360.catalog.api;

import java.util.List;

import cl.duoc.pedidos360.catalog.api.ProductDtos.ProductRequest;
import cl.duoc.pedidos360.catalog.api.ProductDtos.ProductResponse;
import cl.duoc.pedidos360.catalog.api.ProductDtos.StockOperationRequest;
import cl.duoc.pedidos360.catalog.api.ProductDtos.StockOperationResponse;
import cl.duoc.pedidos360.catalog.service.CatalogService;
import cl.duoc.pedidos360.common.security.Roles;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/catalog/* — Caso 0: "CRUD de productos y stock disponible", actor Admin.
 *
 * Matiz de diseno: la LECTURA se abre tambien a Operador y Cliente porque sin ver
 * el catalogo nadie puede armar un pedido. La ESCRITURA queda solo en Admin, que es
 * lo que el enunciado exige.
 */
@RestController
@RequestMapping("/api/catalog")
@Tag(name = "Catalogo", description = "Productos y stock")
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/products")
    @PreAuthorize(Roles.HAS_ANY_BUSINESS_ROLE)
    @Operation(summary = "Lista productos del catalogo")
    public List<ProductResponse> list(
            @RequestParam(name = "onlyActive", defaultValue = "true") boolean onlyActive) {
        return service.findAll(onlyActive).stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/products/{id}")
    @PreAuthorize(Roles.HAS_ANY_BUSINESS_ROLE)
    @Operation(summary = "Obtiene un producto por id")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(service.findById(id));
    }

    @PostMapping("/products")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Crea un producto (solo Admin)")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = ProductResponse.from(service.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/products/{id}")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Actualiza un producto (solo Admin)")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(service.update(id, request));
    }

    @DeleteMapping("/products/{id}")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Desactiva un producto (baja logica, solo Admin)")
    public ProductResponse deactivate(@PathVariable Long id) {
        return ProductResponse.from(service.deactivate(id));
    }

    @PostMapping("/stock/reserve")
    @PreAuthorize(Roles.HAS_ADMIN_OR_OPERATOR)
    @Operation(summary = "Descuenta stock al aceptar un pedido (llamado por ms-orders)")
    public StockOperationResponse reserve(@Valid @RequestBody StockOperationRequest request) {
        List<ProductResponse> products = service.reserve(request.lines()).stream()
                .map(ProductResponse::from).toList();
        return new StockOperationResponse(request.orderId(), "RESERVED", products);
    }

    @PostMapping("/stock/release")
    @PreAuthorize(Roles.HAS_ADMIN_OR_OPERATOR)
    @Operation(summary = "Devuelve stock al cancelar un pedido ya aceptado")
    public StockOperationResponse release(@Valid @RequestBody StockOperationRequest request) {
        List<ProductResponse> products = service.release(request.lines()).stream()
                .map(ProductResponse::from).toList();
        return new StockOperationResponse(request.orderId(), "RELEASED", products);
    }
}
