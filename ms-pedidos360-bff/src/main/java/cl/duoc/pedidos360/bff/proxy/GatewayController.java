package cl.duoc.pedidos360.bff.proxy;

import java.net.URI;
import java.util.List;
import java.util.Set;

import cl.duoc.pedidos360.common.web.BusinessException;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Reenvia cualquier /api/<servicio>/** al microservicio correspondiente,
 * agregando el access token del usuario (token relay).
 *
 * Por que no dejar que Angular llame directo a cada microservicio:
 *  - habria que configurar CORS y una integracion de API Gateway por servicio,
 *  - las URLs internas quedarian expuestas en el bundle del navegador,
 *  - cualquier cambio de topologia obligaria a redeployar el frontend.
 */
@RestController
@EnableConfigurationProperties(DownstreamProperties.class)
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    /** Cabeceras que NO se reenvian: las recalcula el cliente HTTP de salida. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "connection", "content-length", "transfer-encoding", "keep-alive",
            "upgrade", "accept-encoding", "authorization", "cookie", "origin", "referer");

    private final RestClient restClient;
    private final DownstreamProperties properties;

    public GatewayController(RestClient.Builder builder, DownstreamProperties properties) {
        this.restClient = builder.build();
        this.properties = properties;
    }

    @RequestMapping("/api/{service}/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          @RequestBody(required = false) byte[] body,
                                          Authentication authentication) {
        String path = request.getRequestURI();
        String service = serviceOf(path);
        String baseUrl = properties.getServices().get(service);
        if (baseUrl == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "No hay un microservicio registrado para /api/" + service);
        }

        String query = request.getQueryString();
        URI target = URI.create(baseUrl + path + (query == null ? "" : "?" + query));
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        log.debug("BFF {} {} -> {}", method, path, target);

        RestClient.RequestBodySpec spec = restClient.method(method)
                .uri(target)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken(authentication));

        copyHeaders(request, spec);

        if (body != null && body.length > 0) {
            spec = spec.body(body);
        }

        try {
            return spec.exchange((req, res) -> {
                byte[] responseBody = res.getBody().readAllBytes();
                HttpHeaders headers = new HttpHeaders();
                MediaType contentType = res.getHeaders().getContentType();
                headers.setContentType(contentType == null ? MediaType.APPLICATION_JSON : contentType);
                // Se devuelve el status tal cual lo dio el microservicio: un 403 de
                // orders debe llegar al navegador como 403, no convertido en 500.
                return ResponseEntity.status(res.getStatusCode()).headers(headers).body(responseBody);
            }, false);
        } catch (ResourceAccessException ex) {
            // El microservicio no esta levantado o no responde. 502 Bad Gateway es
            // el codigo correcto: el problema no es del cliente ni del BFF.
            log.warn("El microservicio '{}' no responde: {}", service, ex.getMessage());
            throw new BusinessException(HttpStatus.BAD_GATEWAY,
                    "El microservicio '" + service + "' no esta disponible en este momento.");
        }
    }

    private void copyHeaders(HttpServletRequest request, RestClient.RequestBodySpec spec) {
        List<String> names = java.util.Collections.list(request.getHeaderNames());
        for (String name : names) {
            if (HOP_BY_HOP.contains(name.toLowerCase())) {
                continue;
            }
            spec.header(name, request.getHeader(name));
        }
    }

    private static String serviceOf(String path) {
        String[] parts = path.split("/");
        // path = "" / "api" / "<servicio>" / ...
        return parts.length > 2 ? parts[2] : "";
    }

    private static String rawToken(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        throw new BusinessException(HttpStatus.UNAUTHORIZED, "Peticion sin token JWT valido");
    }
}
