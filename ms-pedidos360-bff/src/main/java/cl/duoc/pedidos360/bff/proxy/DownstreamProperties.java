package cl.duoc.pedidos360.bff.proxy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mapa "primer segmento de la ruta" -> URL base del microservicio.
 * Ejemplo: /api/orders/123 -> http://ms-orders:8081/api/orders/123
 */
@ConfigurationProperties(prefix = "pedidos360.downstream")
public class DownstreamProperties {

    private Map<String, String> services = new LinkedHashMap<>();

    public Map<String, String> getServices() {
        return services;
    }

    public void setServices(Map<String, String> services) {
        this.services = services;
    }
}
