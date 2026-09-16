package cl.duoc.pedidos360.catalog.service;

import java.util.ArrayList;
import java.util.List;

import cl.duoc.pedidos360.catalog.api.ProductDtos.ProductRequest;
import cl.duoc.pedidos360.catalog.domain.Product;
import cl.duoc.pedidos360.catalog.domain.ProductRepository;
import cl.duoc.pedidos360.common.web.BusinessException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ProductRepository repository;

    public CatalogService(ProductRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<Product> findAll(boolean onlyActive) {
        return onlyActive ? repository.findByActiveTrueOrderByNameAsc() : repository.findAll();
    }

    @Transactional(readOnly = true)
    public Product findById(Long id) {
        return repository.findById(id).orElseThrow(() -> BusinessException.notFound("Producto", id));
    }

    @Transactional
    public Product create(ProductRequest request) {
        if (repository.existsBySku(request.sku())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Ya existe un producto con el SKU " + request.sku());
        }
        Product product = new Product(request.sku(), request.name(), request.description(),
                request.price(), request.stock());
        if (request.active() != null) {
            product.setActive(request.active());
        }
        return repository.save(product);
    }

    @Transactional
    public Product update(Long id, ProductRequest request) {
        Product product = findById(id);
        repository.findBySku(request.sku())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException(HttpStatus.CONFLICT,
                            "El SKU " + request.sku() + " ya pertenece a otro producto");
                });
        product.setSku(request.sku());
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStock(request.stock());
        if (request.active() != null) {
            product.setActive(request.active());
        }
        product.touch();
        return repository.save(product);
    }

    /** Baja logica: nunca se borra un producto referenciado por pedidos historicos. */
    @Transactional
    public Product deactivate(Long id) {
        Product product = findById(id);
        product.setActive(false);
        product.touch();
        return repository.save(product);
    }

    /**
     * Regla clave del Caso 0: "Stock decrece al aceptar pedido".
     *
     * Se valida TODO antes de descontar nada. Si una sola linea no tiene stock,
     * la transaccion completa se revierte y el pedido no se acepta: evita quedar
     * con medio pedido descontado.
     */
    @Transactional
    public List<Product> reserve(List<cl.duoc.pedidos360.catalog.api.ProductDtos.StockLine> lines) {
        List<Product> affected = new ArrayList<>();
        for (var line : lines) {
            Product product = repository.findBySku(line.sku())
                    .orElseThrow(() -> BusinessException.notFound("Producto con SKU", line.sku()));
            if (!product.hasStock(line.quantity())) {
                throw new BusinessException(HttpStatus.CONFLICT,
                        "Stock insuficiente para " + line.sku() + ": disponible " + product.getStock()
                                + ", solicitado " + line.quantity());
            }
            affected.add(product);
        }
        for (int i = 0; i < lines.size(); i++) {
            affected.get(i).decreaseStock(lines.get(i).quantity());
        }
        return repository.saveAll(affected);
    }

    /** Compensacion: si el pedido se cancela despues de aceptado, el stock vuelve. */
    @Transactional
    public List<Product> release(List<cl.duoc.pedidos360.catalog.api.ProductDtos.StockLine> lines) {
        List<Product> affected = new ArrayList<>();
        for (var line : lines) {
            repository.findBySku(line.sku()).ifPresent(product -> {
                product.increaseStock(line.quantity());
                affected.add(product);
            });
        }
        return repository.saveAll(affected);
    }
}
